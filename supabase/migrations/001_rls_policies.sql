-- spec: SP-0 — RLS policies for BeCalm Android MVP
-- Architecture: Android sends Supabase JWT → Railway validates + writes to Supabase with service_role key
-- Android NEVER calls Supabase REST directly for data R/W.
-- RLS on all three tables: per-user isolation via auth.uid().
-- Railway uses service_role key which bypasses RLS — that is intentional (Railway = trusted writer).

-- ============================================================
-- raw_ingestion_events RLS
-- ============================================================
ALTER TABLE raw_ingestion_events ENABLE ROW LEVEL SECURITY;

-- Users can only SELECT their own events (Android reads via Railway, but Supabase direct reads are user-scoped)
CREATE POLICY "rls_raw_events_select_own"
    ON raw_ingestion_events
    FOR SELECT
    USING (auth.uid() = user_id);

-- INSERT / UPDATE / DELETE are performed by Railway (service_role) — not by Android directly.
-- Android JWT cannot write to this table.
CREATE POLICY "rls_raw_events_no_direct_insert"
    ON raw_ingestion_events
    FOR INSERT
    WITH CHECK (false); -- Railway uses service_role which bypasses RLS

CREATE POLICY "rls_raw_events_no_direct_update"
    ON raw_ingestion_events
    FOR UPDATE
    USING (false);

CREATE POLICY "rls_raw_events_no_direct_delete"
    ON raw_ingestion_events
    FOR DELETE
    USING (false);

-- ============================================================
-- commitments RLS
-- ============================================================
ALTER TABLE commitments ENABLE ROW LEVEL SECURITY;

CREATE POLICY "rls_commitments_select_own"
    ON commitments
    FOR SELECT
    USING (auth.uid() = user_id);

-- action_state updates come through Railway PATCH endpoint — no direct Android writes
CREATE POLICY "rls_commitments_no_direct_insert"
    ON commitments
    FOR INSERT
    WITH CHECK (false);

CREATE POLICY "rls_commitments_no_direct_update"
    ON commitments
    FOR UPDATE
    USING (false);

CREATE POLICY "rls_commitments_no_direct_delete"
    ON commitments
    FOR DELETE
    USING (false);

-- ============================================================
-- calendar_events RLS
-- ============================================================
ALTER TABLE calendar_events ENABLE ROW LEVEL SECURITY;

CREATE POLICY "rls_calendar_events_select_own"
    ON calendar_events
    FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "rls_calendar_events_no_direct_insert"
    ON calendar_events
    FOR INSERT
    WITH CHECK (false);

CREATE POLICY "rls_calendar_events_no_direct_update"
    ON calendar_events
    FOR UPDATE
    USING (false);

CREATE POLICY "rls_calendar_events_no_direct_delete"
    ON calendar_events
    FOR DELETE
    USING (false);
