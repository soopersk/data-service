-- Make calculator_sli_daily frequency-aware so DAILY and MONTHLY runs of the same
-- calculator no longer blend on shared (month-end) reporting dates.
--
-- The old 3-column PRIMARY KEY (calculator_id, tenant_id, reporting_date) is exactly
-- what forced a DAILY run and a MONTHLY run to collide on a month-end date. ON CONFLICT
-- upserts also need a UNIQUE arbiter, so `frequency` must be part of the key. This is a
-- derived aggregate table (not the source of truth), so we simply widen its PRIMARY KEY
-- to include `frequency`.
--
-- `frequency NOT NULL DEFAULT 'DAILY'` means a future calculator with no frequency
-- concept simply writes 'DAILY' transparently.

ALTER TABLE calculator_sli_daily
    ADD COLUMN IF NOT EXISTS frequency VARCHAR(10) NOT NULL DEFAULT 'DAILY';

ALTER TABLE calculator_sli_daily
    DROP CONSTRAINT IF EXISTS calculator_sli_daily_pkey;

ALTER TABLE calculator_sli_daily
    ADD CONSTRAINT calculator_sli_daily_pkey
    PRIMARY KEY (calculator_id, tenant_id, frequency, reporting_date);

-- idx_calculator_sli_daily_recent (calculator_id, tenant_id, reporting_date DESC) from V3
-- is retained as-is: it serves the frequency-agnostic collapse reads (findRecentAggregates,
-- findByReportingDates). The new PK index covers the frequency-scoped findAverageDuration.

-- Recompute the aggregate from the source of truth (calculator_runs), grouped by
-- frequency. This un-blends any historical rows that mixed DAILY + MONTHLY on a
-- shared reporting date. Aggregate is derived data and fully reconstructable, so a
-- truncate-and-rebuild is safe. Mirrors RunIngestionService.updateDailyAggregate():
-- only completed runs (end_time IS NOT NULL) are aggregated; start/end minutes are UTC.
TRUNCATE TABLE calculator_sli_daily;

INSERT INTO calculator_sli_daily (
    calculator_id, tenant_id, frequency, reporting_date,
    total_runs, success_runs, sla_breaches,
    sum_duration_ms, sum_start_min_utc, sum_end_min_utc, computed_at
)
SELECT
    calculator_id,
    tenant_id,
    frequency,
    reporting_date,
    COUNT(*),
    COUNT(*) FILTER (WHERE status = 'SUCCESS'),
    COUNT(*) FILTER (WHERE sla_breached),
    COALESCE(SUM(duration_ms), 0),
    COALESCE(SUM(
        EXTRACT(HOUR   FROM start_time AT TIME ZONE 'UTC') * 60 +
        EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC')
    ), 0),
    COALESCE(SUM(
        CASE WHEN end_time IS NOT NULL THEN
            EXTRACT(HOUR   FROM end_time AT TIME ZONE 'UTC') * 60 +
            EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC')
        ELSE 0 END
    ), 0),
    NOW()
FROM calculator_runs
WHERE end_time IS NOT NULL
GROUP BY calculator_id, tenant_id, frequency, reporting_date;
