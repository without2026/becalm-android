# P0 Hardening Plan — Zero-to-Deploy Production Gate

**Status**: DRAFT — awaiting CTO confirm
**Author**: Claude (AI CTO role)
**Target**: close 6 critical gaps before first real zero-to-deploy run
**Scope**: governance + supply-chain + static analysis only. P1 items (observability, canary, mutation, a11y, i18n, threat-model automation, migration gates) deliberately excluded.

---

## 0. Summary Table

| # | Artifact | Type | LOC est. | Blocks merge? | Blocks prod? |
|---|---|---|---|---|---|
| 1 | `.github/workflows/security-sast.yml` | new workflow | ~120 | ✅ | ✅ (transitively) |
| 2 | `.github/workflows/sbom-sign.yml` | new workflow | ~180 | ⬜ (runs post-build) | ✅ (required for prod) |
| 3 | `.github/dependabot.yml` + `.github/workflows/dependency-review.yml` | new config + workflow | ~60 | ✅ (dep-review on PR) | — |
| 4 | `.github/CODEOWNERS` + `tools/setup-branch-protection.sh` | new file + bootstrap | ~80 | ✅ (enforcement) | — |
| 5 | `.github/workflows/deploy-production.yml` edit + env rules doc | edit + runbook | ~30 diff | — | ✅ |
| 6 | `.pipeline/orchestrator/required-pr-checks.yml` edit + orchestrator state | edit + schema | ~40 | ✅ | — |

**Rollout order**: 4 → 6 → 1 → 3 → 2 → 5. Governance first (nothing else matters if AI can still bypass). Then make SAST/SCA required. SBOM + prod env protection last because they depend on prior pieces.

---

## 1. SAST — `security-sast.yml`

### 1.1 Spec (behavior)

```yaml
module: security-sast
version: 1
behaviors:
  - id: SAST-001
    type: api
    description: "CodeQL Kotlin+Python+JS analysis runs on every PR"
    precondition: "PR opened against main"
    expected: "workflow completes, SARIF uploaded to code-scanning, fails if severity >= error"
    source: phase2-acceptance
  - id: SAST-002
    type: api
    description: "Semgrep runs with custom ruleset .semgrep/"
    precondition: "PR opened"
    expected: "fails if any rule of severity ERROR matches"
    source: phase2-acceptance
  - id: SAST-003
    type: lifecycle
    description: "SAST baseline does not regress"
    precondition: "PR introduces new code"
    expected: "new findings > 0 AND severity >= warning => job fail"
    source: domain-invariant
    trigger: pr_sync
  - id: SAST-004
    type: api
    description: "Ignore path rules documented and reviewed"
    precondition: "paths-ignore edited in workflow"
    expected: "CODEOWNERS security review required"
    source: regression-guard
```

### 1.2 Workflow skeleton

```yaml
name: Security SAST
on:
  pull_request: { types: [opened, synchronize, reopened] }
  push: { branches: [main] }
  schedule: [{ cron: "17 3 * * 1" }]  # weekly full re-scan
permissions:
  actions: read
  contents: read
  security-events: write
jobs:
  codeql:
    strategy:
      fail-fast: false
      matrix:
        language: [ kotlin, python, javascript ]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: github/codeql-action/init@v3
        with: { languages: "${{ matrix.language }}", queries: security-extended }
      - uses: github/codeql-action/autobuild@v3
      - uses: github/codeql-action/analyze@v3
        with: { category: "/language:${{ matrix.language }}" }
  semgrep:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: pip install semgrep
      - run: semgrep ci --config=auto --config=.semgrep/ --error --severity=ERROR
```

### 1.3 Custom Semgrep rules (`.semgrep/`)

Minimum starter set (one file per theme):

