-- Flyway: disableTransaction

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_frequency_time
ON calculator_runs(frequency, created_at DESC);