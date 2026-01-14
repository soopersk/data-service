package com.company.observability.repository;

import com.company.observability.domain.Calculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_BASE = """
        SELECT calculator_id, name, description, frequency,
               sla_target_duration_ms, sla_target_end_hour_cet,
               owner_team, active, created_at, updated_at
        FROM calculators
        """;

    public Optional<Calculator> findById(String calculatorId) {
        String sql = SELECT_BASE + " WHERE calculator_id = ?";

        List<Calculator> results = jdbcTemplate.query(sql, new CalculatorRowMapper(), calculatorId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<Calculator> findAllActive() {
        String sql = SELECT_BASE + " WHERE active = true ORDER BY name";
        return jdbcTemplate.query(sql, new CalculatorRowMapper());
    }

    public Calculator save(Calculator calculator) {
        if (calculator.getCreatedAt() == null) {
            calculator.setCreatedAt(Instant.now());
        }
        calculator.setUpdatedAt(Instant.now());

        String sql = """
            INSERT INTO calculators (
                calculator_id, name, description, frequency,
                sla_target_duration_ms, sla_target_end_hour_cet,
                owner_team, active, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (calculator_id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                frequency = EXCLUDED.frequency,
                sla_target_duration_ms = EXCLUDED.sla_target_duration_ms,
                sla_target_end_hour_cet = EXCLUDED.sla_target_end_hour_cet,
                owner_team = EXCLUDED.owner_team,
                active = EXCLUDED.active,
                updated_at = EXCLUDED.updated_at
            """;

        jdbcTemplate.update(sql,
                calculator.getCalculatorId(),
                calculator.getName(),
                calculator.getDescription(),
                calculator.getFrequency(),
                calculator.getSlaTargetDurationMs(),
                calculator.getSlaTargetEndHourCet(),
                calculator.getOwnerTeam(),
                calculator.getActive(),
                calculator.getCreatedAt(),
                calculator.getUpdatedAt()
        );

        return calculator;
    }

    private static class CalculatorRowMapper implements RowMapper<Calculator> {
        @Override
        public Calculator mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Calculator.builder()
                    .calculatorId(rs.getString("calculator_id"))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .frequency(rs.getString("frequency"))
                    .slaTargetDurationMs(rs.getLong("sla_target_duration_ms"))
                    .slaTargetEndHourCet(rs.getBigDecimal("sla_target_end_hour_cet"))
                    .ownerTeam(rs.getString("owner_team"))
                    .active(rs.getBoolean("active"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        }
    }
}