- `android-webview.yml` — forbid `setJavaScriptEnabled(true) + loadUrl(extras)`, forbid `setAllowFileAccessFromFileURLs(true)`.
- `android-crypto.yml` — forbid `DES`, `MD5`, `"AES/ECB/NoPadding"`, hardcoded IVs.
- `android-storage.yml` — forbid plaintext token in `SharedPreferences` (heuristic: `putString` + key contains "token|secret|jwt").
- `kotlin-log.yml` — forbid `Log.d` with exception objects containing `password|authorization`.
- `python-sql.yml` — forbid f-string into `cursor.execute`.
- `generic-eval.yml` — forbid `eval`, `exec`, Python `pickle.load` on user input.

### 1.4 Orchestrator integration

- Add `sast` category to `required-pr-checks.yml` (see §6).
- `ci-auto-fix.yml` should **NOT** attempt to auto-fix SAST findings (false-positive risk, security-sensitive). Instead, surface to CTO in `layer1.failure_summary`.
- Gate pattern: if SAST fails, orchestrator sets `L1_FAILED` with `failure_summary: "SAST violation: {rule_id}"` and skips auto-fix attempts.

### 1.5 Acceptance

- PR that introduces `Runtime.exec(userInput)` fails within 5 min.
- PR that only edits `README.md` completes SAST in <2 min with zero findings.
- SARIF visible on PR "Files changed → Code scanning" tab.

---

## 2. SBOM + Signing + Provenance — `sbom-sign.yml`

### 2.1 Spec

```yaml
module: sbom-sign
version: 1
behaviors:
  - id: SBOM-001
    type: api
    description: "CycloneDX SBOM generated for each buildable artifact"
    precondition: "main branch build produces AAB/API image"
    expected: "sbom.cdx.json uploaded, contains all gradle/pip deps with purl+version+hash"
    source: phase2-acceptance
  - id: SBOM-002
    type: api
    description: "cosign signs AAB and SBOM with OIDC keyless"
    precondition: "artifact built on main"
    expected: "cosign.sig + rekor entry exist; verify succeeds with cert-identity=github workflow"
    source: phase2-acceptance
  - id: SBOM-003
    type: api
    description: "SLSA provenance generated"
    precondition: "artifact produced"
    expected: "intoto attestation contains commit sha, workflow hash, builder id"
    source: phase2-acceptance
  - id: SBOM-004
    type: lifecycle
    description: "deploy-production refuses artifact without valid signature"
    precondition: "prod deploy triggered"
    expected: "cosign verify fails => workflow fails before Railway push"
    source: domain-invariant
    trigger: workflow_dispatch
```

### 2.2 Workflow skeleton

```yaml
name: SBOM & Sign
on:
  workflow_call:
    inputs:
      artifact_path: { type: string, required: true }
      artifact_name: { type: string, required: true }
    outputs:
      sbom_digest: { value: ${{ jobs.sbom.outputs.digest }} }
permissions:
  id-token: write   # OIDC for cosign keyless + SLSA
  contents: read
  packages: write
  attestations: write
jobs:
  sbom:
    runs-on: ubuntu-latest
    outputs:
      digest: ${{ steps.hash.outputs.digest }}
    steps:
      - uses: actions/checkout@v4
      - uses: anchore/sbom-action@v0
        with:
          path: .
          format: cyclonedx-json
          output-file: sbom.cdx.json
      - id: hash
        run: echo "digest=$(sha256sum sbom.cdx.json | cut -d' ' -f1)" >> $GITHUB_OUTPUT
      - uses: actions/upload-artifact@v4
        with: { name: sbom-${{ inputs.artifact_name }}, path: sbom.cdx.json }

  sign:
    needs: sbom
    runs-on: ubuntu-latest
    steps:
      - uses: sigstore/cosign-installer@v3
      - run: cosign sign-blob --yes --bundle cosign.bundle ${{ inputs.artifact_path }}
      - uses: actions/upload-artifact@v4
        with: { name: sig-${{ inputs.artifact_name }}, path: cosign.bundle }

  provenance:
    needs: sign
    uses: slsa-framework/slsa-github-generator/.github/workflows/generator_generic_slsa3.yml@v2
    with:
      base64-subjects: ${{ needs.sbom.outputs.digest }}
      upload-assets: true
```

### 2.3 Callers

