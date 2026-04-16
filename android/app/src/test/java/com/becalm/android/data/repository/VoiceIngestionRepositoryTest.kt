package com.becalm.android.data.repository

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

// spec: ING-001 — ContentObserver registration
// spec: ING-011 — foreground catch-up primary path
// spec: ING-012 — cursor persistence per source

class VoiceIngestionRepositoryTest {

    private val context: Context = mockk()
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val dataStore: DataStore<Preferences> = mockk()

    private lateinit var repository: VoiceIngestionRepository

    @Before
    fun setUp() {
        repository = VoiceIngestionRepository(context, rawIngestionEventDao, dataStore)
    }

    // spec: ING-001 — ContentObserver registered without crash
    @Test
    fun `registerContentObserver does not throw when SAF uri is null`() {
        val contentResolver: ContentResolver = mockk(relaxed = true)
        every { context.contentResolver } returns contentResolver
        // Should not throw
        repository.registerContentObserver(null)
        repository.unregisterContentObserver()
    }

    // spec: ING-011 — performForegroundCatchUp returns empty when no new files
    @Test
    fun `performForegroundCatchUp returns empty list when no files since cursor`() = runTest {
        val prefs = preferencesOf(DataStoreKeys.CURSOR_VOICE to System.currentTimeMillis())
        coEvery { dataStore.data } returns flowOf(prefs)

        val contentResolver: ContentResolver = mockk()
        every { context.contentResolver } returns contentResolver
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val result = repository.performForegroundCatchUp()
        assert(result.isEmpty())
    }

    // spec: ING-012 — getContentObserverFireCount starts at 0
    @Test
    fun `contentObserverFireCount starts at zero`() {
        assert(repository.getContentObserverFireCount() == 0L)
    }

    // spec: ING-001 — unregister is idempotent
    @Test
    fun `unregisterContentObserver is safe to call when not registered`() {
        // Should not throw
        repository.unregisterContentObserver()
    }
}
