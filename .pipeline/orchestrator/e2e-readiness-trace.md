# E2E Readiness Trace — Zero-to-Deploy

**Purpose**: before adding more workflows (Step 3c / P0 §1-3), audit every state transition to find the earliest halt point.

**Verdict**: **pipeline would halt at state `REPO_BOOTSTRAP` / `IDLE → L0_BRIEFING` — the target app repo is empty and the skill generator templates do not yet exist.** Multiple governance dependencies also block later states even if Layer 0 were run manually.

---

## Environment snapshot (2026-04-15)

| Asset | State |
|---|---|
| GitHub account `without2026` | user (not org), **free plan** |
| `without2026/zero-to-deploy-platform` | PRIVATE, main has raw + 3a merged (3b merged too); **no branch protection** (free-plan private repos cannot use protection API) |
| `without2026/automateApp` | PUBLIC, **empty repo** (0 bytes) |
| `without2026/zero-to-deploy-skill` | **does not exist** |
| `/home/jakek/without/.claude/skills/zero-to-deploy/templates/` | **does not exist** |
| `/home/jakek/without/.claude/skills/zero-to-deploy/lib/render.py` | **does not exist** |
| `/home/jakek/without/automateTestApp/` | exists, **0 files** |
| `/home/jakek/without/.pipeline/platform.yml` | points to `without2026/automateApp`, `project_root: automateTestApp` |
| Workspace `/home/jakek/without/` git origin | `without2026/BeCalm.git` (legacy) — mismatch with platform.yml |

---

## State-by-state trace

Legend: ✅ ready · ⚠️ works-with-caveats · ❌ halt · 🚫 no path forward

### A) Pre-flight (skill invocation)

| Step | Needs | Exists? | Verdict |
|---|---|---|---|
| Skill load | `.claude/skills/zero-to-deploy/SKILL.md` | ✅ | ✅ |
| Read platform.yml | `.pipeline/platform.yml` | ✅ (at workspace root) | ⚠️ consumer would read its own copy; this one is "wrong place" but acceptable for mock |
| `without-cto` skill | `.claude/skills/without-cto/SKILL.md` + `references/` | ✅ | ✅ |
| `expert-debate` skill | `.claude/skills/expert-debate/SKILL.md` | ✅ | ✅ |

### B) `IDLE → L0_BRIEFING`

| Step | Needs | Current | Verdict |
|---|---|---|---|
| `uuidgen` session id | OS tool | ✅ | ✅ |
| Read `l0-brief.tmpl.md` | `.pipeline/orchestrator/l0-brief.tmpl.md` | ✅ | ✅ |
| Dispatch `Task` subagent | subagent available | ✅ (generalPurpose) | ✅ |
| Write session state | `.pipeline/.sessions/{id}.json` + schema | ✅ schema recently updated | ✅ |
| Branch creation | git push to target repo | ❌ target repo is EMPTY — no `main` to branch from | ❌ HALT |
| Repo-bootstrap (plan §3.1) | skill templates + `render.py` + git/gh MCP | ❌ templates don't exist | ❌ HALT |

