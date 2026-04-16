package com.becalm.android.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// spec: ING-012 — per-source cursor persistence keys
// spec: ONB-001..ONB-008 — onboarding state keys

object DataStoreKeys {
    // --- Onboarding state ---
    // spec: ONB-008 — written true only after ColdSyncScreen dismissed or cold sync completes
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    // spec: AUTH-006 — terms accepted
    val TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")

    // --- Source connection state ---
    val VOICE_SAF_URI = stringPreferencesKey("voice_saf_uri") // SAF-granted folder URI
    val GMAIL_CONNECTED = booleanPreferencesKey("gmail_connected")
    val OUTLOOK_MAIL_CONNECTED = booleanPreferencesKey("outlook_mail_connected")
    val NAVER_IMAP_CONNECTED = booleanPreferencesKey("naver_imap_connected")
    val DAUM_IMAP_CONNECTED = booleanPreferencesKey("daum_imap_connected")
    val GOOGLE_CALENDAR_CONNECTED = booleanPreferencesKey("google_calendar_connected")
    val OUTLOOK_CALENDAR_CONNECTED = booleanPreferencesKey("outlook_calendar_connected")

    // --- Per-source sync cursors (spec: ING-012) ---
    // voice: MediaStore DATE_MODIFIED epoch millis
    val CURSOR_VOICE = longPreferencesKey("cursor_voice")
    // gmail: history.list startHistoryId
    val CURSOR_GMAIL = stringPreferencesKey("cursor_gmail")
    // outlook_mail: messages/delta @odata.deltaLink
    val CURSOR_OUTLOOK_MAIL = stringPreferencesKey("cursor_outlook_mail")
    // naver_imap: "UIDVALIDITY:lastUID" composite
    val CURSOR_NAVER_IMAP = stringPreferencesKey("cursor_naver_imap")
    // daum_imap: "UIDVALIDITY:lastUID" composite
    val CURSOR_DAUM_IMAP = stringPreferencesKey("cursor_daum_imap")
    // google_calendar: events.list syncToken
    val CURSOR_GOOGLE_CALENDAR = stringPreferencesKey("cursor_google_calendar")
    // outlook_calendar: events/delta deltaLink
    val CURSOR_OUTLOOK_CALENDAR = stringPreferencesKey("cursor_outlook_calendar")

    // --- Battery optimization state (spec: ONB-005) ---
    val BATTERY_OPTIMIZATION_REQUESTED = booleanPreferencesKey("battery_optimization_requested")

    // --- Contacts permission state (spec: ENR-001, ONB-CONTACTS) ---
    val CONTACTS_PERMISSION_GRANTED = booleanPreferencesKey("contacts_permission_granted")
}
