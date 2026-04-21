# Senior-Engineer Maintainability Rubric — Android Kotlin (Room / DTO / Worker / Repository)

**Scope**: PRs touching Room entities/DAOs/migrations, DTOs, repositories, workers. Target horizon: one solo CTO, 6-month maintenance.
**Decision rule**: Reject if any `[BLOCKING]` criterion fails. `[NIT]` criteria are advisory — flag but do not block.
**Verification**: Every criterion is observable in the diff (no runtime required). Reviewers cite file:line.

---

## 1. Readability & Naming

- **READABILITY-01** [BLOCKING] — Class/function/property names follow Google Kotlin Style Guide: PascalCase types, camelCase members, UPPER_SNAKE_CASE for `const val` and top-level `val` with deeply immutable contents only.
- **READABILITY-02** [BLOCKING] — No meaningless suffixes: `Util`, `Manager`, `Helper`, `Wrapper`, `Data`, `Impl` (unless paired with an interface of the same base name in the same module).
- **READABILITY-03** [BLOCKING] — Acronyms follow Kotlin rules: two-letter acronyms uppercase, longer acronyms title-cased.
- **READABILITY-04** [BLOCKING] — Boolean parameters at call sites are named, not positional, when the function takes ≥2 parameters.
- **READABILITY-05** [NIT] — `it` is used only in single-line, non-nested lambdas.
- **READABILITY-06** [BLOCKING] — 100-column hard limit except URLs in KDoc, `package`/`import`, shell-command comments.
- **READABILITY-07** [BLOCKING] — Repositories/DAOs/workers expose `List`/`Set`/`Map`/`Flow`/`StateFlow`, never `MutableList`/`MutableStateFlow`/`ArrayList` as public return types.

## 2. Documentation / KDoc

- **KDOC-01** [BLOCKING] — Every `public` or `protected` type, function, and property has a KDoc block.
- **KDOC-02** [BLOCKING] — KDoc summary is a capitalized, punctuated sentence followed by a blank line.
- **KDOC-03** [BLOCKING] — Every Room `@Entity` KDoc lists (a) mirrored server table, (b) each `@Index` and the query it serves, (c) logical foreign keys not Room-enforced.
- **KDOC-04** [BLOCKING] — Every public DAO method KDoc states return-type contract: `Flow` (hot) vs one-shot `suspend`, throw behavior, empty-result semantics.
- **KDOC-05** [BLOCKING] — Comments state *why* / *invariant* / *non-obvious consequence*, not *what*.
- **KDOC-06** [BLOCKING] — Use `[symbol]` KDoc link syntax; `@param`/`@return` only for descriptions that don't fit main prose.

## 3. Cohesion & Single-Responsibility

- **COHESION-01** [BLOCKING] — Each public function's KDoc summary can be written without "and" / "그리고" / "및".
- **COHESION-02** [BLOCKING] — Repositories don't do HTTP directly; DAOs don't make network calls; workers don't hold Activity/View references.
- **COHESION-03** [BLOCKING] — No class exceeds ~400 LOC; no function exceeds ~50 LOC without KDoc justification.
- **COHESION-04** [BLOCKING] — Parameter count on public functions ≤5, else group into a data class / parameter object.
- **COHESION-05** [BLOCKING] — Single PR doesn't mix refactoring + feature + bug fix. Renames/moves separate commits.

## 4. Error Handling & Failure Modes

- **ERROR-01** [BLOCKING] — Repositories/workers return `BecalmResult<T>` for fallible operations, not nullable returns or cross-layer exceptions.
- **ERROR-02** [BLOCKING] — `CancellationException` never swallowed inside `catch (e: Exception)` / `catch (t: Throwable)`.
- **ERROR-03** [BLOCKING] — Errors map to a typed `BecalmError` variant, not a raw String or Throwable.
- **ERROR-04** [BLOCKING] — No silent `catch { }` / `runCatching { }.getOrNull()` without logging in data/worker/repository layers.
- **ERROR-05** [BLOCKING] — Partial-failure batch operations report per-item outcomes, don't throw on first failure or silently drop.

