package com.becalm.android.core.result

import com.becalm.android.core.util.Logger

/**
 * DAO/로컬 DB 작업을 `try { ... } catch (Exception) { -> BecalmError.Io }` 패턴으로 통합하는
 * [Logger] 확장 함수.
 *
 * ## 시그니처 근거
 * 원래 `RawIngestionRepository`와 `CommitmentRepository`에 각각 존재하던 `daoOp`
 * 프라이빗 헬퍼를 하나로 합친 것이다. 두 호출자 모두 **바이트-동일**한 로그 문자열 / BecalmError.Io
 * 메시지를 유지할 수 있도록 다음 2-파라미터 형태로 확정한다:
 *
 * - [logMessage] — `logger.e(tag, ...)`에 그대로 전달되는 로그 문자열이며,
 *   원본 예외의 `message`가 null일 때 `BecalmError.Io`의 대체 문자열로도 쓰인다.
 *   `RawIngestionRepository` 기존 호출부는 이를 `"<op> failed"` 형태로, `CommitmentRepository`는
 *   필요 시 `userId=$userId` 같은 동적 값을 포함한 임의 문자열로 전달한다.
 *
 * ## CancellationException
 * 이 helper는 의도적으로 [kotlinx.coroutines.CancellationException]을 별도 재-throw 하지 않는다.
 * 기존 두 repository의 helper도 동일하게 swallow 패턴을 썼고, 본 통합은 **동작을 보존**하는 것이
 * 목표이기 때문이다. cancellation semantics 수정은 별도 기술부채 이슈로 다룬다.
 *
 * ## 사용 예
 * ```
 * override suspend fun insertLocal(...): BecalmResult<String> =
 *     logger.daoOp(TAG, "insert failed") {
 *         dao.insert(entity)
 *         entity.id
 *     }
 * ```
 */
internal inline fun <T> Logger.daoOp(
    tag: String,
    logMessage: String,
    block: () -> T,
): BecalmResult<T> = try {
    BecalmResult.Success(block())
} catch (ex: Exception) {
    e(tag, logMessage, ex)
    BecalmResult.Failure(BecalmError.Io(ex.message ?: logMessage))
}
