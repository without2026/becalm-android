package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapFetchResult
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Worker-level regression for the ING-011 parallel-execution invariant
 * (`.spec/data-ingestion.spec.yml:155`): "6개 소스 어댑터는 병렬 실행되며 한 어댑터의 실패가
 * 다른 어댑터의 실행을 중단시키지 않는다".
 *
 * The lower-level [com.becalm.android.data.local.secure.ImapCredentialStoreTest] already
 * proves that [ImapCredentialStore] returns disjoint credentials for `NAVER_IMAP` and
 * `DAUM_IMAP`. This test elevates that guarantee to the actual worker code path: it
 * instantiates both [ImapNaverWorker] and [ImapDaumWorker] against one shared
 * [ImapCredentialStore] + [ImapCredentialStoreMigrator], drives them concurrently via
 * `coroutineScope { async … async … }`, and asserts that each worker's IMAP fetch call
 * received only its own host + credentials — i.e. the new per-provider namespace is
 * actually wired through the worker barrier (`migrateIfNeeded()` → `load(sourceType)`)
 * and neither worker can see the other's app-password.
 *
 * If the store ever regressed to the single-tuple layout, both workers would fetch
 * against the same host and this test would fail at the [ImapClient.fetchSince]
 * assertion.
 *
 * Plan: `docs/plans/repo-imap-per-provider-credentials.md` §6 — "Integration test —
 * ImapConcurrentWorkersTest — concurrent run of ImapNaverWorker and ImapDaumWorker
 * reads only their own namespace".
 */
@RunWith(RobolectricTestRunner::class)
class ImapConcurrentWorkersTest {

    private val context: Context = mockk(relaxed = true)
    private val naverWorkerParams: WorkerParameters = mockk(relaxed = true)
    private val daumWorkerParams: WorkerParameters = mockk(relaxed = true)
    private val userPrefs: DataStore<Preferences> = mockk()

    // One shared store + migrator, as in production.
    private val imapCredentialStore: ImapCredentialStore = mockk()
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator = mockk(relaxed = true)

    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val imapClient: ImapClient = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private val naverCredentials = ImapCredentials(
        username = "alice@naver.com",
        appPassword = "naver-app-pw",
        host = "imap.naver.com",
        port = 993,
    )

    private val daumCredentials = ImapCredentials(
        username = "bob@daum.net",
        appPassword = "daum-app-pw",
        host = "imap.daum.net",
        port = 993,
    )

    /**
     * Records which host:user pair each `ImapClient.fetchSince` invocation actually
     * received. The test asserts Naver and Daum recorded exactly their own credentials
     * — the primary cross-account-leak vector this refactor closes.
     */
    private val fetchLog = mutableListOf<Pair<String, String>>()

    @Test
    fun `concurrent run of Naver and Daum workers reads only their own namespace`() = runTest {
        // userPrefs emits a current_user_id (workers need it non-blank).
        val prefsSnapshot = mutablePreferencesOf().apply {
            set(stringPreferencesKey("current_user_id"), "user-uuid-1")
        }
        every { userPrefs.data } returns flowOf(prefsSnapshot)

        // Migrator is idempotent; both workers call migrateIfNeeded() before load().
        coEvery { imapCredentialStoreMigrator.migrateIfNeeded() } just Runs

        // Per-provider credentials are strictly partitioned in the store.
        coEvery { imapCredentialStore.load(SourceType.NAVER_IMAP) } returns naverCredentials
        coEvery { imapCredentialStore.load(SourceType.DAUM_IMAP) } returns daumCredentials

        // No stored cursor on either mailbox — workers go through the full-resync path.
        every { syncCursorStore.observeImapState(any()) } returns flowOf(null)

        // ImapClient.fetchSince records which credential tuple it received and returns
        // an empty fetch so the worker short-circuits past the persist path.
        coEvery {
            imapClient.fetchSince(
                host = any(),
                port = any(),
                user = any(),
                password = any(),
                uidValidity = any(),
                uidNext = any(),
                maxMessages = any(),
            )
        } answers {
            // fetchSince(host, port, user, password, uidValidity, uidNext, maxMessages)
            // — host is arg 0, user is arg 2.
            synchronized(fetchLog) {
                fetchLog.add(firstArg<String>() to thirdArg<String>())
            }
            BecalmResult.Success(ImapFetchResult(messages = emptyList(), newUidValidity = 1L, newUidNext = 1L))
        }

        val naverWorker = ImapNaverWorker(
            appContext = context,
            workerParams = naverWorkerParams,
            userPrefs = userPrefs,
            imapCredentialStore = imapCredentialStore,
            imapCredentialStoreMigrator = imapCredentialStoreMigrator,
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepository = rawIngestionRepository,
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )
        val daumWorker = ImapDaumWorker(
            appContext = context,
            workerParams = daumWorkerParams,
            userPrefs = userPrefs,
            imapCredentialStore = imapCredentialStore,
            imapCredentialStoreMigrator = imapCredentialStoreMigrator,
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepository = rawIngestionRepository,
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )
        every { naverWorkerParams.runAttemptCount } returns 0
        every { daumWorkerParams.runAttemptCount } returns 0

        // Run both workers concurrently — the exact production interleaving.
        val (naverResult, daumResult) = coroutineScope {
            val n = async { naverWorker.doWork() }
            val d = async { daumWorker.doWork() }
            n.await() to d.await()
        }

        assertEquals(Result.success(), naverResult)
        assertEquals(Result.success(), daumResult)

        // Each worker connected to its own host with its own username. No
        // cross-contamination: the Naver record must contain the Naver user, and
        // vice-versa. A single-tuple regression would break this assertion.
        val snapshot = synchronized(fetchLog) { fetchLog.toList() }
        assertEquals(2, snapshot.size)
        val (naverHost, naverUser) = snapshot.first { it.first == "imap.naver.com" }
        val (daumHost, daumUser) = snapshot.first { it.first == "imap.daum.net" }
        assertEquals("imap.naver.com", naverHost)
        assertEquals("alice@naver.com", naverUser)
        assertEquals("imap.daum.net", daumHost)
        assertEquals("bob@daum.net", daumUser)
    }
}
