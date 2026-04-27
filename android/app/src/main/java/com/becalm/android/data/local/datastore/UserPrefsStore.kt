package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.becalm.android.data.remote.dto.SourceType
import kotlinx.datetime.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Persistent store for non-secret user preferences.
 *
 * All preferences are written to the `becalm_user_prefs.preferences_pb` DataStore file
 * provided by [DataStoreModule.provideUserPrefsDataStore]. This file is intentionally
 * separate from the sync-cursor file so that durable UI/UX settings are never accidentally
 * wiped by cursor invalidation logic.
 *
 * **Security boundary**: This store must NOT hold auth tokens, refresh tokens, or
 * cryptographic keys. Those belong exclusively to SP-15's `EncryptedTokenStore`
 * (EncryptedSharedPreferences-backed).
 *
 * [currentUserId] mirrors the Supabase session user ID held in `EncryptedTokenStore` but
 * is kept here for fast-path access (no AES decrypt round-trip) in non-auth code paths
 * such as analytics and Room query filtering.
 *
 * ## Logout
 * Call [clearAll] as part of the sign-out sequence to reset all preferences to their
 * defaults, preventing the next user's session from seeing stale values.
 */
public interface UserPrefsStore {

    /**
     * Emits the Supabase `auth.users` UUID of the currently signed-in user, or `null`
     * when no session is active.
     *
     * This is a non-secret copy of the user ID carried in the encrypted session token.
     * It must be updated whenever a sign-in or sign-out completes.
     */
    public fun observeCurrentUserId(): Flow<String?>

    /**
     * Persists [userId] as the current Supabase user ID.
     *
     * Pass `null` to clear (equivalent to marking the session as absent without a full
     * [clearAll]).
     *
     * @param userId Supabase UUID string, or `null` on sign-out.
     */
    public suspend fun setCurrentUserId(userId: String?)

    /**
     * Emits `true` once the user has completed the onboarding flow, `false` otherwise.
     *
     * Defaults to `false` on first install. The main-activity navigation graph uses this
     * value to decide whether to route new launches to the onboarding graph or the home
     * graph.
     */
    public fun observeOnboardingCompleted(): Flow<Boolean>

    /**
     * Marks onboarding as [completed].
     *
     * @param completed `true` when the user has finished onboarding; `false` to reset.
     */
    public suspend fun setOnboardingCompleted(completed: Boolean)

    /**
     * Emits the durable per-step onboarding status map.
     *
     * Keys and values are intentionally stored as stable strings so the data layer does
     * not depend on UI enums. The UI layer maps these to OnboardingStep / StepStatus.
     */
    public fun observeOnboardingStepStatuses(): Flow<Map<String, String>>

    /** Persists multiple onboarding step statuses atomically for multi-step branches. */
    public suspend fun setOnboardingStepStatuses(statuses: Map<String, String>)

    /** Emits the epoch-millisecond timestamp when Stage 1 first completed, or null. */
    public fun observeColdSyncStage1CompletedAt(): Flow<Long?>

    /** Persists the Stage 1 completion timestamp, or clears it with null. */
    public suspend fun setColdSyncStage1CompletedAt(epochMs: Long?)

    /** Emits whether Stage 1 was deferred via [나중에 하기]. */
    public fun observeColdSyncStage1Deferred(): Flow<Boolean>

    /** Persists the Stage 1 deferred flag. */
    public suspend fun setColdSyncStage1Deferred(deferred: Boolean)

    /** Emits the epoch-millisecond timestamp when Stage 1 was deferred, or null. */
    public fun observeColdSyncDeferredAt(): Flow<Long?>

    /** Persists the Stage 1 deferred timestamp, or clears it with null. */
    public suspend fun setColdSyncDeferredAt(epochMs: Long?)

    /** Emits the epoch-millisecond timestamp when Stage 2 completed, or null. */
    public fun observeColdSyncStage2CompletedAt(): Flow<Long?>

    /** Persists the Stage 2 completion timestamp, or clears it with null. */
    public suspend fun setColdSyncStage2CompletedAt(epochMs: Long?)

    /** Emits whether Stage 2 was explicitly deferred by the user. */
    public fun observeColdSyncStage2Deferred(): Flow<Boolean>

    /** Persists the Stage 2 deferred flag. */
    public suspend fun setColdSyncStage2Deferred(deferred: Boolean)

