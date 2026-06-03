package com.becalm.android.core.di

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.getOrNull
import com.becalm.android.core.util.Logger
import com.becalm.android.BuildConfig
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
public class DefaultAuthTokenProvider @Inject constructor(
    private val authClientProvider: Provider<SupabaseAuthClient>,
    private val sessionStore: SupabaseSessionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val logger: Logger,
) : AuthTokenProvider {

    private val cachedAccessToken = AtomicReference<String?>(null)
    private val refreshMutex = Mutex()

    init {
        startSessionObservation()
    }

    override fun currentAccessToken(): String? = cachedAccessToken.get()

    override suspend fun primeCache() {
        updateCache(sessionStore.load()?.accessToken)
    }

    override fun invalidate() {
        updateCache(null)
    }

    override suspend fun refresh(previousAccessToken: String): AuthTokenProvider.RefreshResult =
        refreshMutex.withLock {
            val current = sessionStore.load()
                ?: return@withLock AuthTokenProvider.RefreshResult.Unauthenticated
            if (current.accessToken.isDebugLocalOnlyToken()) {
                updateCache(current.accessToken)
                return@withLock AuthTokenProvider.RefreshResult.Failed
            }
            if (current.refreshToken.isBlank()) {
                sessionStore.clear()
                updateCache(null)
                return@withLock AuthTokenProvider.RefreshResult.Unauthenticated
            }

            val cached = cachedAccessToken.get()
            if (cached != null && cached != previousAccessToken) {
                return@withLock AuthTokenProvider.RefreshResult.Refreshed(cached)
            }

            val result = authClientProvider.get().refresh(current)
            val refreshed = result.getOrNull()
            if (refreshed != null) {
                sessionStore.save(refreshed)
                updateCache(refreshed.accessToken)
                return@withLock AuthTokenProvider.RefreshResult.Refreshed(refreshed.accessToken)
            }

            when (result) {
                is BecalmResult.Failure -> when (result.error) {
                    is com.becalm.android.core.result.BecalmError.Unauthorized -> {
                        sessionStore.clear()
                        updateCache(null)
                        AuthTokenProvider.RefreshResult.Unauthenticated
                    }
                    else -> AuthTokenProvider.RefreshResult.Failed
                }
                is BecalmResult.Success -> error("unreachable")
            }
        }

    private fun startSessionObservation() {
        applicationScope.launch(ioDispatcher) {
            try {
                primeCache()
                sessionStore.observe().collect { session ->
                    updateCache(session?.accessToken)
                }
            } catch (e: Throwable) {
                logger.e("DefaultAuthTokenProvider", "session observer died — cache will stale", e)
            }
        }
    }

    private fun updateCache(accessToken: String?) {
        cachedAccessToken.set(accessToken)
    }

    private fun String.isDebugLocalOnlyToken(): Boolean =
        BuildConfig.DEBUG &&
            substringAfterLast('.', missingDelimiterValue = "") in DEBUG_LOCAL_ONLY_TOKEN_SIGNATURES

    private companion object {
        private val DEBUG_LOCAL_ONLY_TOKEN_SIGNATURES = setOf(
            AuthInterceptor.DEBUG_LOCAL_ONLY_TOKEN_SIGNATURE,
            "debug",
        )
    }
}