## 5. Testing Coverage & Quality

- **TEST-01** [BLOCKING] — Every new public repository/DAO/worker method has at least one happy-path and one failure-mode test.
- **TEST-02** [BLOCKING] — Every new Room migration has a `MigrationTestHelper`-based test seeding realistic data at old version, running with `validateDroppedTables=true`, asserting row-level preservation.
- **TEST-03** [BLOCKING] — Database schema JSON under `app/schemas/<db>/<version>.json` is committed for every new version bump.
- **TEST-04** [BLOCKING] — Tests use `runTest { }` with injected `TestDispatcher`, not `runBlocking` for coroutine code.
- **TEST-05** [BLOCKING] — Test method names describe scenario + expected outcome.
- **TEST-06** [BLOCKING] — Tests don't assert Kotlin stdlib, Room internals, or injection framework behavior.
- **TEST-07** [NIT] — Prefer fakes over mocks for repository/data-source interfaces.

## 6. Concurrency & Coroutines

- **CONCURRENCY-01** [BLOCKING] — `Dispatchers.IO`/`Default`/`Main` injected as `CoroutineDispatcher` constructor parameters, not referenced directly in business logic.
- **CONCURRENCY-02** [BLOCKING] — No `GlobalScope`; long-lived scope is injected `CoroutineScope` with `SupervisorJob`.
- **CONCURRENCY-03** [BLOCKING] — Public suspend functions are main-safe (cheap or `withContext(ioDispatcher)`).
- **CONCURRENCY-04** [BLOCKING] — `MutableStateFlow`/`MutableSharedFlow` are `private`; public surface is immutable `StateFlow`/`SharedFlow`.
- **CONCURRENCY-05** [BLOCKING] — UI-observed hot flows use `.stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)`.
- **CONCURRENCY-06** [BLOCKING] — Long-running worker loops call `ensureActive()` or rely on suspending functions between iterations.

## 7. Database / Room Specifics

- **ROOM-01** [BLOCKING] — Every `@Entity` field uses `@ColumnInfo(name = "snake_case_name")` — no reliance on Room's auto-conversion.
- **ROOM-02** [BLOCKING] — Entity column set, types, nullability, default values match migration SQL byte-for-byte.
- **ROOM-03** [BLOCKING] — Every `@Index` has a KDoc line naming the DAO query it backs. Unused indexes removed.
- **ROOM-04** [BLOCKING] — Migrations execute only full SQL literals — no string interpolation from entity-name constants.
- **ROOM-05** [BLOCKING] — Migrations idempotent against their version pair; `DROP`/`CREATE` guard with `IF EXISTS`/`IF NOT EXISTS`.
- **ROOM-06** [BLOCKING] — `@Database(exportSchema = true)`; schema JSON directory VCS-tracked; version bump without JSON commit = reject.
- **ROOM-07** [BLOCKING] — `@ForeignKey` declared only when referenced table exists locally; logical-only refs documented in KDoc.
- **ROOM-08** [BLOCKING] — DAO streaming reads return `Flow`; one-shot reads are `suspend fun`; no new `LiveData<>`.
- **ROOM-09** [BLOCKING] — `@Insert`/`@Update` declare explicit `onConflict` strategy.
- **ROOM-10** [BLOCKING] — Idempotency claims on `@Insert(onConflict = REPLACE)` only valid when the entity has a stable PK or a `UNIQUE` index on the natural key. Random-UUID PKs without a unique natural-key constraint cannot claim idempotency.

## 8. API Surface & Contracts (DTO → Entity)

