-- File: src/main/resources/db/migration/V2__create_materialized_view.sql

CREATE MATERIALIZED VIEW recent_runs_optimized AS
SELECT 
    r.run_id,
    r.calculator_id,
    r.calculator_name,
    r.tenant_id,
    r.start_time,
    r.end_time,
    r.duration_ms,
    r.start_hour_cet,
    r.end_hour_cet,
    r.status,
    r.sla_breached,
    r.sla_breach_reason,
    r.created_at,
    r.frequency,
    ROW_NUMBER() OVER (
        PARTITION BY r.calculator_id, r.tenant_id 
        ORDER BY r.created_at DESC
    ) as row_num
FROM calculator_runs r
WHERE 
    (r.frequency = 'DAILY' AND r.created_at >= NOW() - INTERVAL '2 days')
    OR 
    (r.frequency = 'MONTHLY' AND r.created_at >= NOW() - INTERVAL '10 days')
ORDER BY r.calculator_id, r.tenant_id, r.created_at DESC;

CREATE UNIQUE INDEX idx_recent_runs_opt_pk ON recent_runs_optimized(calculator_id, tenant_id, row_num);
CREATE INDEX idx_recent_runs_opt_created ON recent_runs_optimized(created_at DESC);