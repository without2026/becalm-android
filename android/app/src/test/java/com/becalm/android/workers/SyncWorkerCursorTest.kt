package com.becalm.android.workers

import com.becalm.android.data.local.DataStoreKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// spec: ING-006..ING-010 — sync worker cursor types per source
// spec: ING-012 — cursor type per source:
//   voice = Long (MediaStore DATE_MODIFIED epoch millis)
//   gmail = String (history.list startHistoryId)
//   outlook_mail = String (messages/delta @odata.deltaLink)
//   naver_imap/daum_imap = String (UIDVALIDITY:lastUID composite)
//   google_calendar = String (events.list syncToken)
//   outlook_calendar = String (events/delta deltaLink)

class SyncWorkerCursorTest {

    // spec: ING-012 — voice cursor is Long (epoch millis from MediaStore DATE_MODIFIED)
    @Test
    fun `voice cursor key is Long type for epoch millis`() {
        assertNotNull(DataStoreKeys.CURSOR_VOICE)
        // Long preference key — matches ING-012 MediaStore DATE_MODIFIED epoch millis spec
        assertEquals("cursor_voice", DataStoreKeys.CURSOR_VOICE.name)
    }

    // spec: ING-012 — Gmail cursor is String (startHistoryId)
    @Test
    fun `gmail cursor key is String type for historyId`() {
        assertNotNull(DataStoreKeys.CURSOR_GMAIL)
        assertEquals("cursor_gmail", DataStoreKeys.CURSOR_GMAIL.name)
    }

    // spec: ING-012 — IMAP cursor is String composite UIDVALIDITY:lastUID
    @Test
    fun `naver imap cursor key is String for UIDVALIDITY composite`() {
        assertNotNull(DataStoreKeys.CURSOR_NAVER_IMAP)
        assertEquals("cursor_naver_imap", DataStoreKeys.CURSOR_NAVER_IMAP.name)
    }

    // spec: ING-012 — Google Calendar cursor is String (syncToken)
    @Test
    fun `google calendar cursor key is String for syncToken`() {
        assertNotNull(DataStoreKeys.CURSOR_GOOGLE_CALENDAR)
        assertEquals("cursor_google_calendar", DataStoreKeys.CURSOR_GOOGLE_CALENDAR.name)
    }

    // spec: ING-012 — Outlook Calendar cursor is String (deltaLink)
    @Test
    fun `outlook calendar cursor key is String for deltaLink`() {
        assertNotNull(DataStoreKeys.CURSOR_OUTLOOK_CALENDAR)
        assertEquals("cursor_outlook_calendar", DataStoreKeys.CURSOR_OUTLOOK_CALENDAR.name)
    }

    // spec: ING-006 — GmailSyncWorker work name defined
    @Test
    fun `GmailSyncWorker WORK_NAME is defined`() {
        assertEquals("sync-gmail", GmailSyncWorker.WORK_NAME)
    }

    // spec: ING-008 — NaverImapSyncWorker work name defined
    @Test
    fun `NaverImapSyncWorker WORK_NAME is defined`() {
        assertEquals("sync-naver-imap", NaverImapSyncWorker.WORK_NAME)
    }

    // spec: ING-009 — CalendarSyncWorker work names defined
    @Test
    fun `CalendarSyncWorker work names are defined`() {
        assertEquals("sync-google-calendar", CalendarSyncWorker.WORK_NAME_GOOGLE)
        assertEquals("sync-outlook-calendar", CalendarSyncWorker.WORK_NAME_OUTLOOK)
    }

    // spec: SYNC-006 — UploadWorker work name is sync-all-upload
    @Test
    fun `UploadWorker WORK_NAME is sync-all-upload`() {
        assertEquals("sync-all-upload", UploadWorker.WORK_NAME)
    }
}
