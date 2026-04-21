package com.becalm.android.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.MetricsStoreImpl
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
 * Selects the [DataStore] backed by `becalm_sync_cursors.preferences_pb`.
 * Required because the graph has two `DataStore<Preferences>` bindings — without
 * distinct qualifiers Hilt's AP cannot resolve injection sites.
 */
@Qualifier
@Retention(BINARY)
public annotation class SyncCursors

/**
 * Selects the [DataStore] backed by `becalm_user_prefs.preferences_pb`.
 * See [SyncCursors] for rationale.
 */
@Qualifier
@Retention(BINARY)
public annotation class UserPrefs

// ─── DataStore delegate properties ──────────────────────────────────────────

// Private — consumers obtain instances exclusively through the Hilt graph.
private val Context.syncCursorsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "becalm_sync_cursors")

// Separate file from syncCursorsDataStore so a clearAll() on cursors never wipes user prefs.
private val Context.userPrefsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "becalm_user_prefs")

// ─── Hilt module ─────────────────────────────────────────────────────────────

/**
 * Wires all DataStore dependencies for the BeCalm Android app.
 *
 * Provides two `DataStore<Preferences>` instances distinguished by qualifiers:
 * - [@SyncCursors][SyncCursors] — ephemeral per-source pagination state.
 * - [@UserPrefs][UserPrefs] — durable user preferences (theme, locale, onboarding).
 *
 * Neither file holds auth tokens or cryptographic material; those belong exclusively
 * to SP-15's `EncryptedTokenStore` (EncryptedSharedPreferences-backed).
 */
@Module
@InstallIn(SingletonComponent::class)
public object DataStoreModule {

    @Provides
    @Singleton
    @SyncCursors
    public fun provideSyncCursorsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.syncCursorsDataStore

    @Provides
    @Singleton
    @UserPrefs
    public fun provideUserPrefsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.userPrefsDataStore

    @Provides
    @Singleton
    public fun provideSyncCursorStore(
        @SyncCursors ds: DataStore<Preferences>,
    ): SyncCursorStore = SyncCursorStoreImpl(ds)

    @Provides
    @Singleton
    public fun provideUserPrefsStore(
        @UserPrefs ds: DataStore<Preferences>,
    ): UserPrefsStore = UserPrefsStoreImpl(ds)

    /**
     * Wires [MetricsStoreImpl] over the same [@UserPrefs][UserPrefs] DataStore so the
     * `email_subject_only_skipped` counter shares a file with [UserPrefsStore] (one fewer
     * `.preferences_pb` to manage at sign-out wipe). Hilt injects the qualifier through
     * the impl's `@Inject` constructor; this @Provides surfaces the interface.
     */
    @Provides
    @Singleton
    public fun provideMetricsStore(
        @UserPrefs ds: DataStore<Preferences>,
    ): MetricsStore = MetricsStoreImpl(ds)
}
