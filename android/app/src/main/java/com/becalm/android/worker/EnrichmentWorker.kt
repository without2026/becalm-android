package com.becalm.android.worker

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

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
 * on [Dispatchers.IO] inside [doWork].
 *
 * ## ENR-003 — Auth guard
 * Resolves the current userId from [AuthRepository.currentSession]. Returns
 * [Result.failure] immediately when no session is present.
 *
 * ## ENR-004 — Permission guard
 * Checks [android.Manifest.permission.READ_CONTACTS] before any ContactsContract access.
 * Returns [Result.failure] (not retry) when the permission is absent — a missing
 * permission cannot be remedied by WorkManager backoff; it requires the user to grant it
 * through the onboarding flow (SP-53).
 *
 * ## ENR-005 — PersonRef collection
 * Collects the set of distinct personRefs present in [RawIngestionRepository] for the
 * authenticated user via a one-shot snapshot of [RawIngestionRepository.observeTimelineForUser].
 *
 * ## ENR-006 — Freshness gate (7-day TTL)
 * Skips ContactsContract lookup for any personRef whose existing [PersonEnrichmentEntity]
 * was synced within the last 7 days, as determined by [PersonEnrichmentEntity.lastSyncedAt].
 *
 * ## ENR-007 — ContactsContract lookup strategy
 * Selects the lookup URI based on a canonicalized personRef:
 * - Contains `@` → email → [ContactsContract.CommonDataKinds.Email.CONTENT_URI]
 * - Starts with `+` or all digits → phone → [ContactsContract.PhoneLookup.CONTENT_FILTER_URI]
 * - Otherwise (display-name surrogate) → [ContactsContract.Contacts.CONTENT_URI]
 *
 * PII guard: personRef values are NEVER passed to [Logger]. A non-reversible 8-char
 * hex surrogate is logged instead (see [redactPersonRef]).
 */
