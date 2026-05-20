package com.company.observability.repository;

import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.DailyAggregate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
     * Recompute the aggregate for a trailing reporting-date range from the source of truth
     * ({@code calculator_runs}), grouped by frequency. Idempotent: deletes the range then
     * rebuilds it, so re-running yields identical rows. Mirrors the V8 backfill SQL — only
     * completed runs ({@code end_time IS NOT NULL}) are aggregated; start/end minutes are UTC.
     *
     * <p>Called by the nightly {@code DailyAggregationJob}; replaces the former per-completion
     * incremental upsert.
     */
    @Transactional
    public int recomputeForDateRange(LocalDate fromInclusive, LocalDate toInclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", fromInclusive)
                .addValue("to", toInclusive);

        jdbcTemplate.update(
                "DELETE FROM calculator_sli_daily WHERE reporting_date BETWEEN :from AND :to", params);

        String insert = """
            INSERT INTO calculator_sli_daily (
                calculator_id, tenant_id, frequency, reporting_date,
                total_runs, success_runs, sla_breaches,
                sum_duration_ms, sum_start_min_utc, sum_end_min_utc, computed_at
            )
            SELECT
                calculator_id,
                tenant_id,
                frequency,
                reporting_date,
                COUNT(*),
                COUNT(*) FILTER (WHERE status = 'SUCCESS'),
                COUNT(*) FILTER (WHERE sla_breached),
                COALESCE(SUM(duration_ms), 0),
                COALESCE(SUM(
                    EXTRACT(HOUR   FROM start_time AT TIME ZONE 'UTC') * 60 +
                    EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC')
                ), 0),
                COALESCE(SUM(
                    CASE WHEN end_time IS NOT NULL THEN
                        EXTRACT(HOUR   FROM end_time AT TIME ZONE 'UTC') * 60 +
                        EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC')
                    ELSE 0 END
                ), 0),
                NOW()
            FROM calculator_runs
            WHERE end_time IS NOT NULL
              AND reporting_date BETWEEN :from AND :to
            GROUP BY calculator_id, tenant_id, frequency, reporting_date
            """;

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            int inserted = jdbcTemplate.update(insert, params);
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "recompute_daily").register(meterRegistry));
            return inserted;
        } catch (Exception e) {
            log.error("event=daily_aggregate.recompute outcome=failure from={} to={}", fromInclusive, toInclusive, e);
            throw new RuntimeException("Failed to recompute daily aggregates", e);
        }
    }

    /**
     * Fetch recent aggregates for trending. Collapses across frequency so callers that
     * are not frequency-scoped (trends, sla-summary, runtime) see one row per reporting
     * date — preserving behavior from before the frequency dimension was added.
     */
    public List<DailyAggregate> findRecentAggregates(
            String calculatorId, String tenantId, int days) {

        String sql = """
            SELECT calculator_id, tenant_id, reporting_date,
                   SUM(total_runs)        AS total_runs,
                   SUM(success_runs)      AS success_runs,
                   SUM(sla_breaches)      AS sla_breaches,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   MAX(computed_at)       AS computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = :calculatorId AND tenant_id = :tenantId
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_id, tenant_id, reporting_date
            ORDER BY reporting_date DESC
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
            SELECT calculator_id, tenant_id, reporting_date,
                   SUM(total_runs)        AS total_runs,
                   SUM(success_runs)      AS success_runs,
                   SUM(sla_breaches)      AS sla_breaches,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   MAX(computed_at)       AS computed_at
            FROM calculator_sli_daily
            WHERE calculator_id = :calculatorId AND tenant_id = :tenantId
            AND reporting_date IN (:reportingDates)
            GROUP BY calculator_id, tenant_id, reporting_date
            ORDER BY reporting_date DESC
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

    /**
     * Frequency-scoped rolling profile for one calculator over a trailing-day window
     * (avg duration + avg start/end minute). Cache-aside source for
     * {@code CalculatorProfileService}. Returns a zero-sample profile when no history exists.
     */
    public CalculatorProfile findProfile(
            String calculatorId, String tenantId, String frequency, int days) {

        String sql = """
            SELECT COALESCE(SUM(sum_duration_ms), 0)   AS sum_duration_ms,
                   COALESCE(SUM(sum_start_min_utc), 0) AS sum_start_min_utc,
                   COALESCE(SUM(sum_end_min_utc), 0)   AS sum_end_min_utc,
                   COALESCE(SUM(total_runs), 0)        AS total_runs
            FROM calculator_sli_daily
            WHERE calculator_id = :calculatorId AND tenant_id = :tenantId
            AND frequency = :frequency
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    calculatorId, tenantId, frequency,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_profile outcome=failure calculator_id={} frequency={}",
                    calculatorId, frequency, e);
            return CalculatorProfile.fromSums(calculatorId, tenantId, frequency, 0, 0, 0, 0);
        }
    }

    /**
     * Compute profiles for all active calculators of one frequency over a trailing-day window
     * in a single query. Used by the nightly job to warm the profile cache.
     */
    public List<CalculatorProfile> findAllProfiles(String frequency, int days) {
        String sql = """
            SELECT calculator_id, tenant_id,
                   SUM(sum_duration_ms)   AS sum_duration_ms,
                   SUM(sum_start_min_utc) AS sum_start_min_utc,
                   SUM(sum_end_min_utc)   AS sum_end_min_utc,
                   SUM(total_runs)        AS total_runs
            FROM calculator_sli_daily
            WHERE frequency = :frequency
            AND reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            GROUP BY calculator_id, tenant_id
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("frequency", frequency)
                .addValue("days", days);

        try {
            return jdbcTemplate.query(sql, params, (rs, rowNum) -> CalculatorProfile.fromSums(
                    rs.getString("calculator_id"), rs.getString("tenant_id"), frequency,
                    rs.getLong("sum_duration_ms"), rs.getLong("sum_start_min_utc"),
                    rs.getLong("sum_end_min_utc"), rs.getInt("total_runs")));
        } catch (Exception e) {
            log.error("event=daily_aggregate.find_all_profiles outcome=failure frequency={}", frequency, e);
            return Collections.emptyList();
        }
    }

    private static class DailyAggregateRowMapper implements RowMapper<DailyAggregate> {
        @Override
        public DailyAggregate mapRow(ResultSet rs, int rowNum) {
            try {
                return new DailyAggregate(
                        rs.getString("calculator_id"),
                        rs.getString("tenant_id"),
                        rs.getObject("reporting_date", LocalDate.class),
                        rs.getInt("total_runs"),
                        rs.getInt("success_runs"),
                        rs.getInt("sla_breaches"),
                        rs.getLong("sum_duration_ms"),
                        rs.getLong("sum_start_min_utc"),
                        rs.getLong("sum_end_min_utc"),
                        fromTimestamp(rs.getTimestamp("computed_at"))
                );
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map daily aggregate", e);
            }
        }
    }
}
