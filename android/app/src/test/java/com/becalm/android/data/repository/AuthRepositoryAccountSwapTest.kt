package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.auth.ProcessRestarter
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.msgraph.MsGraphTokenProviderImpl
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test

/**
 * Account-swap regression tests for [AuthRepositoryImpl] (R4 fix).
 *
 * AUTH-008 (`.spec/auth.spec.yml:73`) requires that a different account signing in
 * on the same device cannot inherit `@Singleton` DAO references bound to the
 * previous user. Because the Hilt graph cannot be rebuilt in-process, the repository
 * hands off to [ProcessRestarter] whenever the provider hash changes at sign-in time.
 * Same-account re-sign-in (identical hash) must **not** restart so the routine
 * sign-out → sign-back-in UX stays seamless.
 */
public class AuthRepositoryAccountSwapTest {

    private lateinit var authClient: SupabaseAuthClient
    private lateinit var sessionStore: SupabaseSessionStore
    private lateinit var tokenProvider: AuthTokenProvider
    private lateinit var deviceKeyStore: DeviceKeyStore
    private lateinit var syncCursorStore: SyncCursorStore
    private lateinit var userPrefsStore: UserPrefsStore
    private lateinit var databaseProvider: BeCalmDatabaseProvider
    private lateinit var workScheduler: WorkScheduler
    private lateinit var contentObserverBootstrap: ContentObserverBootstrap
    private lateinit var personEnrichmentRepository: PersonEnrichmentRepository
    private lateinit var imapCredentialStore: ImapCredentialStore
    private lateinit var googleAuthTokenProvider: GoogleAuthTokenProviderImpl
    private lateinit var msGraphTokenProvider: MsGraphTokenProviderImpl
    private lateinit var processRestarter: ProcessRestarter
    private lateinit var logger: Logger

    private lateinit var repo: AuthRepositoryImpl

    private val userASession = SupabaseSession(
        accessToken = "a-access",
        refreshToken = "a-refresh",
        userId = "user-a-uuid",
        email = "a@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )
    private val userBSession = userASession.copy(userId = "user-b-uuid", email = "b@example.com")

    @Before
    public fun setUp() {
        authClient = mockk()
        sessionStore = mockk(relaxed = true)
        tokenProvider = mockk(relaxed = true)
        deviceKeyStore = mockk(relaxed = true)
        syncCursorStore = mockk(relaxed = true)
        userPrefsStore = mockk(relaxed = true)
        databaseProvider = mockk(relaxed = true)
        workScheduler = mockk(relaxed = true)
        contentObserverBootstrap = mockk(relaxed = true)
        personEnrichmentRepository = mockk(relaxed = true)
        imapCredentialStore = mockk(relaxed = true)
        googleAuthTokenProvider = mockk(relaxed = true)
        msGraphTokenProvider = mockk(relaxed = true)
        processRestarter = mockk()
        logger = mockk(relaxed = true)

        // ProcessRestarter.restart() is `Nothing`; tests need the stub to throw rather
        // than actually terminate the JVM so a swap-path assertion can still execute.
        every { processRestarter.restart() } throws ProcessRestartInvokedMarker

        repo = AuthRepositoryImpl(
            authClient = authClient,
            sessionStore = sessionStore,
            tokenProvider = tokenProvider,
            deviceKeyStore = deviceKeyStore,
            syncCursorStore = syncCursorStore,
            userPrefsStore = userPrefsStore,
            databaseProvider = databaseProvider,
            workScheduler = workScheduler,
            contentObserverBootstrap = contentObserverBootstrap,
            personEnrichmentRepository = personEnrichmentRepository,
            imapCredentialStore = imapCredentialStore,
            googleAuthTokenProvider = googleAuthTokenProvider,
            msGraphTokenProvider = msGraphTokenProvider,
            processRestarter = processRestarter,
            logger = logger,
        )
    }

    @Test
    public fun signIn_triggersRestart_whenAccountSwapDetected() = runTest {
        // Provider already holds user A's file; user B signs in on the same device.
        every { databaseProvider.currentUserIdHash() } returns BeCalmDatabase.deriveUserIdHash("user-a-uuid")
        coEvery { authClient.signInWithEmail(any(), any()) } returns BecalmResult.Success(userBSession)

        val result: Throwable = try {
            repo.signInWithEmail("b@example.com", "pw")
            error("expected ProcessRestarter.restart to have thrown the marker")
        } catch (t: Throwable) {
            t
        }

        assert(result === ProcessRestartInvokedMarker)
        verify(exactly = 1) { processRestarter.restart() }
        coVerify(exactly = 1) {
            databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash("user-b-uuid"))
        }
    }

    @Test
    public fun signIn_doesNotRestart_whenSameUserReSignsIn() = runTest {
        // Provider already holds user A's file and user A signs in again (the routine
        // sign-out → sign-back-in flow).
        every { databaseProvider.currentUserIdHash() } returns BeCalmDatabase.deriveUserIdHash("user-a-uuid")
        coEvery { authClient.signInWithEmail(any(), any()) } returns BecalmResult.Success(userASession)

        repo.signInWithEmail("a@example.com", "pw")

        verify(exactly = 0) { processRestarter.restart() }
        coVerify(exactly = 1) {
            databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash("user-a-uuid"))
        }
    }

    @Test
    public fun signIn_doesNotRestart_onFirstSignInOfProcess() = runTest {
        // Cold boot: provider has never opened a DB; there is no prior hash to
        // diff against so the swap branch must stay untaken.
        every { databaseProvider.currentUserIdHash() } returns null
        coEvery { authClient.signInWithEmail(any(), any()) } returns BecalmResult.Success(userASession)

        repo.signInWithEmail("a@example.com", "pw")

        verify(exactly = 0) { processRestarter.restart() }
    }

    @Test
    public fun signInWithGoogle_appliesSameSwapDetection() = runTest {
        every { databaseProvider.currentUserIdHash() } returns BeCalmDatabase.deriveUserIdHash("user-a-uuid")
        coEvery { authClient.signInWithGoogleIdToken(any()) } returns BecalmResult.Success(userBSession)

        try {
            repo.signInWithGoogle("id-token")
        } catch (_: Throwable) {
            // expected
        }

        verify(exactly = 1) { processRestarter.restart() }
    }

    private object ProcessRestartInvokedMarker : Throwable("ProcessRestarter.restart invoked")
}