**⇒ First halt point.** If we invoked zero-to-deploy on `automateApp` right now, there is nothing to branch from and no REPO_BOOTSTRAP logic in the skill yet (we only wrote the plan, didn't implement the skill changes).

### C) `L0_RUNNING → L0_GATE_2/4/5`

Assuming we manually bootstrap the app repo (push a skeleton, create a feature branch):

| Step | Needs | Verdict |
|---|---|---|
| Phase 1 — idea-validator subagent | `project-idea-validator` agent | ✅ (inferred available) |
| Phase 2 — create `.spec/*.spec.yml` + contracts | writer access + schema | ⚠️ empty repo has no `.spec/` yet |
| Phase 3 — research subagents | search-specialist etc. | ✅ |
| Phase 4 — agent-select table | table JSON schema | ✅ |
| Phase 5 — expert-debate | skill | ✅ |
| Phase 6 — TDD impl | agents + `spec-coverage.py`, `assert-guard.py` | ⚠️ scripts now live in **platform repo only** (moved in Step 1) — consumer repo doesn't have them. Phase 6 invocation by subagent would fail to locate them. |
| Phase 7 — reviewers | 6 reviewer agents | ✅ |
| Gates via AskUserQuestion | deferred tool | ✅ |

**⇒ Second halt point.** Post-Step-1 reorg, `.pipeline/core/*.py` moved to the platform repo. Any workflow that runs these scripts now needs to `git clone` the platform repo first. The old workflow files (`adapter-gates.yml` etc.) still assume the scripts are in the consumer repo at `.pipeline/core/`. Not fixed yet.

### D) `L0_DONE → PR_CREATED`

| Step | Needs | Verdict |
|---|---|---|
| `guard_repo.py` | compare origin vs platform.yml.github.repo | ⚠️ consumer repo (`automateApp`) origin matches platform.yml target (`without2026/automateApp`) ✅ — would pass IF consumer has the script |
| `gh pr create` | gh CLI + authenticated account | ✅ |

**⇒ Mostly OK** if consumer repo has the scripts.

### E) `PR_CREATED → L1_MONITORING → L1_PASSED`

| Step | Needs | Verdict |
|---|---|---|
| `ci-review.yml` triggers on PR | workflow present in consumer repo | ❌ automateApp has no workflows at all |
| `adapter-build/tests/gates` run | caller stubs pointing to `@v1` | ❌ no caller stubs, and **platform repo has no `v1` tag** (only `v0.0.1-raw`) |
| `required-pr-checks.yml` category match | the 7 categories (deterministic/tests/review/merge + P0 sast/sca/supply) | ❌ SAST, SCA, supply_chain workflows don't exist |
| `ci-auto-fix.yml` | workflow + ANTHROPIC_API_KEY secret + auto-fix script reachable | ❌ no consumer workflow; also still `workflow_run` trigger not convertible cross-repo |

**⇒ Third halt point.** Layer 1 cannot run. Caller stubs for the consumer app don't exist; no `v1` tag; P0 security workflows don't exist.

### F) `L1_PASSED → MERGE_GATE → MERGED`

| Step | Needs | Verdict |
|---|---|---|
| Human CODEOWNERS review pre-check (Q3) | `.github/CODEOWNERS` in consumer + reviewer presence in `gh pr view --json reviews` | ❌ no CODEOWNERS in automateApp |
| GitHub platform enforcement | branch protection + `require_code_owner_reviews` | ⚠️ automateApp is PUBLIC so protection IS available; needs setup |
| `gh pr merge --squash` | mergeable + approver satisfied | conditional |
| Orchestrator self-restriction | SKILL.md logic (now implemented) | ✅ |

**⇒ Fourth halt point.** Orchestrator's self-restriction check is implemented (Day 1 §6), but without `.github/CODEOWNERS` + branch protection + a human reviewer, it either always blocks or always allows — both wrong.

### G) `MERGED → L2_STAGING → L2_PROD`

| Step | Needs | Verdict |
|---|---|---|
| `deploy-staging.yml` trigger on main push | workflow present + Railway secrets | ❌ not in automateApp, and Railway secrets not set |
| `deploy-production.yml` workflow_dispatch | workflow present + env:production + reviewers | ❌ not in automateApp; environment protection not configured |
| `cosign verify-blob` pre-deploy | signed AAB + SBOM workflow | ❌ sbom-sign.yml doesn't exist |
| `L2_PROD_AWAITING_APPROVAL` polling | env protection rule | ❌ not configured |
| Smoke test | staging URL + backend health endpoint | ❌ no backend, no URL |
| Auto-rollback | Railway API | ❌ |

**⇒ L2 entirely non-functional.**

### H) Governance cross-cut (Q3 integrity)

| Requirement | Verdict |
|---|---|
| Consumer app branch protection | ❌ not applied |
| Platform repo branch protection | 🚫 **blocked by GitHub free plan** (private repo limitation) |
| CODEOWNERS | ❌ not applied |
| Signed commits required | 🚫 blocked on private platform repo; possible on public automateApp |
| Environment protection (prod) | ❌ not configured |
| SBOM + cosign | ❌ workflow doesn't exist |
| SAST required check | ❌ workflow doesn't exist |

---

## Halt-point dependency graph

```
HALT 1: REPO_BOOTSTRAP / L0_BRIEFING
  ├─ blocked by: skill templates + render.py don't exist
  ├─ blocked by: automateApp is empty (no main branch with initial commit)
  └─ blocked by: .pipeline/core scripts moved to platform repo,
                 consumer app has no way to fetch them at runtime

HALT 2: L0 Phase 6 (impl)
  └─ blocked by: adapter-gates/tests workflows still assume .pipeline/core
                 in consumer repo (not yet updated post-Step-1 move)

HALT 3: L1_MONITORING
  ├─ blocked by: no caller stubs in automateApp
  ├─ blocked by: no v1 tag on platform repo (can't pin @v1 yet)
  ├─ blocked by: SAST/SCA/SBOM workflows don't exist (P0 §1-3 pending)
  └─ blocked by: 5 platform workflows not workflow_call-compatible (Step 3c)

HALT 4: MERGE_GATE
  ├─ blocked by: no CODEOWNERS in automateApp
  └─ blocked by: no branch protection on automateApp

HALT 5: L2_STAGING/PROD
  ├─ blocked by: no deploy workflow caller stubs
  ├─ blocked by: Railway secrets not set (if backend involved)
  ├─ blocked by: cosign/SBOM workflow doesn't exist
  └─ blocked by: prod environment protection not configured

GOVERNANCE CROSS-CUT
  └─ 🚫 PLATFORM REPO BRANCH PROTECTION blocked by free-plan limitation.
         Two workarounds:
           (a) make platform repo PUBLIC (visibility changes Q2 timeline)
           (b) upgrade to GitHub Pro ($4/mo)
           (c) accept that platform repo self-governance is orchestrator-only
               until public/paid.
```

---

## Minimum Viable E2E ("narrow happy path")

To prove the skill actually moves through the state machine without halting, the **minimum** we need to add (in order):

1. **Accept the free-plan constraint**: skip platform-repo branch protection for now (governance falls entirely on automateApp which is public). Document this as technical debt. Revisit when going public or paying.
2. **Skill templates** minimal set:
   - `templates/pr.yml.tmpl` (caller stub)
   - `templates/CODEOWNERS.tmpl`
   - `templates/platform.yml.android.tmpl`
   - `templates/.gitignore.tmpl`
   - `lib/render.py` (zero-dep `{{VAR}}` substitution)
3. **Skill SKILL.md `REPO_BOOTSTRAP`** state implementation (or: manually bootstrap automateApp for the first run, add REPO_BOOTSTRAP logic later)
4. **Step 3c (5 workflows → workflow_call dual-mode)** — without this, no caller stub works
5. **P0 §1-3 (SAST + dep-review + SBOM)** — without SAST/dep-review workflows, required-pr-checks categories `sast` and `sca` never match, so L1_PASSED can never be reached
6. **Platform repo `v0.1.0` tag** — so caller stubs can pin `@v1`
7. **Apply `setup-branch-protection.sh` to automateApp** (not platform repo — blocked by plan)
8. **Initial automateApp scaffolding**: push a tiny Kotlin/Gradle skeleton so `adapter-build.yml` has something to build. Or: accept that the first run's Phase 6 will create the scaffold, but then the FIRST PR can't run CI on an empty repo...

Order matters. Items 1-6 are the "make the engine start" work. Item 7 is governance. Item 8 is the specific target.

---

## Recommended next move (one-PR decisions)

Rather than rushing Step 3c + P0 §1-3 as separate PRs against the platform repo, take a **vertical-slice dogfood approach**:

**PR 3c-1 — "Narrow happy path dogfood"**:
- Convert **just** `ci-review.yml` + `adapter-tests.yml` + `adapter-gates.yml` to dual-mode (workflow_call + existing triggers preserved)
- Tag platform repo `v0.1.0-alpha` (pre-release)
- Add skill templates for the minimum files (pr.yml.tmpl, CODEOWNERS.tmpl, platform.yml.android.tmpl, gitignore)
- Write a **smoke-test playbook** document (`docs/smoke-test-android-todo.md`): step-by-step to manually run the first pass against automateApp with a trivial idea (e.g. "empty Android app that shows 'hello world' — no auth, no todo — smallest possible scaffold"). This proves the engine starts.

After that PR is green and smoke test passes at each state:
- **PR 3c-2**: remaining 2 workflows (ci-auto-fix, deploy-*) to workflow_call
- **PR 3d-1**: SAST + dep-review (P0 §1, §3)
- **PR 3d-2**: SBOM + cosign (P0 §2)
- **PR 3d-3**: ci-auto-fix 3-tier guard (P0 §7)
- **PR 3e**: tag `v0.1.0` stable, promote workflow-call-audit to blocker

This sequence lets us **catch design bugs early** (the first smoke test against a trivial app will expose things the plan missed — especially around how consumer workflows fetch platform-repo scripts).

---

## Decision request

Two choices:

**A. Vertical-slice dogfood first** (recommended) — PR 3c-1 as described above. Small, testable, catches integration bugs before we pile on more layers.

**B. Continue horizontal** — Step 3c then P0 §1-3 as planned, then smoke-test everything at once. Faster if nothing breaks, slower to diagnose if something does.

**C. Declare readiness good-enough and run zero-to-deploy now** — not viable; HALT 1 is certain.

CTO choice?
