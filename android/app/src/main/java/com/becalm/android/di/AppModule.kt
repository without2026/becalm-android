package com.becalm.android.di

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.WorkManager
import com.becalm.android.BuildConfig
import com.becalm.android.data.local.BeCalmDatabase
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.dao.EmailBodyDao
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.dao.TranscriptDao
import com.becalm.android.data.remote.api.BeCalmApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

// DataStore extension — single instance per app
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "becalm_prefs")

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthPrefs

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---- Room Database ----

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BeCalmDatabase =
        Room.databaseBuilder(context, BeCalmDatabase::class.java, BeCalmDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration() // MVP only — production will use proper migrations
            .build()

    @Provides fun provideRawIngestionEventDao(db: BeCalmDatabase): RawIngestionEventDao = db.rawIngestionEventDao()
    @Provides fun provideCommitmentDao(db: BeCalmDatabase): CommitmentDao = db.commitmentDao()
    @Provides fun provideCalendarEventDao(db: BeCalmDatabase): CalendarEventDao = db.calendarEventDao()
    @Provides fun providePersonEnrichmentDao(db: BeCalmDatabase): PersonEnrichmentDao = db.personEnrichmentDao()
    @Provides fun provideTranscriptDao(db: BeCalmDatabase): TranscriptDao = db.transcriptDao()
    @Provides fun provideEmailBodyDao(db: BeCalmDatabase): EmailBodyDao = db.emailBodyDao()

    // ---- DataStore ----

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore

    // ---- WorkManager ----

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    // ---- Auth token storage (spec: AUTH-001) ----
    // EncryptedSharedPreferences for Supabase JWT tokens

    @Provides
    @Singleton
    @AuthPrefs
    fun provideEncryptedSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "becalm_auth_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---- Network ----

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BECALM_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideBeCalmApi(retrofit: Retrofit): BeCalmApi =
        retrofit.create(BeCalmApi::class.java)
}
