# Audio Speaker Confirmation MVP A

## Status

Confirmed scope. MVP A intentionally excludes voice embedding and cross-recording voice identity.

## Problem

Meeting audio produces source-local speaker labels such as `SPEAKER_02`. Those labels are useful evidence, but they are not stable person identity. For BeCalm, the user must be able to confirm which real person a speaker label represents before that speaker is trusted in person memory, interaction timelines, and future work context.

## Product Contract

MVP A keeps the system person-first and local-first:

- Email, phone, contactId, and verified alias remain deterministic identity anchors.
- Audio speaker labels are never treated as canonical people.
- Audio speaker confirmation is a review step: the app recommends candidates, the user confirms.
- Existing people and contacts are first-class choices. Free text remains available for new people.
- Confirmed audio speaker evidence is written into `memory.md` as a local-only voice evidence link.
- Voice chunks are local-only. MVP A records deterministic chunk filenames and local `voice://chunk/...` links in memory. Actual chunk materialization requires segment offsets and stays out of MVP A.

## Flow

```text
meeting audio selected
  -> backend Clova preview
  -> user selects self speaker before extraction
  -> extraction receives speaker context
  -> unresolved non-self speakers appear in 사람 확인
  -> user picks existing person/contact or enters a new person
  -> source_event_participants are resolved
  -> person index rebuilds
  -> ProfileMemoryWorker regenerates memory.md
  -> memory.md includes Local Voice Evidence links for confirmed speaker rows
```

## UI Contract

In `사람 확인`, audio speaker rows use speaker confirmation language:

- The card is framed as "this speaker needs confirmation", not as a raw person row.
- `다른 사람` opens a contact-style chooser.
- The chooser includes recommendation rows, existing people, and searchable contacts.
- Candidate labels distinguish `추천 후보`, `등록된 사람`, `연락처`, and `선택됨`.
- Internal ids such as UUIDs and `qa-person-*` must not be shown as contact metadata.

## Memory Contract

`memory.md` gains a local-only voice evidence section when confirmed speaker participants exist.

Example:

```md
## Local Voice Evidence

- 2026-05-12 meeting speaker `SPEAKER_02`: [voice chunk](voice://chunk/voice_chunk_abcd1234.m4a). Evidence: 금요일까지 자료를 공유하겠습니다.
```

Rules:

- The section is local-only and should not be interpreted as cloud audio storage.
- Links use `voice://chunk/{filename}` rather than raw filesystem paths.
- Filenames must not include person names, emails, or phone numbers.
- The filename is deterministic from user id, person id, source event id, and speaker label.
- If the user deletes local raw/source evidence for a date range, voice chunks and manifests must follow the same deletion policy in a later storage task.

## Non-goals

- No voice embedding.
- No automatic cross-recording voice match.
- No backend storage of voice chunks.
- No full audio chunk extraction until segment offsets are persisted.
- No automatic matching based only on agenda/context.

## Tests

- ViewModel exposes existing people and contact-only rows as manual match choices.
- Manual match UI renders existing people/contacts and routes selected choice through the existing `onManualMatch` path.
- Internal anchors are hidden from manual match contact metadata.
- `memory.md` renders local voice evidence links for confirmed speaker participants.
- `memory.md` still validates with the existing markdown validator.
