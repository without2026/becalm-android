# Three-Layer Architecture — Zero-to-Deploy as a Generator

**Status**: DRAFT — supersedes the repo-layout sections of `p0-hardening-plan.md` (§3, §4, §5, §10). Governance decisions from §12 of that plan remain valid.
**Goal**: restructure zero-to-deploy so the **skill generates production-grade apps**, with CI/CD governance living in a versioned platform repo, not hardcoded into a single app.

---

## 0. North-star invariants (non-negotiable)

1. **Skill is episodic, CI is continuous.** The skill runs only when invoked; workflows must run on every GitHub event forever. ⇒ Workflows must be files in remote repos, not MCP calls.
2. **Generated apps are self-governing.** An app produced by zero-to-deploy must pass audits without the skill present. ⇒ Governance artifacts (CODEOWNERS, branch protection, env rules) are emitted into the target repo.
3. **One governance source, many consumers.** Workflow drift across apps is the #1 maintenance cost. ⇒ Reusable workflows (`on: workflow_call:`) live in one platform repo; apps pin to a version.
4. **Mock and real apps are structurally identical.** `automateApp` must be shaped exactly as the skill will shape future real apps. No special-case shortcuts.
5. **Q3 constraint preserved.** AI cannot merge without human CODEOWNERS review. This is enforced at **both** the GitHub platform (branch protection, CODEOWNERS) and the orchestrator layer (pre-merge check).

---

## 1. Three layers

```
┌───────────────────────────────────────────────────────────┐
│ LAYER A — SKILL (local, episodic)                          │
│ ~/.claude/skills/zero-to-deploy/                           │
│   SKILL.md                    orchestration state machine  │
│   templates/                  files emitted into new apps  │
│     workflows/main.yml.tmpl   3-line caller stub           │
│     CODEOWNERS.tmpl                                        │
│     platform.yml.{adapter}.tmpl                            │
│     dependabot.yml.tmpl                                    │
│     setup-branch-protection.sh.tmpl                        │
│     spec/README.md.tmpl                                    │
│     .gitignore.tmpl                                        │
│ ~/.claude/skills/without-cto/     (unchanged — 7-phase)    │
│ ~/.claude/skills/expert-debate/   (unchanged)              │
└─────────────┬─────────────────────────────────────────────┘
              │  at generation time, skill renders templates
              │  and pushes via GitHub MCP
              ▼
┌───────────────────────────────────────────────────────────┐
│ LAYER B — PLATFORM REPO (remote, versioned, 1 instance)    │
│ github.com/without2026/zero-to-deploy-platform             │
│ Tag: v0.1.0 (semver)                                       │
│   .github/workflows/                                       │
│     _ci-review.yml           on: workflow_call             │
│     _adapter-build.yml       on: workflow_call             │
│     _adapter-tests.yml       on: workflow_call             │
│     _adapter-gates.yml       on: workflow_call             │
│     _security-sast.yml       on: workflow_call             │
│     _dependency-review.yml   on: workflow_call             │
│     _sbom-sign.yml           on: workflow_call             │
│     _ci-auto-fix.yml         on: workflow_call             │
│     _ci-scenario-gen.yml     on: workflow_call             │
│     _deploy-staging.yml      on: workflow_call             │
│     _deploy-production.yml   on: workflow_call             │
│   .pipeline/core/            authority.yml, spec-schema,   │
│                              spec-coverage.py,             │
│                              assert-guard.py,              │
│                              judgment-prompt.md            │
│   .pipeline/adapters/        android/, electron/, web/     │
│   .semgrep/                  shared rule library           │
│   .github/scripts/           cross_validator.py,           │
│                              coverage-delta.py,            │
│                              validate_generated_changes.py │
│   VERSION.md                 changelog, breaking changes   │
│   README.md                  how to consume                │
└─────────────┬─────────────────────────────────────────────┘
              │  generated apps reference @v0.1.0
              ▼
┌───────────────────────────────────────────────────────────┐
│ LAYER C — GENERATED APPS (remote, N instances)             │
│ github.com/without2026/<app-name>                          │
│   .github/                                                 │
│     CODEOWNERS                 tier 1/2 paths              │
│     dependabot.yml             app-specific ecosystems     │
│     workflows/                                             │
│       pr.yml                   caller: review+sast+tests   │
│       deploy-staging.yml       caller: staging deploy      │
│       deploy-production.yml    caller: prod deploy         │
│   .pipeline/                                               │
│     platform.yml               app-specific config         │
│   .spec/                       app behaviors + contracts   │
│   tools/setup-branch-protection.sh  (optional local copy)  │
│   app/                         source code                 │
└───────────────────────────────────────────────────────────┘
```

