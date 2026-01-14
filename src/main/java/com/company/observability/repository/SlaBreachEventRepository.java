package com.company.observability.repository;

import com.company.observability.domain.SlaBreachEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SlaBreachEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<SlaBreachEvent> findUnalertedBreaches(int limit) {
        String sql = """
            SELECT breach_id, run_id, calculator_id, calculator_name, tenant_id,
                   breach_type, expected_value, actual_value, severity,
                   alerted, alerted_at, alert_status, created_at
            FROM sla_breach_events
            WHERE alerted = false
            ORDER BY created_at ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(sql, new SlaBreachEventRowMapper(), limit);
    }

    public SlaBreachEvent save(SlaBreachEvent breach) {
        if (breach.getCreatedAt() == null) {
            breach.setCreatedAt(Instant.now());
        }

        String sql = """
            INSERT INTO sla_breach_events (
                run_id, calculator_id, calculator_name, tenant_id,
                breach_type, expected_value, actual_value, severity,
                alerted, alerted_at, alert_status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            ps.setTimestamp(12, Timestamp.from(breach.getCreatedAt()));
            return ps;
        }, keyHolder);

        breach.setBreachId(keyHolder.getKey().longValue());
        return breach;
    }

    public void update(SlaBreachEvent breach) {
        String sql = """
            UPDATE sla_breach_events
            SET alerted = ?,
                alerted_at = ?,
                alert_status = ?
            WHERE breach_id = ?
            """;

        jdbcTemplate.update(sql,
                breach.getAlerted(),
                breach.getAlertedAt() != null ? Timestamp.from(breach.getAlertedAt()) : null,
                breach.getAlertStatus(),
                breach.getBreachId()
        );
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
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .build();
        }
    }
}