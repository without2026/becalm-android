package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// ─── Value types ────────────────────────────────────────────────────────────

/**
 * Represents the cursor state for an IMAP mailbox.
 *
 * Both fields must be treated as a single atomic unit: [uidValidity] is the server-assigned
 * value that identifies the mailbox generation, and [lastSeenUid] is only meaningful while
 * [uidValidity] matches the value returned by the server.
 *
 * If the server returns a new [uidValidity] the caller must discard [lastSeenUid] and
 * perform a full re-sync, then call [SyncCursorStore.setImapState] with the new pair.
 *
 * @param uidValidity IMAP UIDVALIDITY value as returned by the server `SELECT` response.
 * @param lastSeenUid The highest UID successfully ingested in the previous sync pass.
 */
public data class ImapCursorState(val uidValidity: Long, val lastSeenUid: Long)

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Persistent store for per-source incremental-sync cursors.
 *
 * Each sync source tracks a different cursor shape; this interface exposes:
 * - A **generic string API** ([observeCursor] / [setCursor] / [clearCursor]) for sources
 *   that use opaque string tokens (MS Graph delta links, Google Calendar sync tokens).
 * - **Typed convenience functions** for sources with structured numeric cursors (Gmail
 *   historyId, IMAP UIDVALIDITY+UID pairs, MediaStore timestamps).
 *
 * ## Cursor invalidation (HTTP 410 / UIDVALIDITY change)
 * When a sync engine receives an HTTP 410 Gone from the Gmail or MS Graph API, or detects
 * a UIDVALIDITY change on an IMAP connection, it must call [clearCursor] (or
 * [setImapState] with the new state) before the next sync pass so the engine falls back
 * to a full re-sync rather than resuming from a stale cursor.
 *
 * ## Logout
 * [clearAll] wipes every cursor atomically. Call it from the sign-out flow before any
 * session state is removed.
 *
 * ## Source identifiers used for generic API
 * "gmail" | "imap_naver" | "imap_daum" | "outlook_mail" | "google_calendar" |
 * "outlook_calendar" | "sms_mms" | "voice"
 */
public interface SyncCursorStore {

    // ─── Generic string-cursor API ───────────────────────────────────────────

    /**
     * Emits the raw cursor string stored for [source], or `null` if no cursor has been
     * persisted yet (first run or after [clearCursor]).
     *
     * Applicable to sources with opaque string cursors:
     * - "outlook_mail" / "outlook_calendar" — MS Graph `deltaLink` URL
     * - "google_calendar" — Google Calendar `syncToken`
     *
     * @param source Source identifier string, e.g. "google_calendar".
     */
    public fun observeCursor(source: String): Flow<String?>

    /**
     * Persists [cursor] for [source]. Pass `null` to erase the cursor (equivalent to
     * [clearCursor]).
     *
     * @param source Source identifier string.
     * @param cursor Opaque cursor value, or `null` to clear.
     */
    public suspend fun setCursor(source: String, cursor: String?)

    /**
     * Erases the cursor for [source], forcing the next sync pass to perform a full
     * re-sync from the beginning.
     *
     * Call this when a sync engine receives HTTP 410 Gone (Gmail history expiry,
     * MS Graph delta token expiry) or any other invalidation signal.
     *
     * @param source Source identifier string.
     */
    public suspend fun clearCursor(source: String)

    /**
     * Atomically erases all cursors stored in this DataStore file.
     *
     * Call during sign-out so that the next sign-in triggers a full re-sync for
     * every source rather than resuming from a previous user's cursors.
     */
    public suspend fun clearAll()

    // ─── Gmail ───────────────────────────────────────────────────────────────

    /**
     * Emits the Gmail `historyId` cursor, or `null` when no cursor has been persisted.
     *
     * A `null` result causes the sync engine to fall back to a full message list fetch.
     * The Gmail History API returns HTTP 410 when a historyId has expired (typically after
     * 7 days of inactivity); the sync engine must call [clearCursor] with source "gmail"
     * (or [setGmailHistoryId] with `null`) upon receiving that response.
     */
    public fun observeGmailHistoryId(): Flow<Long?>

    /**
     * Persists a new Gmail [historyId]. Pass `null` to clear (triggers full re-sync on
     * next pass).
     *
     * @param historyId The `historyId` value returned by the Gmail API, or `null` to reset.
     */
    public suspend fun setGmailHistoryId(historyId: Long?)

    // ─── IMAP ────────────────────────────────────────────────────────────────

    /**
     * Emits the [ImapCursorState] for the given [mailbox], or `null` when no state has
     * been persisted.
     *
     * [mailbox] must be "naver" or "daum". A `null` result causes the sync engine to
     * fetch all messages (full re-sync). If the server-returned UIDVALIDITY differs from
     * [ImapCursorState.uidValidity], the engine must discard [ImapCursorState.lastSeenUid]
     * and call [setImapState] with the new pair after completing a full re-sync.
     *
     * @param mailbox IMAP mailbox identifier: "naver" | "daum".
     */
    public fun observeImapState(mailbox: String): Flow<ImapCursorState?>