---

## 2. Platform repo — file-by-file

### 2.1 Reusable workflow interface conventions

Every reusable workflow follows the same contract:

```yaml
name: <Display Name>     # e.g. "Security SAST"
on:
  workflow_call:
    inputs:
      ref:
        type: string
        required: false
        default: ${{ github.sha }}
      adapter:
        type: string
        required: true
        description: "android | electron | web"
    secrets:
      # declared only if this workflow needs org-level secrets
      RAILWAY_TOKEN: { required: false }
      ANTHROPIC_API_KEY: { required: false }
      # ... per-workflow
    outputs:
      status:
        value: ${{ jobs.main.outputs.status }}

permissions:
  contents: read
  # id-token/write, security-events/write, etc. added per-workflow
  # NOTE: callers must also declare these permissions at the job level.

jobs:
  main:
    runs-on: ubuntu-latest
    outputs:
      status: ${{ steps.summary.outputs.status }}
    steps:
      # ...
```

**Critical naming convention — required status check match**:

When a caller invokes `uses: .../security-sast.yml@v1` as job name `sast`, the PR status check appears as **`sast / main`** (caller-job-name / reusable-job-name). Branch protection `contexts` must be written in this `<caller>/<callee>` form:

```
contexts:
  - "sast / codeql (kotlin)"   # caller job 'sast' → reusable job 'codeql' matrix kotlin
  - "tests / android-unit"
  - "review / merge-gate"
```

The skill's `CODEOWNERS.tmpl` and `setup-branch-protection.sh.tmpl` must render the context names by reading the caller's `pr.yml` job names + the reusable workflow's internal job names. Templating must be driven by a single **`manifest.yml`** in the skill (see §3.3).

### 2.2 Workflow list (current → refactored mapping)

| Current (flat, in `/home/jakek/without/.github/workflows/`) | Refactored in platform repo | Notes |
|---|---|---|
| `ci-review.yml` | `_ci-review.yml` (workflow_call) | `classify` job stays caller-side so PR LOC routing works per-app |
| `adapter-build.yml` | `_adapter-build.yml` | Input: adapter, outputs artifact path |
| `adapter-tests.yml` | `_adapter-tests.yml` | Matrix resolved by adapter input |
| `adapter-gates.yml` | `_adapter-gates.yml` | spec-coverage + assert-guard + detect-secrets |
| `android-*.yml`, `electron-*.yml`, `web-*.yml` | **absorbed into `_adapter-*.yml`** (matrix) | Removes 9 files, dedup |
| `ci-auto-fix.yml` | `_ci-auto-fix.yml` | Keeps Tier-1/2 guard (from §7 of p0-hardening-plan) |
| `ci-scenario-gen.yml` | `_ci-scenario-gen.yml` | |
| `deploy-staging.yml` | `_deploy-staging.yml` | Adds SBOM verify input |
| `deploy-production.yml` | `_deploy-production.yml` | Adds env-protection docs + cosign verify step |
| *(new)* | `_security-sast.yml` | CodeQL matrix + Semgrep |
| *(new)* | `_dependency-review.yml` | |
| *(new)* | `_sbom-sign.yml` | Called from build job post-AAB |

### 2.3 `.pipeline/core/` and `.pipeline/adapters/`

Moved **as-is** from current `/home/jakek/without/.pipeline/`. These Python scripts are executed by the reusable workflows via `uses: actions/checkout@v4` + `actions/setup-python@v5` + `pip install -e`. The platform repo becomes the canonical home.