    /** Emits the persistable SAF tree URI granted for the Recordings folder, or null. */
    public fun observeRecordingFolderTreeUri(): Flow<String?>

    /** Persists or clears the Recordings SAF tree URI grant. */
    public suspend fun setRecordingFolderTreeUri(uri: String?)

    /**
     * Emits the current theme mode preference.
     *
     * Valid values: "system" | "light" | "dark". Defaults to "system" when no preference
     * has been set. Theme.kt enforces a forced-dark baseline per the design spec;
     * this pref is stored for forward compatibility with a user-selectable theme toggle.
     */
    public fun observeThemeMode(): Flow<String>

    /**
     * Persists the theme [mode] preference.
     *
     * @param mode One of "system", "light", or "dark".
     */
    public suspend fun setThemeMode(mode: String)

    /**
     * Emits the user's preferred IETF BCP 47 locale tag (e.g. "ko-KR", "en-US"), or
     * `null` when the app should follow the device system locale.
     */
    public fun observeLocaleTag(): Flow<String?>

    /**
     * Persists the locale [tag].
     *
     * @param tag IETF BCP 47 locale string, or `null` to follow the system locale.
     */
    public suspend fun setLocaleTag(tag: String?)

    /**
     * Emits the epoch-millisecond timestamp at which the user last dismissed the Doze
     * whitelist prompt, or `null` if the prompt has never been shown or dismissed.
     *
     * Used by the SAMSUNG-001 UX gate to suppress repeated battery-optimisation nag
     * dialogs within a cooldown window.
     */
    public fun observeDozePromptDismissedAt(): Flow<Long?>

    /**
     * Records [epochMs] as the timestamp when the Doze whitelist prompt was dismissed.
     *
     * @param epochMs Wall-clock epoch milliseconds of the dismissal, or `null` to reset.
     */
    public suspend fun setDozePromptDismissedAt(epochMs: Long?)

    /**
     * Emits `true` when the user has notifications enabled, `false` when disabled.
     *
     * Defaults to `true` on first install. The value is persisted across process deaths
     * under the `notifications_enabled` key. [clearAll] resets it to the default.
     */
    public fun observeNotificationsEnabled(): Flow<Boolean>

    /**
     * Persists the notifications [enabled] preference.
     *
     * @param enabled `true` to enable notifications; `false` to disable.
     */
    public suspend fun setNotificationsEnabled(enabled: Boolean)

    /**
     * Emits `true` when the user has granted PIPA 제3자 제공 + 국외 이전 동의
     * (third-party provision and international transfer consent) required for the voice
     * pipeline to upload audio to Railway / Vertex AI.
     *
     * Defaults to `false` on first install. Audio bytes MUST NOT leave the device until
     * this returns `true` (VOI-004, ONB-PIPA invariant).
     *
     * Persisted under key `pipa_third_party_consent`.
     */
    public fun observeThirdPartyProvisionConsent(): Flow<Boolean>

    /**
     * Persists the PIPA third-party provision + international transfer consent [granted] flag.
     *
     * Setting to `true` releases all `sync_status='awaiting_consent'` voice events for upload.
     * Also writes the current wall-clock time to `pipa_consent_timestamp_millis` (ONB-PIPA).
     *
     * Setting to `false` does NOT retroactively delete already-uploaded commitments
     * (소급 삭제 없음 — per VOI-004). The `pipa_consent_timestamp_millis` key is cleared so
     * that downstream readers cannot misinterpret a stale timestamp as active consent.
     *
     * @param granted `true` when the user grants consent; `false` when consent is withdrawn.
     */
    public suspend fun setThirdPartyProvisionConsent(granted: Boolean)

    /** Emits whether all background processing is temporarily paused (PIPA-004). */
    public fun observeProcessingPaused(): Flow<Boolean>

    /** Persists the processing-paused flag (PIPA-004). */
    public suspend fun setProcessingPaused(paused: Boolean)

    /** Emits the epoch-millisecond timestamp when processing was paused, or null. */
    public fun observePauseStartedAt(): Flow<Long?>

    /** Persists the processing-paused start time, or clears it with null. */
    public suspend fun setPauseStartedAt(epochMs: Long?)

    /** Emits the append-only local PIPA action log, newest-first. */
    public fun observePipaActionLog(): Flow<List<PipaActionLogEntry>>

