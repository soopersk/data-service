-- Flyway: disableTransaction

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_time_desc
ON calculator_runs(created_at DESC);
