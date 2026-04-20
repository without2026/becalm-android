# E2E Verification — `person-enrichment` 모듈

Spec: `becalm-android/.spec/person-enrichment.spec.yml`

> **PIPA invariant**: `persons_enrichment` 데이터는 절대 Railway/Supabase 로 전송되지 않는다. **온디바이스 only**.
> → 이 모듈의 CTO 검증 1순위는 "network boundary leak 없음" 이다.

---

## 0. 전체 흐름

```
Android ContactsContract.Data   ◀── READ_CONTACTS permission
        │
        ▼
EnrichmentWorker (worker/EnrichmentWorker.kt:68)
  ├─ queryContact() (L217) — ContactsContract 직접 읽기
  ├─ lookupByEmail / lookupByPhone / lookupByDisplayName (L170/182/197)
  └─ PersonEnrichmentRepository.upsertAll(entities)
        │
        ▼
Room: persons_enrichment
   data/local/db/entity/PersonEnrichmentEntity.kt
   data/local/db/dao/PersonEnrichmentDao.kt
   data/repository/PersonEnrichmentRepository.kt  ← NO RailwayApi dependency (architecture guard)

Consumer UI:
   ui/persons/PersonsViewModel.kt:76
   ui/persons/PersonDetailViewModel.kt
   ui/today/TodayViewModel.kt  (person_ref 표시명 조회)
   ui/commitments/CommitmentManagementViewModel.kt
```

Class-level guard comment (PIPA-critical):
`data/repository/PersonEnrichmentRepository.kt:19` — "NEVER crosses the network boundary"
`data/repository/PersonEnrichmentRepository.kt:93` — "Adding such a [network] dependency is a PIPA violation"

---

## ENR-001 — 온보딩 CONTACTS 권한 화면

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/onboarding/ContactsPermissionScreen.kt` | [허용]/[나중에] |
| Nav | `ui/navigation/BecalmNavHost.kt:106` | `BecalmRoute.OnboardingContacts` |

---

## ENR-002 — 권한 거부 graceful skip

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker gate | `EnrichmentWorker.kt:78` | 권한 미부여 시 `Result.success()` early-return (또는 skip) |
| UI fallback | `ui/persons/PersonsViewModel.kt:151` | `toPersonRow` — persons_enrichment 없을 때 원본 person_ref fallback |

---

## ENR-003 — 권한 부여 직후 enrichment

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Worker | `EnrichmentWorker.kt:78` | `doWork()` — ContactsContract 스캔 |
| Lookup | `EnrichmentWorker.kt:170/182/197` | email/phone/displayName 3방향 |
| Normalize | `EnrichmentWorker.kt:285` | `classifyRef(ref)` — `RefKind` (EMAIL/PHONE/NAME) |
| Upsert | `PersonEnrichmentRepository.kt:121` | `upsertAll(entities)` |
| DAO | `data/local/db/dao/PersonEnrichmentDao.kt` | `upsertAll` / Room @Upsert |

**Verify phone normalization**: E.164 변환 (010-1234-5678 → +821012345678). `grep -n "E164\|formatToE164" becalm-android/android/app/src/main/java/com/becalm/android/worker/EnrichmentWorker.kt`

---

## ENR-004 — 전화/이메일 별도 person_ref 레코드 (MVP 제약)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Entity PK | `data/local/db/entity/PersonEnrichmentEntity.kt` | `@PrimaryKey person_ref: String` |
| 정책 | spec invariant — UI 는 두 레코드를 별도 인물로 표시 |

---

## ENR-005 — 1일 주기 + ContentObserver 실시간 재동기화

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Periodic | `worker/WorkSchedulerImpl.kt` | `PeriodicWorkRequest<EnrichmentWorker>(1, DAYS)` |
| ContentObserver | `worker/ContentObserverBootstrap.kt:73` | start (ContactsContract URI 등록) |
| TTL gate | `EnrichmentWorker.kt:268` | `isWithinTtl(syncedAt, now)` |

---

## ENR-006 — 매칭 실패 fallback (person_ref 원본값)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| UI | `ui/persons/PersonsViewModel.kt:151` | `toPersonRow` — nullable enrichment 처리 |
| Repo | `PersonEnrichmentRepository.kt:108` | `findByPersonRef(personRef)` — nullable |

---

## ENR-007 — 로그아웃/앱 삭제 시 전체 삭제

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Repo | `PersonEnrichmentRepository.kt:131` | `deleteAll()` |
| Invocation | `data/repository/AuthRepository.kt:159` `signOut()` 경로 | logout 시 deleteAll 호출 확인 |

**Verify**:
```
grep -rn "persons_enrichment.*deleteAll\|PersonEnrichmentRepository.*deleteAll" becalm-android/android/app/src/main/java
```

---

## ENR-008 — Settings → Sources 목록 '연락처' 의사 소스 행

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/sources/SourcesListScreen.kt` | "연락처" row |
| VM | `ui/sources/SourcesListViewModel.kt:62` | 7번째 source row (contacts) |
| Status | `data/repository/SourceStatusRepository.kt` | contacts permission 상태 합성 |

---

## Invariants — PIPA 핵심 가드

| Invariant | 자동 검증 |
| --- | --- |
| **persons_enrichment → Railway/Supabase 전송 금지** | `grep -rn "PersonEnrichment" becalm-android/android/app/src/main/java/com/becalm/android/data/remote` → **히트 0건 필수** |
| Repository 에 RailwayApi 의존성 금지 | `PersonEnrichmentRepositoryImpl` 의 `@Inject constructor` 에 `RailwayApi` / `VoiceApi` / `SupabaseAuthClient` 주입 **없어야 함** (`PersonEnrichmentRepository.kt:97`) |
| ContactsContract 외 민감 데이터 접근 금지 | `grep -rn "Telephony\|Sms\|CallLog" becalm-android/android/app/src/main/java/com/becalm/android/worker/EnrichmentWorker.kt` → **히트 0건** (단, `ContentObserverBootstrap` 의 `registerSmsObserver` / `registerCallLogObserver` 는 별도 경로이므로 해당 모듈 별도 검토 필요) |
| Upsert 키 = person_ref | `PersonEnrichmentEntity.@PrimaryKey person_ref` |
| 로그아웃 시 enrichment 삭제 | `AuthRepositoryImpl.signOut` 에서 `PersonEnrichmentRepository.deleteAll()` 호출 |

---

## 주의 — `ContentObserverBootstrap.registerSmsObserver` / `registerCallLogObserver` 가 존재함

`worker/ContentObserverBootstrap.kt:98` `registerSmsObserver` / L128 `registerCallLogObserver` — 이것은 enrichment 가 아닌 **별도 ingestion 경로**로 보인다. CTO 는 다음을 확인해야 한다:
- [ ] SMS / CallLog 가 어떤 spec 에 매핑되어 있는가? 현재 `.spec/` 파일 9개 중 이에 대한 behavior 가 **없다** → spec gap 가능.
- [ ] 해당 observer 가 실제 raw_ingestion_events 에 insert 한다면, PIPA invariant ("SMS, 통화 기록 접근 없음") 와 **직접 충돌**.

이 gap 은 본 verification 스위프의 1순위 escalation 항목.

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `ui/persons/PersonsViewModelTest.kt` | ENR-006 fallback |
| `ui/persons/PersonDetailViewModelTest.kt` | enrichment join |
| (없음) | ENR-003/005/007 worker/unit 테스트 추가 필요 |
