package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

// ─── Outlook mail cursor keys ────────────────────────────────────────────────

/**
 * DataStore cursor key for the Outlook INBOX mail delta token (ING-007).
 *
 * Exposed as a top-level public const so ingestion workers and tests share one
 * literal rather than re-declaring a string (grep invariant per plan §6).
 * Consumed through [SyncCursorStore.observeCursor] / [SyncCursorStore.setCursor].
 */
public const val OUTLOOK_MAIL_INBOX_CURSOR_KEY: String = "outlook_mail_inbox_delta"

/**
 * DataStore cursor key for the Outlook SENT mail delta token (ING-007).
 *
 * Companion to [OUTLOOK_MAIL_INBOX_CURSOR_KEY] — the two cursors advance
 * independently because Graph's `/me/mailFolders/{folder}/messages/delta`
 * endpoints mint separate delta tokens per folder scope.
 */
public const val OUTLOOK_MAIL_SENT_CURSOR_KEY: String = "outlook_mail_sent_delta"

/**
 * Legacy cursor key used before folder-scoped Outlook Mail deltas landed.
 *
 * Retained here only for the one-shot migration in
 * [SyncCursorStoreImpl.runOutlookMailCursorMigrationV2]; new code must NOT reference
 * this value.
 */
internal const val LEGACY_OUTLOOK_MAIL_CURSOR_KEY: String = "outlook_mail_delta"

/**
 * DataStore flag that prevents the Outlook v2 cursor migration from running more
 * than once per install. Set to `true` by
 * [SyncCursorStoreImpl.runOutlookMailCursorMigrationV2] on first successful
 * completion.
 */
internal const val OUTLOOK_MAIL_CURSOR_MIGRATION_V2_DONE_KEY: String =
    "outlook_mail_migration_v2_done"

// ─── IMAP cursor v2 migration ───────────────────────────────────────────────

/**
 * Pre-Wave-3 IMAP mailbox identifier used by [SyncCursorStore.observeImapState]
 * when the Naver ingestion worker tracked a single INBOX cursor. Retained only
 * for [SyncCursorStoreImpl.runImapCursorMigrationV2] to promote it to the
 * folder-scoped successor `"naver_inbox"`.
 */
internal const val LEGACY_IMAP_NAVER_MAILBOX: String = "naver"

/**
 * Pre-Wave-3 IMAP mailbox identifier used by [SyncCursorStore.observeImapState]
 * when the Daum ingestion worker tracked a single INBOX cursor. Retained only
 * for [SyncCursorStoreImpl.runImapCursorMigrationV2] to promote it to the
 * folder-scoped successor `"daum_inbox"`.
 */
internal const val LEGACY_IMAP_DAUM_MAILBOX: String = "daum"

/**
 * DataStore flag that prevents the IMAP v2 cursor migration from running more
 * than once per install. Set to `true` by
 * [SyncCursorStoreImpl.runImapCursorMigrationV2] on first successful completion.
 */
internal const val IMAP_CURSOR_MIGRATION_V2_DONE_KEY: String =
    "imap_cursor_migration_v2_done"

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
 * - A **generic string API** ([observeCursor] / [setCursor] / [clearCursor]) for app-side
 *   feed cursors such as paged mirror refresh chains.
 * - **Typed convenience functions** for sources with structured numeric cursors (Gmail
 *   historyId, IMAP UIDVALIDITY+UID pairs, MediaStore timestamps).
 *
 * ## Cursor invalidation (HTTP 410 / UIDVALIDITY change)
 * When a sync engine receives an HTTP 410 Gone from the Gmail API, or detects a
 * UIDVALIDITY change on an IMAP connection, it must call [clearCursor] (or [setImapState]
 * with the new state) before the next sync pass so the engine falls back to a full re-sync
 * rather than resuming from a stale cursor.
 *
 * ## Logout
 * [clearAll] wipes every cursor atomically. Call it from the sign-out flow before any
 * session state is removed.
 *
 * ## Source identifiers used for generic API
 * "calendar_events" | "commitments_cursor" | "outlook_mail" (legacy migration only) |
 * "sms_mms" | "voice"
 */
