# Wave 5 — codex:review approval evidence

**PR**: [#31](https://github.com/without2026/becalm-android/pull/31) — `feat/ui/wave-5`
**Final sha**: `1efbe46`
**Date**: 2026-04-22
**Rounds**: 3

## Round-by-round verdicts

| Round | Verdict | P1 count | Notes |
|-------|---------|----------|-------|
| 1 | REJECTED | 2 | W5-P1-01 (SRC-004 tap-through), W5-P1-02 (event_snippet render) |
| 2 | REJECTED | 1 | W5-P1-03 (SRC-008 3-section + collapsed 이행 완료) |
| 3 | **APPROVED** | 0 | All prior P1s confirmed-resolved |

## P1 findings and resolution

### W5-P1-01 — SRC-004 tap-through missing
- **Cite**: `.spec/source-viewer.spec.yml:37-45` + `.spec/contracts/ui-map.yml:206-210`
- **Fix**: commit `a73d7ba` added `onEventTap` parameter to `InteractionHistoryRow`, wired `navController.navigate(BecalmRoute.RawEventDetail(personId, eventId).path)` from `PersonDetailScreen`.

### W5-P1-02 — event_snippet not rendered
- **Cite**: `.spec/contracts/ui-map.yml:206-210` "소스 아이콘 + event_title + event_snippet(truncated) + timestamp"
- **Fix**: commit `a73d7ba` added `snippet: String?` to `InteractionRow.Event`, plumbed `entity.eventSnippet?.take(200)` from VM, rendered as 2-line secondary body.

### W5-P1-03 — SRC-008 3-section structure with collapsed 이행 완료
- **Cite**: `.spec/source-viewer.spec.yml:76-84` § SRC-008
- **Fix**: commit `1efbe46` split `PersonDetailList` into `pendingCommitmentsSection` / `completedCommitmentsSection` / `historySection`. Completed section uses `ExpandableSectionHeader` + `rememberSaveable` collapsed-by-default; per-section counted headers via new string resources.

## Scope fences upheld across rounds

Codex correctly classified the following as out of scope (per plan §7 / wave-follow-up / spec-fence):
- Calendar-event direction rendering (db-calendar-status-recurring deferred)
- Avatar / aliases / heatmap
- EnrichmentWorker periodic scheduling
- HTML-only `원문보기` WebView
- Voice / calendar specialized RawEvent branches
- Roborazzi snapshot tests
- Pre-existing wave-3 test drift
- Commitment / calendar tap-through on PersonDetail

No circling — each round produced strictly smaller P1 sets (2 → 1 → 0), no findings were re-raised or regressed.
