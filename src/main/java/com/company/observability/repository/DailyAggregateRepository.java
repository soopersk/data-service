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
 * Daily aggregate repository with reporting_date alignment
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DailyAggregateRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Atomic upsert using reporting_date (matches partition key)
     */
    public void upsertDaily(String calculatorId, String tenantId, LocalDate reportingDate,
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
                success_runs = calculator_sli_daily.success_runs + EXCLUDED.success_runs,
                sla_breaches = calculator_sli_daily.sla_breaches + EXCLUDED.sla_breaches,
                avg_duration_ms = (
                    (calculator_sli_daily.avg_duration_ms * calculator_sli_daily.total_runs + EXCLUDED.avg_duration_ms) 
                    / (calculator_sli_daily.total_runs + 1)
                ),
                avg_start_min_cet = (
                    (calculator_sli_daily.avg_start_min_cet * calculator_sli_daily.total_runs + EXCLUDED.avg_start_min_cet)
                    / (calculator_sli_daily.total_runs + 1)
                ),
                avg_end_min_cet = (
                    (calculator_sli_daily.avg_end_min_cet * calculator_sli_daily.total_runs + EXCLUDED.avg_end_min_cet)
                    / (calculator_sli_daily.total_runs + 1)
                ),
                computed_at = NOW()
            """;

        int successIncr = "SUCCESS".equals(status) ? 1 : 0;
        int breachIncr = slaBreached ? 1 : 0;

        try {
            jdbcTemplate.update(sql,
                    calculatorId, tenantId, reportingDate,
                    successIncr, breachIncr, durationMs, startMinCet, endMinCet
            );
        } catch (Exception e) {
            log.error("Failed to upsert daily aggregate for calculator {} on {}",
                    calculatorId, reportingDate, e);
            throw new RuntimeException("Failed to update daily aggregate", e);
        }
    }

    /**
     * Fetch recent aggregates for trending
     */
    public List<DailyAggregate> findRecentAggregates(
            String calculatorId, String tenantId, int days) {

        String sql = """
            SELECT calculator_id, tenant_id, day_cet, total_runs, success_runs,
                   sla_breaches, avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = ? AND tenant_id = ?
            AND day_cet >= CURRENT_DATE - INTERVAL '? days'
            ORDER BY day_cet DESC
            """;

        try {
            return jdbcTemplate.query(sql, new DailyAggregateRowMapper(),
                    calculatorId, tenantId, days);
        } catch (Exception e) {
            log.error("Failed to fetch recent aggregates for calculator {}", calculatorId, e);
            throw new RuntimeException("Failed to fetch daily aggregates", e);
        }
    }

    /**
     * Get aggregates for specific reporting dates (for MONTHLY calculators)
     */
    public List<DailyAggregate> findByReportingDates(
            String calculatorId, String tenantId, List<LocalDate> reportingDates) {

        if (reportingDates == null || reportingDates.isEmpty()) {
            return Collections.emptyList();
        }

        String placeholders = String.join(",", Collections.nCopies(reportingDates.size(), "?"));

        String sql = String.format("""
            SELECT calculator_id, tenant_id, day_cet, total_runs, success_runs,
                   sla_breaches, avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = ? AND tenant_id = ?
            AND day_cet IN (%s)
            ORDER BY day_cet DESC
            """, placeholders);

        Object[] params = new Object[2 + reportingDates.size()];
        params[0] = calculatorId;
        params[1] = tenantId;
        for (int i = 0; i < reportingDates.size(); i++) {
            params[2 + i] = reportingDates.get(i);
        }

        try {
            return jdbcTemplate.query(sql, new DailyAggregateRowMapper(), params);
        } catch (Exception e) {
            log.error("Failed to fetch aggregates by reporting dates", e);
            throw new RuntimeException("Failed to fetch aggregates by date", e);
        }
    }

    private static class DailyAggregateRowMapper implements RowMapper<DailyAggregate> {
        @Override
        public DailyAggregate mapRow(ResultSet rs, int rowNum) {
            try {
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
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map daily aggregate", e);
            }
        }
    }
}