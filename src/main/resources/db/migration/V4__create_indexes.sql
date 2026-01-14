-- Flyway: disableTransaction

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_frequency_created
ON calculator_runs(frequency, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_tenant_calc_created
ON calculator_runs(tenant_id, calculator_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_breach_severity_created
ON sla_breach_events(severity, created_at DESC);
