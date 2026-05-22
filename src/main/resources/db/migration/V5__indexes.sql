-- flyway:transactional=false

-- ══════════════════════════════════════════════════════════════════
-- calculator_runs indexes
-- ══════════════════════════════════════════════════════════════════

-- Primary query workhorse: status queries, analytics, batch lookups (calculator_id is globally unique)
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_calculator_frequency_idx
    ON calculator_runs (calculator_id, frequency, reporting_date DESC, created_at DESC);

-- Running run count (partial index — very small)
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_status_idx
    ON calculator_runs (status, reporting_date DESC)
    WHERE status = 'RUNNING';

-- SLA monitoring job (partial index — very small)
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_sla_idx
    ON calculator_runs (sla_time, status)
    WHERE status = 'RUNNING' AND sla_time IS NOT NULL;

-- Regional batch queries: covers findRegionalBatchRuns + findRegionalBatchHistory (reporting_date-scoped)
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_regional_batch_idx
    ON calculator_runs (reporting_date, run_type, region);

-- Dashboard queries: covers findDashboardCalculatorRuns + findDashboardCalculatorHistory
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_dashboard_idx
    ON calculator_runs (reporting_date, frequency, run_number);

-- ══════════════════════════════════════════════════════════════════
-- sla_breach_events indexes
-- ══════════════════════════════════════════════════════════════════

-- Alert processing (partial index for unprocessed breaches — very small)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sla_breach_events_unalerted
    ON sla_breach_events (created_at)
    WHERE alerted = false;

-- Keyset pagination, counts, breach queries (calculator_id is globally unique)
CREATE INDEX CONCURRENTLY IF NOT EXISTS sla_breach_events_calculator_created_idx
    ON sla_breach_events (calculator_id, created_at DESC, breach_id DESC);

-- Severity-filtered queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS sla_breach_events_calculator_severity_created_idx
    ON sla_breach_events (calculator_id, severity, created_at DESC, breach_id DESC);
