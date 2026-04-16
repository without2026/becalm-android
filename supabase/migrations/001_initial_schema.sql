-- spec: SP-0 — BeCalm Android MVP initial schema
-- Supabase ap-northeast-2 (PIPA 한국 개인정보보호법 준수)
-- Tables: raw_ingestion_events, commitments, calendar_events
-- Writer: Railway FastAPI service only (Android never writes Supabase REST directly)
-- auth.users managed by Supabase Auth — BeCalm does NOT DDL it

-- ============================================================
-- raw_ingestion_events
-- spec: data-model — mirrors Room RawIngestionEvent entity
-- sync_status / retry_count / last_attempt_at are Room-side tracking columns, NOT in Supabase
-- ============================================================
CREATE TABLE IF NOT EXISTS raw_ingestion_events (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    -- spec: ING-015 — client-generated UUID v4 idempotency key; Railway uses for upsert dedup
    client_event_id             UUID        NOT NULL,
    -- spec: ING-001..ING-010: voice | gmail | outlook_mail | naver_imap | daum_imap | google_calendar | outlook_calendar
    source_type                 TEXT        NOT NULL,
    source_ref                  TEXT,       -- file URI / email message_id / calendar event id
    -- spec: data-model — person_ref precedence: E.164 phone > lowercase email > normalized display_name
    person_ref                  TEXT,       -- NULL for events with no identifiable counterparty
    event_title                 TEXT,       -- voice: MediaStore TITLE / email: subject / calendar: title
    event_snippet               TEXT,       -- voice: first ~200 chars of transcript (post-STT) / email: first 200 chars
    duration_seconds            INTEGER,    -- voice only; null for non-voice
    location                    TEXT,       -- calendar only; null for non-calendar
    -- spec: VOI-002 — updated after LLM extraction pipeline; default 0
    commitments_extracted_count INTEGER     NOT NULL DEFAULT 0,
    timestamp                   TIMESTAMPTZ NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- spec: ING-015 — (user_id, client_event_id) UNIQUE constraint for Railway idempotent upsert
    CONSTRAINT uq_user_client_event UNIQUE (user_id, client_event_id)
);

-- spec: data-model indexes
CREATE INDEX IF NOT EXISTS idx_raw_events_user_sync_status
    ON raw_ingestion_events (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_raw_events_user_timestamp
    ON raw_ingestion_events (user_id, timestamp DESC);

-- spec: data-model — supports PersonDetailScreen timeline query (SRC-002)
CREATE INDEX IF NOT EXISTS idx_raw_events_user_person_time
    ON raw_ingestion_events (user_id, person_ref, timestamp DESC);

-- ============================================================
-- commitments
-- spec: data-model — mirrors Room Commitment entity
-- ============================================================
CREATE TABLE IF NOT EXISTS commitments (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    -- spec: CMT-001, CMT-002: give = user committed to counterparty; take = counterparty committed to user
    direction                   TEXT        NOT NULL CHECK (direction IN ('give', 'take')),
    counterparty_raw            TEXT,       -- phone# | email | attendee name — raw, uncanonicalized
    -- spec: data-model — canonicalized; same precedence as raw_ingestion_events.person_ref
    person_ref                  TEXT,
    title                       TEXT        NOT NULL,
    description                 TEXT,
    -- spec: data-model — verbatim source text; legally sensitive — never summarized or modified
    quote                       TEXT        NOT NULL,
    source_event_title          TEXT,       -- denormalized event_title for CommitmentCard display
    -- spec: data-model — timestamp of source event (not extraction time)
    source_event_occurred_at    TIMESTAMPTZ NOT NULL,
    due_date                    DATE,
    -- spec: CMT-005..CMT-007: pending | reminded | followed_up | completed
    action_state                TEXT        NOT NULL DEFAULT 'pending'
                                    CHECK (action_state IN ('pending', 'reminded', 'followed_up', 'completed')),
    source_type                 TEXT        NOT NULL,
    source_ref                  TEXT,
    confidence                  FLOAT       NOT NULL DEFAULT 0.0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- spec: data-model indexes
-- spec: CMT-010 — CommitmentManagementScreen queries
CREATE INDEX IF NOT EXISTS idx_commitments_user_action_due
    ON commitments (user_id, action_state, due_date ASC);

CREATE INDEX IF NOT EXISTS idx_commitments_user_created
    ON commitments (user_id, created_at DESC);

-- spec: SRC-002, /v1/persons/{id}/commitments
CREATE INDEX IF NOT EXISTS idx_commitments_user_person_due
    ON commitments (user_id, person_ref, due_date ASC);

-- ============================================================
-- calendar_events
-- spec: data-model — mirrors Room CalendarEvent entity
-- ============================================================
CREATE TABLE IF NOT EXISTS calendar_events (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    -- spec: ING-009, ING-010: google_calendar | outlook_calendar
    source_type TEXT        NOT NULL CHECK (source_type IN ('google_calendar', 'outlook_calendar')),
    source_ref  TEXT,       -- external calendar event ID for upsert deduplication
    title       TEXT        NOT NULL,
    start_at    TIMESTAMPTZ NOT NULL,
    end_at      TIMESTAMPTZ NOT NULL,
    attendees_raw TEXT,     -- raw JSON string of attendees
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- spec: data-model — upsert by (user_id, source_ref) for calendar deduplication
    CONSTRAINT uq_calendar_event UNIQUE (user_id, source_ref)
);

-- spec: data-model — TDY-005 today timeline query
CREATE INDEX IF NOT EXISTS idx_calendar_events_user_start
    ON calendar_events (user_id, start_at ASC);
