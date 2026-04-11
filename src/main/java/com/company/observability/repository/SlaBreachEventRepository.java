package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.*;
import static com.company.observability.util.TimeUtils.fromTimestamp;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SlaBreachEventRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private static final String SELECT_COLUMNS = """
            breach_id, run_id, calculator_id, calculator_name, tenant_id,
            breach_type, expected_value, actual_value, severity,
            alerted, alerted_at, alert_status, retry_count, last_error, created_at""";

    private static final String SELECT_FROM = "SELECT " + SELECT_COLUMNS + "\nFROM sla_breach_events\n";

    private static final String BASE_WHERE = """
            WHERE calculator_id = :calculatorId AND tenant_id = :tenantId
            AND created_at >= NOW() - CAST(:days AS INTEGER) * INTERVAL '1 day'""";

    private static final String ORDER_DESC = "\nORDER BY created_at DESC, breach_id DESC";

    private static final SlaBreachEventRowMapper ROW_MAPPER = new SlaBreachEventRowMapper();

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
            ) VALUES (
                :runId, :calculatorId, :calculatorName, :tenantId,
                :breachType, :expectedValue, :actualValue, :severity,
                :alerted, :alertedAt, :alertStatus, :retryCount, :lastError, :createdAt
            )
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", breach.getRunId())
                .addValue("calculatorId", breach.getCalculatorId())
                .addValue("calculatorName", breach.getCalculatorName())
                .addValue("tenantId", breach.getTenantId())
                .addValue("breachType", breach.getBreachType().name())
                .addValue("expectedValue", breach.getExpectedValue())
                .addValue("actualValue", breach.getActualValue())
                .addValue("severity", breach.getSeverity().name())
                .addValue("alerted", breach.getAlerted())
                .addValue("alertedAt", breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null)
                .addValue("alertStatus", breach.getAlertStatus().name())
                .addValue("retryCount", breach.getRetryCount() != null ? breach.getRetryCount() : 0)
                .addValue("lastError", breach.getLastError())
                .addValue("createdAt", Timestamp.from(breach.getCreatedAt()));

        KeyHolder keyHolder = new GeneratedKeyHolder();

        Timer.Sample sample = Timer.start(meterRegistry);
        jdbcTemplate.update(sql, params, keyHolder);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "save_breach").register(meterRegistry));

        breach.setBreachId(keyHolder.getKey().longValue());
        return breach;
    }

    /**
     * Find unalerted breaches for batch processing
     */
    public List<SlaBreachEvent> findUnalertedBreaches(int limit) {
        String sql = SELECT_FROM + """
            WHERE alerted = false
            AND alert_status IN ('PENDING', 'FAILED')
            ORDER BY created_at ASC
            LIMIT :limit""";

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        return jdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    /**
     * Update breach status after alert attempt
     */
    public void update(SlaBreachEvent breach) {
        String sql = """
            UPDATE sla_breach_events
            SET alerted = :alerted,
                alerted_at = :alertedAt,
                alert_status = :alertStatus,
                retry_count = :retryCount,
                last_error = :lastError
            WHERE breach_id = :breachId
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("alerted", breach.getAlerted())
                .addValue("alertedAt", breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null)
                .addValue("alertStatus", breach.getAlertStatus().name())
                .addValue("retryCount", breach.getRetryCount())
                .addValue("lastError", breach.getLastError())
                .addValue("breachId", breach.getBreachId());

        jdbcTemplate.update(sql, params);
    }

    /**
     * Aggregated breach counts by severity for analytics summary/trends.
     */
    public Map<String, Integer> countBySeverity(
            String calculatorId, String tenantId, int days) {
        String sql = """
            SELECT severity, COUNT(*) AS cnt
            FROM sla_breach_events
            """ + BASE_WHERE + """

            GROUP BY severity""";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        Map<String, Integer> result = new HashMap<>();
        Timer.Sample sample = Timer.start(meterRegistry);
        jdbcTemplate.query(sql, params, rs -> {
            while (rs.next()) {
                result.put(
                        rs.getString("severity"),
                        ((Number) rs.getObject("cnt")).intValue()
                );
            }
        });
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "count_by_severity").register(meterRegistry));
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
            """ + BASE_WHERE + """

            GROUP BY COALESCE(breach_type, 'UNKNOWN')""";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        Map<String, Integer> result = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            while (rs.next()) {
                result.put(
                        rs.getString("breach_type"),
                        ((Number) rs.getObject("cnt")).intValue()
                );
            }
        });
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
            """ + BASE_WHERE + """

            GROUP BY (created_at AT TIME ZONE 'CET')::DATE""";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        Map<LocalDate, String> result = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            while (rs.next()) {
                LocalDate day = rs.getObject("day_cet", LocalDate.class);
                int rank = ((Number) rs.getObject("worst_rank")).intValue();
                result.put(day, severityFromRank(rank));
            }
        });
        return result;
    }

    /**
     * Find breaches with offset pagination and optional severity filter.
     */
    public List<SlaBreachEvent> findByCalculatorIdPaginated(
            String calculatorId, String tenantId, int days,
            String severity, int offset, int limit) {

        boolean hasSeverity = severity != null && !severity.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        StringBuilder sql = new StringBuilder(SELECT_FROM).append(BASE_WHERE);

        if (hasSeverity) {
            sql.append("\nAND severity = :severity");
            params.addValue("severity", severity);
        }

        sql.append("\nORDER BY created_at DESC");
        sql.append("\nLIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<SlaBreachEvent> results = jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_breaches").register(meterRegistry));
        return results;
    }

    /**
     * Keyset pagination for SLA breach history (stable on created_at, breach_id).
     */
    public List<SlaBreachEvent> findByCalculatorIdKeyset(
            String calculatorId, String tenantId, int days, String severity,
            Instant cursorCreatedAt, Long cursorBreachId, int limit) {

        boolean hasCursor = cursorCreatedAt != null && cursorBreachId != null;
        boolean hasSeverity = severity != null && !severity.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        StringBuilder sql = new StringBuilder(SELECT_FROM).append(BASE_WHERE);

        if (hasSeverity) {
            sql.append("\nAND severity = :severity");
            params.addValue("severity", severity);
        }

        if (hasCursor) {
            sql.append("\nAND (created_at, breach_id) < (:cursorCreatedAt, :cursorBreachId)");
            params.addValue("cursorCreatedAt", Timestamp.from(cursorCreatedAt));
            params.addValue("cursorBreachId", cursorBreachId);
        }

        sql.append(ORDER_DESC);
        sql.append("\nLIMIT :limit");
        params.addValue("limit", limit);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<SlaBreachEvent> results = jdbcTemplate.query(sql.toString(), params, ROW_MAPPER);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_breaches").register(meterRegistry));
        return results;
    }

    /**
     * Count breaches with optional severity filter.
     */
    public long countByCalculatorIdAndPeriod(
            String calculatorId, String tenantId, int days, String severity) {

        boolean hasSeverity = severity != null && !severity.isBlank();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sla_breach_events\n")
                .append(BASE_WHERE);

        if (hasSeverity) {
            sql.append("\nAND severity = :severity");
            params.addValue("severity", severity);
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), params, Long.class);
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
                    .breachType(BreachType.fromString(rs.getString("breach_type")))
                    .expectedValue(rs.getObject("expected_value", Long.class))
                    .actualValue(rs.getObject("actual_value", Long.class))
                    .severity(Severity.fromString(rs.getString("severity")))
                    .alerted(rs.getBoolean("alerted"))
                    .alertedAt(fromTimestamp(rs.getTimestamp("alerted_at")))
                    .alertStatus(AlertStatus.fromString(rs.getString("alert_status")))
                    .retryCount(rs.getInt("retry_count"))
                    .lastError(rs.getString("last_error"))
                    .createdAt(fromTimestamp(rs.getTimestamp("created_at")))
                    .build();
        }
    }
}
