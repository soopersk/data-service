-- flyway:transactional=false

CREATE INDEX IF NOT EXISTS calculator_runs_tenant_calculator_frequency_idx
    ON calculator_runs (tenant_id, calculator_id, frequency, reporting_date DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS sla_breach_events_tenant_calculator_created_idx
    ON sla_breach_events (tenant_id, calculator_id, created_at DESC, breach_id DESC);

CREATE INDEX IF NOT EXISTS sla_breach_events_tenant_calculator_severity_created_idx
    ON sla_breach_events (tenant_id, calculator_id, severity, created_at DESC, breach_id DESC);