- `adapter-build.yml` (android AAB) → call `sbom-sign.yml` with `artifact_path: app-release.aab`.
- `deploy-staging.yml` (backend image if containerized) → same.
- `deploy-production.yml` → **add verify step before Railway push**:

```yaml
- name: Verify artifact signature (prod gate)
  run: |
    cosign verify-blob \
      --bundle cosign.bundle \
      --certificate-identity-regexp "https://github.com/without2026/automateApp" \
      --certificate-oidc-issuer https://token.actions.githubusercontent.com \
      ./app-release.aab
```

### 2.4 Acceptance

- Artifact download page shows `.aab`, `sbom.cdx.json`, `cosign.bundle`, `*.intoto.jsonl`.
- `cosign verify-blob` with correct cert-identity succeeds; with wrong identity fails.
- Rekor public log contains entry at `https://search.sigstore.dev/?hash=<sha>`.
- Tamper test: modify AAB bytes → verify fails → prod deploy blocked.

---

## 3. Dependabot + Dependency Review

### 3.1 `.github/dependabot.yml`

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/automateTestApp"
    schedule: { interval: "daily" }
    open-pull-requests-limit: 10
    groups:
      minor-and-patch:
        update-types: ["minor", "patch"]
  - package-ecosystem: "pip"
    directory: "/BeCalmv3/api"
    schedule: { interval: "daily" }
  - package-ecosystem: "npm"
    directory: "/BeCalmv3/desktop"
    schedule: { interval: "weekly" }
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule: { interval: "weekly" }
```

### 3.2 `.github/workflows/dependency-review.yml`

Runs on PR; fails if the diff introduces a dep with CVSS ≥ 7 or a forbidden license.

```yaml
name: Dependency Review
on: { pull_request: { types: [opened, synchronize, reopened] } }
permissions: { contents: read, pull-requests: write }
jobs:
  review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
          deny-licenses: GPL-3.0, AGPL-3.0
          comment-summary-in-pr: always
```

### 3.3 Spec (abridged)

- `SCA-001` Dependabot daily scan runs for gradle/pip.
- `SCA-002` dependency-review blocks PR if new dep has CVE CVSS≥7.
- `SCA-003` forbidden licenses (GPL/AGPL) blocked at PR gate.
- `SCA-004` auto-merge allowed for patch-level updates after CodeQL + tests pass.

### 3.4 Orchestrator integration

- Add `sca` category to required-pr-checks. Dependabot PRs are **not** routed through zero-to-deploy (they skip Layer 0). `ci-auto-fix` ignores them.

### 3.5 Acceptance

- Within 24h of enabling, at least one Dependabot PR opens for an existing dep.
- A synthetic PR adding `log4j-core:2.14.0` fails dependency-review.

---

## 4. CODEOWNERS + Branch Protection (the most important one)

### 4.1 `.github/CODEOWNERS`

```
# Default owner — CTO must review everything not explicitly overridden
*                                   @jakekang28

# Security-critical paths — require security review
.github/workflows/                  @jakekang28
.github/workflows/security-*.yml    @jakekang28
.github/workflows/sbom-*.yml        @jakekang28
.github/workflows/deploy-*.yml      @jakekang28
.github/CODEOWNERS                  @jakekang28
.pipeline/orchestrator/             @jakekang28
.pipeline/core/authority.yml        @jakekang28

# Backend
/BeCalmv3/api/                      @jakekang28
/BeCalmv3/sidecar/                  @jakekang28

# Android
/automateTestApp/                   @jakekang28
```

*Single-CTO today. When a second engineer joins, add their handle to high-risk paths to enforce real 2-person rule. Meanwhile, signed commits + AI-reviewer acting as second checker gives best-effort separation.*

### 4.2 `tools/setup-branch-protection.sh`

Idempotent script invoking `gh api` to configure main branch protection. Run once by CTO after merging this PR.

```bash
#!/usr/bin/env bash
set -euo pipefail
REPO="without2026/automateApp"
BRANCH="main"

