package com.becalm.android.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    /**
     * Emits the set of source-type strings the user has enabled for ingestion.
     *
     * Stub (SP-14.1): always emits an empty set until the full source-enable UI is built.
     */
    public fun observeEnabledSources(): Flow<Set<String>>

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
     * @param provider One of the enumerated email providers (`GMAIL`, `OUTLOOK_MAIL`, `IMAP`).
     * @param granted  `true` on [동의] tap, `false` on [동의 안 함] tap.
     */
    public suspend fun setEmailPipaConsent(provider: EmailPipaProvider, granted: Boolean)

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
 * | Per-provider PIPA consent        | `pipa_email_<provider>_consent`  | false   |
 * | Per-provider PIPA consent at     | `pipa_email_<provider>_consent_at` | null  |
 * | Per-provider source connected    | `<provider>_connected`           | false   |
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
    private fun userOnboardingCompletedKey(userId: String) =
        booleanPreferencesKey(namespaced(userId, "onboarding_completed"))

    private fun userPipaThirdPartyConsentKey(userId: String) =
        booleanPreferencesKey(namespaced(userId, "pipa_third_party_consent"))

    private fun userPipaConsentTimestampKey(userId: String) =
        longPreferencesKey(namespaced(userId, "pipa_consent_timestamp_millis"))

    private fun emailPipaConsentKey(userId: String, provider: EmailPipaProvider) =
        booleanPreferencesKey(namespaced(userId, "pipa_email_${provider.storageKey}_consent"))

    private fun emailPipaConsentAtKey(userId: String, provider: EmailPipaProvider) =
        longPreferencesKey(namespaced(userId, "pipa_email_${provider.storageKey}_consent_at"))

    private fun emailSourceConnectedKey(userId: String, provider: EmailPipaProvider) =
        booleanPreferencesKey(namespaced(userId, "${provider.storageKey}_connected"))

    private fun namespaced(userId: String, base: String): String =
        "user_${com.becalm.android.data.local.db.BeCalmDatabase.deriveUserIdHash(userId)}_$base"

    override fun observeCurrentUserId(): Flow<String?> =
        dataStore.data.map { it[currentUserIdKey] }

    override suspend fun setCurrentUserId(userId: String?) =
        dataStore.editNullable(currentUserIdKey, userId)

    override fun observeOnboardingCompleted(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[userOnboardingCompletedKey(userId)] ?: false
        }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[userOnboardingCompletedKey(userId)] = completed
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
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[userPipaThirdPartyConsentKey(userId)] ?: false
        }

    override suspend fun setThirdPartyProvisionConsent(granted: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[userPipaThirdPartyConsentKey(userId)] = granted
            if (granted) {
                prefs[userPipaConsentTimestampKey(userId)] = System.currentTimeMillis()
            } else {
                prefs.remove(userPipaConsentTimestampKey(userId))
            }
        }
    }

    override fun observeTermsAccepted(): Flow<Boolean> =
        dataStore.data.map { it[termsAcceptedKey] ?: false }

    override suspend fun setTermsAccepted(accepted: Boolean) {
        dataStore.edit { prefs -> prefs[termsAcceptedKey] = accepted }
    }

    // Stub (SP-14.1): always emits empty set until the source-enable UI lands.
    override fun observeEnabledSources(): Flow<Set<String>> =
        kotlinx.coroutines.flow.flowOf(emptySet())

    override fun observeImapMigrated(): Flow<Boolean> =
        dataStore.data.map { it[imapCredentialStoreMigratedKey] ?: false }

    override suspend fun setImapMigrated(value: Boolean) {
        dataStore.edit { prefs -> prefs[imapCredentialStoreMigratedKey] = value }
    }

    override fun observeEmailSourceConnected(provider: EmailPipaProvider): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[emailSourceConnectedKey(userId, provider)] ?: false
        }

    override suspend fun setEmailSourceConnected(provider: EmailPipaProvider, connected: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[emailSourceConnectedKey(userId, provider)] = connected
        }
    }

    override fun observeEmailPipaConsent(provider: EmailPipaProvider): Flow<Boolean> =
        dataStore.data.map { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@map false
            prefs[emailPipaConsentKey(userId, provider)] ?: false
        }

    override suspend fun setEmailPipaConsent(provider: EmailPipaProvider, granted: Boolean) {
        dataStore.edit { prefs ->
            val userId = prefs[currentUserIdKey] ?: return@edit
            prefs[emailPipaConsentKey(userId, provider)] = granted
            if (granted) {
                prefs[emailPipaConsentAtKey(userId, provider)] = System.currentTimeMillis()
            } else {
                prefs.remove(emailPipaConsentAtKey(userId, provider))
            }
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
