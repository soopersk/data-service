-- flyway:transactional=false

CREATE INDEX IF NOT EXISTS calculator_runs_lookup_idx
    ON calculator_runs (calculator_id, tenant_id, reporting_date DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS calculator_runs_tenant_idx
    ON calculator_runs (tenant_id, reporting_date DESC);

CREATE INDEX IF NOT EXISTS calculator_runs_status_idx
    ON calculator_runs (status, reporting_date DESC)
    WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS calculator_runs_sla_idx
    ON calculator_runs (sla_time, status)
    WHERE status = 'RUNNING' AND sla_time IS NOT NULL;

CREATE INDEX IF NOT EXISTS calculator_runs_frequency_idx
    ON calculator_runs (frequency, reporting_date DESC);