    /** Appends a local-only PIPA activity-log entry. */
    public suspend fun appendPipaActionLog(entry: PipaActionLogEntry)

    /**
     * Emits whether the user has accepted the terms of service.
     *
     * Defaults to `false`. Once accepted, the value persists across app restarts so the
     * terms screen is not shown again (ONB-001).
     */
    public fun observeTermsAccepted(): Flow<Boolean>

    /**
     * Persists the terms-of-service acceptance flag.
     */
    public suspend fun setTermsAccepted(accepted: Boolean)

    /** Emits the set of source-type strings the user has enabled for foreground ingestion. */
    public fun observeEnabledSources(): Flow<Set<String>>

    /**
     * Emits whether the non-email [sourceType] is enabled for ingestion.
     *
     * Supported values: `voice`, `google_calendar`, `outlook_calendar`.
     * Email sources continue to use [observeEmailSourceConnected].
     */
    public fun observeSourceEnabled(sourceType: String): Flow<Boolean>

    /**
     * Persists whether the non-email [sourceType] is enabled for ingestion.
     *
     * Supported values: `voice`, `google_calendar`, `outlook_calendar`.
     * Email sources continue to use [setEmailSourceConnected].
     */
    public suspend fun setSourceEnabled(sourceType: String, enabled: Boolean)

    /**
     * Emits `true` once the one-shot legacy-to-namespaced [ImapCredentialStore]
     * migration has completed successfully.
     *
     * Defaults to `false` on install. Used exclusively by
     * [com.becalm.android.data.local.secure.ImapCredentialStoreMigrator] to short-circuit
     * on subsequent app starts. Persisted under key `imap_credential_store_migrated_v1`.
     */
    public fun observeImapMigrated(): Flow<Boolean>

    /**
     * Persists the [ImapCredentialStore][com.becalm.android.data.local.secure.ImapCredentialStore]
     * migration-completed flag.
     *
     * The migrator only sets this to `true` after a successful migration or an
     * idempotent no-op. It is never set from product UI — the key is namespaced so
     * a future v2 migration can ship its own flag.
     *
     * @param value `true` once the migration has completed; `false` to reset (tests only).
     */
    public suspend fun setImapMigrated(value: Boolean)

    /**
     * Emits the persisted "user has completed an interactive OAuth grant for this email
     * provider" flag (S6-F/G/H). Backed by per-provider DataStore keys
     * `<provider_storage>_connected` and cleared either by the full-wipe sign-out path
     * or by the future Settings "reconnect" flow.
     *
     * UI layer trusts this flag for the onboarding terminal gate; the credential
     * store is still consulted by network callers before each API request.
     */
    public fun observeEmailSourceConnected(provider: EmailPipaProvider): Flow<Boolean>

    /**
     * Persists the `<provider>_connected` flag (S6-F/G/H).
     *
     * Writes land at the tail of the OAuth / IMAP sign-in success path after the
     * credential has been committed to the encrypted store — write order matters so
     * a crash between the two operations leaves the app in a recoverable state
     * (the flag-off case is interpreted as "re-connect needed").
     */
    public suspend fun setEmailSourceConnected(provider: EmailPipaProvider, connected: Boolean)

    /**
     * Emits whether the email [provider] is backend-managed rather than owned by a local worker.
     */
    public fun observeEmailSourceManagedByBackend(provider: EmailPipaProvider): Flow<Boolean>

    /**
     * Persists whether the email [provider] is backend-managed.
     */
    public suspend fun setEmailSourceManagedByBackend(provider: EmailPipaProvider, managed: Boolean)

    /**
     * Emits the stored PIPA 제3자 제공 consent flag for the email [provider] (one of
     * [EmailPipaProvider.GMAIL], [EmailPipaProvider.OUTLOOK_MAIL],
     * [EmailPipaProvider.NAVER_IMAP], [EmailPipaProvider.DAUM_IMAP]).
     *
     * `false` when the user has never interacted with the consent screen or has
     * explicitly declined; `true` only after an explicit `[동의]` tap on
     * [com.becalm.android.ui.onboarding.OnboardingEmailPipaConsentScreen]. The
     * companion timestamp is stored under `pipa_email_{provider}_consent_at`.
     */
    public fun observeEmailPipaConsent(provider: EmailPipaProvider): Flow<Boolean>

