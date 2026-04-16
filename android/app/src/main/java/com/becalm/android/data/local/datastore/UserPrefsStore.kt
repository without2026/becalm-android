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
     * Atomically clears all preferences stored in this DataStore file.
     *
     * Call during sign-out to ensure the next sign-in starts from default preference
     * values rather than inheriting a previous user's settings.
     */
    public suspend fun clearAll()
}

// ─── Implementation ──────────────────────────────────────────────────────────

/**
 * [DataStore]-backed implementation of [UserPrefsStore].
 *
 * All data is written to the `becalm_user_prefs.preferences_pb` file provided by the
 * [DataStoreModule.provideUserPrefsDataStore] binding.
 *
 * ## Key scheme
 * | Preference                       | Key type | Key name                         | Default    |
 * |----------------------------------|----------|----------------------------------|------------|
 * | Current user ID                  | String   | `current_user_id`                | null       |
 * | Onboarding completed             | Boolean  | `onboarding_completed`           | false      |
 * | Theme mode                       | String   | `theme_mode`                     | "system"   |
 * | Locale tag                       | String   | `locale_tag`                     | null       |
 * | Doze prompt dismissed at         | Long     | `doze_whitelist_prompt_dismissed`| null       |
 */
public class UserPrefsStoreImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPrefsStore {

    private val currentUserIdKey = stringPreferencesKey("current_user_id")
    private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val localeTagKey = stringPreferencesKey("locale_tag")
    private val dozePromptDismissedAtKey = longPreferencesKey("doze_whitelist_prompt_dismissed")

    override fun observeCurrentUserId(): Flow<String?> =
        dataStore.data.map { it[currentUserIdKey] }

    override suspend fun setCurrentUserId(userId: String?) {
        dataStore.edit { prefs ->
            if (userId != null) prefs[currentUserIdKey] = userId
            else prefs.remove(currentUserIdKey)
        }
    }

    override fun observeOnboardingCompleted(): Flow<Boolean> =
        dataStore.data.map { it[onboardingCompletedKey] ?: false }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs -> prefs[onboardingCompletedKey] = completed }
    }

    override fun observeThemeMode(): Flow<String> =
        dataStore.data.map { it[themeModeKey] ?: "system" }

    override suspend fun setThemeMode(mode: String) {
        dataStore.edit { prefs -> prefs[themeModeKey] = mode }
    }

    override fun observeLocaleTag(): Flow<String?> =
        dataStore.data.map { it[localeTagKey] }

    override suspend fun setLocaleTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag != null) prefs[localeTagKey] = tag else prefs.remove(localeTagKey)
        }
    }

    override fun observeDozePromptDismissedAt(): Flow<Long?> =
        dataStore.data.map { it[dozePromptDismissedAtKey] }

    override suspend fun setDozePromptDismissedAt(epochMs: Long?) {
        dataStore.edit { prefs ->
            if (epochMs != null) prefs[dozePromptDismissedAtKey] = epochMs
            else prefs.remove(dozePromptDismissedAtKey)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
