#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

"$ROOT_DIR/qa/emulator/scripts/verify_person_rendering_qa.sh"
"$ROOT_DIR/qa/emulator/scripts/verify_meeting_speaker_matching_qa.sh"
"$ROOT_DIR/qa/emulator/scripts/measure_android_readiness.sh"

cat <<EOF
BeCalm beta readiness QA passed.

Covered:
  - Korean People landing and evidence upload entry
  - message screenshot picker path
  - person detail give/take/schedule rendering
  - meeting speaker review and existing-person matching
  - local memory.md generation
  - cold start, memory, and frame stats report
EOF