gh api -X PUT "/repos/$REPO/branches/$BRANCH/protection" \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Security SAST / codeql (kotlin)",
      "Security SAST / codeql (python)",
      "Security SAST / codeql (javascript)",
      "Security SAST / semgrep",
      "Dependency Review / review",
      "CI Review Pipeline / merge-gate",
      "Adapter Tests / android-unit",
      "Adapter Tests / android-instrumented",
      "Adapter Gates / spec-coverage",
      "Adapter Gates / assert-guard"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "require_code_owner_reviews": true,
    "dismiss_stale_reviews": true,
    "require_last_push_approval": true
  },
  "required_signatures": true,
  "required_linear_history": true,
  "required_conversation_resolution": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "restrictions": null
}
JSON
```

### 4.3 Orchestrator-level change (critical)

**Current bug**: orchestrator runs `gh pr merge --squash` unconditionally after Gate 4 (Merge).
**Fix**: orchestrator must check that the PR has a CODEOWNERS approving review **from a human account** before merging. If only the PR author approved (or only bot approvals), orchestrator sets status `MERGE_GATE_BLOCKED` and tells CTO: "external human review required; waiting". This is enforced both by GitHub branch protection AND by orchestrator pre-check (defense in depth).

Add to SKILL.md MERGE_GATE state:
```
Before calling gh pr merge:
  gh pr view {pr} --json reviews -q '[.reviews[] | select(.state=="APPROVED") | .author.login] | unique'
  => if list is empty or contains only bot[*] or the PR author => abort merge, set status MERGE_GATE_BLOCKED
```

### 4.4 Spec

- `GOV-001` CODEOWNERS review required for protected paths.
- `GOV-002` signed commits required on main (commits without sig rejected).
- `GOV-003` linear history enforced.
- `GOV-004` orchestrator refuses merge without human CODEOWNERS approval (enforced at orchestrator layer, not just GitHub).
- `GOV-005` force-push to main impossible even for admin (`enforce_admins: true`).

### 4.5 Acceptance

- Push with no GPG sig → rejected.
- PR with only Dependabot approval → orchestrator refuses to merge.
- Attempt to delete main → rejected.
- `tools/setup-branch-protection.sh` is idempotent (re-running produces no diff).

---

## 5. GitHub Environment Protection (Production)

### 5.1 What changes

No code change in `deploy-production.yml` (beyond §2 verify step). Instead: **GitHub Settings → Environments → `production`** must be configured with:

```yaml
# runbook stored at .pipeline/orchestrator/env-protection.md
environment: production
required_reviewers:
  - jakekang28
wait_timer_minutes: 10
deployment_branch_policy:
  protected_branches: true
  custom_branch_policies: false
prevent_self_review: true
secrets:
  - RAILWAY_TOKEN
  - PROD_API_URL
  - COSIGN_EXPERIMENTAL: "1"
```

### 5.2 Orchestrator change

New state: `L2_PROD_AWAITING_APPROVAL` (between `L2_PROD_GATE` and `L2_PROD_DEPLOYING`).

```
L2_PROD_GATE (CTO clicks Deploy in AskQuestion)
 -> orchestrator: gh workflow run deploy-production.yml ...
 -> poll: gh run view {id} --json status
 -> while status == "waiting":  # GitHub env approval pending
      status = L2_PROD_AWAITING_APPROVAL
      notify CTO: "GitHub environment 'production' requires approval"
      poll every 30s, max 1h, then timeout -> FAILED
 -> when status == "in_progress": transition to L2_PROD_DEPLOYING