public interface SyncCursorStore {

    // ─── Generic string-cursor API ───────────────────────────────────────────

    /**
     * Emits the raw cursor string stored for [source], or `null` if no cursor has been
     * persisted yet (first run or after [clearCursor]).
     *
     * Applicable to app-side opaque cursors such as:
     * - "calendar_events" — backend mirror pagination cursor
     * - "commitments_cursor" — commitments feed pagination cursor
     * - "outlook_mail" — legacy migration key retained for upgrade cleanup only
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
     * Call this when a sync engine receives HTTP 410 Gone, pagination invalidation,
     * or any other signal that requires a bounded full refresh.
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

    // ─── One-shot migrations ─────────────────────────────────────────────────

    /**
     * Promotes the pre-Wave-3 single Outlook mail cursor ("outlook_mail_delta")
     * into the folder-scoped INBOX key ("outlook_mail_inbox_delta"). Idempotent:
     * gated by a DataStore flag so repeated invocations after the first successful
     * run are cheap no-ops.
     *
     * ## Semantics
     * - If a value exists under the legacy key AND the INBOX key is still unset:
     *   copy legacy → INBOX, delete the legacy key.
     * - The SENT key is intentionally left null — Wave 1 never indexed sent mail,
     *   so the first Wave 3 run performed a bounded cold full-sync of that folder.
     *
     * Safe to call from BecalmApplication.onCreate on every launch; performs at
     * most one DataStore read after the migration has completed.
     */
    public suspend fun runOutlookMailCursorMigrationV2()

    /**
     * Promotes the pre-Wave-3 single-INBOX IMAP cursors (`"naver"`, `"daum"`) into
     * the folder-scoped successors `"naver_inbox"` and `"daum_inbox"`. Idempotent:
     * gated by [IMAP_CURSOR_MIGRATION_V2_DONE_KEY] so repeated invocations after the
     * first successful run are cheap no-ops.
     *
     * ## Semantics
     * - For each legacy mailbox (`"naver"`, `"daum"`), if the legacy
     *   `imap_<legacy>_uidvalidity` + `imap_<legacy>_uid` pair exists AND the
     *   `imap_<legacy>_inbox_*` destination keys are still unset, copy the pair
     *   atomically.
     * - The `"naver_sent"` and `"daum_sent"` cursors are intentionally left null —
     *   Wave 1 never indexed sent mail, so the first Wave 3 run performs a bounded
     *   cold full-sync of that folder via the 30-day `SEARCH SINCE` window applied
     *   by [ImapClientImpl.fetchSince].
     * - The legacy keys are always removed on successful migration so that a future
     *   regression cannot accidentally re-read them.
     *
     * Safe to call from [BecalmApplication.onCreate] on every launch; performs at
     * most one DataStore read after the migration has completed.
     *
     * Spec refs: ING-008 (`.spec/data-ingestion.spec.yml:78-85`),
     * ING-013 (`.spec/data-ingestion.spec.yml:105-110`).
     */
    public suspend fun runImapCursorMigrationV2()
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

    // ─── Outlook mail cursor v2 migration ────────────────────────────────────

    /**
     * Migration flag key. `true` once the pre-Wave-3 "outlook_mail_delta" entry has
     * been promoted to "outlook_mail_inbox_delta" (or confirmed absent). Gating on
     * this flag ensures the migration runs at most once even under app-startup
     * concurrency — the Hilt singleton scope plus the `first()` read here makes the
     * read/write a single atomic transaction.
     */
    private val outlookMailMigrationV2DoneKey =
        booleanPreferencesKey(OUTLOOK_MAIL_CURSOR_MIGRATION_V2_DONE_KEY)

