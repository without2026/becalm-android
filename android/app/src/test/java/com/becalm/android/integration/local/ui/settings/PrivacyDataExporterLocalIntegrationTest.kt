package com.becalm.android.integration.local.ui.settings

import com.becalm.android.core.di.AppModule
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.settings.PrivacyDataExporter
import java.util.zip.ZipInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PrivacyDataExporterLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val prefs = UserPrefsStoreImpl(LocalIntegrationSupport.prefsDataStore("privacy-export"))
    private val exporter = PrivacyDataExporter(
        rawIngestionEventDao = db.rawIngestionEventDao(),
        commitmentDao = db.commitmentDao(),
        calendarEventDao = db.calendarEventDao(),
        emailBodyDao = db.emailBodyDao(),
        personEnrichmentDao = db.personEnrichmentDao(),
        userProfileDao = db.userProfileDao(),
        userPrefsStore = prefs,
        moshi = AppModule.provideMoshi(),
    )

    @Test
    fun `PIPA-002 export contains required json files and excludes tokens`() = runTest {
        prefs.setCurrentUserId(USER_ID)
        prefs.setOnboardingCompleted(true)
        prefs.setProcessingPaused(true)
        prefs.appendPipaActionLog(
            PipaActionLogEntry(
                action = "processing_pause",
                timestampIso = "2026-04-23T00:00:00Z",
            ),
        )

        db.rawIngestionEventDao().insert(rawEvent())
        db.commitmentDao().insert(commitment())
        db.calendarEventDao().insertAll(listOf(calendarEvent()))
        db.emailBodyDao().insert(emailBody())
        db.personEnrichmentDao().upsert(enrichment())
        db.userProfileDao().upsert(userProfile())

        val payload = exporter.export(USER_ID, nowEpochMs = 1_777_777_777_000)
        val entries = unzipEntries(payload.bytes)

        assertTrue(entries.containsKey("raw_ingestion_events.json"))
        assertTrue(entries.containsKey("commitments.json"))
        assertTrue(entries.containsKey("calendar_events.json"))
        assertTrue(entries.containsKey("email_body.json"))
        assertTrue(entries.containsKey("persons_enrichment.json"))
        assertTrue(entries.containsKey("user_profile.json"))
        assertTrue(entries.containsKey("datastore.json"))
        assertTrue(entries.containsKey("README.txt"))
        assertTrue(entries["datastore.json"].orEmpty().contains("processing_pause"))
        assertFalse(entries["datastore.json"].orEmpty().contains("access-token"))
        assertFalse(entries["datastore.json"].orEmpty().contains("refresh-token"))
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun rawEvent() = RawIngestionEventEntity(
        id = "raw-1",
        userId = USER_ID,
        clientEventId = "client-raw-1",
        sourceType = "gmail",
        sourceRef = "msg-1",
        eventTitle = "Mail subject",
        personRef = "person@example.com",
        timestamp = Instant.parse("2026-04-23T00:00:00Z"),
    )

    private fun commitment() = CommitmentEntity(
        id = "cmt-1",
        userId = USER_ID,
        direction = "give",
        counterpartyRaw = "person@example.com",
        personRef = "person@example.com",
        title = "Follow up",
        description = null,
        quote = "I will send it tomorrow",
        sourceEventTitle = "Mail subject",
        sourceEventOccurredAt = Instant.parse("2026-04-23T00:00:00Z"),
        dueAt = null,
        dueHint = null,
        sourceType = "gmail",
        sourceRef = "msg-1",
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "pending",
        createdAt = Instant.parse("2026-04-23T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-23T00:00:00Z"),
    )

    private fun calendarEvent() = CalendarEventEntity(
        id = "evt-1",
        userId = USER_ID,
        sourceType = "google_calendar",
        sourceRef = "gcal-1",
        title = "Meeting",
        startAt = Instant.parse("2026-04-23T01:00:00Z"),
        endAt = Instant.parse("2026-04-23T02:00:00Z"),
        attendeesRaw = "a@example.com",
    )

    private fun emailBody() = EmailBodyEntity(
        id = "body-1",
        rawEventId = "raw-1",
        providerMessageId = "msg-1",
        folder = "INBOX",
        subject = "Mail subject",
        fromAddress = "sender@example.com",
        toAddresses = """["person@example.com"]""",
        bodyPlain = "hello",
        attachmentsMeta = """[]""",
        rawHeaders = """{}""",
        receivedAt = Instant.parse("2026-04-23T00:00:00Z"),
    )

    private fun enrichment() = PersonEnrichmentEntity(
        personRef = "person@example.com",
        displayName = "Person",
        lastSyncedAt = Instant.parse("2026-04-23T00:00:00Z"),
    )

    private fun userProfile() = UserProfileEntity(
        userId = USER_ID,
        createdAt = Instant.parse("2026-04-23T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-23T00:00:00Z"),
    )

    private companion object {
        private const val USER_ID = "user-1"
    }
}
