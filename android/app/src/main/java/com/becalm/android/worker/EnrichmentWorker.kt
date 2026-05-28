package com.becalm.android.worker

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.person.PersonIdentityResolver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Provider

/**
 * PIPA INVARIANT — ON-DEVICE ONLY.
 *
 * This worker reads contact metadata from [ContactsContract] and writes it to the
 * `persons_enrichment` Room table via [PersonEnrichmentRepository]. It NEVER calls
 * any network API. It has NO dependency on RailwayApi, SupabaseApi, or any DTO type.
 * Adding a network collaborator to this class is a PIPA (개인정보 보호법) violation and
 * MUST be rejected at code review.
 *
 * ## ENR-002 — Worker identity and scheduling
 * Registered as a periodic [CoroutineWorker] by the WorkScheduler (SP-32). Runs
 * on the injected IO dispatcher inside [doWork].
 *
 * ## ENR-003 — Auth guard
 * Resolves the current userId from [AuthRepository.currentSession]. Returns
 * [Result.failure] immediately when no session is present. DAO-backed repositories are
 * intentionally injected as [Provider]s and resolved only after this check so a stale
 * WorkManager row cannot crash pre-auth startup by forcing Room open during worker
 * construction.
 *
 * ## ENR-004 — Permission guard
 * Checks [android.Manifest.permission.READ_CONTACTS] before any ContactsContract access.
 * Returns [Result.failure] (not retry) when the permission is absent — a missing
 * permission cannot be remedied by WorkManager backoff; it requires the user to grant it
 * through the onboarding flow (SP-53).
 *
 * ## ENR-005 — Contacts baseline collection
 * Scans the device ContactsContract baseline directly and records every matchable local
 * identity key (phone, email, display-name alias) into [PersonEnrichmentEntity]. Source
 * participants are matched against this local baseline later by [PersonInteractionIndexWorker].
 *
 * ## ENR-006 — Baseline replacement
 * Replaces the previous local baseline after a successful scan. The worker no longer creates
 * "miss" cache rows from app-generated people, because contacts are the baseline, not a
 * post-hoc enrichment pass for already-created person rows.
 */
@HiltWorker
public class EnrichmentWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val personEnrichmentRepositoryProvider: Provider<PersonEnrichmentRepository>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val processingPauseGate: ProcessingPauseGate,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        authRepository: AuthRepository,
        personEnrichmentRepositoryProvider: Provider<PersonEnrichmentRepository>,
        sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
        processingPauseGate: ProcessingPauseGate,
        workScheduler: WorkScheduler,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        authRepositoryProvider = Provider { authRepository },
        personEnrichmentRepositoryProvider = personEnrichmentRepositoryProvider,
        sourceStatusRepositoryProvider = sourceStatusRepositoryProvider,
        processingPauseGate = processingPauseGate,
        workScheduler = workScheduler,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (processingPauseGate.shouldSkip(TAG)) {
            return@withContext Result.success()
        }
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        // ENR-003: resolve userId; null session → terminal failure
        val userId = authRepositoryProvider.get().currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "doWork aborted — no authenticated session")
            return@withContext Result.failure()
        }

        // ENR-004: READ_CONTACTS permission guard
        val permissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            logger.w(TAG, "doWork aborted — READ_CONTACTS permission not granted")
            return@withContext Result.failure()
        }

        val personEnrichmentRepository = personEnrichmentRepositoryProvider.get()
        val sourceStatusRepository = sourceStatusRepositoryProvider.get()
        val now = Clock.System.now()

        val baseline = scanContactsBaseline(now)
        personEnrichmentRepository.replaceAll(baseline)

        sourceStatusRepository.recordSyncSuccess(SOURCE_TYPE_ENRICHMENT, now)
        workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
        logger.d(TAG, "doWork complete contactBaselineRows=${baseline.size} userPresent=${userId.isNotBlank()}")
        Result.success()
    }

    // ── ContactsContract baseline scan ────────────────────────────────────────

    private fun scanContactsBaseline(now: Instant): List<PersonEnrichmentEntity> =
        buildContactBaselineEntities(
            rows = readContactNameRows() + readPhoneRows() + readEmailRows(),
            now = now,
        )

    private fun readContactNameRows(): List<ContactBaselineScanRow> = queryRows(
        uri = ContactsContract.Contacts.CONTENT_URI,
        projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ),
    ) { cursor ->
        ContactBaselineScanRow(
            contactId = cursor.getOptionalString(ContactsContract.Contacts._ID),
            displayName = cursor.getOptionalString(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
        )
    }

    private fun readPhoneRows(): List<ContactBaselineScanRow> = queryRows(
        uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
    ) { cursor ->
        ContactBaselineScanRow(
            contactId = cursor.getOptionalString(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
            displayName = cursor.getOptionalString(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY),
            phone = cursor.getOptionalString(ContactsContract.CommonDataKinds.Phone.NUMBER),
        )
    }

    private fun readEmailRows(): List<ContactBaselineScanRow> = queryRows(
        uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        ),
    ) { cursor ->
        ContactBaselineScanRow(
            contactId = cursor.getOptionalString(ContactsContract.CommonDataKinds.Email.CONTACT_ID),
            displayName = cursor.getOptionalString(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY),
            email = cursor.getOptionalString(ContactsContract.CommonDataKinds.Email.ADDRESS),
        )
    }

    private fun queryRows(
        uri: android.net.Uri,
        projection: Array<String>,
        mapper: (Cursor) -> ContactBaselineScanRow,
    ): List<ContactBaselineScanRow> = appContext.contentResolver.query(
        uri,
        projection,
        null,
        null,
        null,
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(mapper(cursor))
            }
        }
    }.orEmpty()

    public companion object {
        private const val TAG = "EnrichmentWorker"

        /** Maximum WorkManager runAttemptCount before permanent failure. */
        public const val MAX_RETRIES: Int = 5

        /**
         * Source identifier used with [SourceStatusRepository.recordSyncSuccess].
         * Not a wire [com.becalm.android.data.remote.dto.SourceType] value;
         * enrichment data is on-device only.
         */
        public const val SOURCE_TYPE_ENRICHMENT: String = "enrichment"

    }
}

