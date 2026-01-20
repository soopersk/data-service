CREATE TABLE IF NOT EXISTS calculator_runs (
    run_id VARCHAR(100) NOT NULL,
    -- Key attribute for partitioning
    reporting_date DATE NOT NULL,

    -- Calculator metadata
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    frequency VARCHAR(20) NOT NULL CHECK (frequency IN ('DAILY', 'MONTHLY')),

    -- Timing information
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,

    -- CET time conversions for display
    start_hour_cet DECIMAL(4, 2),
    end_hour_cet DECIMAL(4, 2),

    -- Status
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CANCELLED')),

    -- SLA tracking (absolute time-based)
    sla_time TIMESTAMPTZ,
    expected_duration_ms BIGINT,
    estimated_start_time TIMESTAMPTZ,
    estimated_end_time TIMESTAMPTZ,

    -- SLA breach tracking
    sla_breached BOOLEAN DEFAULT false,
    sla_breach_reason TEXT,

    -- Metadata
    run_parameters TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Composite primary key including partition key
    PRIMARY KEY (run_id, reporting_date)
) PARTITION BY RANGE (reporting_date);