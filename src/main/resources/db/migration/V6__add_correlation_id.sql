-- Adds correlation_id to calculator_runs to support grouping split runs into one logical run.
-- Airflow sets the same correlationId on every physical split that belongs to one logical run.
-- NULL means the run is standalone and needs no grouping.
-- Immutable after first INSERT (omitted from ON CONFLICT DO UPDATE in repository).

ALTER TABLE calculator_runs ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);

-- Partial index: only indexes rows where correlation_id is set, keeping the index small.
-- Leading column (calculator_id, globally unique) matches the primary query pattern.
CREATE INDEX IF NOT EXISTS calculator_runs_correlation_idx
    ON calculator_runs (calculator_id, correlation_id, reporting_date)
    WHERE correlation_id IS NOT NULL;