// ── Top-level helpers ─────────────────────────────────────────────────────────

internal data class ContactBaselineScanRow(
    val contactId: String?,
    val displayName: String?,
    val email: String? = null,
    val phone: String? = null,
)

internal fun buildContactBaselineEntities(
    rows: List<ContactBaselineScanRow>,
    now: Instant,
): List<PersonEnrichmentEntity> {
    val candidates = rows.flatMap { row ->
        buildList {
            PersonIdentityResolver.normalizePhoneAnchor(row.phone)?.let { phone ->
                add(row.toEntityCandidate(personRef = phone))
            }
            PersonIdentityResolver.normalizeRelationEmailAnchor(row.email)?.let { email ->
                add(row.toEntityCandidate(personRef = email))
            }
            PersonIdentityResolver.normalizeAlias(row.displayName)?.let { alias ->
                add(row.toEntityCandidate(personRef = alias))
            }
        }
    }
    return candidates
        .groupBy { it.personRef }
        .values
        .mapNotNull { grouped ->
            val strongest = grouped.maxWithOrNull(
                compareBy<ContactBaselineEntityCandidate> { it.sourceContactId?.isNotBlank() == true }
                    .thenBy { displayQualityScore(it.displayName) },
            ) ?: return@mapNotNull null
            PersonEnrichmentEntity(
                personRef = strongest.personRef,
                displayName = strongest.displayName,
                sourceContactId = strongest.sourceContactId,
                lastSyncedAt = now,
            )
        }
        .sortedBy { it.personRef }
}

private data class ContactBaselineEntityCandidate(
    val personRef: String,
    val displayName: String?,
    val sourceContactId: String?,
)

private fun ContactBaselineScanRow.toEntityCandidate(personRef: String): ContactBaselineEntityCandidate =
    ContactBaselineEntityCandidate(
        personRef = personRef,
        displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
        sourceContactId = contactId?.trim()?.takeIf { it.isNotEmpty() },
    )

private fun Cursor.getOptionalString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) return null
    return getString(index)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun displayQualityScore(value: String?): Int {
    val normalized = value?.trim().orEmpty()
    if (normalized.isEmpty()) return 0
    if (normalized.contains('@')) return 1
    return 2
}
