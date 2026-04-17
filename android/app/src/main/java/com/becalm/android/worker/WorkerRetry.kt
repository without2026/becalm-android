package com.becalm.android.worker

import androidx.work.CoroutineWorker
import com.becalm.android.core.util.Logger

/**
 * 여섯 개 CoroutineWorker (UploadWorker / EnrichmentWorker / MediaStoreWorker /
 * OutlookMailWorker / OutlookCalendarWorker / ImapNaverWorker) 에 동일하게 존재하던
 * `runAttemptCount >= MAX_RETRIES` 가드를 한 곳으로 모은 확장 함수.
 *
 * 반환값이 `true`이면 호출자는 즉시 `Result.failure()`를 반환해야 한다. 로그 문자열
 * `"Exceeded $max attempts, failing permanently"`과 로그 레벨(error)은 기존
 * 호출부들과 **byte-identical** 하게 유지된다 — `$max`는 각 Worker의 `MAX_RETRIES`
 * 상수를 그대로 전달받으므로 로그 출력이 이전과 달라지지 않는다.
 *
 * ## 호출 예
 * ```
 * override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
 *     if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()
 *     ...
 * }
 * ```
 */
internal fun CoroutineWorker.hasExceededMaxRetries(
    logger: Logger,
    tag: String,
    max: Int,
): Boolean {
    if (runAttemptCount < max) return false
    logger.e(tag, "Exceeded $max attempts, failing permanently")
    return true
}