**Action**: skill's `without-cto` SKILL.md Phase 6 references `/.pipeline/core/spec-coverage.py` — must be updated to work whether the scripts are in the app repo (current) or pulled from the platform repo during workflow execution. Decision: **scripts always live in platform repo; apps only hold config (`platform.yml`) and data (`.spec/`)**. The caller workflow does `git clone --depth 1 https://github.com/without2026/zero-to-deploy-platform.git@v0.1.0 /tmp/ztd` and runs scripts from there.

### 2.4 `.github/scripts/`

`cross_validator.py`, `coverage-delta.py`, `validate_generated_changes.py` currently untracked at repo root. Moved into platform repo under `.github/scripts/`. Callers reference them via the same `/tmp/ztd` checkout pattern.

### 2.5 `VERSION.md` + tag policy

- SemVer: `v<major>.<minor>.<patch>`
- MAJOR: breaking change to reusable workflow *inputs*, *required secrets*, or *output names*
- MINOR: new reusable workflow, new optional input, new gate category
- PATCH: bugfix, rule tuning, doc change
- Apps pin to `@v<major>` (floating minor/patch) by default
- Security-sensitive apps pin to `@<sha>` (immutable) — opt-in via `platform.yml`

**Release gating**: platform repo's own CI (yes, it has its own CI) runs `actionlint` + `.github/scripts/validate_generated_changes.py` + a **"fixture app" roundtrip** (each platform PR renders a dummy app, applies the change, runs the full zero-to-deploy pipeline against it, must reach COMPLETE). Tag is applied only after fixture pipeline passes.

### 2.6 README.md

Consumer docs: "how to generate an app pinned to this platform." Includes the caller-stub examples from §3.2.

---

## 3. Skill layer — `~/.claude/skills/zero-to-deploy/`

### 3.1 SKILL.md changes (summary)

The existing state machine is preserved. Three additions:

1. **New phase at IDLE**: `REPO_BOOTSTRAP`. Before `L0_BRIEFING`, the orchestrator:
   - Reads `platform.yml` being drafted (after platform auto-design section)
   - Creates the target GitHub repo via MCP (`gh repo create`)
   - Renders templates from `templates/` into a local scratch dir
   - Pushes initial commit on `main` (skeleton — no app code yet)
   - Applies branch protection via `tools/setup-branch-protection.sh`
   - **Then** proceeds to L0_BRIEFING, where the subagent does feature work on `feat/*` branch

2. **Resumption from an existing app**: if `platform.yml` already points to an existing repo (e.g. `automateApp` for mock), skip `REPO_BOOTSTRAP`, jump directly to `L0_BRIEFING`.

3. **Platform pin resolution**: during template rendering, resolve `${PLATFORM_VERSION}` (e.g. `v0.1.0`) from a field in the skill's metadata. Each skill release pins exactly one platform version.

### 3.2 `templates/workflows/pr.yml.tmpl`

```yaml
name: PR
on:
  pull_request:
    types: [opened, synchronize, reopened]
permissions:
  contents: read
  pull-requests: write
  security-events: write
  id-token: write

jobs:
  sast:
    uses: without2026/zero-to-deploy-platform/.github/workflows/_security-sast.yml@{{PLATFORM_VERSION}}
    with:
      adapter: {{ADAPTER}}
    secrets: inherit

  dep-review:
    uses: without2026/zero-to-deploy-platform/.github/workflows/_dependency-review.yml@{{PLATFORM_VERSION}}

  build:
    needs: [sast]
    uses: without2026/zero-to-deploy-platform/.github/workflows/_adapter-build.yml@{{PLATFORM_VERSION}}
    with:
      adapter: {{ADAPTER}}

  tests:
    needs: [build]
    uses: without2026/zero-to-deploy-platform/.github/workflows/_adapter-tests.yml@{{PLATFORM_VERSION}}
    with:
      adapter: {{ADAPTER}}

  gates:
    needs: [build]
    uses: without2026/zero-to-deploy-platform/.github/workflows/_adapter-gates.yml@{{PLATFORM_VERSION}}
    with:
      adapter: {{ADAPTER}}

  review:
    needs: [build, tests, gates]
    uses: without2026/zero-to-deploy-platform/.github/workflows/_ci-review.yml@{{PLATFORM_VERSION}}
    secrets: inherit

  auto-fix:
    needs: [review]
    if: failure()
    uses: without2026/zero-to-deploy-platform/.github/workflows/_ci-auto-fix.yml@{{PLATFORM_VERSION}}
    secrets: inherit
```

