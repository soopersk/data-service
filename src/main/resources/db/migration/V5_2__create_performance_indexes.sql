-- Flyway: disableTransaction

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_calc_tenant_time
ON calculator_runs(calculator_id, tenant_id, created_at DESC);
