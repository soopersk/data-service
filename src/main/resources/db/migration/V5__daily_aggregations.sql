
CREATE TABLE IF NOT EXISTS calculator_sli_daily (
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    day_cet DATE NOT NULL,
    total_runs INT DEFAULT 0,
    success_runs INT DEFAULT 0,
    sla_breaches INT DEFAULT 0,
    avg_duration_ms BIGINT DEFAULT 0,
    avg_start_min_cet INT DEFAULT 0,  -- Minutes since midnight CET (0-1439)
    avg_end_min_cet INT DEFAULT 0,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (calculator_id, tenant_id, day_cet),
    CONSTRAINT fk_daily_calculator FOREIGN KEY (calculator_id)
        REFERENCES calculators(calculator_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_calculator_sli_daily_recent
ON calculator_sli_daily(calculator_id, tenant_id, day_cet DESC);
