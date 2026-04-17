package com.becalm.android.core.util

/**
 * 로그/원격 텔레메트리에 식별자(messageId, rawEventId, personRef 등)를 찍을 때 PII 누출을
 * 막기 위한 단방향 마스킹 헬퍼.
 *
 * - 32-bit [String.hashCode]를 0-padding 8자리 hex로 포맷한다.
 * - 같은 입력에는 항상 같은 출력을 주므로 **한 실행 내에서 동일 ID를 상호참조**하는 데는
 *   충분하고, 역으로 원문을 복원할 수는 없다.
 * - 원본 저장소에는 여러 파일이 각자 `private fun redact(...)`를 중복 정의하고 있었는데,
 *   구현이 모두 바이트-동일하여 여기로 통합했다. 호출지 호환을 위해 파라미터 이름만
 *   `value`로 통일한다(기존 이름은 `personRef`/`s`/`value` 혼재).
 * - 널 처리(`value ?: ""`)는 호출지에서 그대로 유지한다 (이 헬퍼는 non-null 전제).
 */
internal fun redact(value: String): String = "%08x".format(value.hashCode())
