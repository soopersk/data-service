CREATE TABLE IF NOT EXISTS calculator_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    period_days INT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    total_runs INT DEFAULT 0,
    successful_runs INT DEFAULT 0,
    failed_runs INT DEFAULT 0,
    avg_duration_ms BIGINT,
    min_duration_ms BIGINT,
    max_duration_ms BIGINT,
    avg_start_hour_cet DECIMAL(4, 2),
    avg_end_hour_cet DECIMAL(4, 2),
    sla_breaches INT DEFAULT 0,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calculator_statistics_latest
    ON calculator_statistics(calculator_id, tenant_id, period_days, computed_at DESC);
