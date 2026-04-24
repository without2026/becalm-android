package com.becalm.android.unit.worker

import com.becalm.android.worker.UploadBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadBackoffSpecTest {

    @Test
    fun `SYNC-005 uses Retry-After header verbatim when present`() {
        assertEquals(60L, UploadBackoff.nextDelaySeconds(attempt = 1, retryAfterSec = 60L))
        assertEquals(1L, UploadBackoff.nextDelaySeconds(attempt = 4, retryAfterSec = 0L))
    }

    @Test
    fun `SYNC-003 first retry uses exponential base around 30 seconds`() {
        val delay = UploadBackoff.nextDelaySeconds(attempt = 1, retryAfterSec = null)

        assertTrue(delay in 24L..35L)
    }

    @Test
    fun `SYNC-003 second retry doubles base before jitter`() {
        val delay = UploadBackoff.nextDelaySeconds(attempt = 2, retryAfterSec = null)

        assertTrue(delay in 48L..71L)
    }

    @Test
    fun `SYNC-003 caps exponential backoff at one hour`() {
        val delay = UploadBackoff.nextDelaySeconds(attempt = 99, retryAfterSec = null)

        assertTrue(delay in 2880L..3600L)
    }
}