Placeholders substituted at generation time:
- `{{PLATFORM_VERSION}}` → `v0.1.0` (from skill metadata)
- `{{ADAPTER}}` → `android` / `electron` / `web` (from platform.yml)

### 3.3 `templates/manifest.yml`

Canonical mapping between caller job names and reusable job names, used to derive the exact branch-protection status check contexts:

```yaml
platform_version: v0.1.0
contexts:
  - caller: sast
    callee_jobs: [codeql, semgrep]
    matrix:
      codeql: [kotlin, python, javascript]
  - caller: dep-review
    callee_jobs: [review]
  - caller: build
    callee_jobs: [main]
  - caller: tests
    callee_jobs: [android-unit, android-instrumented]
    note: "rendered based on adapter; for web: [web-unit, web-e2e]"
  - caller: gates
    callee_jobs: [spec-coverage, assert-guard, detect-secrets]
  - caller: review
    callee_jobs: [classify, claude-review, codex-review, merge-gate]
```

`setup-branch-protection.sh.tmpl` reads manifest.yml + platform.yml.adapter to emit the final `contexts: [...]` list. This eliminates hand-maintained drift between workflow job names and branch protection config.

### 3.4 Other templates

- `CODEOWNERS.tmpl` — Tier 1/2 list, placeholder for org handle
- `platform.yml.android.tmpl` / `.electron.tmpl` / `.web.tmpl` — per-adapter starter configs
- `dependabot.yml.tmpl` — ecosystems list conditional on adapter
- `setup-branch-protection.sh.tmpl` — already created in Day 1; move here and parametrize
- `spec/README.md.tmpl` — explains how to write `.spec/*.spec.yml`
- `.gitignore.tmpl` — common ignores + adapter-specific

### 3.5 Template rendering engine

Simple `envsubst`-style with `{{VAR}}` placeholders. Rendering logic lives in `.claude/skills/zero-to-deploy/lib/render.py`:

```python
def render_template(src: Path, dest: Path, vars: dict[str, str]) -> None:
    text = src.read_text()
    for k, v in vars.items():
        text = text.replace(f"{{{{{k}}}}}", v)
    if "{{" in text:
        raise RuntimeError(f"unresolved placeholder in {src}: {re.findall(r'{{[^}]+}}', text)}")
    dest.write_text(text)
```

No Jinja, no Mustache — keep deps zero.

---

## 4. Generated app layer — what lands in the mock/real app repo

Structure (as emitted):

```
<app-name>/
├── .github/
│   ├── CODEOWNERS                 (from CODEOWNERS.tmpl)
│   ├── dependabot.yml             (from dependabot.yml.tmpl)
│   └── workflows/
│       ├── pr.yml                 (from pr.yml.tmpl)
│       ├── deploy-staging.yml     (caller to _deploy-staging.yml)
│       └── deploy-production.yml  (caller to _deploy-production.yml)
├── .pipeline/
│   └── platform.yml               (adapter, github.repo, version pin)
├── .spec/
│   └── (empty, populated by without-cto Phase 2)
├── tools/
│   └── setup-branch-protection.sh (from template, optional — CTO can run locally)
├── .gitignore
├── README.md                      (auto-generated summary of skill run)
└── app/                           (source, populated by without-cto Phase 6)
```

Anything **NOT** in the generated app:
- `.pipeline/core/`, `.pipeline/adapters/` — live in platform repo
- `.semgrep/` — lives in platform repo
- `ci-auto-fix.yml`, `ci-review.yml` etc. — not caller'd directly; they're called by `pr.yml` transitively
- `.claude/` — local skill assets, not emitted

---

## 5. Secrets & permissions model

### 5.1 Org-level secrets (inherit into every app repo)

Configured once at `without2026` org level, accessible to all generated apps:

| Secret | Used by | Notes |
|---|---|---|
| `ANTHROPIC_API_KEY` | ci-review, ci-auto-fix, ci-scenario-gen | Anthropic API calls |
| `CODEX_API_KEY` | ci-review (cross-validator) | |
| `RAILWAY_TOKEN_STAGING` | deploy-staging | Per-environment token |
| `RAILWAY_TOKEN_PROD` | deploy-production | |