    /**
     * Persists the per-provider email PIPA consent [granted] flag (S6-D).
     *
     * Writing `true` also records a wall-clock epoch-millis timestamp under
     * `pipa_email_{provider}_consent_at` so audit logs can correlate the decision.
     * Writing `false` clears that timestamp.
     *
     * @param provider One of the enumerated email providers.
     * @param granted  `true` on [동의] tap, `false` on [동의 안 함] tap.
     */
    public suspend fun setEmailPipaConsent(provider: EmailPipaProvider, granted: Boolean)

    /**
     * Persists the per-recipient email PIPA consent flag for **every** entry in
     * [providers] inside a single [DataStore.edit] transaction (S6-D, R4 fix).
     *
     * Required by the combined IMAP onboarding disclosure: the UI presents a single
     * all-or-none Agree tap covering Naver Corp and Kakao Corp, so the durable record
     * must also be all-or-none — PIPA Article 17's per-recipient audit trail is only
     * coherent when both keys move together. The single-provider
     * [setEmailPipaConsent] overload remains for Gmail / Outlook disclosures where
     * there is only one recipient and batching adds no value.
     *
     * @param providers Non-empty list of recipients the consent decision applies to.
     * @param granted   `true` on a combined Agree tap; `false` on a combined Decline.
     * @throws IllegalArgumentException when [providers] is empty.
     */
    public suspend fun setEmailPipaConsents(providers: List<EmailPipaProvider>, granted: Boolean)

    /**
     * Atomically clears all preferences stored in this DataStore file.
     *
     * Call during sign-out to ensure the next sign-in starts from default preference
     * values rather than inheriting a previous user's settings.
     */
    public suspend fun clearAll()
}

/**
 * Email PIPA provider namespaces recognised by [UserPrefsStore.setEmailPipaConsent]
 * (S6-D). Kept as an enum rather than loose strings so the consent surface cannot
 * accept a typo'd source name at compile time.
 *
 * PIPA Article 17 requires per-recipient consent — Naver Corp and Kakao Corp are
 * legally distinct recipients for IMAP ingestion, so [NAVER_IMAP] and [DAUM_IMAP]
 * carry independent DataStore records even though the UI presents a single
 * combined IMAP disclosure screen. The onboarding surface writes both records on a
 * unified Agree tap (the user is shown both recipients in the copy) and records
 * [DENIED] on both when the user declines so the revocation UX can target either
 * independently later.
 */
public enum class EmailPipaProvider(public val storageKey: String) {
    GMAIL(storageKey = "gmail"),
    OUTLOOK_MAIL(storageKey = "outlook_mail"),
    NAVER_IMAP(storageKey = "naver_imap"),
    DAUM_IMAP(storageKey = "daum_imap");

    public companion object {
        /**
         * Providers grouped under the combined IMAP onboarding disclosure — the single
         * "imap" screen slug collapses to both recipients at write time so the consent
         * audit remains per-recipient per PIPA Article 17.
         */
        public val IMAP_GROUP: List<EmailPipaProvider> = listOf(NAVER_IMAP, DAUM_IMAP)
    }
}

/** Local-only activity-log row for PIPA rights execution (PIPA-007). */
public data class PipaActionLogEntry(
    val action: String,
    val timestampIso: String,
    val details: Map<String, String> = emptyMap(),
)

// ─── Implementation ──────────────────────────────────────────────────────────

