-- Throwaway data generator for the continue-watching EXPLAIN ANALYZE gate (ROADMAP phase 4).
-- NOT a migration — fake data must never enter flyway_schema_history. Run manually:
--   docker exec -i playhead-postgres psql -U playhead -d playhead < load/seed_playback_state.sql
--
-- 200,000 rows: 10,000 profiles x 20 titles each, updated_at spread across the last 30 days so
-- ORDER BY updated_at DESC has real work to do.

INSERT INTO playback_state
    (profile_id, title_id, position_seconds, duration_seconds, sequence, updated_at)
SELECT
    'p_' || (g / 20),
    't_' || (g % 20),
    (random() * 7000)::int,
    8100,
    g,
    now() - (random() * interval '30 days')
FROM generate_series(0, 199999) AS g
ON CONFLICT (profile_id, title_id) DO NOTHING;

ANALYZE playback_state;
