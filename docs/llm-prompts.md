# BeCalm Android — LLM Prompt Contracts

**Status**: spec (not implementation). 이 문서는 Railway 백엔드가 Vertex AI Gemini 2.5 Flash에 전송하는 system instruction + responseSchema + context binding 규약을 codify한다.
**Owner**: Backend 팀 (prompt 문자열 자체는 `becalm-backend` 레포에서 버전 관리) / Android 팀은 `user_profile` · `folder` · `source_type` 힌트를 payload로 정확히 전달하는 책임.
**Related specs**: `.spec/voice-pipeline.spec.yml` (VOI-003), `.spec/email-pipeline.spec.yml` (EMAIL-001), `.spec/data-ingestion.spec.yml`, `.spec/contracts/api-contract.yml` CommitmentDraft schema, `.spec/contracts/data-model.yml` user_profile table.

---

## 1. 목적

Gemini 2.5 Flash가 오디오 또는 이메일 본문에서 give/take commitment를 추출할 때 준수해야 하는 **추출 규약**을 명시한다. 모든 프롬프트는 다음 5개 원칙을 따른다:

1. **Structured output only** — responseSchema(CommitmentDraft[]) 위반은 즉시 실패 (HTTP 502).
2. **Verbatim quote** — LLM은 quote 필드를 발화·본문 원문 그대로 복사하고 절대 요약·편집하지 않는다.
3. **User-centric direction** — give/take 방향은 **로그인한 사용자(화자 '나')** 관점에서 판정한다.
4. **Explicit temporal triplet** — due_at(정규화 ISO8601 KST) / due_hint(원문 표현) / due_is_approximate(추론 여부)를 분리 추출한다.
5. **Confidence honesty** — 추출 확신도(0..1)를 정직하게 보고하고 경계가 모호하면 0.6 이하로 낮춘다.

---

## 2. System Instruction 골격

아래는 voice/email 공통 시스템 프롬프트의 구조를 기술적으로 명시한 것이다. 실제 문자열은 backend 레포의 `prompts/commitment_extractor.ko.md`에 버전 관리된다.

```
[Role]
You are a precise extraction agent for Korean business commitments.
Your user is {user_profile.display_name_override | 'this app user'} (phone: {user_profile.phone_e164_self}, timezone: {user_profile.timezone | 'Asia/Seoul'}).
Extract give/take IOU commitments from the input and emit CommitmentDraft[] via structured output.
Never summarize or rewrite the quote field. Copy source text verbatim.

[Direction]
- "give" = the user promised something to the counterparty.
- "take" = the counterparty promised something to the user.
- Source boundary hints (override only if body evidence is explicit):
  - source_type=voice|call_recording: the speaker matching {phone_e164_self} is the user; the other speaker(s) are counterparties. If speaker identity is ambiguous, use contextual cues (speech patterns, "제가"/"저는" vs "저희 쪽에서") and lower confidence to ≤0.6.
  - folder=INBOX (email): baseline direction=take (the sender is the counterparty).
  - folder=SENT (email): baseline direction=give (the user wrote this).
  - Body evidence may override baseline (e.g. an INBOX email saying "제가 월요일에 보내드리겠습니다" is give-from-counterparty, not take).

[Temporal Triplet]
For each commitment, emit:
- due_at: ISO8601 with +09:00 offset, OR null if no time reference.
- due_hint: verbatim original temporal phrase (e.g. "다음 주", "월말", "내일 오전") — preserved for UI display and future LLM re-inference.
- due_is_approximate: true iff due_at was inferred from relative phrasing; false iff source stated an explicit date+time.
Rules:
- "내일" resolves against source_event_occurred_at + timezone.
- "다음 주 월요일" resolves to the Monday of the next ISO week in Asia/Seoul.
- "월말" resolves to the last calendar day of the current month at 18:00 KST unless otherwise cued.
- If relative phrase cannot be resolved unambiguously, set due_at=null and due_is_approximate=false, preserve due_hint.

[Person Reference]
- If source carries a resolved person_ref (passed in context), use it verbatim for person_ref output.
- LLM may override person_ref with a body-explicit mention (e.g. "김대리한테") — emit the normalized form and mark confidence ≤0.7.
- If counterparty cannot be identified, set person_ref=null (UI will route to Unassigned).

[Quote]
- quote = verbatim source text fragment containing the commitment signal.
- Never paraphrase. Include the minimal span that preserves meaning; prefer full sentence.
- For voice input: quote contains transcribed Korean as the speaker said it (do not normalize honorifics or filler words).
- For email input: quote is a substring of body_plain (or Jsoup-extracted text from body_html).

[Confidence]
- 0.9+ : explicit commitment language ("...하겠습니다", "...드리겠습니다") + clear due_at + identifiable person_ref.
- 0.7–0.9: clear commitment but one of (direction / due_at / person_ref) requires inference.
- 0.5–0.7: ambiguous signal, inferred boundaries.
- < 0.5: do not emit (suppress).

[Output]
Return CommitmentDraft[] as structured output.
Empty array if no commitments found. Do not fabricate.
```

---

## 3. responseSchema (CommitmentDraft)

