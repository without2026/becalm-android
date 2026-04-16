package com.becalm.android.ui.settings

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.work.WorkManager
import com.becalm.android.data.local.DataStoreKeys
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

// spec: SMG-001 — source status list with 7 rows
// spec: SMG-004 — disconnect clears cursor and credentials

@OptIn(ExperimentalCoroutinesApi::class)
class SourceManagementViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val context: Context = mockk()
    private val dataStore: DataStore<Preferences> = mockk()
    private val workManager: WorkManager = mockk(relaxed = true)

    // spec: SMG-001 — 7 rows: 6 sources + contacts
    @Test
    fun `loadSourceStatuses returns 7 source rows`() = runTest {
        val prefs = preferencesOf(
            DataStoreKeys.GMAIL_CONNECTED to true,
            DataStoreKeys.CONTACTS_PERMISSION_GRANTED to true
        )
        coEvery { dataStore.data } returns flowOf(prefs)

        val vm = SourceManagementViewModel(context, dataStore, workManager)
        advanceUntilIdle()

        assertEquals(7, vm.uiState.value.sources.size)
    }

    // spec: SMG-001 — Gmail connected state shown correctly
    @Test
    fun `Gmail shows CONNECTED when gmail_connected is true`() = runTest {
        val prefs = preferencesOf(DataStoreKeys.GMAIL_CONNECTED to true)
        coEvery { dataStore.data } returns flowOf(prefs)

        val vm = SourceManagementViewModel(context, dataStore, workManager)
        advanceUntilIdle()

        val gmailSource = vm.uiState.value.sources.find { it.sourceId == "gmail" }
        assertEquals(SourceConnectionState.CONNECTED, gmailSource?.state)
    }

    // spec: SMG-001 — disconnected state when not connected
    @Test
    fun `source shows DISCONNECTED when not connected`() = runTest {
        val prefs = preferencesOf() // empty prefs = all disconnected
        coEvery { dataStore.data } returns flowOf(prefs)

        val vm = SourceManagementViewModel(context, dataStore, workManager)
        advanceUntilIdle()

        val gmailSource = vm.uiState.value.sources.find { it.sourceId == "gmail" }
        assertEquals(SourceConnectionState.DISCONNECTED, gmailSource?.state)
    }

    // spec: SMG-004 — disconnect does not throw
    @Test
    fun `disconnectSource completes without error`() = runTest {
        val prefs = preferencesOf(DataStoreKeys.GMAIL_CONNECTED to true)
        coEvery { dataStore.data } returns flowOf(prefs)
        coEvery { dataStore.edit(any()) } coAnswers {
            val block = firstArg<suspend (MutablePreferences) -> Unit>()
            val mutablePrefs = mockk<MutablePreferences>(relaxed = true)
            block(mutablePrefs)
            prefs
        }

        val vm = SourceManagementViewModel(context, dataStore, workManager)
        advanceUntilIdle()
        // Should not throw
        vm.disconnectSource("gmail")
        advanceUntilIdle()
    }
}