/**
 * [DataStore]-backed implementation of [UserPrefsStore].
 *
 * All data is written to the `becalm_user_prefs.preferences_pb` file provided by the
 * [DataStoreModule.provideUserPrefsDataStore] binding.
 *
 * ## Key scheme
 * Global keys (shared across all users on the device):
 * | Preference                       | Key type | Key name                         | Default    |
 * |----------------------------------|----------|----------------------------------|------------|
 * | Current user ID                  | String   | `current_user_id`                | null       |
 * | Theme mode                       | String   | `theme_mode`                     | "system"   |
 * | Locale tag                       | String   | `locale_tag`                     | null       |
 * | Doze prompt dismissed at         | Long     | `doze_whitelist_prompt_dismissed`| null       |
 * | Notifications enabled            | Boolean  | `notifications_enabled`          | true       |
 * | Terms accepted                   | Boolean  | `terms_accepted`                 | false      |
 * | IMAP store migrated (v1)         | Boolean  | `imap_credential_store_migrated_v1` | false   |
 *
 * User-scoped keys (AUTH-008, `.spec/auth.spec.yml:73`): namespaced as
 * `user_<sha256(user_id)[:16]>_<base>` — a different account on the same device
 * never observes the prior user's values. Reads return the default when
 * `current_user_id` is null (i.e. pre-sign-in); writes silently no-op on the same
 * precondition.
 *
 * | Preference                       | Base key                         | Default |
 * |----------------------------------|----------------------------------|---------|
 * | Onboarding completed             | `onboarding_completed`           | false   |
 * | PIPA third-party consent (voice) | `pipa_third_party_consent`       | false   |
 * | PIPA consent timestamp millis    | `pipa_consent_timestamp_millis`  | null    |
 * | Processing paused                | `processing_paused`              | false   |
 * | Processing pause started at      | `pause_started_at`               | null    |
 * | PIPA action log (JSON array)     | `pipa_action_log`                | []      |
 * | Per-provider PIPA consent        | `pipa_email_<provider>_consent`  | false   |
 * | Per-provider PIPA consent at     | `pipa_email_<provider>_consent_at` | null  |
 * | Per-provider source connected    | `<provider>_connected`           | false   |
 * | Per-provider backend owner flag  | `<provider>_backend_managed`     | false   |
 */
public class UserPrefsStoreImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPrefsStore {

    private val currentUserIdKey = stringPreferencesKey("current_user_id")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val localeTagKey = stringPreferencesKey("locale_tag")
    private val dozePromptDismissedAtKey = longPreferencesKey("doze_whitelist_prompt_dismissed")
    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
    private val termsAcceptedKey = booleanPreferencesKey("terms_accepted")
    private val imapCredentialStoreMigratedKey = booleanPreferencesKey("imap_credential_store_migrated_v1")

    // AUTH-008 (.spec/auth.spec.yml:73) requires user-scoped DataStore namespaces for
    // cursor/onboarding/PIPA flags so a different account on the same device never
    // inherits the prior user's session. The helpers below derive
    // `user_<hash>_<base>` key names where <hash> is the first 16 hex chars of the
    // signed-in user's SHA-256 UUID — identical to the Room filename suffix
    // [BeCalmDatabase.deriveUserIdHash] applies.
    private fun userScoped(userId: String): UserScopedPrefsKeys = UserScopedPrefsKeys(userId)

    private fun namespaced(userId: String, base: String): String =
        "user_${com.becalm.android.data.local.db.BeCalmDatabase.deriveUserIdHash(userId)}_$base"

