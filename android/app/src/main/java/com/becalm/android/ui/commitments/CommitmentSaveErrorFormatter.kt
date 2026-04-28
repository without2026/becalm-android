package com.becalm.android.ui.commitments

import com.becalm.android.core.result.BecalmError

internal object CommitmentSaveErrorFormatter {
    const val SUPERSEDE_SOURCE_NOT_FOUND: String = "원문 commitment를 찾지 못했습니다"

    fun format(error: BecalmError): String = when (error) {
        is BecalmError.Unauthorized -> "로그인이 필요합니다"
        is BecalmError.NotFound -> "삭제된 약속입니다"
        is BecalmError.Validation -> error.message
        else -> "저장 실패 — 다시 시도해주세요"
    }
}