### 5.2 Per-repo secrets

| Secret | Used by | Notes |
|---|---|---|
| `CODEOWNERS_HUMAN_REVIEWER_HANDLE` | orchestrator pre-merge check | Currently `@jakekang28` |
| `STAGING_API_URL` / `PROD_API_URL` | smoke tests | |

### 5.3 Permissions — caller vs callee

GitHub does NOT auto-propagate `permissions:` from caller to reusable workflow. Each reusable workflow must **self-declare** the permissions it needs inside its own `jobs.*.permissions:` block. Caller's top-level `permissions:` only sets defaults for jobs that don't specify.

Critical permissions matrix:

| Reusable workflow | Required permission | Caller must grant |
|---|---|---|
| `_security-sast.yml` | `security-events: write` | same |
| `_sbom-sign.yml` | `id-token: write`, `attestations: write` | same |
| `_ci-auto-fix.yml` | `contents: write`, `pull-requests: write` | same |
| `_deploy-production.yml` | `id-token: write`, `deployments: write` | same |

### 5.4 `secrets: inherit` policy

Default: caller uses `secrets: inherit` to pass all org+repo secrets to the reusable workflow. Reusable workflow declares which secrets it actually **reads** (defensive — if secret missing, job fails at declaration, not mid-execution).

---

## 6. Migration path from current state

### 6.1 Inventory

- `/home/jakek/without/` untracked files include `.pipeline/`, `.github/workflows/`, `.spec/contracts/project-layout.template.yml`, `BeCalmLanding/`, various `BeCalmv2` files, `BeCalmv4/`, `automateTestApp/`, `docs/`, `kestrel/`, `vendor/`, CLAUDE.md, etc.
- `BeCalmv3/` has its own remote; keep as-is.
- Current `origin` is `without2026/BeCalm.git` (legacy, misnamed) — NOT touched.

### 6.2 Six-step migration

Each step is a single `git` operation + MCP call. CTO approval between 1 and 2.

**Step 1** — Create platform repo scaffold locally:
```bash
mkdir -p ~/without/zero-to-deploy-platform
cd ~/without/zero-to-deploy-platform
git init -b main
```
Copy from current `~/without/`:
- `.pipeline/core/` → `./pipeline/core/`  (note: **drop leading dot** in platform repo; app repos will keep `.pipeline/` for config-only)
- `.pipeline/adapters/` → `./pipeline/adapters/`
- `.github/workflows/*.yml` → `./.github/workflows/` (will be refactored to `workflow_call` in Step 3)
- `.github/scripts/*` → `./.github/scripts/`

**Step 2** — Create `without2026/zero-to-deploy-platform` remote via MCP, push initial commit (no refactor yet, just move). Tag `v0.0.1-raw` as a historical reference.

**Step 3** — Refactor each workflow to `workflow_call` in a single PR. Run `actionlint` + a fixture roundtrip. Merge. Tag `v0.1.0`.

**Step 4** — Create skill templates under `~/.claude/skills/zero-to-deploy/templates/`. Move current `/home/jakek/without/.github/CODEOWNERS` and `tools/setup-branch-protection.sh` → `templates/CODEOWNERS.tmpl` and `templates/setup-branch-protection.sh.tmpl`, parametrize.

**Step 5** — Create `without2026/automateApp` mock repo via MCP. Generate its contents by running the skill's `REPO_BOOTSTRAP` phase (end-to-end dogfooding). Confirm the rendered files look identical to what a real future app would get.

**Step 6** — Run `setup-branch-protection.sh` against `automateApp`. Open a throwaway PR. Verify branch protection enforces CODEOWNERS, signed commits, required status checks matching the rendered contexts. Verify Q3 (orchestrator refuses to merge without human review) works end-to-end.

**Step 7 (NOT a migration step, but the real payoff)** — Run the full zero-to-deploy pipeline on `automateApp` for the idea `"auth가 포함된 CEO/CTO todo 공유 Android 앱"`. Reach COMPLETE or surface the first real-run gaps.

