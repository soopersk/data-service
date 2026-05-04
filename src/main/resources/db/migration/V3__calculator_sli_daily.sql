-- Daily aggregate table using SUM-based storage (atomically safe under concurrent upserts).
-- Averages are computed at read time via DailyAggregate helper methods.
CREATE TABLE IF NOT EXISTS calculator_sli_daily (
    calculator_id      VARCHAR(100)   NOT NULL,
    tenant_id          VARCHAR(50)    NOT NULL,
    reporting_date     DATE           NOT NULL,
    total_runs         INT            DEFAULT 0,
    success_runs       INT            DEFAULT 0,
    sla_breaches       INT            DEFAULT 0,
    sum_duration_ms    BIGINT         DEFAULT 0,
    sum_start_min_utc  BIGINT         DEFAULT 0,
    sum_end_min_utc    BIGINT         DEFAULT 0,
    computed_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    PRIMARY KEY (calculator_id, tenant_id, reporting_date)
);

CREATE INDEX IF NOT EXISTS idx_calculator_sli_daily_recent
    ON calculator_sli_daily (calculator_id, tenant_id, reporting_date DESC);
