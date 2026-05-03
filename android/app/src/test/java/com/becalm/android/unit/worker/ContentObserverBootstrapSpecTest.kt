package com.becalm.android.unit.worker

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.provider.MediaStore
import com.becalm.android.core.util.Logger
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.ForegroundWorkScheduler
import com.becalm.android.worker.ProcessingPauseGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ContentObserverBootstrapSpecTest {

    private val context: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val workScheduler: ForegroundWorkScheduler = mockk(relaxed = true)
    private val processingPauseGate: ProcessingPauseGate = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `ING-001 start registers mediasore observer once and observed changes enqueue catch-up`() {
        val observer = slot<ContentObserver>()
        every { context.contentResolver } returns contentResolver

        every { processingPauseGate.isPausedBlocking() } returns false
        val bootstrap = ContentObserverBootstrap(context, workScheduler, processingPauseGate, logger)

        bootstrap.start()
        bootstrap.start()

        verify(exactly = 1) {
            contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                capture(observer),
            )
        }
        verify(exactly = 1) {
            contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                any(),
            )
        }

        observer.captured.onChange(false, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        verify(exactly = 1) { workScheduler.enqueueMediaStoreOneShotNow() }
    }

    @Test
    fun `ING-001 stop unregisters registered observer`() {
        val observer = slot<ContentObserver>()
        every { context.contentResolver } returns contentResolver

        every { processingPauseGate.isPausedBlocking() } returns false
        val bootstrap = ContentObserverBootstrap(context, workScheduler, processingPauseGate, logger)

        bootstrap.start()
        verify(exactly = 1) {
            contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                capture(observer),
            )
        }
        verify(exactly = 1) {
            contentResolver.registerContentObserver(
                MediaStore.Files.getContentUri("external"),
                true,
                any(),
            )
        }

        bootstrap.stop()

        verify(exactly = 1) { contentResolver.unregisterContentObserver(observer.captured) }
    }

    @Test
    fun `PIPA-004 observer change early returns while processing is paused`() {
        every { processingPauseGate.isPausedBlocking() } returns true
        val bootstrap = ContentObserverBootstrap(context, workScheduler, processingPauseGate, logger)

        bootstrap.handleObservedMediaStoreChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

        verify(exactly = 0) { workScheduler.enqueueMediaStoreOneShotNow() }
    }
}
