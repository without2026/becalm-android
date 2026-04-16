#!/usr/bin/env bash
# setup-branch-protection.sh
# ─────────────────────────────────────────────────────────────────────────────
# Idempotent bootstrap of GitHub branch protection + production environment
# for the zero-to-deploy pipeline.
#
# Reads the target repository from .pipeline/platform.yml (github.repo).
# Safe to re-run: applies `gh api -X PUT` which replaces config wholesale.
#
# Requirements:
#   - gh CLI authenticated as a repo admin
#   - yq (mikefarah) installed: https://github.com/mikefarah/yq
#   - Target repo already exists on GitHub
#
# Usage:
#   tools/setup-branch-protection.sh                 # apply
#   tools/setup-branch-protection.sh --dry-run       # print payloads, don't PUT
#   tools/setup-branch-protection.sh --verify-only   # fetch current, diff against desired
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_YAML="$PIPELINE_DIR/.pipeline/platform.yml"

die() { echo "error: $*" >&2; exit 1; }

command -v gh >/dev/null || die "gh CLI not installed"
command -v yq >/dev/null || die "yq not installed (need mikefarah/yq)"
[[ -f "$PLATFORM_YAML" ]]    || die "platform.yml not found at $PLATFORM_YAML"

REPO=$(yq -r '.github.repo' "$PLATFORM_YAML")
BRANCH=$(yq -r '.github.base_branch // "main"' "$PLATFORM_YAML")

[[ "$REPO" != "null" && -n "$REPO" ]] || die "platform.yml .github.repo is empty"

MODE="${1:-apply}"
case "$MODE" in
  --dry-run|--verify-only|apply) ;;
  *) die "unknown mode: $MODE (expected apply | --dry-run | --verify-only)" ;;
esac

echo "→ Target: $REPO (branch: $BRANCH) [mode: $MODE]"

# Verify repo is accessible
gh api "/repos/$REPO" --jq '.full_name' >/dev/null || die "cannot access $REPO — check gh auth / repo name"

# ─── Branch Protection ──────────────────────────────────────────────────────
# Status check names MUST match CI job display names in .github/workflows/.
# Required status checks list the ROLL-UP jobs emitted by the skill's
# pr.yml.tmpl caller (see templates/common/.github/workflows/pr.yml.tmpl).
# Only roll-up jobs are required here — they aggregate per-adapter leaf
# results and are stable across adapter choice, so the list does not
# drift when the consumer switches android↔web↔electron.
#
# Naming comes from GitHub's check-suite convention:
#   <caller-job>/<reusable-dispatcher-job>
#   (example: caller job `tests` calls adapter-tests.yml which has the
#    roll-up job `test-result` → context = "tests / test-result")
#
# When SAST / SCA / SBOM workflows land, append their roll-up contexts
# here and re-run the script.
BP_PAYLOAD=$(cat <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "tests / test-result",
      "gates / gate-result"
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
  "restrictions": null,
  "block_creations": false,
  "lock_branch": false,
  "allow_fork_syncing": false
}
JSON
)

# ─── Production Environment Protection ──────────────────────────────────────
# wait_timer: 5 minutes (CTO decision 2026-04-15)
# prevent_self_review: true (the person who triggered the deploy cannot approve it)
# deployment_branch_policy: protected_branches-only (only main can deploy)
CTO_USER_ID=$(gh api "/users/jakekang28" --jq '.id' 2>/dev/null || echo "")
[[ -n "$CTO_USER_ID" ]] || die "could not resolve @jakekang28 user id; is gh auth correct?"

ENV_PAYLOAD=$(cat <<JSON
{
  "wait_timer": 5,
  "prevent_self_review": true,
  "reviewers": [
    {"type": "User", "id": $CTO_USER_ID}
  ],
  "deployment_branch_policy": {
    "protected_branches": true,
    "custom_branch_policies": false
  }
}
JSON
)

# ─── Apply / verify ─────────────────────────────────────────────────────────
apply_branch_protection() {
  echo "→ Applying branch protection to $REPO:$BRANCH"
  echo "$BP_PAYLOAD" | gh api -X PUT "/repos/$REPO/branches/$BRANCH/protection" \
    -H "Accept: application/vnd.github+json" --input - >/dev/null
  echo "  ✓ branch protection applied"
}

apply_env_protection() {
  echo "→ Applying production environment protection"
  echo "$ENV_PAYLOAD" | gh api -X PUT "/repos/$REPO/environments/production" \
    -H "Accept: application/vnd.github+json" --input - >/dev/null
  echo "  ✓ production env applied (wait_timer=5min, reviewer=@jakekang28)"
}

verify_branch_protection() {
  echo "→ Current branch protection:"
  gh api "/repos/$REPO/branches/$BRANCH/protection" \
    --jq '{required_signatures: .required_signatures.enabled, linear: .required_linear_history.enabled, enforce_admins: .enforce_admins.enabled, reviews: .required_pull_request_reviews.required_approving_review_count, codeowners: .required_pull_request_reviews.require_code_owner_reviews, contexts: .required_status_checks.contexts}' \
    2>/dev/null || echo "  (no protection configured yet)"
}

verify_env_protection() {
  echo "→ Current production env:"
  gh api "/repos/$REPO/environments/production" \
    --jq '{wait_timer: .wait_timer, reviewers: [.protection_rules[]? | select(.type=="required_reviewers") | .reviewers[]? | .reviewer.login], branch_policy: .deployment_branch_policy}' \
    2>/dev/null || echo "  (no production env configured yet)"
}

case "$MODE" in
  --dry-run)
    echo "═══ Branch Protection Payload ═══"
    echo "$BP_PAYLOAD" | yq -P
    echo
    echo "═══ Environment Payload ═══"
    echo "$ENV_PAYLOAD" | yq -P
    ;;
  --verify-only)
    verify_branch_protection
    echo
    verify_env_protection
    ;;
  apply)
    apply_branch_protection
    apply_env_protection
    echo
    echo "→ Post-apply verification:"
    verify_branch_protection
    echo
    verify_env_protection
    echo
    echo "✓ Done. Re-run with --verify-only to audit drift later."
    ;;
esac
