CREATE TABLE calculator_sli_daily (
    calculator_id VARCHAR(100),
    tenant_id VARCHAR(50),
    day_cet DATE,

    total_runs INT DEFAULT 0,
    success_runs INT DEFAULT 0,
    sla_breaches INT DEFAULT 0,

    avg_duration_ms BIGINT,
    avg_start_min_cet INT,  -- Minutes since midnight CET (0-1439)
    avg_end_min_cet INT,    -- Minutes since midnight CET (0-1439)

    computed_at TIMESTAMPTZ DEFAULT NOW(),

    PRIMARY KEY (calculator_id, tenant_id, day_cet)
);

-- Index for recent aggregates queries
CREATE INDEX idx_sli_daily_recent
ON calculator_sli_daily(calculator_id, tenant_id, day_cet DESC);

-- Index for date range queries
CREATE INDEX idx_sli_daily_date_range
ON calculator_sli_daily(day_cet DESC);