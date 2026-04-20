package com.becalm.android.core.util.coroutines

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [rethrowIfCancellation].
 *
 * Spec ref: docs/round6-plan.md § 6A.2 — primitives for structured-concurrency-safe error mapping.
 */
class CancellationTest {

    @Test
    fun `rethrows CancellationException unchanged`() {
        val original = CancellationException("user cancelled")
        try {
            original.rethrowIfCancellation()
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            // Same instance must propagate; callers rely on message/cause for diagnostics.
            assertSame("Exception instance must propagate unchanged", original, e)
        }
    }

    @Test
    fun `does not rethrow non-CancellationException`() {
        val original = IllegalStateException("domain failure")
        // No exception should escape; function should be a no-op for non-cancellation throwables.
        original.rethrowIfCancellation()
        // Nothing to assert here beyond "did not throw" — JUnit's default success is the contract.
    }

    @Test
    fun `does not rethrow RuntimeException that is not cancellation`() {
        val original = RuntimeException("retryable")
        original.rethrowIfCancellation()
    }

    @Test
    fun `does not rethrow Error subclass`() {
        val original = AssertionError("boom")
        original.rethrowIfCancellation()
    }

    @Test
    fun `rethrows subclass of CancellationException`() {
        // Subclasses of CancellationException (e.g. TimeoutCancellationException) must also
        // propagate — otherwise structured-concurrency timeouts get swallowed.
        val original = object : CancellationException("timed out") {}
        try {
            original.rethrowIfCancellation()
            fail("Expected subclass of CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("timed out", e.message)
            assertSame(original, e)
        }
    }
}