    override suspend fun runOutlookMailCursorMigrationV2() {
        // Fast path: flag already set, nothing to do.
        val prefs = dataStore.data.first()
        if (prefs[outlookMailMigrationV2DoneKey] == true) return

        val legacyKey = stringPreferencesKey("cursor_$LEGACY_OUTLOOK_MAIL_CURSOR_KEY")
        val inboxKey = stringPreferencesKey("cursor_$OUTLOOK_MAIL_INBOX_CURSOR_KEY")

        dataStore.edit { mutable ->
            val legacyValue = mutable[legacyKey]
            val inboxValue = mutable[inboxKey]

            // Only promote when the destination is empty — if the caller has
            // already set a fresh INBOX cursor (via a newer worker run ahead of
            // the migration) we must not trample it.
            if (legacyValue != null && inboxValue == null) {
                mutable[inboxKey] = legacyValue
            }
            // Always remove the legacy entry regardless of promotion — the key is
            // dead-code after Wave 3 and leaving it around risks a future grep
            // regression.
            mutable.remove(legacyKey)
            mutable[outlookMailMigrationV2DoneKey] = true
        }
    }

    // ─── IMAP cursor v2 migration ────────────────────────────────────────────

    /**
     * Migration flag key for the IMAP cursor v2 promotion. `true` once the
     * pre-Wave-3 `"naver"` + `"daum"` entries have been promoted to the
     * `"naver_inbox"` + `"daum_inbox"` folder-scoped cursors (or confirmed
     * absent). See [runImapCursorMigrationV2] for semantics.
     */
    private val imapMigrationV2DoneKey =
        booleanPreferencesKey(IMAP_CURSOR_MIGRATION_V2_DONE_KEY)

    override suspend fun runImapCursorMigrationV2() {
        // Fast path: flag already set, nothing to do.
        val prefs = dataStore.data.first()
        if (prefs[imapMigrationV2DoneKey] == true) return

        dataStore.edit { mutable ->
            promoteLegacyImapMailbox(mutable, LEGACY_IMAP_NAVER_MAILBOX, "naver_inbox")
            promoteLegacyImapMailbox(mutable, LEGACY_IMAP_DAUM_MAILBOX, "daum_inbox")
            mutable[imapMigrationV2DoneKey] = true
        }
    }

    /**
     * Helper for [runImapCursorMigrationV2]. Given a legacy mailbox name such as
     * `"naver"` and its folder-scoped successor `"naver_inbox"`, copies the
     * (UIDVALIDITY, UID) pair under the new keys iff the destination is still
     * unset, then removes the legacy pair regardless.
     *
     * Writes happen only inside the caller's [DataStore.edit] transaction so the
     * entire migration is atomic even if the process is killed mid-edit.
     */
    private fun promoteLegacyImapMailbox(
        mutable: androidx.datastore.preferences.core.MutablePreferences,
        legacyMailbox: String,
        inboxMailbox: String,
    ) {
        val legacyValidity = mutable[imapValidityKey(legacyMailbox)]
        val legacyUid = mutable[imapUidKey(legacyMailbox)]
        val destinationValidityPresent = mutable[imapValidityKey(inboxMailbox)] != null

        // Only promote when the destination is empty AND both legacy values are present;
        // a partial (validity-only or uid-only) legacy state is a bug signal from an
        // earlier crash — dropping it forces a clean cold-sync rather than restoring
        // half-valid state.
        if (legacyValidity != null && legacyUid != null && !destinationValidityPresent) {
            mutable[imapValidityKey(inboxMailbox)] = legacyValidity
            mutable[imapUidKey(inboxMailbox)] = legacyUid
        }
        // Always remove the legacy keys regardless of promotion.
        mutable.remove(imapValidityKey(legacyMailbox))
        mutable.remove(imapUidKey(legacyMailbox))
    }
}
