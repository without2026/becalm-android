package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.domain.onboarding.FirstMemoryInput

public data class FirstMemorySaveResult(
    val personId: String,
    val commitmentId: String,
    val sourceRef: String,
    val syncedRemotely: Boolean,
)

public interface FirstMemoryRepository {
    public suspend fun save(input: FirstMemoryInput): BecalmResult<FirstMemorySaveResult>
}
