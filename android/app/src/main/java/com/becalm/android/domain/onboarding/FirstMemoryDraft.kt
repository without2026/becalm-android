package com.becalm.android.domain.onboarding

public enum class FirstMemoryOrigin {
    EMAIL,
    CALL,
    MEETING,
    MESSENGER,
}

public enum class FirstMemoryKind {
    MY_ACTION,
    THEIR_ACTION,
    SHARED_SCHEDULE,
}

public data class FirstMemoryDraft(
    val clientMemoryId: String,
    val origin: FirstMemoryOrigin?,
    val personName: String,
    val promiseText: String,
    val kind: FirstMemoryKind?,
    val dueHint: String?,
)

public data class FirstMemoryInput(
    val clientMemoryId: String,
    val origin: FirstMemoryOrigin,
    val personName: String,
    val promiseText: String,
    val kind: FirstMemoryKind,
    val dueHint: String?,
)
