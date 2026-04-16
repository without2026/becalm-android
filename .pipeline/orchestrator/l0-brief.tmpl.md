# Layer 0 Brief

You are running Layer 0 of the zero-to-deploy pipeline as a subagent.
Your job: follow the WithoutCTO 7-phase pipeline to implement the idea below.

## CRITICAL INSTRUCTIONS

1. Read and follow `.claude/skills/without-cto/SKILL.md` for the full 7-phase pipeline.
2. You are running as a **SUBAGENT**. You cannot ask the user questions directly.
3. On **Phase 2 HARD GATE**: instead of calling AskUserQuestion, STOP and return a JSON
   result with `status: "gate_blocked"` and `gate: "phase2"`. Include the full subproblem
   breakdown and spec drafts in the `payload`.
4. On **Phase 4 HARD GATE**: same pattern — return `gate_blocked` with `gate: "phase4"`
   and the agent selection table in the `payload`.
5. On **Phase 5 HARD GATE** (expert debate did not converge): return `gate_blocked` with
   `gate: "phase5"` and `payload`: `{ "topic", "positions" (≥2), "why_no_convergence"? }`.
6. On **completion**: commit all changes, push the branch, and return a JSON result with
   `status: "complete"`.
7. On **failure**: return a JSON result with `status: "failed"` and an error description.
8. **Phase range / handoff** — Follow `## Phase range` and `## Orchestrator` below; see without-cto Subagent Mode §7–8.

## Idea

{{idea}}

## Platform (from platform.yml)

- project_root: {{project_root}}
- platform: {{platform}}
- adapter: {{adapter}}
- display_name: {{display_name}}
- languages: {{languages_summary}}
- github repo: {{github_repo}} (base: {{github_base_branch}})

## Spec Contract (compressed)

- Dir: {{spec_dir}} (relative to repo root)
- contracts_profile: {{contracts_profile}} (must match `contracts.contracts_profile` in Phase 2 JSON)
- Schema: `.pipeline/core/spec-schema.json`
- Authority: `.pipeline/core/authority.yml`
- Types: api, ui_interaction, lifecycle, permission, gesture
- Quantity floor: 3/public endpoint, 2/internal fn, 1/UI screen, 1 invariant/module
- For API behaviors: write endpoint contracts in an OpenAPI 3.x-compatible shape (explicit schemas for arrays/items + 4xx/5xx errors).
- Comment patterns:
{{comment_patterns}}

## Project Layout

{{project_structure_summary}}

## Git Workflow

- Branch: `feat/{{branch_slug}}`
- Commit format: `type(scope): description`
- Push to origin after all phases complete.
- Do NOT create a PR — the orchestrator handles PR creation.

## Orchestrator (zero-to-deploy)

`DISPATCH: phase_5_7_separate: true` — When the CTO confirms Phase 4 on **resume**, return `segment_complete` only (see without-cto Subagent §7). A **second** subagent brief will run Phases 5–7.

## Phase range (filled by orchestrator)

{{phase_range}}

## Return Format

Your final message MUST contain exactly one JSON code block with this shape:

```json
{
  "status": "complete | gate_blocked | failed | segment_complete",
  "gate": null,
  "segment": null,
  "branch": "feat/{{branch_slug}}",
  "commits": [
    {"hash": "abc1234", "message": "feat(scope): description"}
  ],
  "specs_created": [
    {"id": "MOD-001", "module": "module_name", "description": "behavior description"}
  ],
  "tests_count": 0,
  "spec_coverage": "0/0",
  "files_changed_count": 0,
  "summary": "2-3 line implementation summary",
  "risks": ["risk 1", "risk 2"],
  "payload": null,
  "error": null
}
```

### Gate-blocked return (Phase 2):

```json
{
  "status": "gate_blocked",
  "gate": "phase2",
  "branch": "feat/{{branch_slug}}",
  "payload": {
    "subproblems": [
      {
        "id": "SP-1",
        "statement": "one-line problem statement",
        "type": "build",
        "dependencies": ["none"],
        "done_when": "grading criterion"
      }
    ],
    "spec_drafts": [
      {
        "file": ".spec/module.spec.yml",
        "behaviors": [
          {
            "id": "MOD-001",
            "type": "api",
            "description": "behavior description",
            "precondition": "test setup",
            "expected": "machine-verifiable assertion",
            "source": "phase2-acceptance"
          }
        ]
      }
    ],
    "contracts": {
      "contracts_profile": "fullstack",
      "data_model": {
        "version": 1,
        "tables": [
          {
            "name": "todos",
            "columns": [
              { "name": "id", "type": "uuid", "primary": true },
              { "name": "title", "type": "text" }
            ]
          }
        ],
        "relationships": [],
        "migration_notes": []
      },
      "api_contract": {
        "version": 1,
        "base_path": "/api",
        "auth": "bearer_jwt",
        "endpoints": [
          {
            "method": "GET",
            "path": "/todos",
            "auth": "required",
            "response": { "200": "{ items: Todo[], total: int }" },
            "spec_refs": ["MOD-001"]
          }
        ]
      },
      "ui_map": {
        "version": 1,
        "routes": [
          {
            "path": "/",
            "screen": "Dashboard",
            "data": ["GET /api/todos"],
            "components": ["TodoList"]
          }
        ],
        "shared_components": []
      }
    }
  }
}
```

### Gate-blocked return (Phase 5 — debate tiebreak):

```json
{
  "status": "gate_blocked",
  "gate": "phase5",
  "branch": "feat/{{branch_slug}}",
  "payload": {
    "topic": "Highest-blast-radius subproblem (e.g. SP-2 sync strategy)",
    "positions": [
      {
        "name": "Approach A",
        "summary": "One paragraph",
        "risks": ["latency on slow networks"]
      },
      {
        "name": "Approach B",
        "summary": "One paragraph",
        "risks": ["more client complexity"]
      }
    ],
    "why_no_convergence": "Experts disagreed on offline vs server source of truth."
  }
}
```

### Segment handoff (after Phase 4 approved; zero-to-deploy first subagent only):

```json
{
  "status": "segment_complete",
  "segment": "post_phase4",
  "gate": null,
  "branch": "feat/{{branch_slug}}",
  "summary": "Phases 1–4 complete; hand off to Phase 5–7 subagent."
}
```

### Gate-blocked return (Phase 4):

```json
{
  "status": "gate_blocked",
  "gate": "phase4",
  "branch": "feat/{{branch_slug}}",
  "payload": {
    "agent_table": [
      {
        "subproblem": "SP-1",
        "agent": "backend-developer",
        "layers_touched": ["DB", "API"],
        "justification": "one sentence",
        "blast_radius": "local"
      }
    ]
  }
}
```
