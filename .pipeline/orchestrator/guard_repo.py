#!/usr/bin/env python3
"""
Guard against running zero-to-deploy against the wrong GitHub repository.

Checks that:
- `.pipeline/platform.yml` defines `github.repo` (owner/name) and optionally `github.base_branch`
- the current git `origin` remote points to that same repo

Exit codes:
  0 = OK
  2 = BLOCK (mismatch or missing required config)
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys

try:
    import yaml
except ImportError:
    yaml = None


def _find_repo_root() -> pathlib.Path:
    p = pathlib.Path.cwd()
    for candidate in [p, *p.parents]:
        if (candidate / ".pipeline" / "platform.yml").exists():
            return candidate
    return p


def _load_platform(path: pathlib.Path) -> dict:
    text = path.read_text(encoding="utf-8")
    if yaml:
        return yaml.safe_load(text) or {}
    # best effort fallback (platform.yml should be YAML; require pyyaml)
    print("[BLOCK] pyyaml is required to parse .pipeline/platform.yml for repo guard")
    sys.exit(2)


def _run_git(repo_root: pathlib.Path, args: list[str]) -> str:
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=True,
        )
    except subprocess.CalledProcessError as e:
        msg = (e.stderr or e.stdout or "").strip()
        raise RuntimeError(msg or f"git {' '.join(args)} failed") from e
    return result.stdout.strip()


def _parse_repo_from_remote(url: str) -> str | None:
    """
    Accept common remote formats:
      - https://github.com/OWNER/REPO.git
      - git@github.com:OWNER/REPO.git
    Returns 'OWNER/REPO' or None.
    """
    url = url.strip()
    m = re.search(r"github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/.]+)(?:\.git)?$", url)
    if not m:
        return None
    return f"{m.group('owner')}/{m.group('repo')}"


def main() -> None:
    repo_root = _find_repo_root()
    platform_path = repo_root / ".pipeline" / "platform.yml"
    if not platform_path.exists():
        print("[BLOCK] .pipeline/platform.yml not found")
        sys.exit(2)

    platform = _load_platform(platform_path)
    gh = platform.get("github", {}) if isinstance(platform, dict) else {}
    expected_repo = (gh.get("repo") or "").strip()
    if not expected_repo:
        print("[BLOCK] platform.yml missing github.repo (expected e.g. 'without2026/BeCalm')")
        sys.exit(2)

    origin_url = _run_git(repo_root, ["remote", "get-url", "origin"])
    actual_repo = _parse_repo_from_remote(origin_url)
    if not actual_repo:
        print(f"[BLOCK] Could not parse GitHub repo from origin URL: {origin_url}")
        sys.exit(2)

    if actual_repo.lower() != expected_repo.lower():
        print("[BLOCK] Repo mismatch: refusing to run GitHub operations.")
        print(f"  platform.yml github.repo = {expected_repo}")
        print(f"  git origin remote repo  = {actual_repo}")
        print(f"  origin url              = {origin_url}")
        sys.exit(2)

    base_branch = (gh.get("base_branch") or "main").strip()
    print("[PASS] Repo guard OK")
    print(f"  repo: {actual_repo}")
    print(f"  base_branch: {base_branch}")


if __name__ == "__main__":
    main()

