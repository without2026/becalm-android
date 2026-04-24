package com.becalm.android.unit.worker

import com.becalm.android.worker.RetryAction
import com.becalm.android.worker.Voice502Action
import com.becalm.android.worker.VoiceUploadStateMachine
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceUploadStateMachineSpecTest {

    @Test
    fun `VOI-006 retries while runAttemptCount is below maxAttempts minus one`() {
        assertEquals(RetryAction.Retry, VoiceUploadStateMachine.decideRetryAction(runAttemptCount = 0, maxAttempts = 3))
        assertEquals(RetryAction.Retry, VoiceUploadStateMachine.decideRetryAction(runAttemptCount = 1, maxAttempts = 3))
    }

    @Test
    fun `VOI-006 quarantines when current execution already reached final allowed attempt`() {
        assertEquals(RetryAction.Quarantine, VoiceUploadStateMachine.decideRetryAction(runAttemptCount = 2, maxAttempts = 3))
        assertEquals(RetryAction.Quarantine, VoiceUploadStateMachine.decideRetryAction(runAttemptCount = 3, maxAttempts = 3))
    }

    @Test
    fun `VOI-002 and VOI-003 quarantine deterministic 502 envelopes`() {
        assertEquals(Voice502Action.Quarantine, VoiceUploadStateMachine.decide502Action(errorCode = "output_truncated"))
        assertEquals(Voice502Action.Quarantine, VoiceUploadStateMachine.decide502Action(errorCode = "schema_violation"))
    }

    @Test
    fun `VOI-006 treats transient or unparseable 502 envelopes as retryable`() {
        assertEquals(Voice502Action.HandleAsTransient, VoiceUploadStateMachine.decide502Action(errorCode = "vertex_upstream_error"))
        assertEquals(Voice502Action.HandleAsTransient, VoiceUploadStateMachine.decide502Action(errorCode = null))
        assertEquals(Voice502Action.HandleAsTransient, VoiceUploadStateMachine.decide502Action(errorCode = "unknown_error"))
    }
}