### 6.3 Pre-existing `/home/jakek/without/` cleanup

After Step 5, the parent dir has duplicate content (original + new locations). Cleanup:
- `.pipeline/`, `.github/workflows/`, etc. → delete (now lives in platform repo)
- `automateTestApp/` → delete (now lives in `automateApp` repo)
- Keep: `CLAUDE.md`, `docs/`, `BeCalmv3/`, `BeCalmv4/`, workspace-level instructions

Optional: convert `/home/jakek/without/` from a misnamed `BeCalm.git` clone into a plain workspace directory (delete `.git/`). This is destructive — CTO approval required.

---

## 7. Testing plan for the platform repo itself

The platform repo has its own CI. Roundtrip tests:

1. **actionlint** — yaml validity
2. **unit** — `.pipeline/core/spec-coverage.py`, `assert-guard.py`, `render.py` pytest
3. **fixture-roundtrip** — `fixtures/mock-android-app/` is an empty Android project; PR to platform repo triggers a workflow that:
   - Checks out fixture
   - Renders skill templates into a scratch dir pointing at the PR's commit (`@<pr-sha>`)
   - Runs `actionlint` on rendered workflows
   - Optionally, dispatches those workflows against the fixture via `act` (nektos/act) or a scratch repo
4. **upgrade compatibility** — diff with the previous tag; if a `workflow_call` input/secret/output was removed, fail unless PR title contains `BREAKING:`.

---

## 8. Risk register specific to this refactor

| Risk | Likelihood | Mitigation |
|---|---|---|
| Required status check name drift between platform and app repos | High | `manifest.yml` as single source of truth (§3.3) |
| Reusable workflow permissions silently miss `id-token: write` | Medium | Caller stub template pre-declares all common perms |
| Platform repo outage blocks every app's CI | Low (GitHub itself) | `@<sha>` pins for security-critical apps; escape hatch doc |
| Secrets leak across apps via org-level inheritance | Medium | Per-workflow secret declaration enforces least-privilege; audit doc |
| Dogfooding chicken-and-egg (platform repo's own CI needs reusable workflows) | Medium | Platform repo uses **relative** `uses: ./.github/workflows/_foo.yml` for self-CI, different from consumers' cross-repo `@v1` |
| Migration leaves half-moved files on disk | Low | Step 6.3 cleanup, gated by CTO approval |

---

## 9. Rollout schedule

| Day | Deliverable | Validates |
|---|---|---|
| 1 | This plan (CTO review) | Alignment |
| 2 | Platform repo Step 1-3 (copy + `workflow_call` refactor for ci-review, adapter-build/tests/gates) | Core CI works |
| 3 | Platform repo: add security-sast, dependency-review, sbom-sign (P0 §1-3). Tag v0.1.0. | Governance parity |
| 3 | Skill templates + `render.py` + `manifest.yml` | Generator layer |
| 4 | Create `automateApp` repo via skill REPO_BOOTSTRAP. Branch protection applied. | End-to-end mock |
| 5 | Run zero-to-deploy on `automateApp` for the "CEO/CTO todo" idea. First real run, collect gaps. | Real-run validation |

---

## 10. CTO Decisions (resolved 2026-04-15)

| # | Question | Decision |
|---|---|---|
| 1 | Platform repo name | `without2026/zero-to-deploy-platform` ✅ |
| 2 | Visibility | **private** initially; reassess after `v1.0.0` — may promote to public for supply-chain credibility ✅ |
| 3 | Default version pin | `@v1` floating for all apps (mock + real), consumer-selectable `@<sha>` later for high-security cases ✅ |
| 4 | Skill storage | Separate **private** `without2026/zero-to-deploy-skill` repo. Never merged with platform repo. ✅ |
| 5 | Workspace cleanup | **5B + 5-b-Y**: tar-backup originals to `~/without-backup-YYYY-MM-DD.tar.gz` (7-day retention), then delete. `/home/jakek/without/` becomes plain workspace git (remote removed, `CLAUDE.md`/`docs/` tracked locally only). ✅ |

Step 1 begins immediately. Skill-repo extraction deferred to Step 4; workspace cleanup deferred to Step 6.3 (after mock app roundtrip proves migration).
