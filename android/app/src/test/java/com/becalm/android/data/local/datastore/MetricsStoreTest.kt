package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MetricsStoreImpl].
 *
 * Uses a hand-written in-memory [DataStore<Preferences>] fake (mirroring the pattern
 * established in `SourceStatusRepositoryTest`) so the tests run on plain JVM without
 * Robolectric, file system, or Android Context.
 *
 * Plan acceptance criterion:
 * > `MetricsStoreTest` ... `incrementSubjectOnlySkipped twice → observeSubjectOnlySkipped emits 2`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetricsStoreTest {

    @Test
    fun `incrementSubjectOnlySkipped twice yields observeSubjectOnlySkipped value 2`() = runTest {
        val dataStore = FakePreferencesDataStore()
        val store: MetricsStore = MetricsStoreImpl(dataStore)

        store.incrementSubjectOnlySkipped()
        store.incrementSubjectOnlySkipped()

        assertEquals(2, store.observeSubjectOnlySkipped().first())
    }

    @Test
    fun `observeSubjectOnlySkipped defaults to 0 before any increment`() = runTest {
        val dataStore = FakePreferencesDataStore()
        val store: MetricsStore = MetricsStoreImpl(dataStore)

        assertEquals(0, store.observeSubjectOnlySkipped().first())
    }

    // ─── Test fakes ──────────────────────────────────────────────────────────

    /**
     * Hand-written in-memory [DataStore<Preferences>] — emits a single
     * [MutableStateFlow] of [Preferences] snapshots through [data] and applies
     * every [updateData] transformation atomically via an internal mutex.
     *
     * Modeled on `SourceStatusRepositoryTest.FakePreferencesDataStore` so the
     * harness behaviour matches the rest of the test suite.
     */
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val stateFlow = MutableStateFlow<Preferences>(emptyPreferences())
        private val editMutex = Mutex()

        override val data: Flow<Preferences> = stateFlow

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            editMutex.withLock {
                val current = stateFlow.value
                val mutable: MutablePreferences = mutablePreferencesOf().apply {
                    current.asMap().forEach { (k, v) ->
                        @Suppress("UNCHECKED_CAST")
                        set(k as Preferences.Key<Any>, v)
                    }
                }
                val updated = transform(mutable)
                stateFlow.value = updated
                stateFlow.value
            }
    }
}