@HiltWorker
public class EnrichmentWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (runAttemptCount >= MAX_RETRIES) {
            logger.e(TAG, "Exceeded $MAX_RETRIES attempts, failing permanently")
            return@withContext Result.failure()
        }

        // ENR-003: resolve userId; null session → terminal failure
        val userId = authRepository.currentSession()?.userId
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

        val now = Clock.System.now()

        // ENR-005: one-shot snapshot of distinct non-null personRefs for this user
        val personRefs: Set<String> = rawIngestionRepository
            .observeTimelineForUser(userId, limit = MAX_PERSON_REF_SCAN)
            .first()
            .mapNotNull { it.personRef }
            .toSet()

        if (personRefs.isEmpty()) {
            logger.d(TAG, "doWork — no personRefs found, nothing to enrich")
            sourceStatusRepository.recordSyncSuccess(SOURCE_TYPE_ENRICHMENT, now)
            return@withContext Result.success()
        }

        logger.d(TAG, "doWork — personRef count=${personRefs.size}")

        var enrichedCount = 0

        for (ref in personRefs) {
            // ENR-006: freshness gate — skip if row is <7 days old
            val existing = personEnrichmentRepository.findByPersonRef(ref)
            if (existing != null && isWithinTtl(existing.lastSyncedAt, now)) {
                logger.d(TAG, "skip fresh ref=${redactPersonRef(ref)}")
                continue
            }

            // ENR-007: ContactsContract lookup
            val looked = lookupContact(ref)
            if (looked != null) {
                personEnrichmentRepository.upsert(
                    PersonEnrichmentEntity(
                        personRef = ref,
                        displayName = looked.displayName,
                        sourceContactId = looked.contactId,
                        lastSyncedAt = now,
                    ),
                )
                logger.d(TAG, "enriched ref=${redactPersonRef(ref)}")
                enrichedCount++
            } else {
                // No matching ContactsContract row — upsert a minimal row so that
                // the freshness gate suppresses re-querying for 7 days.
                personEnrichmentRepository.upsert(
                    PersonEnrichmentEntity(
                        personRef = ref,
                        lastSyncedAt = now,
                    ),
                )
                logger.d(TAG, "no contact found ref=${redactPersonRef(ref)}")
            }
        }

        sourceStatusRepository.recordSyncSuccess(SOURCE_TYPE_ENRICHMENT, now)
        logger.d(TAG, "doWork complete enrichedCount=$enrichedCount totalRefs=${personRefs.size}")
        Result.success()
    }

    // ── ContactsContract lookup ───────────────────────────────────────────────

    /**
     * Queries [ContactsContract] for the given [personRef] using the strategy
     * dictated by ENR-007. Returns a [ContactResult] on a match, null on miss.
     */
    private fun lookupContact(personRef: String): ContactResult? =
        when (classifyRef(personRef)) {
            RefKind.EMAIL -> lookupByEmail(personRef)
            RefKind.PHONE -> lookupByPhone(personRef)
            RefKind.NAME -> lookupByDisplayName(personRef)
        }

    private fun lookupByEmail(email: String): ContactResult? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
        )
        val selection = "${ContactsContract.CommonDataKinds.Email.DATA1} = ?"
        val selectionArgs = arrayOf(email)

        return appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val contactId = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID),
            )
            val displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                ),
            )
            ContactResult(contactId = contactId, displayName = displayName?.takeIf { it.isNotBlank() })
        }
    }

    private fun lookupByPhone(phone: String): ContactResult? {
        val lookupUri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(phone)
            .build()

        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
        )

        return appContext.contentResolver.query(
            lookupUri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val contactId = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID),
            )
            val displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME),
            )
            ContactResult(contactId = contactId, displayName = displayName?.takeIf { it.isNotBlank() })
        }
    }

    private fun lookupByDisplayName(name: String): ContactResult? {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} = ?"
        val selectionArgs = arrayOf(name)

        return appContext.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val contactId = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID),
            )
            val displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            )
            ContactResult(contactId = contactId, displayName = displayName?.takeIf { it.isNotBlank() })
        }
    }

    // ── Internal types ────────────────────────────────────────────────────────

    /** Minimal contact metadata extracted from a ContactsContract cursor row. */
    private data class ContactResult(
        val contactId: String?,
        val displayName: String?,
    )

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

        /**
         * Maximum number of raw ingestion events scanned to derive the personRef set.
         * Capped to avoid loading an unbounded timeline on devices with years of history.
         */
        private const val MAX_PERSON_REF_SCAN: Int = 2_000

        /** TTL after which an existing enrichment row is considered stale. */
        private val ENRICHMENT_TTL = 7.days

        /** Returns true when [syncedAt] is within the freshness window relative to [now]. */
        private fun isWithinTtl(syncedAt: Instant, now: Instant): Boolean =
            (now - syncedAt) < ENRICHMENT_TTL
    }
}

// ── Top-level helpers ─────────────────────────────────────────────────────────

/**
 * Discriminates the canonical form of a personRef to select the correct
 * ContactsContract lookup strategy (ENR-007).
 *
 * - [RefKind.EMAIL]  — ref contains `@`
 * - [RefKind.PHONE]  — ref starts with `+`, or all characters are digits
 * - [RefKind.NAME]   — everything else (display-name surrogate)
 */
private enum class RefKind { EMAIL, PHONE, NAME }

private fun classifyRef(ref: String): RefKind = when {
    ref.contains('@') -> RefKind.EMAIL
    ref.startsWith('+') || ref.all { it.isDigit() } -> RefKind.PHONE
    else -> RefKind.NAME
}

/**
 * Returns a non-reversible 8-char hex surrogate for [personRef].
 *
 * personRef is PII under PIPA; raw values must never appear in logcat.
 * Mirrors the pattern used in [com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl].
 */
private fun redactPersonRef(personRef: String): String =
    "%08x".format(personRef.hashCode())
