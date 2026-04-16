package com.becalm.android.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.SyncCursorStoreImpl
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.annotation.AnnotationRetention.BINARY

// ─── Qualifier annotations ───────────────────────────────────────────────────

/**
 * Hilt qualifier that selects the [DataStore] instance backed by the
 * `becalm_sync_cursors.preferences_pb` file.
 *
 * Required because Hilt's dependency graph contains two `DataStore<Preferences>` bindings
 * (one for cursors, one for user prefs). Without distinct qualifiers the Hilt annotation
 * processor cannot resolve which instance to inject at each call site and fails with a
 * "Cannot be provided without an @Provides-annotated method" compile error.
 */
@Qualifier
@Retention(BINARY)
public annotation class SyncCursors

/**
 * Hilt qualifier that selects the [DataStore] instance backed by the
 * `becalm_user_prefs.preferences_pb` file.
 *
 * See [SyncCursors] for the rationale behind having two separate qualifiers.
 */
@Qualifier
@Retention(BINARY)
public annotation class UserPrefs

// ─── DataStore delegate properties ──────────────────────────────────────────

/**
 * Top-level delegate that creates (or re-opens) the `becalm_sync_cursors` DataStore file
 * on the given [Context].
 *
 * The [preferencesDataStore] delegate guarantees that only one [DataStore] instance per
 * file name is active in the process. Calling this property on the same context repeatedly
 * always returns the same instance.
 *
 * This property is intentionally `private` — consumers obtain the instance exclusively
 * through the Hilt graph via [DataStoreModule.provideSyncCursorsDataStore].
 */
private val Context.syncCursorsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "becalm_sync_cursors")

/**
 * Top-level delegate that creates (or re-opens) the `becalm_user_prefs` DataStore file
 * on the given [Context].
 *
 * See [syncCursorsDataStore] for general notes. Keeping user preferences in a separate
 * file prevents a [SyncCursorStore.clearAll] call (issued on cursor invalidation or
 * during sync engine reset) from wiping durable user-facing settings.
 *
 * This property is intentionally `private` — consumers obtain the instance exclusively
 * through the Hilt graph via [DataStoreModule.provideUserPrefsDataStore].
 */
private val Context.userPrefsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "becalm_user_prefs")

// ─── Hilt module ─────────────────────────────────────────────────────────────

/**
 * Hilt module that wires all DataStore dependencies for the BeCalm Android app.
 *
 * ## Two files, two qualifiers
 * The module provides two separate `DataStore<Preferences>` instances:
 * - [@SyncCursors][SyncCursors] → `becalm_sync_cursors.preferences_pb` — ephemeral
 *   per-source pagination state. Can be cleared freely without affecting UI settings.
 * - [@UserPrefs][UserPrefs] → `becalm_user_prefs.preferences_pb` — durable user
 *   preferences (theme, locale, onboarding state). Cleared only on explicit sign-out.
 *
 * Hilt requires [SyncCursors] and [UserPrefs] qualifiers because its annotation processor
 * rejects two unqualified `@Provides` methods with the same return type in the same
 * component — it cannot determine which binding to use at injection sites.
 *
 * ## Security boundary
 * Neither DataStore file holds auth tokens or cryptographic material. Those belong
 * exclusively to SP-15's `EncryptedTokenStore` (EncryptedSharedPreferences-backed).
 */
@Module
@InstallIn(SingletonComponent::class)
public object DataStoreModule {

    /**
     * Provides the singleton [DataStore] instance for sync cursors.
     *
     * Scoped to [SingletonComponent] so all repositories and workers share the same
     * file handle. The [preferencesDataStore] delegate enforces single-instance
     * semantics at the OS level as well.
     *
     * @param context Application context supplied by Hilt's [@ApplicationContext] qualifier.
     */
    @Provides
    @Singleton
    @SyncCursors
    public fun provideSyncCursorsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.syncCursorsDataStore

    /**
     * Provides the singleton [DataStore] instance for user preferences.
     *
     * @param context Application context supplied by Hilt's [@ApplicationContext] qualifier.
     */
    @Provides
    @Singleton
    @UserPrefs
    public fun provideUserPrefsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.userPrefsDataStore

    /**
     * Provides the singleton [SyncCursorStore] backed by the [@SyncCursors][SyncCursors]
     * DataStore instance.
     *
     * Sync workers and repositories inject [SyncCursorStore] directly — they never depend
     * on the underlying [DataStore] to avoid bypassing the typed cursor API.
     *
     * @param ds The [@SyncCursors][SyncCursors]-qualified DataStore instance.
     */
    @Provides
    @Singleton
    public fun provideSyncCursorStore(
        @SyncCursors ds: DataStore<Preferences>,
    ): SyncCursorStore = SyncCursorStoreImpl(ds)

    /**
     * Provides the singleton [UserPrefsStore] backed by the [@UserPrefs][UserPrefs]
     * DataStore instance.
     *
     * ViewModels and navigation controllers inject [UserPrefsStore] directly rather than
     * the raw [DataStore] so that key-naming details remain encapsulated inside
     * [UserPrefsStoreImpl].
     *
     * @param ds The [@UserPrefs][UserPrefs]-qualified DataStore instance.
     */
    @Provides
    @Singleton
    public fun provideUserPrefsStore(
        @UserPrefs ds: DataStore<Preferences>,
    ): UserPrefsStore = UserPrefsStoreImpl(ds)
}
