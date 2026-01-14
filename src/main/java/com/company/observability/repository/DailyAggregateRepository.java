package com.company.observability.repository;

import com.company.observability.domain.DailyAggregate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * GT Enhancement: Repository for daily aggregated metrics
 * Uses PostgreSQL's ON CONFLICT for atomic upsert with rolling averages
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DailyAggregateRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Atomic upsert of daily metrics using PostgreSQL's ON CONFLICT
     * Implements rolling average calculation for incremental updates
     */
    public void upsertDaily(String calculatorId, String tenantId, LocalDate dayCet,
                            String status, boolean slaBreached, long durationMs,
                            int startMinCet, int endMinCet) {
        String sql = """
            INSERT INTO calculator_sli_daily (
                calculator_id, tenant_id, day_cet,
                total_runs, success_runs, sla_breaches,
                avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (calculator_id, tenant_id, day_cet)
            DO UPDATE SET
                total_runs = calculator_sli_daily.total_runs + 1,
                success_runs = calculator_sli_daily.success_runs + ?,
                sla_breaches = calculator_sli_daily.sla_breaches + ?,
                avg_duration_ms = 
                    ((calculator_sli_daily.avg_duration_ms * calculator_sli_daily.total_runs) + ?)
                    / (calculator_sli_daily.total_runs + 1),
                avg_start_min_cet = 
                    ((calculator_sli_daily.avg_start_min_cet * calculator_sli_daily.total_runs) + ?)
                    / (calculator_sli_daily.total_runs + 1),
                avg_end_min_cet = 
                    ((calculator_sli_daily.avg_end_min_cet * calculator_sli_daily.total_runs) + ?)
                    / (calculator_sli_daily.total_runs + 1),
                computed_at = NOW()
            """;

        int successIncr = "SUCCESS".equals(status) ? 1 : 0;
        int breachIncr = slaBreached ? 1 : 0;

        jdbcTemplate.update(sql,
                calculatorId, tenantId, dayCet,
                successIncr, breachIncr, durationMs, startMinCet, endMinCet,
                successIncr, breachIncr, durationMs, startMinCet, endMinCet
        );
    }

    /**
     * Fetch recent daily aggregates for trending/dashboard
     */
    public List<DailyAggregate> findRecentAggregates(String calculatorId, String tenantId, int days) {
        String sql = """
            SELECT calculator_id, tenant_id, day_cet, total_runs, success_runs,
                   sla_breaches, avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = ? AND tenant_id = ?
            AND day_cet >= CURRENT_DATE - INTERVAL '? days'
            ORDER BY day_cet DESC
            """;

        return jdbcTemplate.query(sql, new DailyAggregateRowMapper(),
                calculatorId, tenantId, days);
    }

    private static class DailyAggregateRowMapper implements RowMapper<DailyAggregate> {
        @Override
        public DailyAggregate mapRow(ResultSet rs, int rowNum) throws SQLException {
            return DailyAggregate.builder()
                    .calculatorId(rs.getString("calculator_id"))
                    .tenantId(rs.getString("tenant_id"))
                    .dayCet(rs.getDate("day_cet").toLocalDate())
                    .totalRuns(rs.getInt("total_runs"))
                    .successRuns(rs.getInt("success_runs"))
                    .slaBreaches(rs.getInt("sla_breaches"))
                    .avgDurationMs(rs.getLong("avg_duration_ms"))
                    .avgStartMinCet(rs.getInt("avg_start_min_cet"))
                    .avgEndMinCet(rs.getInt("avg_end_min_cet"))
                    .computedAt(rs.getTimestamp("computed_at").toInstant())
                    .build();
        }
    }
}
