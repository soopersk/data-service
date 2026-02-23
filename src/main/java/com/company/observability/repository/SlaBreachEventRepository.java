package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SlaBreachEventRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * FIXED: Idempotent save - throws exception if duplicate
     * Caller must handle DuplicateKeyException
     */
    public SlaBreachEvent save(SlaBreachEvent breach) throws DuplicateKeyException {
        if (breach.getCreatedAt() == null) {
            breach.setCreatedAt(Instant.now());
        }

        String sql = """
            INSERT INTO sla_breach_events (
                run_id, calculator_id, calculator_name, tenant_id,
                breach_type, expected_value, actual_value, severity,
                alerted, alerted_at, alert_status, retry_count, last_error, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, breach.getRunId());
            ps.setString(2, breach.getCalculatorId());
            ps.setString(3, breach.getCalculatorName());
            ps.setString(4, breach.getTenantId());
            ps.setString(5, breach.getBreachType());
            ps.setObject(6, breach.getExpectedValue());
            ps.setObject(7, breach.getActualValue());
            ps.setString(8, breach.getSeverity());
            ps.setBoolean(9, breach.getAlerted());
            ps.setTimestamp(10, breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null);
            ps.setString(11, breach.getAlertStatus());
            ps.setInt(12, breach.getRetryCount() != null ? breach.getRetryCount() : 0);
            ps.setString(13, breach.getLastError());
            ps.setTimestamp(14, Timestamp.from(breach.getCreatedAt()));
            return ps;
        }, keyHolder);

        breach.setBreachId(keyHolder.getKey().longValue());
        return breach;
    }

    /**
     * Find unalerted breaches for batch processing
     */
    public List<SlaBreachEvent> findUnalertedBreaches(int limit) {
        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, retry_count, last_error, created_at
            FROM sla_breach_events
            WHERE alerted = false
            AND alert_status IN ('PENDING', 'FAILED')
            ORDER BY created_at ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(), limit);
    }

    /**
     * Update breach status after alert attempt
     */
    public void update(SlaBreachEvent breach) {
        String sql = """
            UPDATE sla_breach_events
            SET alerted = ?,
                alerted_at = ?,
                alert_status = ?,
                retry_count = ?,
                last_error = ?
            WHERE breach_id = ?
            """;

        jdbcTemplate.update(sql,
                breach.getAlerted(),
                breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null,
                breach.getAlertStatus(),
                breach.getRetryCount(),
                breach.getLastError(),
                breach.getBreachId()
        );
    }

    /**
     * Find breaches for a calculator within a time period
     */
    public List<SlaBreachEvent> findByCalculatorIdAndPeriod(
            String calculatorId, String tenantId, int days) {
        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, retry_count, last_error, created_at
            FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            ORDER BY created_at DESC
            """;

        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                calculatorId, tenantId, days);
    }

    /**
     * Aggregated breach counts by severity for analytics summary/trends.
     */
    public Map<String, Integer> countBySeverity(
            String calculatorId, String tenantId, int days) {
        String sql = """
            SELECT severity, COUNT(*) AS cnt
            FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            GROUP BY severity
            """;

        Map<String, Integer> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(
                    rs.getString("severity"),
                    ((Number) rs.getObject("cnt")).intValue()
            );
        }, calculatorId, tenantId, days);
        return result;
    }

    /**
     * Aggregated breach counts by breach_type for analytics summary.
     */
    public Map<String, Integer> countByType(
            String calculatorId, String tenantId, int days) {
        String sql = """
            SELECT COALESCE(breach_type, 'UNKNOWN') AS breach_type, COUNT(*) AS cnt
            FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            GROUP BY COALESCE(breach_type, 'UNKNOWN')
            """;

        Map<String, Integer> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(
                    rs.getString("breach_type"),
                    ((Number) rs.getObject("cnt")).intValue()
            );
        }, calculatorId, tenantId, days);
        return result;
    }

    /**
     * Worst breach severity per CET day (CRITICAL > HIGH > MEDIUM > LOW).
     */
    public Map<LocalDate, String> findWorstSeverityByDay(
            String calculatorId, String tenantId, int days) {
        String sql = """
            SELECT (created_at AT TIME ZONE 'CET')::DATE AS day_cet,
                   MAX(
                       CASE severity
                           WHEN 'CRITICAL' THEN 4
                           WHEN 'HIGH' THEN 3
                           WHEN 'MEDIUM' THEN 2
                           WHEN 'LOW' THEN 1
                           ELSE 0
                       END
                   ) AS worst_rank
            FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            GROUP BY (created_at AT TIME ZONE 'CET')::DATE
            """;

        Map<LocalDate, String> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            LocalDate day = rs.getObject("day_cet", LocalDate.class);
            int rank = ((Number) rs.getObject("worst_rank")).intValue();
            result.put(day, severityFromRank(rank));
        }, calculatorId, tenantId, days);
        return result;
    }

    /**
     * Find breaches with pagination and optional severity filter
     */
    public List<SlaBreachEvent> findByCalculatorIdPaginated(
            String calculatorId, String tenantId, int days,
            String severity, int offset, int limit) {

        if (severity != null && !severity.isBlank()) {
            String sql = """
                SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                       breach_type, expected_value, actual_value, severity,
                       alerted, alerted_at, alert_status, retry_count, last_error, created_at
                FROM sla_breach_events
                WHERE calculator_id = ? AND tenant_id = ?
                AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
                AND severity = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """;
            return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                    calculatorId, tenantId, days, severity, limit, offset);
        }

        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, retry_count, last_error, created_at
            FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;
        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                calculatorId, tenantId, days, limit, offset);
    }

    /**
     * Keyset pagination for SLA breach history (stable on created_at, breach_id).
     */
    public List<SlaBreachEvent> findByCalculatorIdKeyset(
            String calculatorId, String tenantId, int days, String severity,
            Instant cursorCreatedAt, Long cursorBreachId, int limit) {

        boolean hasCursor = cursorCreatedAt != null && cursorBreachId != null;
        boolean hasSeverity = severity != null && !severity.isBlank();

        if (hasSeverity && hasCursor) {
            String sql = """
                SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                       breach_type, expected_value, actual_value, severity,
                       alerted, alerted_at, alert_status, retry_count, last_error, created_at
                FROM sla_breach_events
                WHERE calculator_id = ? AND tenant_id = ?
                AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
                AND severity = ?
                AND (created_at, breach_id) < (?, ?)
                ORDER BY created_at DESC, breach_id DESC
                LIMIT ?
                """;
            return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                    calculatorId, tenantId, days, severity,
                    Timestamp.from(cursorCreatedAt), cursorBreachId, limit);
        }

        if (hasSeverity) {
            String sql = """
                SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                       breach_type, expected_value, actual_value, severity,
                       alerted, alerted_at, alert_status, retry_count, last_error, created_at
                FROM sla_breach_events
                WHERE calculator_id = ? AND tenant_id = ?
                AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
                AND severity = ?
                ORDER BY created_at DESC, breach_id DESC
                LIMIT ?
                """;
            return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                    calculatorId, tenantId, days, severity, limit);
        }

        if (hasCursor) {
            String sql = """
                SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                       breach_type, expected_value, actual_value, severity,
                       alerted, alerted_at, alert_status, retry_count, last_error, created_at
                FROM sla_breach_events
                WHERE calculator_id = ? AND tenant_id = ?
                AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
                AND (created_at, breach_id) < (?, ?)
                ORDER BY created_at DESC, breach_id DESC
                LIMIT ?
                """;
            return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                    calculatorId, tenantId, days, Timestamp.from(cursorCreatedAt), cursorBreachId, limit);
        }

        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, retry_count, last_error, created_at
            FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            ORDER BY created_at DESC, breach_id DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(),
                calculatorId, tenantId, days, limit);
    }

    /**
     * Count breaches with optional severity filter
     */
    public long countByCalculatorIdAndPeriod(
            String calculatorId, String tenantId, int days, String severity) {

        if (severity != null && !severity.isBlank()) {
            String sql = """
                SELECT COUNT(*) FROM sla_breach_events
                WHERE calculator_id = ? AND tenant_id = ?
                AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
                AND severity = ?
                """;
            Long count = jdbcTemplate.queryForObject(sql, Long.class,
                    calculatorId, tenantId, days, severity);
            return count != null ? count : 0;
        }

        String sql = """
            SELECT COUNT(*) FROM sla_breach_events
            WHERE calculator_id = ? AND tenant_id = ?
            AND created_at >= NOW() - CAST(? AS INTEGER) * INTERVAL '1 day'
            """;
        Long count = jdbcTemplate.queryForObject(sql, Long.class,
                calculatorId, tenantId, days);
        return count != null ? count : 0;
    }

    private static String severityFromRank(int rank) {
        return switch (rank) {
            case 4 -> "CRITICAL";
            case 3 -> "HIGH";
            case 2 -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static class SlaBreachEventRowMapper implements RowMapper<SlaBreachEvent> {
        @Override
        public SlaBreachEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return SlaBreachEvent.builder()
                    .breachId(rs.getLong("breach_id"))
                    .runId(rs.getString("run_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .calculatorName(rs.getString("calculator_name"))
                    .tenantId(rs.getString("tenant_id"))
                    .breachType(rs.getString("breach_type"))
                    .expectedValue(rs.getObject("expected_value", Long.class))
                    .actualValue(rs.getObject("actual_value", Long.class))
                    .severity(rs.getString("severity"))
                    .alerted(rs.getBoolean("alerted"))
                    .alertedAt(rs.getTimestamp("alerted_at") != null ?
                            rs.getTimestamp("alerted_at").toInstant() : null)
                    .alertStatus(rs.getString("alert_status"))
                    .retryCount(rs.getInt("retry_count"))
                    .lastError(rs.getString("last_error"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .build();
        }
    }
}
