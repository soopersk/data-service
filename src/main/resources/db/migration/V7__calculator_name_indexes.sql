-- flyway:transactional=false

-- Indexes supporting the name-keyed query paths introduced by
-- GET /api/v1/calculators/batch/runs and GET /api/v1/analytics/calculators/{calculatorName}/executions.
-- Both endpoints filter by calculator_name (readable, globally unique) rather than the
-- upstream UUID calculator_id, so the existing calculator_runs_calculator_frequency_idx does
-- not apply.

-- Batch query: WHERE reporting_date = :d AND frequency = :f AND calculator_name IN (...) [AND run_number = :rn]
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_name_date_idx
    ON calculator_runs (calculator_name, reporting_date DESC, frequency, run_number);

-- Executions query: WHERE calculator_name = :n AND frequency = :f AND reporting_date >= ... [AND run_number = :rn]
-- The batch index above covers it too, but a narrower composite is friendlier to the planner for the single-name path.
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_name_frequency_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, created_at DESC);
