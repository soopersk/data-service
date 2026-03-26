package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.Severity;
import com.company.observability.util.JsonbConverter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.*;
import static com.company.observability.util.TimeUtils.toTimestamp;

/**
 * Repository
 *
 * This repository provides efficient access to calculator run data with a focus on recent runs for each calculator.
 * It uses a combination of PostgreSQL for durable storage and Redis for caching recent runs to optimize read performance.
*/

@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RedisCalculatorCache redisCache;
    private final JsonbConverter jsonbConverter;
    private final MeterRegistry meterRegistry;

    private static final String SELECT_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_time, expected_duration_ms,
               estimated_start_time, estimated_end_time,
               sla_breached, sla_breach_reason,
               run_parameters, additional_attributes,
               created_at, updated_at
        FROM calculator_runs
        """;

    private static final String SELECT_STATUS_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_time, expected_duration_ms,
               estimated_start_time, estimated_end_time,
               sla_breached, sla_breach_reason,
               created_at, updated_at
        FROM calculator_runs
        """;

    /**
     * Find recent runs with partition pruning
     * DAILY: reporting_date in last 2-3 days
     * MONTHLY: reporting_date = end of month
     */
    public List<CalculatorRun> findRecentRuns(
            String calculatorId, String tenantId, CalculatorFrequency frequency, int limit) {

        // Check bloom filter
        if (!redisCache.mightExist(calculatorId)) {
            log.debug("event=bloom_filter_miss calculator_id={}", calculatorId);
            return queryAndCacheRecentRuns(calculatorId, tenantId, frequency, limit);
        }

        // Try Redis sorted set
        Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                calculatorId, tenantId, frequency, limit);

        if (cached.isPresent()) {
            log.debug("event=redis_hit calculator_id={}", calculatorId);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("event=redis_miss calculator_id={}", calculatorId);
        return queryAndCacheRecentRuns(calculatorId, tenantId, frequency, limit);
    }

    /**
     * Query with partition pruning based on frequency
     */
    private List<CalculatorRun> queryAndCacheRecentRuns(
            String calculatorId, String tenantId, CalculatorFrequency frequency, int limit) {

        String sql = buildPartitionPrunedQuery(SELECT_STATUS_BASE, frequency);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("frequency", frequency.name())
                .addValue("limit", limit);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> runs = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(false));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_recent").register(meterRegistry));

        // Populate Redis cache
        if (!runs.isEmpty()) {
            runs.forEach(redisCache::cacheRunOnWrite);
            log.debug("event=cache_populated calculator_id={} count={}", calculatorId, runs.size());
        }

        return runs;
    }

    private String buildPartitionPrunedQuery(String selectBase, CalculatorFrequency frequency) {
        if (frequency == CalculatorFrequency.DAILY) {
            return selectBase + """
                WHERE calculator_id = :calculatorId
                AND tenant_id = :tenantId
                AND frequency = :frequency
                AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
                AND reporting_date <= CURRENT_DATE
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT :limit
                """;
        } else {
            return selectBase + """
                WHERE calculator_id = :calculatorId
                AND tenant_id = :tenantId
                AND frequency = :frequency
                AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
                AND reporting_date >= CURRENT_DATE - INTERVAL '13 months'
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT :limit
                """;
        }
    }

    /**
     * Batch query with partition pruning
     */
    public Map<String, List<CalculatorRun>> findBatchRecentRuns(
            List<String> calculatorIds, String tenantId, CalculatorFrequency frequency, int limit) {

        if (calculatorIds == null || calculatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<CalculatorRun>> result = new HashMap<>();
        List<String> cacheMisses = new ArrayList<>();

        // Check Redis for each calculator
        for (String calculatorId : calculatorIds) {
            Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                    calculatorId, tenantId, frequency, limit);

            if (cached.isPresent()) {
                result.put(calculatorId, cached.get());
            } else {
                cacheMisses.add(calculatorId);
            }
        }

        log.debug("event=batch_cache_check hits={} misses={}", result.size(), cacheMisses.size());

        // Query database for cache misses
        if (!cacheMisses.isEmpty()) {
            Map<String, List<CalculatorRun>> dbResults = queryBatchFromDatabase(
                    cacheMisses, tenantId, frequency, limit);

            // Cache the results
            dbResults.forEach((calcId, runs) -> runs.forEach(redisCache::cacheRunOnWrite));

            result.putAll(dbResults);
        }

        return result;
    }

    /**
     * Batch query from database only (skips Redis read checks).
     * Used by RunQueryService after response-cache misses are already known.
     */
    public Map<String, List<CalculatorRun>> findBatchRecentRunsDbOnly(
            List<String> calculatorIds, String tenantId, CalculatorFrequency frequency, int limit) {

        if (calculatorIds == null || calculatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<CalculatorRun>> dbResults = queryBatchFromDatabase(
                calculatorIds, tenantId, frequency, limit);

        dbResults.forEach((calcId, runs) -> runs.forEach(redisCache::cacheRunOnWrite));
        return dbResults;
    }

    /**
     * Single optimized batch query with partition pruning.
     * NPJT expands :calculatorIds list into the IN clause automatically.
     */
    private Map<String, List<CalculatorRun>> queryBatchFromDatabase(
            List<String> calculatorIds, String tenantId, CalculatorFrequency frequency, int limit) {

        String partitionFilter = buildPartitionFilter(frequency);

        String sql = String.format("""
            SELECT * FROM (
                SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                       start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                       status, sla_time, expected_duration_ms,
                       estimated_start_time, estimated_end_time,
                       sla_breached, sla_breach_reason,
                       created_at, updated_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY calculator_id
                           ORDER BY reporting_date DESC, created_at DESC
                       ) as rn
                FROM calculator_runs
                WHERE calculator_id IN (:calculatorIds)
                AND tenant_id = :tenantId
                AND frequency = :frequency
                %s
            ) ranked
            WHERE rn <= :limit
            ORDER BY calculator_id, reporting_date DESC, created_at DESC
            """, partitionFilter);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorIds", calculatorIds)
                .addValue("tenantId", tenantId)
                .addValue("frequency", frequency.name())
                .addValue("limit", limit);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> allRuns = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(false));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_batch").register(meterRegistry));

        // Group by calculator ID
        Map<String, List<CalculatorRun>> grouped = new HashMap<>();
        for (CalculatorRun run : allRuns) {
            grouped.computeIfAbsent(run.getCalculatorId(), k -> new ArrayList<>()).add(run);
        }

        return grouped;
    }

    /**
     * Build partition filter based on frequency
     */
    private String buildPartitionFilter(CalculatorFrequency frequency) {
        if (frequency == CalculatorFrequency.DAILY) {
            return """
                AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
                AND reporting_date <= CURRENT_DATE
                """;
        } else {
            return """
                AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
                AND reporting_date >= CURRENT_DATE - INTERVAL '13 months'
                """;
        }
    }

    /**
     * Write-through upsert with partition key
     */
    public CalculatorRun upsert(CalculatorRun run) {
        Instant now = Instant.now();

        if (run.getCreatedAt() == null) {
            run.setCreatedAt(now);
        }
        run.setUpdatedAt(now);

        String sql = """
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                status, sla_time, expected_duration_ms,
                estimated_start_time, estimated_end_time,
                sla_breached, sla_breach_reason,
                run_parameters, additional_attributes,
                created_at, updated_at
            ) VALUES (
                :runId, :calculatorId, :calculatorName, :tenantId, :frequency, :reportingDate,
                :startTime, :endTime, :durationMs, :startHourCet, :endHourCet,
                :status, :slaTime, :expectedDurationMs,
                :estimatedStartTime, :estimatedEndTime,
                :slaBreached, :slaBreachReason,
                :runParameters, :additionalAttributes,
                :createdAt, :updatedAt
            )
            ON CONFLICT (run_id, reporting_date) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                end_hour_cet = EXCLUDED.end_hour_cet,
                status = EXCLUDED.status,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                run_parameters = EXCLUDED.run_parameters,
                additional_attributes = EXCLUDED.additional_attributes,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", run.getRunId())
                .addValue("calculatorId", run.getCalculatorId())
                .addValue("calculatorName", run.getCalculatorName())
                .addValue("tenantId", run.getTenantId())
                .addValue("frequency", run.getFrequency().name())
                .addValue("reportingDate", run.getReportingDate())
                .addValue("startTime", toTimestamp(run.getStartTime()))
                .addValue("endTime", toTimestamp(run.getEndTime()))
                .addValue("durationMs", run.getDurationMs())
                .addValue("startHourCet", run.getStartHourCet())
                .addValue("endHourCet", run.getEndHourCet())
                .addValue("status", run.getStatus().name())
                .addValue("slaTime", toTimestamp(run.getSlaTime()))
                .addValue("expectedDurationMs", run.getExpectedDurationMs())
                .addValue("estimatedStartTime", toTimestamp(run.getEstimatedStartTime()))
                .addValue("estimatedEndTime", toTimestamp(run.getEstimatedEndTime()))
                .addValue("slaBreached", run.getSlaBreached())
                .addValue("slaBreachReason", run.getSlaBreachReason())
                .addValue("runParameters", jsonbConverter.toJsonb(run.getRunParameters()))
                .addValue("additionalAttributes", jsonbConverter.toJsonb(run.getAdditionalAttributes()))
                .addValue("createdAt", Timestamp.from(run.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(run.getUpdatedAt()));

        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            List<CalculatorRun> results = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(true));
            sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "upsert").register(meterRegistry));

            CalculatorRun savedRun = DataAccessUtils.singleResult(results);
            if (savedRun == null) {
                throw new IllegalStateException("Upsert returned no rows");
            }

            // Write-through cache
            try {
                redisCache.cacheRunOnWrite(savedRun);
            } catch (Exception cacheEx) {
                log.warn("event=cache_write_failed run_id={} error={}", savedRun.getRunId(), cacheEx.getMessage(), cacheEx);
            }

            log.debug("event=upsert_complete run_id={}", savedRun.getRunId());

            return savedRun;

        } catch (Exception e) {
            log.error("event=upsert_failed run_id={}", run.getRunId(), e);
            throw new RuntimeException("Failed to save calculator run", e);
        }
    }

    /**
     * Find by run_id with partition key hint
     */
    public Optional<CalculatorRun> findById(String runId, LocalDate reportingDate) {
        String sql = SELECT_BASE + " WHERE run_id = :runId AND reporting_date = :reportingDate";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("reportingDate", reportingDate);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> results = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(true));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_by_id").register(meterRegistry));

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find by run_id without partition key (slower - scans multiple partitions)
     */
    public Optional<CalculatorRun> findById(String runId) {
        String sql = SELECT_BASE + " WHERE run_id = :runId ORDER BY reporting_date DESC LIMIT 1";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("runId", runId);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> results = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(true));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_by_id").register(meterRegistry));

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }


    /**
     * Mark SLA breached with partition awareness
     */
    public int markSlaBreached(String runId, String breachReason, LocalDate reportingDate) {
        String sql = """
            UPDATE calculator_runs
            SET sla_breached = true,
                sla_breach_reason = :breachReason,
                updated_at = NOW()
            WHERE run_id = :runId
              AND reporting_date = :reportingDate
              AND status = 'RUNNING'
              AND sla_breached = false
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("breachReason", breachReason)
                .addValue("runId", runId)
                .addValue("reportingDate", reportingDate);

        Timer.Sample sample = Timer.start(meterRegistry);
        int updated = jdbcTemplate.update(sql, params);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "mark_sla_breached").register(meterRegistry));
        return updated;
    }

    /**
     * Count running calculators (recent partitions only)
     */
    public int countRunning() {
        // Try Redis first
        Set<String> running = redisCache.getRunningCalculators();
        if (!running.isEmpty()) {
            return running.size();
        }
        // Fallback to database (recent partitions only)
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM calculator_runs
            WHERE status = 'RUNNING'
            AND reporting_date >= CURRENT_DATE - INTERVAL '7 days'
            """, EmptySqlParameterSource.INSTANCE, Integer.class);

        return count != null ? count : 0;
    }

    /**
     * Find runs with SLA severity for performance card (LEFT JOIN with sla_breach_events)
     */
    public List<RunWithSlaStatus> findRunsWithSlaStatus(
            String calculatorId, String tenantId, CalculatorFrequency frequency, int days) {

        String sql = """
            SELECT cr.run_id, cr.calculator_id, cr.calculator_name, cr.reporting_date,
                   cr.start_time, cr.end_time, cr.duration_ms, cr.start_hour_cet, cr.end_hour_cet,
                   cr.sla_time, cr.estimated_start_time, cr.frequency, cr.status,
                   cr.sla_breached, cr.sla_breach_reason,
                   sbe.severity
            FROM calculator_runs cr
            LEFT JOIN sla_breach_events sbe ON sbe.run_id = cr.run_id
            WHERE cr.calculator_id = :calculatorId AND cr.tenant_id = :tenantId AND cr.frequency = :frequency
            AND cr.reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            AND cr.reporting_date <= CURRENT_DATE
            ORDER BY cr.reporting_date ASC, cr.created_at ASC
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("tenantId", tenantId)
                .addValue("frequency", frequency.name())
                .addValue("days", days);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<RunWithSlaStatus> results = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            String severityStr = rs.getString("severity"); // nullable from LEFT JOIN
            return new RunWithSlaStatus(
                    rs.getString("run_id"),
                    rs.getString("calculator_id"),
                    rs.getString("calculator_name"),
                    rs.getObject("reporting_date", LocalDate.class),
                    getInstant(rs, "start_time"),
                    getInstant(rs, "end_time"),
                    rs.getObject("duration_ms", Long.class),
                    rs.getBigDecimal("start_hour_cet"),
                    rs.getBigDecimal("end_hour_cet"),
                    getInstant(rs, "sla_time"),
                    getInstant(rs, "estimated_start_time"),
                    CalculatorFrequency.from(rs.getString("frequency")),
                    RunStatus.fromString(rs.getString("status")),
                    rs.getObject("sla_breached", Boolean.class),
                    rs.getString("sla_breach_reason"),
                    severityStr != null ? Severity.fromString(severityStr) : null
            );
        });
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_runs_with_sla").register(meterRegistry));

        return results;
    }

    /**
     * Get partition statistics for monitoring
     */
    public List<Map<String, Object>> getPartitionStatistics() {
        return jdbcTemplate.queryForList("SELECT * FROM get_partition_statistics()", EmptySqlParameterSource.INSTANCE);
    }


    private static Instant getInstant(ResultSet rs, String columnName) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(columnName);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private class CalculatorRunRowMapper implements RowMapper<CalculatorRun> {
        private final boolean includeJsonb;

        CalculatorRunRowMapper(boolean includeJsonb) {
            this.includeJsonb = includeJsonb;
        }

        @Override
        public CalculatorRun mapRow(ResultSet rs, int rowNum) {
            try {
                var builder = CalculatorRun.builder()
                        .runId(rs.getString("run_id"))
                        .calculatorId(rs.getString("calculator_id"))
                        .calculatorName(rs.getString("calculator_name"))
                        .tenantId(rs.getString("tenant_id"))
                        .frequency(CalculatorFrequency.from(rs.getString("frequency")))
                        .reportingDate(rs.getObject("reporting_date", LocalDate.class))
                        .startTime(getInstant(rs, "start_time"))
                        .endTime(getInstant(rs, "end_time"))
                        .durationMs(rs.getObject("duration_ms", Long.class))
                        .startHourCet(rs.getBigDecimal("start_hour_cet"))
                        .endHourCet(rs.getBigDecimal("end_hour_cet"))
                        .status(RunStatus.fromString(rs.getString("status")))
                        .slaTime(getInstant(rs, "sla_time"))
                        .expectedDurationMs(rs.getObject("expected_duration_ms", Long.class))
                        .estimatedStartTime(getInstant(rs, "estimated_start_time"))
                        .estimatedEndTime(getInstant(rs, "estimated_end_time"))
                        .slaBreached(rs.getBoolean("sla_breached"))
                        .slaBreachReason(rs.getString("sla_breach_reason"))
                        .createdAt(getInstant(rs, "created_at"))
                        .updatedAt(getInstant(rs, "updated_at"));

                if (includeJsonb) {
                    builder.runParameters(jsonbConverter.fromJsonb(rs, "run_parameters"))
                           .additionalAttributes(jsonbConverter.fromJsonb(rs, "additional_attributes"));
                }

                return builder.build();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map calculator run", e);
            }
        }
    }
}
