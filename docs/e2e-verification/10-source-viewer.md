# E2E Verification — `source-viewer` 모듈 (PersonsScreen)

Spec: `becalm-android/.spec/source-viewer.spec.yml`

---

## 0. 전체 흐름

```
PersonsScreen (ui/persons/PersonsScreen.kt)
   └─ PersonsViewModel.kt:76  observePeople (L112)
        │  PersonRow (L33) with PersonEnrichmentEntity LEFT JOIN
        ▼
 [person card tap]
PersonDetailScreen (ui/persons/PersonDetailScreen.kt)
   └─ PersonDetailViewModel.kt  3 sections (미이행/이행완료/상호작용 히스토리)
        │
        ▼
 [event item tap]
RawEventDetailSheet (ui/persons/RawEventDetailSheet.kt)
   └─ RawEventDetailViewModel.kt
        │  reads RawIngestionEventEntity by id

Unassigned bucket:
   ui/persons/UnassignedEventsScreen.kt  (route: BecalmRoute.PersonsUnassigned)
```

Data sources:
- `data/repository/PersonEnrichmentRepository.kt` — 온디바이스 enrichment (PIPA guard, `persons_enrichment`)
- `data/repository/RawIngestionRepository.kt` — raw events per person
- `data/repository/CommitmentRepository.kt` — commitments per person (`observeAllForPerson`)
- Refresh API: `RailwayApi.kt:173` `getPersons`, `L191` `getPersonEvents`, `L209` `getPersonCommitments`

---

## SRC-001 — 인물 카드 목록 (enrichment + DNBadge + 채널 아이콘)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/persons/PersonsScreen.kt` | `LazyColumn<PersonRow>` |
| VM | `ui/persons/PersonsViewModel.kt:76` | `PersonsViewModel` |
| Row | `PersonsViewModel.kt:33` | `PersonRow` data class |
| Mapping | `PersonsViewModel.kt:151` | `PersonEnrichmentEntity.toPersonRow()` |
| Pagination | `data/repository/PaginationDefaults.kt` | `limit=20` |

---

## SRC-002 — PersonDetailScreen 3-섹션

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/persons/PersonDetailScreen.kt` | sections |
| VM | `ui/persons/PersonDetailViewModel.kt` | observe commitments + raw events join |
| Commitments | `CommitmentRepositoryImpl.kt:55` | `observeAllForPerson(userId, personRef)` |
| Raw events | `RawIngestionEventDao.kt:143` | `observeRecentForPerson(userId, personRef, …)` |

---

## SRC-003 — 인물 검색 (substring filter)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| VM | `PersonsViewModel.kt:98` | `onQueryChange(q)` |
| Filter | `PersonsViewModel.kt:112` | `observePeople` 내 쿼리 반영 |
| API | `RailwayApi.kt:173` | `getPersons(q?)` (서버측 검색) — 현재 Room 로컬 필터 우선 |

---

## SRC-004 — 이벤트 상세 시트 (소스별 분기)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Sheet | `ui/persons/RawEventDetailSheet.kt` | 소스 타입별 렌더링 |
| VM | `ui/persons/RawEventDetailViewModel.kt` | load entity |
| DAO | `RawIngestionEventDao.kt:198` | `findById(id, userId)` |

**Verify invariant** (transcript 로컬 only): 시트가 `transcript.text` 를 표시하더라도 그 데이터는 Room 엔티티 내부 필드에서만 온다 (현재 `RawIngestionEventEntity` 에 transcript 필드가 **없으면** 이 behavior 는 spec gap 이다 — 확인 필요).

```
grep -n "transcript" becalm-android/android/app/src/main/java/com/becalm/android/data/local/db/entity/RawIngestionEventEntity.kt
```

---

## SRC-005 — Unassigned 버킷 (person_ref IS NULL)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Screen | `ui/persons/UnassignedEventsScreen.kt` | Unassigned list |
| Nav | `ui/navigation/BecalmNavHost.kt:151` | `BecalmRoute.PersonsUnassigned` |
| DAO | `RawIngestionEventDao.kt:163` | `observeUnassignedRecent(userId, …)` |

---

## SRC-006 — pull-to-refresh → GET /v1/persons

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| API | `RailwayApi.kt:173` | `getPersons` |
| VM | `PersonsViewModel.kt:112` | refresh flow |

---

## SRC-007 — 오프라인 모드 (Room 캐시 + 배지)

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Network state | `data/remote/network/NetworkMonitor.kt` | Flow<Boolean> |
| UI badge | `ui/persons/PersonsScreen.kt` | "오프라인 — 마지막 동기화 HH:mm" |

---

## SRC-008 — PersonDetailScreen 섹션 구조 + commitments_extracted_count 배지

| 단계 | 파일 | 심볼 |
| --- | --- | --- |
| Entity field | `data/local/db/entity/RawIngestionEventEntity.kt` | `commitments_extracted_count: Int` |
| VM | `PersonDetailViewModel.kt` | section state |

---

## Invariants

| Invariant | 검증 |
| --- | --- |
| Room primary, Railway refresh on pull | VM observe 경로가 Flow<RoomEntity> |
| person_ref 그룹핑 Room 쿼리 (별도 persons 테이블 없음) | `RawIngestionEventDao` + `PersonEnrichmentDao` LEFT JOIN 또는 VM 측 조합 |
| enrichment 조인은 온디바이스 only (PIPA) | `PersonEnrichmentRepository` 에 RailwayApi 없음 |
| enrichment 없음 → 원본값 fallback, crash 금지 | `PersonRow` 생성 null-safe |

---

## Tests

| 파일 | 커버 |
| --- | --- |
| `ui/persons/PersonsViewModelTest.kt` | SRC-001/003/005 |
| `ui/persons/PersonDetailViewModelTest.kt` | SRC-002/008 |
| `ui/persons/RawEventDetailViewModelTest.kt` | SRC-004 |
| (없음) | SRC-006/007 — Compose test 필요 |
