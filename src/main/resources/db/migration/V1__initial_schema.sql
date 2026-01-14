-- File: src/main/resources/db/migration/V1__initial_schema.sql

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Calculators table
CREATE TABLE calculators (
    calculator_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    frequency VARCHAR(20) NOT NULL DEFAULT 'DAILY',
    sla_target_duration_ms BIGINT NOT NULL,
    sla_target_end_hour_cet DECIMAL(5,2),
    owner_team VARCHAR(100),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT chk_frequency CHECK (frequency IN ('DAILY', 'MONTHLY'))
);

CREATE INDEX idx_calculators_active ON calculators(active) WHERE active = TRUE;

-- Calculator runs table (partitioned by created_at)
CREATE TABLE calculator_runs (
    run_id VARCHAR(100) PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(200) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    frequency VARCHAR(20),
    
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ,
    duration_ms BIGINT,
    
    start_hour_cet DECIMAL(5,2),
    end_hour_cet DECIMAL(5,2),
    
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    
    sla_duration_ms BIGINT,
    sla_end_hour_cet DECIMAL(5,2),
    
    sla_breached BOOLEAN DEFAULT FALSE,
    sla_breach_reason VARCHAR(100),
    
    run_parameters TEXT,
    
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    CONSTRAINT chk_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT'))
);

CREATE INDEX idx_runs_calculator_created ON calculator_runs(calculator_id, created_at DESC);
CREATE INDEX idx_runs_tenant_created ON calculator_runs(tenant_id, created_at DESC);
CREATE INDEX idx_runs_status ON calculator_runs(status);
CREATE INDEX idx_runs_sla_breach ON calculator_runs(calculator_id, sla_breached) WHERE sla_breached = TRUE;

CREATE INDEX idx_runs_daily_recent ON calculator_runs(calculator_id, created_at DESC)
WHERE frequency = 'DAILY';

CREATE INDEX idx_runs_monthly_recent ON calculator_runs(calculator_id, created_at DESC)
WHERE frequency = 'MONTHLY';

-- Calculator statistics table
CREATE TABLE calculator_statistics (
    stat_id BIGSERIAL PRIMARY KEY,
    calculator_id VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(50),
    
    period_days INTEGER NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    
    total_runs INTEGER NOT NULL,
    successful_runs INTEGER NOT NULL,
    failed_runs INTEGER NOT NULL,
    
    avg_duration_ms BIGINT,
    min_duration_ms BIGINT,
    max_duration_ms BIGINT,
    
    avg_start_hour_cet DECIMAL(5,2),
    avg_end_hour_cet DECIMAL(5,2),
    
    sla_breaches INTEGER DEFAULT 0,
    
    computed_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(calculator_id, tenant_id, period_days, period_start)
);

CREATE INDEX idx_stats_calculator_period ON calculator_statistics(calculator_id, period_days);

-- SLA breach events table
CREATE TABLE sla_breach_events (
    breach_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL,
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(200) NOT NULL,
    tenant_id VARCHAR(50),
    
    breach_type VARCHAR(50) NOT NULL,
    
    expected_value BIGINT,
    actual_value BIGINT,
    
    severity VARCHAR(20) DEFAULT 'MEDIUM',
    
    alerted BOOLEAN DEFAULT FALSE,
    alerted_at TIMESTAMPTZ,
    alert_status VARCHAR(20),
    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_breach_calculator ON sla_breach_events(calculator_id, created_at DESC);
CREATE INDEX idx_breach_alerted ON sla_breach_events(alerted) WHERE alerted = FALSE;