    /**
     * Persists an [ImapCursorState] for [mailbox]. Pass `null` to clear (triggers full
     * re-sync on next pass).
     *
     * @param mailbox IMAP mailbox identifier: "naver" | "daum".
     * @param state   New cursor state, or `null` to reset.
     */
    public suspend fun setImapState(mailbox: String, state: ImapCursorState?)

    // ─── MediaStore ──────────────────────────────────────────────────────────

    /**
     * Emits the `DATE_MODIFIED` / `DATE_ADDED` epoch-millisecond watermark for a
     * MediaStore-backed source, or `null` on first run.
     *
     * [kind] must be "sms" or "voice". The sync engine queries MediaStore with
     * `WHERE date_modified > lastSeenTimestampMs` to avoid re-ingesting already-processed
     * rows.
     *
     * @param kind MediaStore source kind: "sms" | "voice".
     */
    public fun observeMediaStoreLastSeen(kind: String): Flow<Long?>

    /**
     * Persists the epoch-millisecond watermark for [kind].
     *
     * @param kind    MediaStore source kind: "sms" | "voice".
     * @param epochMs Wall-clock epoch milliseconds of the most-recently ingested row,
     *                or `null` to reset.
     */
    public suspend fun setMediaStoreLastSeen(kind: String, epochMs: Long?)
}

// ─── Implementation ──────────────────────────────────────────────────────────

/**
 * [DataStore]-backed implementation of [SyncCursorStore].
 *
 * All data is written to the `becalm_sync_cursors.preferences_pb` file provided by the
 * [DataStoreModule.provideSyncCursorsDataStore] binding.
 *
 * ## Key scheme
 * | Cursor                        | Key type | Key name                            |
 * |-------------------------------|----------|-------------------------------------|
 * | Generic string (any source)   | String   | `cursor_$source`                    |
 * | Gmail historyId               | Long     | `gmail_history_id`                  |
 * | IMAP UIDVALIDITY              | Long     | `imap_${mailbox}_uidvalidity`       |
 * | IMAP last seen UID            | Long     | `imap_${mailbox}_uid`               |
 * | MediaStore watermark          | Long     | `mediastore_${kind}_last_seen`      |
 */
public class SyncCursorStoreImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SyncCursorStore {

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun imapValidityKey(mailbox: String) = longPreferencesKey("imap_${mailbox}_uidvalidity")
    private fun imapUidKey(mailbox: String) = longPreferencesKey("imap_${mailbox}_uid")
    private fun mediaStoreKey(kind: String) = longPreferencesKey("mediastore_${kind}_last_seen")

    // ─── Generic ─────────────────────────────────────────────────────────────

    override fun observeCursor(source: String): Flow<String?> =
        dataStore.data.map { it[stringPreferencesKey("cursor_$source")] }

    override suspend fun setCursor(source: String, cursor: String?) =
        dataStore.editNullable(stringPreferencesKey("cursor_$source"), cursor)

    override suspend fun clearCursor(source: String) = setCursor(source, null)

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    // ─── Gmail ───────────────────────────────────────────────────────────────

    private val gmailHistoryIdKey = longPreferencesKey("gmail_history_id")

    override fun observeGmailHistoryId(): Flow<Long?> =
        dataStore.data.map { it[gmailHistoryIdKey] }

    override suspend fun setGmailHistoryId(historyId: Long?) =
        dataStore.editNullable(gmailHistoryIdKey, historyId)

    // ─── IMAP ────────────────────────────────────────────────────────────────

    override fun observeImapState(mailbox: String): Flow<ImapCursorState?> =
        dataStore.data.map { prefs ->
            val validity = prefs[imapValidityKey(mailbox)]
            val uid = prefs[imapUidKey(mailbox)]
            if (validity != null && uid != null) ImapCursorState(validity, uid) else null
        }

    override suspend fun setImapState(mailbox: String, state: ImapCursorState?) {
        dataStore.edit { prefs ->
            if (state != null) {
                prefs[imapValidityKey(mailbox)] = state.uidValidity
                prefs[imapUidKey(mailbox)] = state.lastSeenUid
            } else {
                prefs.remove(imapValidityKey(mailbox))
                prefs.remove(imapUidKey(mailbox))
            }
        }
    }

    // ─── MediaStore ──────────────────────────────────────────────────────────

    override fun observeMediaStoreLastSeen(kind: String): Flow<Long?> =
        dataStore.data.map { it[mediaStoreKey(kind)] }

    override suspend fun setMediaStoreLastSeen(kind: String, epochMs: Long?) =
        dataStore.editNullable(mediaStoreKey(kind), epochMs)
}
