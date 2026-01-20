-- ================================================================
-- HELPER VIEWS
-- ================================================================

-- View for recent DAILY runs (optimized for 2-3 day lookback)
CREATE OR REPLACE VIEW recent_daily_runs AS
SELECT *
FROM calculator_runs
WHERE frequency = 'DAILY'
AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
AND reporting_date <= CURRENT_DATE;

-- View for recent MONTHLY runs (end of month only)
CREATE OR REPLACE VIEW recent_monthly_runs AS
SELECT *
FROM calculator_runs
WHERE frequency = 'MONTHLY'
AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
AND reporting_date >= CURRENT_DATE - INTERVAL '13 months';

-- View for active/running calculators
CREATE OR REPLACE VIEW active_calculator_runs AS
SELECT *
FROM calculator_runs
WHERE status = 'RUNNING'
AND reporting_date >= CURRENT_DATE - INTERVAL '7 days';
