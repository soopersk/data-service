package com.company.observability.repository;

import com.company.observability.domain.CalculatorRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
               run_parameters, created_at, updated_at
        FROM calculator_runs
        """;

    public Optional<CalculatorRun> findById(String runId) {
        String sql = SELECT_BASE + " WHERE run_id = ?";

        List<CalculatorRun> results = jdbcTemplate.query(sql, new CalculatorRunRowMapper(), runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find recent runs directly from calculator_runs table
     * Optimized with partial index on recent data (30 days)
     * Redis caching handles performance - no materialized view needed
     */
    public List<CalculatorRun> findRecentRuns(
            String calculatorId, String tenantId, int limit) {

        String sql = SELECT_BASE + """
        WHERE calculator_id = ? 
        AND tenant_id = ? 
        AND created_at >= NOW() - INTERVAL '30 days'
        ORDER BY created_at DESC
        LIMIT ?
        """;

        return jdbcTemplate.query(sql, new CalculatorRunRowMapper(),
                calculatorId, tenantId, limit);
    }

    /**
     * Batch query for multiple calculators
     * Uses window function to get top N per calculator efficiently
     * No materialized view - direct query with efficient window function
     */
    public List<CalculatorRun> findBatchRecentRuns(
            List<String> calculatorIds, String tenantId, int limit) {

        if (calculatorIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",",
                calculatorIds.stream().map(id -> "?").toList());

        String sql = String.format("""
        SELECT * FROM (
            SELECT run_id, calculator_id, calculator_name, tenant_id, frequency,
                   start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                   status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
                   run_parameters, created_at, updated_at,
                   ROW_NUMBER() OVER (PARTITION BY calculator_id ORDER BY created_at DESC) as rn
            FROM calculator_runs
            WHERE calculator_id IN (%s)
            AND tenant_id = ?
            AND created_at >= NOW() - INTERVAL '30 days'
        ) ranked
        WHERE rn <= ?
        ORDER BY calculator_id, created_at DESC
        """, placeholders);

        Object[] params = new Object[calculatorIds.size() + 2];
        for (int i = 0; i < calculatorIds.size(); i++) {
            params[i] = calculatorIds.get(i);
        }
        params[calculatorIds.size()] = tenantId;
        params[calculatorIds.size() + 1] = limit;

        return jdbcTemplate.query(sql, new CalculatorRunRowMapper(), params);
    }

    public void upsert(CalculatorRun run) {
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(java.time.Instant.now());
        }
        run.setUpdatedAt(java.time.Instant.now());

        String sql = """
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency,
                start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                status, sla_duration_ms, sla_end_hour_cet, sla_breached, sla_breach_reason,
                run_parameters, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (run_id) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                end_hour_cet = EXCLUDED.end_hour_cet,
                status = EXCLUDED.status,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(sql,
                run.getRunId(),
                run.getCalculatorId(),
                run.getCalculatorName(),
                run.getTenantId(),
                run.getFrequency(),
                Timestamp.from(run.getStartTime()),
                run.getEndTime() != null ? Timestamp.from(run.getEndTime()) : null,
                run.getDurationMs(),
                run.getStartHourCet(),
                run.getEndHourCet(),
                run.getStatus(),
                run.getSlaDurationMs(),
                run.getSlaEndHourCet(),
                run.getSlaBreached(),
                run.getSlaBreachReason(),
                run.getRunParameters(),
                Timestamp.from(run.getCreatedAt()),
                Timestamp.from(run.getUpdatedAt())
        );
    }

    /**
     * GT Enhancement: Mark run as SLA breached (called by Redis expiry listener)
     * Only updates runs that are still in RUNNING status
     */
    public void markSlaBreached(String runId) {
        jdbcTemplate.update("""
            UPDATE calculator_runs
            SET sla_breached = true,
                sla_breach_reason = 'Duration timeout detected (Redis timer)',
                updated_at = NOW()
            WHERE run_id = ? AND status = 'RUNNING'
            """, runId);
    }

    /**
     * Calculate average runtime statistics over lookback period
     * Direct aggregation query - no materialized view
     */
    public Map<String, Object> calculateAverageRuntime(
            String calculatorId, String tenantId, int lookbackDays) {

        String sql = """
            SELECT 
                COUNT(*) as total_runs,
                COUNT(*) FILTER (WHERE status = 'SUCCESS') as successful_runs,
                COUNT(*) FILTER (WHERE status IN ('FAILED', 'TIMEOUT')) as failed_runs,
                AVG(duration_ms) as avg_duration_ms,
                MIN(duration_ms) as min_duration_ms,
                MAX(duration_ms) as max_duration_ms,
                AVG(start_hour_cet) as avg_start_hour_cet,
                AVG(end_hour_cet) as avg_end_hour_cet,
                COUNT(*) FILTER (WHERE sla_breached = true) as sla_breaches
            FROM calculator_runs
            WHERE calculator_id = ?
            AND tenant_id = ?
            AND status IN ('SUCCESS', 'FAILED', 'TIMEOUT')
            AND created_at >= NOW() - INTERVAL '? days'
            """;

        return jdbcTemplate.queryForMap(sql, calculatorId, tenantId, lookbackDays);
    }

    private static class CalculatorRunRowMapper implements RowMapper<CalculatorRun> {
        @Override
        public CalculatorRun mapRow(ResultSet rs, int rowNum) throws SQLException {
            return CalculatorRun.builder()
                    .runId(rs.getString("run_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .calculatorName(rs.getString("calculator_name"))
                    .tenantId(rs.getString("tenant_id"))
                    .frequency(rs.getString("frequency"))
                    .startTime(rs.getTimestamp("start_time") != null ?
                            rs.getTimestamp("start_time").toInstant() : null)
                    .endTime(rs.getTimestamp("end_time") != null ?
                            rs.getTimestamp("end_time").toInstant() : null)
                    .durationMs(rs.getObject("duration_ms", Long.class))
                    .startHourCet(rs.getBigDecimal("start_hour_cet"))
                    .endHourCet(rs.getBigDecimal("end_hour_cet"))
                    .status(rs.getString("status"))
                    .slaDurationMs(rs.getObject("sla_duration_ms", Long.class))
                    .slaEndHourCet(rs.getBigDecimal("sla_end_hour_cet"))
                    .slaBreached(rs.getBoolean("sla_breached"))
                    .slaBreachReason(rs.getString("sla_breach_reason"))
                    .runParameters(rs.getString("run_parameters"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}