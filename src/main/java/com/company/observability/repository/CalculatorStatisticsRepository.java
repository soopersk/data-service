package com.company.observability.repository;

import com.company.observability.domain.CalculatorStatistics;
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
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorStatisticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<CalculatorStatistics> findLatestStatistics(
            String calculatorId, String tenantId, int periodDays) {

        String sql = """
            SELECT stat_id, calculator_id, tenant_id, period_days,
                   period_start, period_end, total_runs, successful_runs, failed_runs,
                   avg_duration_ms, min_duration_ms, max_duration_ms,
                   avg_start_hour_cet, avg_end_hour_cet, sla_breaches, computed_at
            FROM calculator_statistics
            WHERE calculator_id = ? 
            AND tenant_id = ? 
            AND period_days = ?
            ORDER BY computed_at DESC
            LIMIT 1
            """;

        List<CalculatorStatistics> results = jdbcTemplate.query(sql,
                new CalculatorStatisticsRowMapper(), calculatorId, tenantId, periodDays);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public CalculatorStatistics save(CalculatorStatistics stats) {
        if (stats.getComputedAt() == null) {
            stats.setComputedAt(Instant.now());
        }

        String sql = """
            INSERT INTO calculator_statistics (
                calculator_id, tenant_id, period_days, period_start, period_end,
                total_runs, successful_runs, failed_runs,
                avg_duration_ms, min_duration_ms, max_duration_ms,
                avg_start_hour_cet, avg_end_hour_cet, sla_breaches, computed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, stats.getCalculatorId());
            ps.setString(2, stats.getTenantId());
            ps.setInt(3, stats.getPeriodDays());
            ps.setTimestamp(4, Timestamp.from(stats.getPeriodStart()));
            ps.setTimestamp(5, Timestamp.from(stats.getPeriodEnd()));
            ps.setInt(6, stats.getTotalRuns());
            ps.setInt(7, stats.getSuccessfulRuns());
            ps.setInt(8, stats.getFailedRuns());
            ps.setObject(9, stats.getAvgDurationMs());
            ps.setObject(10, stats.getMinDurationMs());
            ps.setObject(11, stats.getMaxDurationMs());
            ps.setBigDecimal(12, stats.getAvgStartHourCet());
            ps.setBigDecimal(13, stats.getAvgEndHourCet());
            ps.setInt(14, stats.getSlaBreaches());
            ps.setTimestamp(15, Timestamp.from(stats.getComputedAt()));
            return ps;
        }, keyHolder);

        stats.setStatId(keyHolder.getKey().longValue());
        return stats;
    }

    private static class CalculatorStatisticsRowMapper implements RowMapper<CalculatorStatistics> {
        @Override
        public CalculatorStatistics mapRow(ResultSet rs, int rowNum) throws SQLException {
            return CalculatorStatistics.builder()
                    .statId(rs.getLong("stat_id"))
                    .calculatorId(rs.getString("calculator_id"))
                    .tenantId(rs.getString("tenant_id"))
                    .periodDays(rs.getInt("period_days"))
                    .periodStart(rs.getTimestamp("period_start").toInstant())
                    .periodEnd(rs.getTimestamp("period_end").toInstant())
                    .totalRuns(rs.getInt("total_runs"))
                    .successfulRuns(rs.getInt("successful_runs"))
                    .failedRuns(rs.getInt("failed_runs"))
                    .avgDurationMs(rs.getObject("avg_duration_ms", Long.class))
                    .minDurationMs(rs.getObject("min_duration_ms", Long.class))
                    .maxDurationMs(rs.getObject("max_duration_ms", Long.class))
                    .avgStartHourCet(rs.getBigDecimal("avg_start_hour_cet"))
                    .avgEndHourCet(rs.getBigDecimal("avg_end_hour_cet"))
                    .slaBreaches(rs.getInt("sla_breaches"))
                    .computedAt(rs.getTimestamp("computed_at").toInstant())
                    .build();
        }
    }
}