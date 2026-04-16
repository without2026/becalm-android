-- spec: SP-0 — Schema summary for DBA review
-- Run this after 001_initial_schema.sql + 001_rls_policies.sql to verify state

SELECT
    table_name,
    column_name,
    data_type,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('raw_ingestion_events', 'commitments', 'calendar_events')
ORDER BY table_name, ordinal_position;

-- Check RLS is enabled
SELECT tablename, rowsecurity
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN ('raw_ingestion_events', 'commitments', 'calendar_events');

-- Check policies
SELECT tablename, policyname, cmd, qual
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename IN ('raw_ingestion_events', 'commitments', 'calendar_events')
ORDER BY tablename, policyname;

-- Check indexes
SELECT indexname, tablename, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('raw_ingestion_events', 'commitments', 'calendar_events')
ORDER BY tablename, indexname;
