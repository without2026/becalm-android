package com.becalm.android.core.di

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.getOrNull
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
    private val logger: Logger,
) : AuthTokenProvider {

    private val cachedAccessToken = AtomicReference<String?>(null)
    private val refreshMutex = Mutex()
    private val observerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

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

            val cached = cachedAccessToken.get()
            if (cached != null && cached != previousAccessToken) {
                return@withLock AuthTokenProvider.RefreshResult.Refreshed(cached)
            }

            val result = authClientProvider.get().refresh(current.refreshToken)
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
        observerScope.launch {
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
}
