package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.WorkScheduler
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal data class NamedAuthStep(
    val name: String,
    val block: suspend () -> Any?,
)

internal class AuthSessionCleanupPlanner(
    private val authClient: SupabaseAuthClient,
    private val sessionStore: SupabaseSessionStore,
    private val tokenProvider: AuthTokenProvider,
    private val deviceKeyStore: DeviceKeyStore,
    private val syncCursorStore: SyncCursorStore,
    private val userPrefsStore: UserPrefsStore,
    private val databaseProvider: BeCalmDatabaseProvider,
    private val workScheduler: WorkScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val imapCredentialStore: ImapCredentialStore,
    private val oauthCredentialStore: OAuthCredentialStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun buildSignOutSteps(session: SupabaseSession?): List<NamedAuthStep> = buildList {
        add(NamedAuthStep("cancelAllWorkers") { workScheduler.cancelAll() })
        add(NamedAuthStep("stopContentObservers") { contentObserverBootstrap.stop() })
        if (session != null) add(NamedAuthStep("serverRevoke") { authClient.signOut(session.accessToken) })
        add(NamedAuthStep("personEnrichmentDeleteAll") { personEnrichmentRepository.deleteAll() })
        add(NamedAuthStep("imapCredentialClear") { imapCredentialStore.clearAll() })
        add(NamedAuthStep("googleOAuthCleanup") { oauthCredentialStore.clearGoogle() })
        add(NamedAuthStep("sessionStoreClear") { sessionStore.clear() })
        add(NamedAuthStep("tokenProviderInvalidate") { tokenProvider.invalidate() })
        add(NamedAuthStep("deviceKeyClear") { deviceKeyStore.clear() })
        add(NamedAuthStep("syncCursorClear") { syncCursorStore.clearAll() })
        add(NamedAuthStep("userPrefsClearAll") { userPrefsStore.clearAll() })
        add(NamedAuthStep("databaseClearAll") { withContext(ioDispatcher) { databaseProvider.current().clearAllTables() } })
        add(NamedAuthStep("databaseClose") { withContext(ioDispatcher) { databaseProvider.close() } })
    }

    suspend fun buildInvalidateSessionSteps(session: SupabaseSession?): List<NamedAuthStep> = buildList {
        add(NamedAuthStep("cancelAllWorkers") { workScheduler.cancelAll() })
        add(NamedAuthStep("stopContentObservers") { contentObserverBootstrap.stop() })
        if (session != null) add(NamedAuthStep("serverRevoke") { authClient.signOut(session.accessToken) })
        add(NamedAuthStep("imapCredentialClear") { imapCredentialStore.clearAll() })
        add(NamedAuthStep("googleOAuthCleanup") { oauthCredentialStore.clearGoogle() })
        add(NamedAuthStep("sessionStoreClear") { sessionStore.clear() })
        add(NamedAuthStep("tokenProviderInvalidate") { tokenProvider.invalidate() })
        add(NamedAuthStep("deviceKeyClear") { deviceKeyStore.clear() })
        add(NamedAuthStep("currentUserIdClear") { userPrefsStore.setCurrentUserId(null) })
    }
}

internal object AuthRepositoryRunner {
    suspend fun <T> runStepNamed(
        label: String,
        logger: Logger,
        block: suspend () -> T,
    ): BecalmResult<T> = try {
        val value = block()
        logger.d("AuthRepository", "$label completed")
        BecalmResult.Success(value)
    } catch (e: IOException) {
        logger.e("AuthRepository", "$label IOException", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "IO error"))
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        logger.e("AuthRepository", "$label unexpected error", e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }

    suspend fun runAllSteps(
        flow: String,
        steps: List<NamedAuthStep>,
        logger: Logger,
    ): BecalmResult<Unit> {
        var firstFailure: BecalmResult.Failure? = null
        for (step in steps) {
            val wrapped = runStepNamed("$flow/${step.name}", logger) { step.block() }
            val stepResult: BecalmResult<*> = when (wrapped) {
                is BecalmResult.Failure -> wrapped
                is BecalmResult.Success -> when (val inner = wrapped.value) {
                    is BecalmResult.Failure -> inner
                    else -> wrapped
                }
            }
            if (firstFailure == null && stepResult is BecalmResult.Failure) {
                firstFailure = stepResult
            }
        }
        return firstFailure ?: BecalmResult.Success(Unit)
    }
}
