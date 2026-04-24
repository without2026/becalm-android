package com.becalm.android.integration.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

internal object LocalIntegrationSupport {
    fun appContext(): Context = ApplicationProvider.getApplicationContext()

    fun inMemoryDatabase(): BeCalmDatabase =
        Room.inMemoryDatabaseBuilder(appContext(), BeCalmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun prefsDataStore(prefix: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = {
                File.createTempFile(prefix, ".preferences_pb").apply { deleteOnExit() }
            },
        )

    fun railwayApi(server: MockWebServer): RailwayApi {
        val moshi = Moshi.Builder()
            .addBecalmAdapters()
            .add(KotlinJsonAdapterFactory())
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return retrofit.create(RailwayApi::class.java)
    }

    fun authenticatedSession(
        userId: String = "user-1",
        email: String = "user@example.com",
    ): SupabaseSession = SupabaseSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        userId = userId,
        email = email,
        expiresAt = Instant.parse("2026-04-24T00:00:00Z"),
    )

    fun workerParams(
        inputData: Data = Data.EMPTY,
        runAttemptCount: Int = 0,
    ): WorkerParameters {
        val directExecutor: Executor = Executor { runnable -> runnable.run() }
        val taskExecutor: TaskExecutor = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = directExecutor
            override fun getSerialTaskExecutor(): androidx.work.impl.utils.taskexecutor.SerialExecutor =
                object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
                    override fun execute(command: Runnable) = command.run()
                    override fun hasPendingTasks(): Boolean = false
                }
        }
        val progressUpdater: ProgressUpdater = ProgressUpdater { _, _, _ ->
            SettableFuture.create<Void>().apply { set(null) }
        }
        val foregroundUpdater: ForegroundUpdater = ForegroundUpdater { _, _, _ ->
            SettableFuture.create<Void>().apply { set(null) }
        }
        return mockk<WorkerParameters>().also { params ->
            every { params.id } returns UUID.randomUUID()
            every { params.inputData } returns inputData
            every { params.tags } returns emptySet()
            every { params.triggeredContentUris } returns emptyList()
            every { params.triggeredContentAuthorities } returns emptyList()
            every { params.network } returns null
            every { params.runAttemptCount } returns runAttemptCount
            every { params.backgroundExecutor } returns directExecutor
            every { params.taskExecutor } returns taskExecutor
            every { params.workerFactory } returns WorkerFactory.getDefaultWorkerFactory()
            every { params.progressUpdater } returns progressUpdater
            every { params.foregroundUpdater } returns foregroundUpdater
        }
    }
}
