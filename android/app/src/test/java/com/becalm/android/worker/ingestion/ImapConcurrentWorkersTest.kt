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
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapFetchResult
import com.becalm.android.data.remote.imap.ImapFolder
import com.becalm.android.data.remote.imap.ImapSpecialUse
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Worker-level regression for the ING-011 parallel-execution invariant
 * (`.spec/data-ingestion.spec.yml:155`): "6개 소스 어댑터는 병렬 실행되며 한 어댑터의 실패가
 * 다른 어댑터의 실행을 중단시키지 않는다".
 *
 * Instantiates both [ImapNaverWorker] and [ImapDaumWorker] against one shared
 * [ImapCredentialStore] + [ImapCredentialStoreMigrator], drives them concurrently via
 * `coroutineScope { async … async … }`, and asserts each worker's IMAP calls received
 * only its own host + credentials — the per-provider namespace holds up under the
 * Wave 3 2-pass INBOX+Sent scope.
 *
 * Spec: `.spec/data-ingestion.spec.yml:155`, `docs/plans/repo-imap-per-provider-credentials.md`.
 */
@RunWith(RobolectricTestRunner::class)
class ImapConcurrentWorkersTest {

    private val context: Context = mockk(relaxed = true)
    private val naverWorkerParams: WorkerParameters = mockk(relaxed = true)
    private val daumWorkerParams: WorkerParameters = mockk(relaxed = true)
    private val userPrefs: DataStore<Preferences> = mockk()

    private val imapCredentialStore: ImapCredentialStore = mockk()
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator = mockk(relaxed = true)

    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val imapClient: ImapClient = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val emailBodyRepository: EmailBodyRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val metricsStore: MetricsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val moshi: Moshi = Moshi.Builder().build()

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
     * Records which (host, user) pair each `ImapClient.listFolders` / `fetchSince`
     * invocation actually received. The test asserts Naver and Daum recorded exactly
     * their own credentials — the primary cross-account-leak vector this refactor closes.
     */
    private val fetchLog = mutableListOf<Pair<String, String>>()

    @Test
    fun `concurrent run of Naver and Daum workers reads only their own namespace`() = runTest {
        val prefsSnapshot = mutablePreferencesOf().apply {
            set(stringPreferencesKey("current_user_id"), "user-uuid-1")
        }
        every { userPrefs.data } returns flowOf(prefsSnapshot)

        coEvery { imapCredentialStoreMigrator.migrateIfNeeded() } just Runs

        coEvery { imapCredentialStore.load(SourceType.NAVER_IMAP) } returns naverCredentials
        coEvery { imapCredentialStore.load(SourceType.DAUM_IMAP) } returns daumCredentials

        every { syncCursorStore.observeImapState(any()) } returns flowOf(null)

        // listFolders returns the minimal pair of folders for each provider so each
        // worker runs both its INBOX + Sent passes.
        coEvery { imapClient.listFolders(host = "imap.naver.com", any(), any(), any()) } answers {
            synchronized(fetchLog) { fetchLog.add("imap.naver.com" to (secondArg<Any?>()?.toString() ?: "?")) }
            BecalmResult.Success(
                listOf(
                    ImapFolder(name = "INBOX", specialUse = ImapSpecialUse.INBOX),
                    ImapFolder(name = "보낸메일함", specialUse = null),
                ),
            )
        }
        coEvery { imapClient.listFolders(host = "imap.daum.net", any(), any(), any()) } answers {
            synchronized(fetchLog) { fetchLog.add("imap.daum.net" to (secondArg<Any?>()?.toString() ?: "?")) }
            BecalmResult.Success(
                listOf(
                    ImapFolder(name = "INBOX", specialUse = ImapSpecialUse.INBOX),
                    ImapFolder(name = "보낸편지함", specialUse = null),
                ),
            )
        }

        // fetchSince records (host, user) and returns empty fetches so the persist path is skipped.
        coEvery {
            imapClient.fetchSince(
                host = any(),
                port = any(),
                user = any(),
                password = any(),
                mailbox = any(),
                uidValidity = any(),
                uidNext = any(),
                sinceDays = any(),
            )
        } answers {
            synchronized(fetchLog) {
                fetchLog.add(firstArg<String>() to thirdArg<String>())
            }
            BecalmResult.Success(
                ImapFetchResult(messages = emptyList(), newUidValidity = 1L, newUidNext = 1L),
            )
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
            emailBodyRepository = emailBodyRepository,
            sourceStatusRepository = sourceStatusRepository,
            workScheduler = workScheduler,
            metricsStore = metricsStore,
            moshi = moshi,
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
            emailBodyRepository = emailBodyRepository,
            sourceStatusRepository = sourceStatusRepository,
            workScheduler = workScheduler,
            metricsStore = metricsStore,
            moshi = moshi,
            logger = logger,
        )
        every { naverWorkerParams.runAttemptCount } returns 0
        every { daumWorkerParams.runAttemptCount } returns 0

        val (naverResult, daumResult) = coroutineScope {
            val n = async { naverWorker.doWork() }
            val d = async { daumWorker.doWork() }
            n.await() to d.await()
        }

        assertEquals(Result.success(), naverResult)
        assertEquals(Result.success(), daumResult)

        // Each worker connected to its own host with its own username. No
        // cross-contamination — the log must contain (imap.naver.com, alice@...) and
        // (imap.daum.net, bob@...) pairs, but neither host paired with the other user.
        val snapshot = synchronized(fetchLog) { fetchLog.toList() }
        val naverHits = snapshot.filter { it.first == "imap.naver.com" }
        val daumHits = snapshot.filter { it.first == "imap.daum.net" }
        assertTrue("Naver host had at least one fetch", naverHits.isNotEmpty())
        assertTrue("Daum host had at least one fetch", daumHits.isNotEmpty())
        assertTrue(
            "Naver fetches must use only the naver user. Got: $naverHits",
            naverHits.all { it.second == "alice@naver.com" || it.second == "?" },
        )
        assertTrue(
            "Daum fetches must use only the daum user. Got: $daumHits",
            daumHits.all { it.second == "bob@daum.net" || it.second == "?" },
        )
    }
}
