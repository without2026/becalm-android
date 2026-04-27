# BeCalm Android MVP Boundary

**Status**: Current boundary and global principles  
**Date**: 2026-04-22  
**Role**: This document defines MVP scope, exclusions, and cross-cutting product rules. Detailed behavioral and data contracts live under [`becalm-android/.spec/`](../.spec/).

## 1. Purpose

BeCalm Android MVP is a Korean-only, Samsung Android-first app that captures business commitments from a single user's daily interactions and preserves them with local-first custody.

This document is the boundary reference for:
- what the MVP includes
- what the MVP explicitly excludes
- what data may be stored
- what success means
- what every domain spec must preserve

It is not the source of truth for endpoint fields, schema columns, or per-screen UI contracts. Those belong in `.spec/`.

## 2. Scope

### Included
- Samsung Android primary phone only
- Korean language only
- Single-user commitment tracking
- 4 source types only:
  - phone call recordings
  - offline meeting recordings
  - email
  - calendar
- Android onboarding, source connection, dashboard, source viewer, settings
- Local-first capture into Room, followed by server mirror

### Explicitly excluded from MVP
- messenger ingestion
- desktop sync
- multi-device sync
- notification / alarm system
- write-back to email or calendar
- person matching and person timeline productization
- video meeting content ingestion
  - Zoom / Teams / Meet audio, transcript, chat, recording download
- non-Samsung OEM support
- multilingual UX

## 3. Source Of Truth

- `Room = source-of-truth`
- `Supabase = Room mirror`
- Android does not use Supabase REST directly for app data writes
- Railway is the server write surface for mirrored data
- There is no server-to-Room reverse sync in MVP

This means:
- the app must be correct even if the mirror is temporarily behind
- local user-visible state is not blocked on server read-back
- server durability matters for mirror health, not for immediate UI authorship

## 4. Success Criteria

The MVP success metric is not extraction quality.

The MVP success metric is:
- `capture custody integrity`
- lossless arrival of raw ingestion events into durable user-scoped server storage

Operationally:
- insert success means `durable commit`, not `accepted for later queueing`
- count-only acknowledgements are insufficient for Android sync state transitions
- batch write responses must identify per-row outcomes by client-generated id

## 5. Data Minimization And Recall

BeCalm does not aim to archive everything. It stores the minimum evidence needed to help the user recall commitments.

### Voice
- full transcript is never stored on Android, Railway, or Supabase
- only `commitment.quote` may be retained for recall
- `quote` for extracted commitments must be:
  - verbatim
  - at most 100 characters
- voice detail is a commitment evidence view, not a transcript viewer

### Email
- email body may be stored locally in Room
- email body is not mirrored to Supabase

### Audio files
- original Samsung recording files are not owned by BeCalm
- BeCalm must not copy, move, or delete them as part of normal MVP behavior

## 6. Consent Boundary

Voice data may leave the device only after the user has granted the required PIPA consent for third-party provision and overseas transfer.

This applies to:
- audio upload for extraction
- extracted verbatim quotes mirrored as commitment evidence

If consent is not granted:
- voice recording detection may still occur locally
- server-side extraction and mirror for voice remain blocked
- the onboarding and source flows must reflect that conditional path

## 7. Retention

Global retention rules for MVP:

- `raw_ingestion_events`: 30 days after sync
- `EmailBody`: 30 days after sync
- `Commitment`: retained until user lifecycle action removes it
- `CalendarEvent`: cancellation is preserved as state, not hard-deleted
- Samsung original audio files: retention/deletion is controlled by the user or Samsung app, not BeCalm

If a domain spec needs a narrower rule, it must not violate these global constraints.

## 8. Account And Session Model

- cloud account is required
- same device multi-account is allowed
- therefore local persistence must be user-scoped
- logout or source disconnect does not erase prior local data by default
- prior local data remains readable, but collection and sync actions may become unavailable until reconnect or re-login

Implications:
- Room and DataStore keys must be isolated by user scope
- account switch must not leak local rows across users
- disconnected sources should render as read-only where applicable
- before a cloud account session exists, no background worker, DAO, or source sync path may touch the user-scoped Room database
- this also applies to stale persisted WorkManager rows restored after process death; worker construction itself must remain Room-safe until authenticated state is confirmed
- foreground catch-up, periodic redundancy, and source-specific manual sync are all gated behind authenticated session state

## 9. Voice Pipeline Authority

For voice ingestion:

1. Android creates the local raw event first.
2. Android mirrors raw metadata to the server.
3. Android calls `voice/transcribe_extract` for draft extraction.
4. Android writes local commitments and quotes into Room first.
5. Android mirrors commitments afterward.

Therefore:
- `voice/transcribe_extract` is draft-only
- Railway is an extraction engine, not the source of truth for commitments
- Supabase mirrors Android-authored structured state

## 10. Spec Layering

This document is the global boundary layer.

The `.spec/` files are the executable contract layer:
- `contracts/data-model.yml` defines schema and persistence rules
- `contracts/api-contract.yml` defines network semantics
- `voice-pipeline.spec.yml` defines extraction behavior
- `auth.spec.yml` and `onboarding.spec.yml` define consent and session flow
- viewer, sync, and source specs define domain-specific UI and worker behavior

When there is tension between an old design note and a current `.spec` contract, the current `.spec` contract wins unless this boundary document is intentionally updated first.

## 11. Related Documents

- Detailed contracts: [`../.spec/`](../.spec/)
- Integration wiring for test planning: [integration-wiring.md](./integration-wiring.md)
- Repo overview: [../README.md](../README.md)