아래 JSONSchema는 `api-contract.yml`의 CommitmentDraft 정의와 동일하며 Gemini 2.5 Flash `responseSchema` 파라미터로 바인딩된다. 스키마 위반은 Railway가 즉시 HTTP 502 `{error:'schema_violation'}`로 반환하고 raw event는 quarantine(VOI-003)된다.

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "required": ["direction", "text", "quote", "due_is_approximate", "confidence"],
    "properties": {
      "direction": { "type": "string", "enum": ["give", "take"] },
      "text": { "type": "string", "description": "Short title for the commitment (≤200 chars)" },
      "person_ref": { "type": ["string", "null"], "description": "Canonicalized counterparty (phone E.164 | email lowercase | display-name normalized). NULL if unidentifiable." },
      "due_at": { "type": ["string", "null"], "format": "date-time", "description": "ISO8601 with +09:00 offset. NULL if no temporal reference." },
      "due_hint": { "type": ["string", "null"], "description": "Verbatim original temporal phrase from source." },
      "due_is_approximate": { "type": "boolean" },
      "quote": { "type": "string", "minLength": 1, "description": "Verbatim source fragment. Never summarized." },
      "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
    }
  }
}
```

Gemini 2.5 Flash는 responseMimeType='application/json' + responseSchema 파라미터 바인딩을 요구한다 (Vertex AI SDK `GenerationConfig`). 스키마 위반 응답은 finish_reason 체크 + JSON parse로 감지한다.

---

## 4. Context Binding 규약

Railway는 요청 시 다음 context 필드를 system instruction 말미에 주입한다. Android는 API 호출 payload에 이 값들을 정확히 포함해야 한다.

| Context key | 출처 | 용도 |
|---|---|---|
| `user_profile.phone_e164_self` | `GET /v1/user_profile` (또는 Android DataStore 캐시) | voice 화자 식별 — 매칭 발화는 '나' 관점 direction 판정 |
| `user_profile.display_name_override` | user_profile | 프롬프트의 user 지칭(선택) |
| `user_profile.timezone` | user_profile (default 'Asia/Seoul') | due_at 상대 표현 resolve |
| `user_profile.preferred_locale` | user_profile (default 'ko') | 출력 언어 (현재 ko 고정) |
| `source_type` | raw_ingestion_events.source_type | voice/call_recording/gmail/outlook_mail/naver_imap/daum_imap 분기 |
| `folder` | EmailBody.folder (이메일 only) | INBOX/SENT baseline direction |
| `source_event_occurred_at` | raw_ingestion_events.timestamp | 상대 시점 resolve 기준점 |
| `person_ref` | raw_ingestion_events.person_ref | baseline counterparty (LLM override 허용) |
| `quoted_text` | EMAIL-005 reply-chain quote block | LLM에 'context only, do not extract'로 전달 |

**Android의 책임**: voice raw event를 생성할 때 DataStore의 `user_profile_cache`를 먼저 읽어 `phone_e164_self`를 Railway payload에 첨부. 이메일 raw event는 `folder` 및 `source_event_occurred_at`을 정확히 전달. 이메일 quoted block은 파서가 분리해 `quoted_text` 필드로 전송.

---

## 5. PIPA·ZDR 준수 가드레일

- Vertex AI 호출은 region=`us-central1` 고정 + ZDR(Zero Data Retention) 플래그 필수 (voice-pipeline invariants).
- 프롬프트에 사용자 실명(`display_name_override`)을 포함할 수는 있으나 **전화번호(`phone_e164_self`)는 speaker disambiguation 목적으로만 system instruction 내부에서 사용**되며 prompt echo·structured output에 그대로 등장하지 않아야 한다.
- email quoted block과 raw 본문은 Railway request scope에서만 메모리 처리되며 Supabase/로그에 저장 금지 (EMAIL-006).
- Gemini 응답에 사용자 의도와 무관한 개인정보(예: 제3자 전화번호)가 포함되어도 CommitmentDraft 필드 밖의 텍스트는 Railway가 모두 버린다 — structured output 외 free-text 없음.
- voice 오디오 바이트는 `pipa_third_party_consent=true`일 때만 Vertex AI에 전달된다(VOI-004). 미동의 상태에서는 Gemini 호출 자체가 수행되지 않는다.

---

## 6. 실패 모드와 UX 매핑

| 실패 | Railway 응답 | Android UX |
|---|---|---|
| JSON parse 실패 / 스키마 위반 | HTTP 502 `{error:'schema_violation'}` | raw event quarantine, ERR-004 주황 배지 |
| finish_reason='MAX_TOKENS' / 잘린 JSON | HTTP 502 `{error:'output_truncated'}` | raw event quarantine, ERR-005 안내 카드(재시도 숨김) |
| Vertex 5xx / timeout | HTTP 502 `{error:'upstream_error'}` | VoiceUploadWorker 지수 backoff 재시도(VOI-006) |
| 오디오 파일 >120분 | HTTP 422 `{error:'duration_exceeded'}` | 클라이언트 업스트림 검증, 본 behavior 도달 안 함 |
| Gemini 응답 `commitments=[]` | HTTP 200 empty | 정상 — raw event `commitments_extracted_count=0`로 기록 |

---

## 7. 버전 관리

- prompts 문자열 변경은 backend repo의 `prompts/commitment_extractor.ko.md` 변경으로 추적 — 이 Android 스펙은 **계약**만 명시한다.
- 프롬프트 버전은 Railway 응답의 `prompt_version` 필드(TBD)로 Android에 회신되어 raw_ingestion_events에 기록 (post-MVP: 재추출 비교 분석용).
- direction 정의·temporal triplet 규칙·confidence 기준은 프롬프트 재작성 시에도 이 문서와 동기화되어야 하며 갱신 시 `.spec/contracts/api-contract.yml`의 CommitmentDraft 주석도 함께 수정한다.
