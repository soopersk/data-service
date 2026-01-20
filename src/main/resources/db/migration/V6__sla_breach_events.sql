CREATE TABLE IF NOT EXISTS sla_breach_events (
    breach_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(100) NOT NULL UNIQUE,  -- UNIQUE ensures idempotency
    calculator_id VARCHAR(100) NOT NULL,
    calculator_name VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    breach_type VARCHAR(50) NOT NULL,
    expected_value BIGINT,
    actual_value BIGINT,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    alerted BOOLEAN DEFAULT false,
    alerted_at TIMESTAMPTZ,
    alert_status VARCHAR(20) DEFAULT 'PENDING'
        CHECK (alert_status IN ('PENDING', 'SENT', 'FAILED', 'RETRYING')),
    retry_count INT DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_breach_run FOREIGN KEY (run_id)
        REFERENCES calculator_runs(run_id) ON DELETE CASCADE
);

CREATE INDEX idx_sla_breach_events_unalerted ON sla_breach_events(created_at)
    WHERE alerted = false;
CREATE INDEX idx_sla_breach_events_calculator ON sla_breach_events(calculator_id, created_at DESC);
