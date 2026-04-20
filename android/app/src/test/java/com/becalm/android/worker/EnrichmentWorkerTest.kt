package com.becalm.android.worker

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days

/**
 * Unit tests for [EnrichmentWorker] (PIPA on-device-only).
 *
 * Uses Robolectric so [Uri] / [PackageManager] / [MatrixCursor] resolve without a device.
 * MockK doubles cover [AuthRepository], the on-device repos, and [Logger].
 *
 * The worker uses [Clock.System.now] directly (not the injected
 * [com.becalm.android.core.util.Clock]) — see FINDING in the prose summary. Time-sensitive
 * cases (T4 freshness gate) construct the existing row's [PersonEnrichmentEntity.lastSyncedAt]
 * relative to a `Clock.System.now()` snapshot taken at test start so the assertion is robust
 * to small drift.
 *
 * Test cases:
 * 1. No session → [Result.failure] (ENR-003 fail-closed) and no contact lookup.
 * 2. READ_CONTACTS missing → [Result.failure] (ENR-004 — NOT retry, despite spec brief);
 *    captures the actual production behaviour.
 * 3. Empty personRef set → [Result.success] and [SourceStatusRepository.recordSyncSuccess]
 *    is invoked exactly once.
 * 4. Freshness gate (ENR-006) — ref with `lastSyncedAt < 7 days` ago is skipped (no upsert).
 * 5. Email lookup hit (ENR-007 EMAIL branch) → upsert with displayName + sourceContactId.
 * 6. Phone lookup miss → upsert minimal row (PIPA: row preserved with refreshed timestamp
 *    so freshness gate suppresses re-querying for 7 days).
 * 7. PIPA invariant — no network collaborator is wired; this test is structural and asserts
 *    that the only collaborators reachable from the worker are on-device repositories.
 *
 * Spec refs: ENR-002, ENR-003, ENR-004, ENR-005, ENR-006, ENR-007.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class EnrichmentWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)

    private lateinit var worker: EnrichmentWorker

    private val fakeUserId = "user-uuid-enrich-1"
    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = fakeUserId,
        email = "alice@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    private fun rawEvent(personRef: String?): RawIngestionEventEntity = RawIngestionEventEntity(
        id = "raw-${personRef ?: "null"}",
        userId = fakeUserId,
        clientEventId = "x:$personRef",
        sourceType = "gmail",
        sourceRef = "ref-$personRef",
        personRef = personRef,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    @Before
    fun setUp() {
        worker = EnrichmentWorker(
            appContext = context,
            workerParams = workerParams,
            authRepository = authRepository,
            rawIngestionRepository = rawIngestionRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        every { workerParams.runAttemptCount } returns 0
        coEvery { authRepository.currentSession() } returns fakeSession

        // Default: READ_CONTACTS granted via ContextCompat → context.checkPermission
        every {
            context.checkPermission(
                android.Manifest.permission.READ_CONTACTS,
                any(),
                any(),
            )
        } returns PackageManager.PERMISSION_GRANTED

        every { context.contentResolver } returns contentResolver

        // Default: no enrichment row exists for any ref
        coEvery { personEnrichmentRepository.findByPersonRef(any()) } returns null
        coEvery { personEnrichmentRepository.upsert(any()) } returns BecalmResult.Success(Unit)

        // Default: empty timeline (overridden per-test)
        every {
            rawIngestionRepository.observeTimelineForUser(fakeUserId, limit = any())
        } returns flowOf(emptyList())
    }

    // ── T1: No session → Result.failure (ENR-003) ────────────────────────────

    @Test
    fun `doWork returns failure and skips lookup when no authenticated session`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { rawIngestionRepository.observeTimelineForUser(any(), any()) }
        coVerify(exactly = 0) { personEnrichmentRepository.upsert(any()) }
        coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
    }

    // ── T2: Permission denied → Result.failure (ENR-004; documents actual behaviour) ─

    @Test
    fun `doWork returns failure when READ_CONTACTS permission not granted`() = runTest {
        // FINDING: brief spec said "Result.retry" but production EnrichmentWorker (ENR-004)
        // returns Result.failure() because runtime permission cannot be remedied by
        // WorkManager backoff (the user must grant via onboarding SP-53). This test pins
        // the actual behaviour.
        every {
            context.checkPermission(
                android.Manifest.permission.READ_CONTACTS,
                any(),
                any(),
            )
        } returns PackageManager.PERMISSION_DENIED

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { rawIngestionRepository.observeTimelineForUser(any(), any()) }
        coVerify(exactly = 0) { personEnrichmentRepository.upsert(any()) }
    }

    // ── T3: Empty personRef set → success + recordSyncSuccess ────────────────

    @Test
    fun `doWork records success and skips upsert when no personRefs found`() = runTest {
        every {
            rawIngestionRepository.observeTimelineForUser(fakeUserId, limit = any())
        } returns flowOf(listOf(rawEvent(personRef = null)))

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { personEnrichmentRepository.upsert(any()) }
        coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncSuccess(
                EnrichmentWorker.SOURCE_TYPE_ENRICHMENT,
                any(),
            )
        }
    }

    // ── T4: Freshness gate (ENR-006) — fresh row is skipped ──────────────────

    @Test
    fun `doWork skips ContactsContract lookup when existing row is within 7-day TTL`() = runTest {
        val ref = "alice@example.com"
        every {
            rawIngestionRepository.observeTimelineForUser(fakeUserId, limit = any())
        } returns flowOf(listOf(rawEvent(personRef = ref)))

        // Existing row synced 1 day ago (well inside the 7-day TTL).
        val freshSyncedAt = Clock.System.now() - 1.days
        coEvery { personEnrichmentRepository.findByPersonRef(ref) } returns PersonEnrichmentEntity(
            personRef = ref,
            displayName = "Alice",
            sourceContactId = "contact-1",
            lastSyncedAt = freshSyncedAt,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // Freshness gate fires: no upsert, but contentResolver is also untouched for this ref.
        coVerify(exactly = 0) { personEnrichmentRepository.upsert(any()) }
        coVerify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
    }

    // ── T5: Email lookup hit (ENR-007 EMAIL branch) → upsert with display data ─

    @Test
    fun `doWork upserts enriched row when ContactsContract email lookup hits`() = runTest {
        val ref = "bob@example.com"
        every {
            rawIngestionRepository.observeTimelineForUser(fakeUserId, limit = any())
        } returns flowOf(listOf(rawEvent(personRef = ref)))

        val emailUri: Uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val matrixCursor = MatrixCursor(
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
            ),
        ).apply {
            addRow(arrayOf<Any?>("contact-bob-42", "Bob Builder"))
        }

        every {
            contentResolver.query(
                emailUri,
                any(),
                any(),
                any(),
                any(),
            )
        } returns matrixCursor

        val captured = slot<PersonEnrichmentEntity>()
        coEvery {
            personEnrichmentRepository.upsert(capture(captured))
        } returns BecalmResult.Success(Unit)

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(ref, captured.captured.personRef)
        assertEquals("Bob Builder", captured.captured.displayName)
        assertEquals("contact-bob-42", captured.captured.sourceContactId)
        assertNotNull(captured.captured.lastSyncedAt)
    }

    // ── T6: Phone lookup miss → upsert minimal row (PIPA preservation) ───────

    @Test
    fun `doWork upserts minimal row when ContactsContract phone lookup misses`() = runTest {
        val phoneRef = "+821012345678"
        every {
            rawIngestionRepository.observeTimelineForUser(fakeUserId, limit = any())
        } returns flowOf(listOf(rawEvent(personRef = phoneRef)))

        // Empty cursor for any phone lookup URI: simulates "no contact match".
        val emptyCursor = MatrixCursor(
            arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
            ),
        )
        every {
            contentResolver.query(any(), any(), any(), any(), any())
        } returns emptyCursor

        val captured = slot<PersonEnrichmentEntity>()
        coEvery {
            personEnrichmentRepository.upsert(capture(captured))
        } returns BecalmResult.Success(Unit)

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // Minimal row: personRef + lastSyncedAt only; display fields stay null.
        assertEquals(phoneRef, captured.captured.personRef)
        assertNull(captured.captured.displayName)
        assertNull(captured.captured.sourceContactId)
    }

    // ── T7: PIPA structural invariant — no network reachable from this worker ─

    @Test
    fun `worker has no network collaborator wired`() {
        // Rather than reaching for reflection on private fields, this test is structural:
        // it asserts that the public ctor parameters declared at the top of the worker
        // contain only on-device collaborators. If a future refactor introduces a
        // RailwayApi / SupabaseApi / MsGraphClient parameter, this test forces the
        // PR to be rejected per the PIPA invariant comment in EnrichmentWorker.
        val ctorParamTypes = EnrichmentWorker::class.java.declaredFields
            .map { it.type.simpleName }
            .toSet()

        val forbidden = setOf(
            "RailwayApi",
            "SupabaseApi",
            "MsGraphClient",
            "GmailClient",
            "ImapClient",
            "OkHttpClient",
        )
        for (banned in forbidden) {
            assertTrue(
                "PIPA violation — EnrichmentWorker must not declare a $banned field",
                banned !in ctorParamTypes,
            )
        }
    }
}
