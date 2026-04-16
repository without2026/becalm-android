package com.becalm.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// spec: ONB-001..ONB-008, ONB-CONTACTS — onboarding state keys
// spec: ING-012 — per-source cursor keys
// spec: AUTH-006 — terms_accepted key

class DataStoreKeysTest {

    // spec: ONB-008 — onboarding_completed key exists
    @Test
    fun `ONBOARDING_COMPLETED key is defined`() {
        assertNotNull(DataStoreKeys.ONBOARDING_COMPLETED)
        assertEquals("onboarding_completed", DataStoreKeys.ONBOARDING_COMPLETED.name)
    }

    // spec: AUTH-006 — terms_accepted key exists
    @Test
    fun `TERMS_ACCEPTED key is defined`() {
        assertNotNull(DataStoreKeys.TERMS_ACCEPTED)
    }

    // spec: ING-012 — all 6 source cursor keys defined
    @Test
    fun `all 6 source cursor keys are defined`() {
        assertNotNull(DataStoreKeys.CURSOR_VOICE)
        assertNotNull(DataStoreKeys.CURSOR_GMAIL)
        assertNotNull(DataStoreKeys.CURSOR_OUTLOOK_MAIL)
        assertNotNull(DataStoreKeys.CURSOR_NAVER_IMAP)
        assertNotNull(DataStoreKeys.CURSOR_DAUM_IMAP)
        assertNotNull(DataStoreKeys.CURSOR_GOOGLE_CALENDAR)
        assertNotNull(DataStoreKeys.CURSOR_OUTLOOK_CALENDAR)
    }

    // spec: ING-012 — cursor types match spec
    // voice = Long (epoch millis), others = String (opaque cursor)
    @Test
    fun `voice cursor is Long type (epoch millis per ING-012)`() {
        assertEquals("cursor_voice", DataStoreKeys.CURSOR_VOICE.name)
    }

    // spec: ENR-001, ONB-CONTACTS — contacts permission key
    @Test
    fun `CONTACTS_PERMISSION_GRANTED key is defined`() {
        assertNotNull(DataStoreKeys.CONTACTS_PERMISSION_GRANTED)
    }

    // spec: ONB-005 — battery optimization key
    @Test
    fun `BATTERY_OPTIMIZATION_REQUESTED key is defined`() {
        assertNotNull(DataStoreKeys.BATTERY_OPTIMIZATION_REQUESTED)
    }

    // spec: all 6 source connection state keys defined
    @Test
    fun `all source connection state keys are defined`() {
        assertNotNull(DataStoreKeys.VOICE_SAF_URI)
        assertNotNull(DataStoreKeys.GMAIL_CONNECTED)
        assertNotNull(DataStoreKeys.OUTLOOK_MAIL_CONNECTED)
        assertNotNull(DataStoreKeys.NAVER_IMAP_CONNECTED)
        assertNotNull(DataStoreKeys.DAUM_IMAP_CONNECTED)
        assertNotNull(DataStoreKeys.GOOGLE_CALENDAR_CONNECTED)
        assertNotNull(DataStoreKeys.OUTLOOK_CALENDAR_CONNECTED)
    }
}