```

Add `L2_PROD_AWAITING_APPROVAL` to session-schema.json status enum.

### 5.3 Spec

- `DEP-001` production env has required_reviewers ≥ 1.
- `DEP-002` production env has wait_timer ≥ 5 minutes (rage-deploy brake).
- `DEP-003` only main branch can deploy to production.
- `DEP-004` orchestrator handles "waiting" run status gracefully (does not treat as failure).
- `DEP-005` env-protection config drift detection: weekly script diffs live config against env-protection.md.

### 5.4 Bootstrap

Extend `tools/setup-branch-protection.sh` with environment config:

```bash
gh api -X PUT "/repos/$REPO/environments/production" \
  --input - <<'JSON'
{
  "wait_timer": 10,
  "prevent_self_review": true,
  "reviewers": [{"type": "User", "id": <CTO_USER_ID>}],
  "deployment_branch_policy": {
    "protected_branches": true,
    "custom_branch_policies": false
  }
}
JSON
```

### 5.5 Acceptance

- `gh workflow run deploy-production.yml` by any account → run enters `waiting`.
- CTO approves in GitHub UI → run proceeds.
- 10-min timer is visible in UI.
- Attempt to run from `feat/xxx` branch → rejected.
- Orchestrator polls waiting state without false-failing.

---

## 6. `required-pr-checks.yml` extension + session-schema update

### 6.1 YAML diff

```yaml
version: 1
required_name_substrings:
  deterministic_gates: [deterministic, gate]
  adapter_tests: [test]
  ai_review: [review, claude, codex, cross-validate, scenario]
  merge_gate: [merge]
  # NEW
  sast: [codeql, semgrep, sast]
  sca: [dependency-review, dependabot]
  supply_chain: [sbom, cosign, provenance, slsa]

ignore_name_substrings: [classify, load-config]

skip_counts_as_success:
  - ai_review

never_skip_as_success:
  - merge_gate
  - deterministic_gates
  - adapter_tests
  # NEW
  - sast
  - sca
  # Note: supply_chain intentionally NOT in never_skip for PRs
  # (only enforced at deploy-production time).
```

### 6.2 `session-schema.json` diff

Under `layer1.check_runs`:

```diff
   "properties": {
     "deterministic_gates": { "$ref": "#/$defs/checkStatus" },
     "adapter_tests": { "$ref": "#/$defs/checkStatus" },
     "ai_review": { "$ref": "#/$defs/checkStatus" },
-    "merge_gate": { "$ref": "#/$defs/checkStatus" }
+    "merge_gate": { "$ref": "#/$defs/checkStatus" },
+    "sast": { "$ref": "#/$defs/checkStatus" },
+    "sca": { "$ref": "#/$defs/checkStatus" },
+    "supply_chain": { "$ref": "#/$defs/checkStatus" }
   }