- **API-01** [BLOCKING] — DTOs and Entities are separate classes; no DTO is a Room `@Entity`; no Entity is wire-serialized.
- **API-02** [BLOCKING] — DTO ↔ Entity mapping extension functions `toEntity`/`toDto`/`toDomain` live in the data layer.
- **API-03** [BLOCKING] — New optional DTO fields have Kotlin default values AND map completely in BOTH directions (entity→dto AND dto→entity). Missing either direction is a silent data loss bug.
- **API-04** [BLOCKING] — Removing/renaming DTO field requires `@Deprecated` stub with migration note.
- **API-05** [BLOCKING] — DTOs annotated `@Serializable` (kotlinx.serialization) or `@JsonClass(generateAdapter = true)` (Moshi, per existing project pattern).

## 9. Security & Privacy

- **PRIVACY-01** [BLOCKING] — No PII passed to `Log.*`/`println`/`Logger` without `redact()`.
- **PRIVACY-02** [BLOCKING] — Identifiers (`personRef`, `messageId`, `rawEventId`, `userId`) logged only via `redact(value)`.
- **PRIVACY-03** [BLOCKING] — Secrets stored via `EncryptedPrefsFactory`/`DeviceKeyStore`/`ImapCredentialStore`, never `SharedPreferences`/`DataStore` directly.
- **PRIVACY-04** [BLOCKING] — Every change touching personal data cites the `.spec/*.yml` MUST-clause with file:line.
- **PRIVACY-05** [BLOCKING] — New device-sourced fields have a `Purpose:` line in KDoc (PIPA purpose-limited collection).
- **PRIVACY-06** [BLOCKING] — Room-only tables marked `room_only: true` in spec MUST NEVER have their columns serialized onto the wire. Upload mappers verified to not emit those fields.

## 10. Dead Code & Dependency Discipline

- **DEADCODE-01** [BLOCKING] — No unused imports.
- **DEADCODE-02** [BLOCKING] — No public function added without an in-repo caller.
- **DEADCODE-03** [BLOCKING] — No drive-by refactors of code outside PR scope.
- **DEADCODE-04** [BLOCKING] — New third-party deps require PR-body justification (why, license, last-release).
- **DEADCODE-05** [NIT] — Removed deprecated APIs listed in PR body with replacement.

## 11. Performance Consciousness

- **PERF-01** [BLOCKING] — Every DAO `@Query` with `WHERE`/`ORDER BY` has an `@Index` covering those columns in order, OR entity KDoc explains why no index is needed.
- **PERF-02** [BLOCKING] — No N+1 query pattern; use `IN (:ids)` or `@Transaction` multimap.
- **PERF-03** [BLOCKING] — Hot Flows use `distinctUntilChanged()` where emitting unchanged is possible; prefer `map` over `flatMapLatest` when inner is deterministic.
- **PERF-04** [BLOCKING] — Bulk writes use `@Transaction` / `withTransaction`; per-row `insert()` in a loop rejected for batches > 10.
- **PERF-05** [NIT] — Large `map.filter` chains use `asSequence()` when intermediate size is large.

## 12. Cross-reference to Spec Documents

- **SPEC-01** [BLOCKING] — Every business-rule branch has a `// .spec/<file>.yml#L<n>` comment linking to the MUST-clause.
- **SPEC-02** [BLOCKING] — New/changed Room migrations cite the `.spec/*.yml` clause motivating the schema change.
- **SPEC-03** [BLOCKING] — New DTO fields cite the backend contract (`.spec/backend-sync.spec.yml` or equivalent).
- **SPEC-04** [BLOCKING] — Removed public APIs / renamed tables cite the spec clause authorizing removal.
- **SPEC-05** [NIT] — Absent spec clause must be explicitly noted with a proposal for the clause to add.

---

## Approval Output Contract

For each criterion: status `PASS` / `FAIL` / `N/A` with one-line evidence (`file:line` or grep command).

**Reject** if any `[BLOCKING]` criterion fails.
**Approve** if all `[BLOCKING]` pass (`[NIT]` items are comments only).

Final verdict line MUST be exactly one of:
- `VERDICT: APPROVED`
- `VERDICT: REJECTED — <short reason summarizing blocking failures>`