    private fun observeUserBoolean(
        default: Boolean,
        keyFor: UserScopedPrefsKeys.() -> Preferences.Key<Boolean>,
    ): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map default
            prefs[userScoped(userId).keyFor()] ?: default
        }

    private fun observeUserLong(
        keyFor: UserScopedPrefsKeys.() -> Preferences.Key<Long>,
    ): Flow<Long?> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map null
            prefs[userScoped(userId).keyFor()]
        }

    private suspend fun editUserBoolean(
        value: Boolean,
        keyFor: UserScopedPrefsKeys.() -> Preferences.Key<Boolean>,
    ) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[userScoped(userId).keyFor()] = value
        }
    }

    private suspend fun editUserLong(
        value: Long?,
        keyFor: UserScopedPrefsKeys.() -> Preferences.Key<Long>,
    ) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            val key = userScoped(userId).keyFor()
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    override fun observeCurrentUserId(): Flow<String?> =
        dataStore.data.map { it[currentUserIdKey] }

    override suspend fun setCurrentUserId(userId: String?) =
        dataStore.editNullable(currentUserIdKey, userId)

    override fun observeOnboardingCompleted(): Flow<Boolean> =
        observeUserBoolean(default = false) { onboardingCompletedKey }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        editUserBoolean(completed) { onboardingCompletedKey }
    }

    override fun observeOnboardingStepStatuses(): Flow<Map<String, String>> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map emptyMap()
            decodeStringMap(prefs[userScoped(userId).onboardingStepStatusesKey])
        }

    override suspend fun setOnboardingStepStatuses(statuses: Map<String, String>) {
        if (statuses.isEmpty()) return
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            val key = userScoped(userId).onboardingStepStatusesKey
            val merged = decodeStringMap(prefs[key]) + statuses
            prefs[key] = encodeStringMap(merged)
        }
    }

    override fun observeColdSyncStage1CompletedAt(): Flow<Long?> =
        observeUserLong { coldSyncStage1CompletedAtKey }

    override suspend fun setColdSyncStage1CompletedAt(epochMs: Long?) =
        editUserLong(epochMs) { coldSyncStage1CompletedAtKey }

    override fun observeColdSyncStage1Deferred(): Flow<Boolean> =
        observeUserBoolean(default = false) { coldSyncStage1DeferredKey }

    override suspend fun setColdSyncStage1Deferred(deferred: Boolean) {
        editUserBoolean(deferred) { coldSyncStage1DeferredKey }
    }

    override fun observeColdSyncDeferredAt(): Flow<Long?> =
        observeUserLong { coldSyncDeferredAtKey }

    override suspend fun setColdSyncDeferredAt(epochMs: Long?) =
        editUserLong(epochMs) { coldSyncDeferredAtKey }

    override fun observeColdSyncStage2CompletedAt(): Flow<Long?> =
        observeUserLong { coldSyncStage2CompletedAtKey }

    override suspend fun setColdSyncStage2CompletedAt(epochMs: Long?) =
        editUserLong(epochMs) { coldSyncStage2CompletedAtKey }

    override fun observeColdSyncStage2Deferred(): Flow<Boolean> =
        observeUserBoolean(default = false) { coldSyncStage2DeferredKey }

    override suspend fun setColdSyncStage2Deferred(deferred: Boolean) {
        editUserBoolean(deferred) { coldSyncStage2DeferredKey }
    }

    override fun observeRecordingFolderTreeUri(): Flow<String?> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map null
            prefs[userScoped(userId).recordingFolderTreeUriKey]
        }

    override suspend fun setRecordingFolderTreeUri(uri: String?) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            val key = userScoped(userId).recordingFolderTreeUriKey
            if (uri == null) prefs.remove(key) else prefs[key] = uri
        }
    }

    override fun observeThemeMode(): Flow<String> =
        dataStore.data.map { it[themeModeKey] ?: "system" }

    override suspend fun setThemeMode(mode: String) {
        dataStore.edit { prefs -> prefs[themeModeKey] = mode }
    }

    override fun observeLocaleTag(): Flow<String?> =
        dataStore.data.map { it[localeTagKey] }

    override suspend fun setLocaleTag(tag: String?) =
        dataStore.editNullable(localeTagKey, tag)

    override fun observeDozePromptDismissedAt(): Flow<Long?> =
        dataStore.data.map { it[dozePromptDismissedAtKey] }

    override suspend fun setDozePromptDismissedAt(epochMs: Long?) =
        dataStore.editNullable(dozePromptDismissedAtKey, epochMs)

    override fun observeNotificationsEnabled(): Flow<Boolean> =
        dataStore.data.map { it[notificationsEnabledKey] ?: true }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[notificationsEnabledKey] = enabled }
    }

    override fun observeThirdPartyProvisionConsent(): Flow<Boolean> =
        observeUserBoolean(default = false) { pipaThirdPartyConsentKey }

    override suspend fun setThirdPartyProvisionConsent(granted: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            val keys = userScoped(userId)
            prefs[keys.pipaThirdPartyConsentKey] = granted
            if (granted) {
                prefs[keys.pipaConsentTimestampKey] = System.currentTimeMillis()
            } else {
                prefs.remove(keys.pipaConsentTimestampKey)
            }
        }
    }

    override fun observeProcessingPaused(): Flow<Boolean> =
        observeUserBoolean(default = false) { processingPausedKey }

    override suspend fun setProcessingPaused(paused: Boolean) {
        editUserBoolean(paused) { processingPausedKey }
    }

    override fun observePauseStartedAt(): Flow<Long?> =
        observeUserLong { pauseStartedAtKey }

    override suspend fun setPauseStartedAt(epochMs: Long?) =
        editUserLong(epochMs) { pauseStartedAtKey }

    override fun observePipaActionLog(): Flow<List<PipaActionLogEntry>> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map emptyList()
            decodePipaActionLog(prefs[userScoped(userId).pipaActionLogKey])
        }

    override suspend fun appendPipaActionLog(entry: PipaActionLogEntry) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            val key = userScoped(userId).pipaActionLogKey
            val existing = decodePipaActionLog(prefs[key]).toMutableList()
            existing.add(0, entry)
            prefs[key] = encodePipaActionLog(existing)
        }
    }

    override fun observeTermsAccepted(): Flow<Boolean> =
        dataStore.data.map { it[termsAcceptedKey] ?: false }

    override suspend fun setTermsAccepted(accepted: Boolean) {
        dataStore.edit { prefs -> prefs[termsAcceptedKey] = accepted }
    }

    override fun observeEnabledSources(): Flow<Set<String>> =
        combine(
            observeSourceEnabled(SourceType.VOICE),
            observeEmailSourceConnected(EmailPipaProvider.GMAIL),
            observeEmailSourceManagedByBackend(EmailPipaProvider.GMAIL),
            observeEmailSourceConnected(EmailPipaProvider.OUTLOOK_MAIL),
            observeEmailSourceManagedByBackend(EmailPipaProvider.OUTLOOK_MAIL),
            observeEmailSourceConnected(EmailPipaProvider.NAVER_IMAP),
            observeEmailSourceManagedByBackend(EmailPipaProvider.NAVER_IMAP),
            observeEmailSourceConnected(EmailPipaProvider.DAUM_IMAP),
            observeEmailSourceManagedByBackend(EmailPipaProvider.DAUM_IMAP),
            observeSourceEnabled(SourceType.GOOGLE_CALENDAR),
            observeSourceEnabled(SourceType.OUTLOOK_CALENDAR),
        ) { values ->
            buildSet {
                if (values[0]) add(SourceType.VOICE)
                if (values[1] && !values[2]) add(SourceType.GMAIL)
                if (values[3] && !values[4]) add(SourceType.OUTLOOK_MAIL)
                if (values[5] && !values[6]) add(SourceType.NAVER_IMAP)
                if (values[7] && !values[8]) add(SourceType.DAUM_IMAP)
                if (values[9]) add(SourceType.GOOGLE_CALENDAR)
                if (values[10]) add(SourceType.OUTLOOK_CALENDAR)
            }
        }

    override fun observeSourceEnabled(sourceType: String): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[userScoped(userId).sourceEnabledKey(sourceType)] ?: false
        }

    override suspend fun setSourceEnabled(sourceType: String, enabled: Boolean) {
        require(sourceType in SUPPORTED_NON_EMAIL_SOURCES) {
            "setSourceEnabled only supports $SUPPORTED_NON_EMAIL_SOURCES, got '$sourceType'"
        }
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[userScoped(userId).sourceEnabledKey(sourceType)] = enabled
        }
    }

    override fun observeImapMigrated(): Flow<Boolean> =
        dataStore.data.map { it[imapCredentialStoreMigratedKey] ?: false }

    override suspend fun setImapMigrated(value: Boolean) {
        dataStore.edit { prefs -> prefs[imapCredentialStoreMigratedKey] = value }
    }

    override fun observeEmailSourceConnected(provider: EmailPipaProvider): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[userScoped(userId).emailSourceConnectedKey(provider)] ?: false
        }

    override suspend fun setEmailSourceConnected(provider: EmailPipaProvider, connected: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[userScoped(userId).emailSourceConnectedKey(provider)] = connected
        }
    }

    override fun observeEmailSourceManagedByBackend(provider: EmailPipaProvider): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[userScoped(userId).emailSourceBackendManagedKey(provider)] ?: false
        }

    override suspend fun setEmailSourceManagedByBackend(provider: EmailPipaProvider, managed: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[userScoped(userId).emailSourceBackendManagedKey(provider)] = managed
        }
    }

    override fun observeEmailPipaConsent(provider: EmailPipaProvider): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[userScoped(userId).emailPipaConsentKey(provider)] ?: false
        }

    override suspend fun setEmailPipaConsent(provider: EmailPipaProvider, granted: Boolean) {
        setEmailPipaConsents(listOf(provider), granted)
    }

    override suspend fun setEmailPipaConsents(providers: List<EmailPipaProvider>, granted: Boolean) {
        require(providers.isNotEmpty()) { "providers must be non-empty" }
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            val keys = userScoped(userId)
            val now = System.currentTimeMillis()
            for (provider in providers) {
                prefs[keys.emailPipaConsentKey(provider)] = granted
                if (granted) {
                    prefs[keys.emailPipaConsentAtKey(provider)] = now
                } else {
                    prefs.remove(keys.emailPipaConsentAtKey(provider))
                }
            }
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    private inner class UserScopedPrefsKeys(userId: String) {
        private val scopedUserId: String = userId

        private fun booleanKey(base: String) = booleanPreferencesKey(namespaced(scopedUserId, base))
        private fun longKey(base: String) = longPreferencesKey(namespaced(scopedUserId, base))

        val onboardingCompletedKey: Preferences.Key<Boolean> =
            booleanKey("onboarding_completed")
        val onboardingStepStatusesKey: Preferences.Key<String> =
            stringPreferencesKey(namespaced(scopedUserId, "onboarding_step_statuses_v1"))
        val coldSyncStage1CompletedAtKey: Preferences.Key<Long> =
            longKey("cold_sync_stage1_completed_at")
        val coldSyncStage1DeferredKey: Preferences.Key<Boolean> =
            booleanKey("cold_sync_stage1_deferred")
        val coldSyncDeferredAtKey: Preferences.Key<Long> =
            longKey("cold_sync_deferred_at")
        val coldSyncStage2CompletedAtKey: Preferences.Key<Long> =
            longKey("cold_sync_stage2_completed_at")
        val coldSyncStage2DeferredKey: Preferences.Key<Boolean> =
            booleanKey("cold_sync_stage2_deferred")
        val recordingFolderTreeUriKey: Preferences.Key<String> =
            stringPreferencesKey(namespaced(scopedUserId, "recording_folder_tree_uri"))
        val pipaThirdPartyConsentKey: Preferences.Key<Boolean> =
            booleanKey("pipa_third_party_consent")
        val pipaConsentTimestampKey: Preferences.Key<Long> =
            longKey("pipa_consent_timestamp_millis")
        val processingPausedKey: Preferences.Key<Boolean> =
            booleanKey("processing_paused")
        val pauseStartedAtKey: Preferences.Key<Long> =
            longKey("pause_started_at")
        val pipaActionLogKey: Preferences.Key<String> =
            stringPreferencesKey(namespaced(scopedUserId, "pipa_action_log"))

        fun emailPipaConsentKey(provider: EmailPipaProvider): Preferences.Key<Boolean> =
            booleanKey("pipa_email_${provider.storageKey}_consent")

        fun emailPipaConsentAtKey(provider: EmailPipaProvider): Preferences.Key<Long> =
            longKey("pipa_email_${provider.storageKey}_consent_at")

        fun emailSourceConnectedKey(provider: EmailPipaProvider): Preferences.Key<Boolean> =
            booleanKey("${provider.storageKey}_connected")

        fun emailSourceBackendManagedKey(provider: EmailPipaProvider): Preferences.Key<Boolean> =
            booleanKey("${provider.storageKey}_backend_managed")

        fun sourceEnabledKey(sourceType: String): Preferences.Key<Boolean> =
            booleanKey("${sourceType}_enabled")
    }

    private fun encodePipaActionLog(entries: List<PipaActionLogEntry>): String =
        JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject().apply {
                        put("action", entry.action)
                        put("timestamp_iso", entry.timestampIso)
                        put(
                            "details",
                            JSONObject().apply {
                                entry.details.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    },
                )
            }
        }.toString()

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun encodeStringMap(values: Map<String, String>): String =
        JSONObject().apply {
            values.forEach { (key, value) -> put(key, value) }
        }.toString()

    private fun decodePipaActionLog(raw: String?): List<PipaActionLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val detailsObject = item.optJSONObject("details") ?: JSONObject()
                    val details = buildMap {
                        val keys = detailsObject.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, detailsObject.optString(key))
                        }
                    }
                    add(
                        PipaActionLogEntry(
                            action = item.optString("action"),
                            timestampIso = item.optString("timestamp_iso"),
                            details = details,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        val SUPPORTED_NON_EMAIL_SOURCES: Set<String> = setOf(
            SourceType.VOICE,
            SourceType.GOOGLE_CALENDAR,
            SourceType.OUTLOOK_CALENDAR,
        )
    }
}