```

Status enum additions (root `status`):

```diff
+  "L2_PROD_AWAITING_APPROVAL",
+  "MERGE_GATE_BLOCKED",
```

### 6.3 SKILL.md diff (summary)

- `L1_MONITORING` polling loop: when computing L1_PASSED, iterate over **all** categories in `required_name_substrings`, not just the legacy 4.
- `MERGE_GATE`: before calling `gh pr merge`, check human CODEOWNERS approval (see §4.3).
- New transition: `MERGE_GATE → MERGE_GATE_BLOCKED` if human approval missing, loops back to `AskQuestion` with reason.
- New transition: `L2_PROD_GATE → L2_PROD_AWAITING_APPROVAL → L2_PROD_DEPLOYING`.

### 6.4 Spec

- `ORCH-001` orchestrator rejects L1_PASSED if any required category has conclusion != pass.
- `ORCH-002` skipped runs in `never_skip_as_success` categories count as fail.
- `ORCH-003` unknown check names logged but not blocking.
- `ORCH-004` MERGE_GATE_BLOCKED resumable (CTO adds reviewer, re-enters MERGE_GATE).
- `ORCH-005` L2_PROD_AWAITING_APPROVAL max 1h timeout.

---

## 7. Cross-cutting: interaction with `ci-auto-fix.yml` — 3-Tier file classification (CTO-approved)

Current auto-fix loop (3 iterations, conf 0.7/0.8) could **attempt to patch SAST findings, downgrade deps to bypass CVEs, or delete cosign verify steps** = self-bypass. Complete blacklist causes friction (legitimate Semgrep false-positive tuning becomes manual-only). Decision: 3-tier file classification.

### Tier 1 — HARD BLACKLIST

If diff touches any of these paths, `ci-auto-fix.yml` exits non-zero immediately, labels PR `needs-human-security-review`, comments reason, and does NOT attempt fixing.

```
.github/CODEOWNERS
.github/workflows/deploy-staging.yml
.github/workflows/deploy-production.yml
.github/workflows/sbom-sign.yml
.github/workflows/security-sast.yml
.github/workflows/dependency-review.yml
tools/setup-branch-protection.sh
.pipeline/orchestrator/required-pr-checks.yml
.pipeline/orchestrator/env-protection.md
.pipeline/orchestrator/session-schema.json
.pipeline/core/authority.yml
```

### Tier 2 — ADVISORY-ONLY (separate PR)

If fix requires editing these files, auto-fix opens a **new** PR titled `security-advisory: {reason}` targeting the same branch, labels it `needs-human-security-review`, and does NOT commit to the original PR. Original PR remains failing until human approves the advisory PR.

```
.semgrep/**
.github/dependabot.yml
.github/workflows/ci-auto-fix.yml
.github/workflows/ci-review.yml
.github/workflows/ci-scenario-gen.yml
.pipeline/orchestrator/*.md
.pipeline/orchestrator/guard_repo.py
```

### Tier 3 — normal auto-fix

Everything else. Current 3-iter, conf 0.7/0.8 behavior preserved.

### Implementation — guard step in ci-auto-fix.yml

```yaml
- name: Security file tier guard
  id: tier_guard
  env:
    PR: ${{ github.event.pull_request.number }}
  run: |
    CHANGED=$(git diff --name-only origin/main...HEAD)
    TIER1='^\.github/(CODEOWNERS|workflows/(deploy-(staging|production)|sbom-sign|security-sast|dependency-review)\.yml)$|^tools/setup-branch-protection\.sh$|^\.pipeline/orchestrator/(required-pr-checks\.yml|env-protection\.md|session-schema\.json)$|^\.pipeline/core/authority\.yml$'
    TIER2='^\.semgrep/|^\.github/dependabot\.yml$|^\.github/workflows/(ci-auto-fix|ci-review|ci-scenario-gen)\.yml$|^\.pipeline/orchestrator/[^/]+\.md$|^\.pipeline/orchestrator/guard_repo\.py$'

    if echo "$CHANGED" | grep -qE "$TIER1"; then
      gh pr comment "$PR" --body "🛑 auto-fix halted: Tier 1 security file in diff. Human review required."
      gh pr edit "$PR" --add-label needs-human-security-review
      echo "tier=1" >> "$GITHUB_OUTPUT"
      exit 1
    fi
    if echo "$CHANGED" | grep -qE "$TIER2"; then
      echo "tier=2" >> "$GITHUB_OUTPUT"
      echo "ADVISORY_ONLY=1" >> "$GITHUB_ENV"
    else
      echo "tier=3" >> "$GITHUB_OUTPUT"
    fi
```

Auto-fix system prompt addition:
> "You are operating in ADVISORY_ONLY=${{ env.ADVISORY_ONLY }} mode. When ADVISORY_ONLY=1, if your fix requires editing files in the current diff, open a new branch `security-advisory/{slug}` and PR titled `security-advisory: {reason}` instead of committing to the current PR. Never delete or relax a security/governance rule without a matching spec entry under `.spec/security/`."

### Defense-in-depth notes

- Tier 1 list is also enforced by CODEOWNERS (§4.1), so even a human committing to these paths needs explicit CODEOWNERS approval.
- When a second reviewer joins the org, CODEOWNERS for Tier 1 paths gains their handle → enforces real 2-person rule automatically.
- Monthly `tools/audit-security-paths.sh` (P1 backlog) will diff Tier 1 git log and flag any non-CTO author.

---

## 8. Secrets & rotation

New secrets needed (add to GitHub org → settings → secrets):

| Secret | Purpose | Source |
|---|---|---|
| `FOSSA_API_KEY` (optional) | license scanning if Dependabot review not enough | FOSSA |
| `COSIGN_EXPERIMENTAL` | enable OIDC keyless | literal "1" |
| (none for CodeQL/Semgrep/Dependabot — GitHub-native) | | |

No long-lived signing keys needed — cosign keyless uses workflow OIDC.

---

## 9. Documentation

Create/update:

- `docs/runbooks/production-deploy.md` — updated with approval flow.
- `docs/runbooks/security-incident.md` — what to do when SAST fails on main.
- `.pipeline/orchestrator/env-protection.md` — desired state of GitHub Environments config (source of truth for drift detection).
- Update `.claude/skills/zero-to-deploy/SKILL.md` with new states + merge human-check logic.
- Update `.claude/skills/without-cto/SKILL.md` Phase 2 checklist: "if app handles PII, generate threat-model.md entry" (minimal threat-model awareness — full STRIDE is P1).

---

## 10. Rollout plan

| Day | Task | Owner | Gate |
|---|---|---|---|
| 0 | Merge this plan (review only) | CTO | CTO confirm |
| 1 | Implement §4 (CODEOWNERS + branch-protection bootstrap script). Run script on repo. | Claude | CTO signs off after `gh api` dry-run |
| 1 | Implement §6 (required-checks + schema + SKILL.md). | Claude | session state parses |
| 2 | Implement §1 (SAST workflow + .semgrep/ starter). | Claude | SAST green on a known-good PR |
| 2 | Implement §3 (Dependabot + dep review). | Claude | Dependabot PR appears |
| 3 | Implement §2 (SBOM+sign+provenance), wire into adapter-build. | Claude | verify-blob succeeds |
| 3 | Implement §5 (env protection bootstrap + orchestrator state). | Claude | prod run enters `waiting` |
| 4 | Re-run zero-to-deploy with canary idea (`hello-world` pass-through) to smoke the hardened loop | CTO | COMPLETE reached |
| 5 | Run **real target**: "auth가 포함된 CEO/CTO todo 공유 Android 앱" | Claude+CTO | staging green, prod held for approval |

---

## 11. Known remaining gaps after P0 (explicit risk-acceptance)

After this PR lands, the pipeline is **startup-production-grade for a team of 1~3**, but still missing (P1 backlog):

1. Progressive rollout / canary (Play Console staged)
2. Observability: Crashlytics/Sentry, OTEL, structured logs
3. Mutation testing (Pitest)
4. Coverage threshold enforcement (not delta-only)
5. Flaky test quarantine
6. a11y automation beyond Android Lint (Espresso a11y assertions, TalkBack scripts)
7. i18n: string extraction lint, pseudo-locale test
8. Threat model / DPIA automation in Phase 2
9. DB migration pre-flight
10. Incident escalation integration (PagerDuty/Opsgenie/Slack)
11. Chaos / fault injection
12. Cost monitoring (CI minutes + Railway)
13. Android-specific: baseline profiles, macrobenchmark, R8 rules, Firebase Test Lab, Play Integrity
14. SLSA L3 hardening (hermetic builder)
15. WORM audit log for gate approvals

**Explicit risk acceptance required from CTO** for running against real users before addressing P1. For the first real run (todo-share app with internal users only), this is acceptable; for external/paid users, P1.1–P1.5 become blockers.

---

## 12. CTO Decisions (resolved 2026-04-15)

| # | Question | Decision |
|---|---|---|
| 1 | GitHub admin access | ✅ granted (CTO will run `setup-branch-protection.sh`) |
| 2 | Single CODEOWNER (`@jakekang28`) ok? | ✅ accepted; TODO comment in CODEOWNERS for 2-person path when hired |
| 3 | Orchestrator self-restriction (AI refuses merge without human review) | ✅ **ACCEPTED** (friction intentional) |
| 4 | Production env `wait_timer` | ✅ **5 minutes** |
| 5 | Dependabot patch-level auto-merge after all checks green | ✅ accepted (implemented via `.github/workflows/dependabot-automerge.yml` in §3) |
| 6 | Auto-fix scope vs security/governance files | ✅ **3-tier** (see revised §7): Tier 1 hard-block, Tier 2 advisory PR, Tier 3 normal |

Implementation begins immediately in the rollout order of §10: §4 + §6 first (Day 1), same PR.
