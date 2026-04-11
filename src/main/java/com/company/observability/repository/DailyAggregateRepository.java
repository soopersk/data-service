package com.company.observability.repository;

import com.company.observability.domain.DailyAggregate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.*;
import static com.company.observability.util.TimeUtils.fromTimestamp;

/**
 * Daily aggregate repository with reporting_date alignment
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class DailyAggregateRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

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
            ) VALUES (:calculatorId, :tenantId, :reportingDate, 1, :successIncr, :breachIncr, :durationMs, :startMinCet, :endMinCet, NOW())
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

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("reportingDate", reportingDate)
                .addValue("successIncr", successIncr)
                .addValue("breachIncr", breachIncr)
                .addValue("durationMs", durationMs)
                .addValue("startMinCet", startMinCet)
                .addValue("endMinCet", endMinCet);

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            jdbcTemplate.update(sql, params);
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "upsert_daily").register(meterRegistry));
        } catch (Exception e) {
            log.error("event=daily_aggregate.upsert outcome=failure calculator_id={} reporting_date={}",
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
            WHERE calculator_id = :calculatorId AND tenant_id = :tenantId
            AND day_cet >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            ORDER BY day_cet DESC
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("days", days);

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            List<DailyAggregate> results = jdbcTemplate.query(sql, params, new DailyAggregateRowMapper());
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_recent_agg").register(meterRegistry));
            return results;
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_recent outcome=failure calculator_id={}", calculatorId, e);
            throw new RuntimeException("Failed to fetch daily aggregates", e);
        }
    }

    /**
     * Get aggregates for specific reporting dates (for MONTHLY calculators).
     * NPJT expands :reportingDates list into the IN clause automatically.
     */
    public List<DailyAggregate> findByReportingDates(
            String calculatorId, String tenantId, List<LocalDate> reportingDates) {

        if (reportingDates == null || reportingDates.isEmpty()) {
            return Collections.emptyList();
        }

        String sql = """
            SELECT calculator_id, tenant_id, day_cet, total_runs, success_runs,
                   sla_breaches, avg_duration_ms, avg_start_min_cet, avg_end_min_cet, computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = :calculatorId AND tenant_id = :tenantId
            AND day_cet IN (:reportingDates)
            ORDER BY day_cet DESC
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("reportingDates", reportingDates);

        try {
            return jdbcTemplate.query(sql, params, new DailyAggregateRowMapper());
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_by_dates outcome=failure calculator_id={}", calculatorId, e);
            throw new RuntimeException("Failed to fetch aggregates by date", e);
        }
    }

    private static class DailyAggregateRowMapper implements RowMapper<DailyAggregate> {
        @Override
        public DailyAggregate mapRow(ResultSet rs, int rowNum) {
            try {
                return new DailyAggregate(
                        rs.getString("calculator_id"),
                        rs.getString("tenant_id"),
                        rs.getDate("day_cet").toLocalDate(),
                        rs.getInt("total_runs"),
                        rs.getInt("success_runs"),
                        rs.getInt("sla_breaches"),
                        rs.getLong("avg_duration_ms"),
                        rs.getInt("avg_start_min_cet"),
                        rs.getInt("avg_end_min_cet"),
                        fromTimestamp(rs.getTimestamp("computed_at"))
                );
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map daily aggregate", e);
            }
        }
    }
}
