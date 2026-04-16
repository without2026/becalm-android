package com.becalm.android.workers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.work.ListenableWorker
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.data.local.dao.EmailBodyDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// spec: ING-012 — GmailSyncWorker cursor persistence
// spec: ING-013 — 410 historyId expiry → cursor reset + 30-day re-sync

class GmailSyncWorkerTest {

    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val emailBodyDao: EmailBodyDao = mockk(relaxed = true)
    private val dataStore: DataStore<Preferences> = mockk(relaxed = true)
    private val gmailClient: GmailClient = mockk()

    private fun makeWorker(): GmailSyncWorker {
        val context = mockk<Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        return GmailSyncWorker(
            context = context,
            workerParams = workerParams,
            rawIngestionEventDao = rawIngestionEventDao,
            emailBodyDao = emailBodyDao,
            dataStore = dataStore
        ).also { it.gmailClient = gmailClient }
    }

    private fun makeMessage(id: String) = GmailMessage(
        messageId = id,
        subject = "Subject $id",
        fromEmail = "sender@example.com",
        snippet = "snippet",
        bodyText = "body text",
        timestampMillis = System.currentTimeMillis()
    )

    // spec: ING-012 — happy path: history.list returns messages + new historyId → cursor persisted
    @Test
    fun `gmailWorker_happyPath_persistsNewCursor`() = runTest {
        val worker = makeWorker()
        val newHistoryId = "history-456"
        val messages = listOf(makeMessage("msg-1"), makeMessage("msg-2"))

        coEvery { gmailClient.fetchHistory("history-123") } returns
            GmailFetchResult.Messages(messages, newHistoryId)

        // Capture the DataStore edit lambda to verify cursor update
        var capturedCursor: String? = null
        coEvery { dataStore.edit(any()) } coAnswers {
            val block = firstArg<suspend (MutablePreferences) -> Unit>()
            val prefs = mockk<MutablePreferences>(relaxed = true)
            val setSlot = slot<String>()
            every { prefs.set(DataStoreKeys.CURSOR_GMAIL, capture(setSlot)) } answers {
                capturedCursor = setSlot.captured
            }
            block(prefs)
            mockk<Preferences>()
        }

        val result = worker.syncGmail(lastHistoryId = "history-123")

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify { gmailClient.fetchHistory("history-123") }
        coVerify(exactly = 2) { rawIngestionEventDao.insert(any()) }
        coVerify(exactly = 2) { emailBodyDao.insert(any()) }
        assertEquals(newHistoryId, capturedCursor)
    }

    // spec: ING-013 — 410 response: cursor reset to null, 30-day full re-sync performed
    @Test
    fun `gmailWorker_on410_resetsCursorAndPerforms30DayResync`() = runTest {
        val worker = makeWorker()

        // First call (incremental) returns 410
        coEvery { gmailClient.fetchHistory(any()) } returns GmailFetchResult.Gone

        // Full resync returns messages
        val resynced = listOf(makeMessage("resync-1"))
        coEvery { gmailClient.fetchMessagesSince(any()) } returns
            GmailFetchResult.Messages(resynced, "new-history-789")

        var removedKey = false
        var capturedCursor: String? = null
        coEvery { dataStore.edit(any()) } coAnswers {
            val block = firstArg<suspend (MutablePreferences) -> Unit>()
            val prefs = mockk<MutablePreferences>(relaxed = true)
            every { prefs.remove(DataStoreKeys.CURSOR_GMAIL) } answers { removedKey = true; null }
            val setSlot = slot<String>()
            every { prefs.set(DataStoreKeys.CURSOR_GMAIL, capture(setSlot)) } answers {
                capturedCursor = setSlot.captured
            }
            block(prefs)
            mockk<Preferences>()
        }

        val result = worker.syncGmail(lastHistoryId = "stale-history-id")

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        // spec: ING-013 — cursor must be cleared before re-sync
        assert(removedKey) { "Expected cursor_gmail to be removed from DataStore on 410" }
        // spec: ING-013 — full resync messages persisted
        coVerify { gmailClient.fetchMessagesSince(any()) }
        coVerify { rawIngestionEventDao.insert(any()) }
        assertEquals("new-history-789", capturedCursor)
    }

    // spec: ING-012 — cold start (no cursor): performs full resync instead of history.list
    @Test
    fun `gmailWorker_coldStart_performsFullResyncWithoutHistoryCall`() = runTest {
        val worker = makeWorker()
        coEvery { gmailClient.fetchMessagesSince(any()) } returns
            GmailFetchResult.Messages(emptyList(), "initial-history-001")
        coEvery { dataStore.edit(any()) } returns mockk(relaxed = true)

        val result = worker.syncGmail(lastHistoryId = null)

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { gmailClient.fetchHistory(any()) }
        coVerify { gmailClient.fetchMessagesSince(any()) }
    }

    // spec: ING-006 — parseEmailToEvent produces correct person_ref (lowercase email)
    @Test
    fun `parseEmailToEvent lowercases fromEmail as person_ref`() {
        val worker = makeWorker()
        val (event, body) = worker.parseEmailToEvent(
            messageId = "msg-abc",
            subject = "Hello",
            fromEmail = "Sender@Example.COM",
            snippetText = "preview text",
            bodyText = "full body",
            timestampMillis = 1000L
        )

        assertEquals("sender@example.com", event.personRef)
        assertEquals("msg-abc", event.sourceRef)
        assertEquals("gmail", event.sourceType)
        assertEquals("preview text", event.eventSnippet)
        assertNotNull(body.rawIngestionId)
        assertEquals(event.clientEventId, body.rawIngestionId)
    }

    // spec: ING-006 — eventSnippet is capped at 200 chars
    @Test
    fun `parseEmailToEvent caps snippet at 200 chars`() {
        val worker = makeWorker()
        val longSnippet = "x".repeat(300)
        val (event, _) = worker.parseEmailToEvent(
            messageId = "m", subject = "s", fromEmail = "a@b.com",
            snippetText = longSnippet, bodyText = "body", timestampMillis = 0L
        )
        assertEquals(200, event.eventSnippet!!.length)
    }
}
