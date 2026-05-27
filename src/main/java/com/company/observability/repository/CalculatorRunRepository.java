package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
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
import static com.company.observability.util.TimeUtils.fromTimestamp;
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
               start_time, end_time, duration_ms,
               status, sla_time, expected_duration_ms,
               estimated_start_time, estimated_end_time,
               sla_breached, sla_breach_reason,
               run_number, run_type, region, correlation_id,
               run_parameters, additional_attributes,
               created_at, updated_at
        FROM calculator_runs
        """;

    private static final String SELECT_STATUS_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
               start_time, end_time, duration_ms,
               status, sla_time, expected_duration_ms,
               estimated_start_time, estimated_end_time,
               sla_breached, sla_breach_reason,
               run_number, run_type, region,
               created_at, updated_at
        FROM calculator_runs
        """;

    /**
     * Find recent runs with partition pruning
     * DAILY: reporting_date in last 2-3 days
     * MONTHLY: reporting_date = end of month
     */
    public List<CalculatorRun> findRecentRuns(
            String calculatorId, Frequency frequency, int limit) {

        // Check bloom filter
        if (!redisCache.mightExist(calculatorId)) {
            log.debug("event=cache.bloom_check outcome=miss calculator_id={}", calculatorId);
            return queryAndCacheRecentRuns(calculatorId, frequency, limit);
        }

        // Try Redis sorted set
        Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                calculatorId, frequency, limit);

        if (cached.isPresent()) {
            log.debug("event=cache.read outcome=hit calculator_id={}", calculatorId);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("event=cache.read outcome=miss calculator_id={}", calculatorId);
        return queryAndCacheRecentRuns(calculatorId, frequency, limit);
    }

    /**
     * Query with partition pruning based on frequency
     */
    private List<CalculatorRun> queryAndCacheRecentRuns(
            String calculatorId, Frequency frequency, int limit) {

        String sql = buildPartitionPrunedQuery(SELECT_STATUS_BASE, frequency);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("frequency", frequency.name())
                .addValue("limit", limit);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> runs = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(false));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_recent").register(meterRegistry));

        // Populate Redis cache
        if (!runs.isEmpty()) {
            runs.forEach(redisCache::cacheRunOnWrite);
            log.debug("event=cache.populate outcome=success calculator_id={} count={}", calculatorId, runs.size());
        }

        return runs;
    }

    private String buildPartitionPrunedQuery(String selectBase, Frequency frequency) {
        if (frequency == Frequency.DAILY) {
            return selectBase + """
                WHERE calculator_id = :calculatorId
                AND frequency = :frequency
                AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
                AND reporting_date <= CURRENT_DATE
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT :limit
                """;
        } else {
            return selectBase + """
                WHERE calculator_id = :calculatorId
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
            List<String> calculatorIds, Frequency frequency, int limit) {

        if (calculatorIds == null || calculatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<CalculatorRun>> result = new HashMap<>();
        List<String> cacheMisses = new ArrayList<>();

        // Check Redis for each calculator
        for (String calculatorId : calculatorIds) {
            Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                    calculatorId, frequency, limit);

            if (cached.isPresent()) {
                result.put(calculatorId, cached.get());
            } else {
                cacheMisses.add(calculatorId);
            }
        }

        log.debug("event=cache.batch_check outcome=success hits={} misses={}", result.size(), cacheMisses.size());

        // Query database for cache misses
        if (!cacheMisses.isEmpty()) {
            Map<String, List<CalculatorRun>> dbResults = queryBatchFromDatabase(
                    cacheMisses, frequency, limit);

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
            List<String> calculatorIds, Frequency frequency, int limit) {

        if (calculatorIds == null || calculatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<CalculatorRun>> dbResults = queryBatchFromDatabase(
                calculatorIds, frequency, limit);

        dbResults.forEach((calcId, runs) -> runs.forEach(redisCache::cacheRunOnWrite));
        return dbResults;
    }

    /**
     * Single optimized batch query with partition pruning.
     * NPJT expands :calculatorIds list into the IN clause automatically.
     */
    private Map<String, List<CalculatorRun>> queryBatchFromDatabase(
            List<String> calculatorIds, Frequency frequency, int limit) {

        String partitionFilter = buildPartitionFilter(frequency);

        String sql = String.format("""
            SELECT * FROM (
                SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                       start_time, end_time, duration_ms,
                       status, sla_time, expected_duration_ms,
                       estimated_start_time, estimated_end_time,
                       sla_breached, sla_breach_reason,
                       run_number, run_type, region, correlation_id,
                       created_at, updated_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY calculator_id
                           ORDER BY reporting_date DESC, created_at DESC
                       ) as rn
                FROM calculator_runs
                WHERE calculator_id IN (:calculatorIds)
                AND frequency = :frequency
                %s
            ) ranked
            WHERE rn <= :limit
            ORDER BY calculator_id, reporting_date DESC, created_at DESC
            """, partitionFilter);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorIds", calculatorIds)
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
    private String buildPartitionFilter(Frequency frequency) {
        if (frequency == Frequency.DAILY) {
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
                start_time, end_time, duration_ms,
                status, sla_time, expected_duration_ms,
                estimated_start_time, estimated_end_time,
                sla_band, sla_breached, sla_breach_reason,
                run_number, run_type, region, correlation_id,
                run_parameters, additional_attributes,
                created_at, updated_at
            ) VALUES (
                :runId, :calculatorId, :calculatorName, :tenantId, :frequency, :reportingDate,
                :startTime, :endTime, :durationMs,
                :status, :slaTime, :expectedDurationMs,
                :estimatedStartTime, :estimatedEndTime,
                :slaBand, :slaBreached, :slaBreachReason,
                :runNumber, :runType, :region, :correlationId,
                :runParameters, :additionalAttributes,
                :createdAt, :updatedAt
            )
            ON CONFLICT (run_id, reporting_date) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                status = EXCLUDED.status,
                sla_band = EXCLUDED.sla_band,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                run_number = EXCLUDED.run_number,
                run_type = EXCLUDED.run_type,
                region = EXCLUDED.region,
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
                .addValue("status", run.getStatus().name())
                .addValue("slaTime", toTimestamp(run.getSlaTime()))
                .addValue("expectedDurationMs", run.getExpectedDurationMs())
                .addValue("estimatedStartTime", toTimestamp(run.getEstimatedStartTime()))
                .addValue("estimatedEndTime", toTimestamp(run.getEstimatedEndTime()))
                .addValue("slaBand", run.getSlaBand() != null ? run.getSlaBand().name() : null)
                .addValue("slaBreached", run.isSlaBreached())
                .addValue("slaBreachReason", run.getSlaBreachReason())
                .addValue("runNumber", run.getRunNumber())
                .addValue("runType", run.getRunType())
                .addValue("region", run.getRegion())
                .addValue("correlationId", run.getCorrelationId())
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
                log.warn("event=cache.write outcome=failure run_id={} error={}", savedRun.getRunId(), cacheEx.getMessage(), cacheEx);
            }

            log.debug("event=run.upsert outcome=success run_id={}", savedRun.getRunId());

            return savedRun;

        } catch (Exception e) {
            log.error("event=run.upsert outcome=failure run_id={}", run.getRunId(), e);
            throw new RuntimeException("Failed to save calculator run", e);
        }
    }

    /**
     * Find by run_id with partition key hint
     */
    public Optional<CalculatorRun> findById(String runId, LocalDate reportingDate) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(reportingDate, "reportingDate must not be null");
        String sql = SELECT_BASE + " WHERE run_id = :runId AND reporting_date = :reportingDate";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("runId", runId)
                .addValue("reportingDate", reportingDate);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> results = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(false));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_by_id").register(meterRegistry));

        return Optional.ofNullable(DataAccessUtils.singleResult(results));
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
     * Mark SLA breach with partition awareness. Idempotent — only updates when sla_band IS NULL
     * (first breach write wins; live detection must not overwrite an already-set band).
     */
    public int markSlaBreach(String runId, LocalDate reportingDate, SlaBand band, String breachReason) {
        String sql = """
            UPDATE calculator_runs
            SET sla_band = :slaBand,
                sla_breached = true,
                sla_breach_reason = :breachReason,
                updated_at = NOW()
            WHERE run_id = :runId
              AND reporting_date = :reportingDate
              AND status = 'RUNNING'
              AND sla_band IS NULL
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("slaBand", band != null ? band.name() : null)
                .addValue("breachReason", breachReason)
                .addValue("runId", runId)
                .addValue("reportingDate", reportingDate);

        Timer.Sample sample = Timer.start(meterRegistry);
        int updated = jdbcTemplate.update(sql, params);
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "mark_sla_breach").register(meterRegistry));
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
            String calculatorId, Frequency frequency, int days) {
        return findRunsWithSlaStatus(calculatorId, frequency, days, null);
    }

    /**
     * @param runNumber e.g. "1" or "2" — pass null to skip the filter (single-bucket tenants)
     */
    public List<RunWithSlaStatus> findRunsWithSlaStatus(
            String calculatorId, Frequency frequency, int days, String runNumber) {

        StringBuilder sql = new StringBuilder("""
            SELECT cr.run_id, cr.calculator_id, cr.calculator_name, cr.reporting_date,
                   cr.start_time, cr.end_time, cr.duration_ms,
                   cr.sla_time, cr.estimated_start_time, cr.frequency, cr.status,
                   cr.sla_band, cr.sla_breach_reason, cr.correlation_id,
                   cr.run_number, cr.expected_duration_ms
            FROM calculator_runs cr
            WHERE cr.calculator_id = :calculatorId AND cr.frequency = :frequency
            AND cr.reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            AND cr.reporting_date <= CURRENT_DATE
            """);
        if (runNumber != null) {
            sql.append("AND cr.run_number = :runNumber\n");
        }
        sql.append("ORDER BY cr.reporting_date ASC, cr.created_at ASC");

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorId", calculatorId)
                .addValue("frequency", frequency.name())
                .addValue("days", days);
        if (runNumber != null) {
            params.addValue("runNumber", runNumber);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        List<RunWithSlaStatus> results = jdbcTemplate.query(sql.toString(), params, runWithSlaStatusMapper());
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_runs_with_sla").register(meterRegistry));

        return results;
    }

    /**
     * Name-keyed run history for GET /executions, sourced solely from calculator_runs.
     * Filters by calculator_name (readable, unique-per-tenant) instead of the upstream UUID.
     * No sla_breach_events join: the SLA grade is derived downstream from sla_breached +
     * sla_breach_reason (both set on-write by SlaEvaluationService), so severity() is always null.
     *
     * @param runNumber e.g. "1" or "2" — pass null to skip the filter (single-bucket tenants)
     */
    public List<RunWithSlaStatus> findRunsByName(
            String calculatorName, Frequency frequency, int days, String runNumber) {

        StringBuilder sql = new StringBuilder("""
            SELECT cr.run_id, cr.calculator_id, cr.calculator_name, cr.reporting_date,
                   cr.start_time, cr.end_time, cr.duration_ms,
                   cr.sla_time, cr.estimated_start_time, cr.frequency, cr.status,
                   cr.sla_band, cr.sla_breach_reason, cr.correlation_id,
                   cr.run_number, cr.expected_duration_ms
            FROM calculator_runs cr
            WHERE cr.calculator_name = :calculatorName AND cr.frequency = :frequency
            AND cr.reporting_date >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day'
            AND cr.reporting_date <= CURRENT_DATE
            """);
        if (runNumber != null) {
            sql.append("AND cr.run_number = :runNumber\n");
        }
        sql.append("ORDER BY cr.reporting_date ASC, cr.created_at ASC");

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("frequency", frequency.name())
                .addValue("days", days);
        if (runNumber != null) {
            params.addValue("runNumber", runNumber);
        }

        log.debug("event=db.query outcome=start query=find_runs_by_name calculatorName={} frequency={} days={} runNumber={}",
                calculatorName, frequency, days, runNumber);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<RunWithSlaStatus> results = jdbcTemplate.query(sql.toString(), params, runWithSlaStatusMapper());
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_runs_by_name").register(meterRegistry));

        log.debug("event=db.query outcome=complete query=find_runs_by_name calculatorName={} frequency={} rows={}",
                calculatorName, frequency, results.size());
        return results;
    }

    /**
     * Returns ALL rows for the given date/frequency/calculatorNames — no SQL deduplication.
     * Filters by calculator_name (human-readable, unique per tenant), not the upstream UUID
     * stored in calculator_id. Deduplication (splits, reruns) is handled in CalculatorStateService.
     *
     * @param runNumber e.g. "1" or "2" — pass null to skip the filter (single-bucket tenants)
     */
    public List<CalculatorRun> findAllRunsByDateAndDimension(
            LocalDate reportingDate,
            Frequency frequency,
            String runNumber,
            List<String> calculatorNames) {

        if (calculatorNames.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM calculator_runs
                WHERE reporting_date  = :reportingDate
                  AND frequency       = :frequency
                  AND calculator_name IN (:calculatorNames)
                """);
        if (runNumber != null) {
            sql.append("  AND run_number = :runNumber\n");
        }
        sql.append("""
                ORDER BY calculator_name,
                         COALESCE(correlation_id, ''),
                         COALESCE(region, ''),
                         COALESCE(run_type, ''),
                         created_at ASC
                """);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("reportingDate", reportingDate)
                .addValue("frequency", frequency.name())
                .addValue("calculatorNames", calculatorNames);
        if (runNumber != null) {
            params.addValue("runNumber", runNumber);
        }

        log.debug("event=db.query outcome=start query=find_all_runs_by_date_dimension reportingDate={} frequency={} calculatorCount={} runNumber={}",
                reportingDate, frequency, calculatorNames.size(), runNumber);

        Timer.Sample sample = Timer.start(meterRegistry);
        List<CalculatorRun> results = jdbcTemplate.query(sql.toString(), params, new CalculatorRunRowMapper(false));
        sample.stop(Timer.builder(DB_QUERY_DURATION).tag("query", "find_all_runs_by_date_dimension").register(meterRegistry));

        log.debug("event=db.query outcome=complete query=find_all_runs_by_date_dimension reportingDate={} frequency={} rows={}",
                reportingDate, frequency, results.size());
        return results;
    }

    /**
     * Get partition statistics for monitoring
     */
    public List<Map<String, Object>> getPartitionStatistics() {
        return jdbcTemplate.queryForList("SELECT * FROM get_partition_statistics()", EmptySqlParameterSource.INSTANCE);
    }

    /**
     * Returns the most recent run with a non-null {@code expected_duration_ms} for the given
     * calculator name and frequency, across any reporting_date. Used as a fallback estimator
     * when {@code CalculatorProfile} has insufficient samples (new/infrequent calculators).
     *
     * <p>Uses index: {@code calculator_runs_latest_estimate_by_name_idx}.
     */
    public Optional<CalculatorRun> findLatestRunEstimatesByName(String calculatorName, Frequency frequency) {
        String sql = SELECT_BASE + """
            WHERE calculator_name = :calculatorName
              AND frequency = :frequency
              AND expected_duration_ms IS NOT NULL
            ORDER BY reporting_date DESC, created_at DESC
            LIMIT 1
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("calculatorName", calculatorName)
                .addValue("frequency", frequency.name());

        List<CalculatorRun> results = jdbcTemplate.query(sql, params, new CalculatorRunRowMapper(false));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private RowMapper<RunWithSlaStatus> runWithSlaStatusMapper() {
        return (rs, rowNum) -> {
            String bandStr = rs.getString("sla_band");
            SlaBand slaBand = bandStr != null ? SlaBand.valueOf(bandStr) : null;
            return new RunWithSlaStatus(
                    rs.getString("run_id"),
                    rs.getString("calculator_id"),
                    rs.getString("calculator_name"),
                    rs.getObject("reporting_date", LocalDate.class),
                    fromTimestamp(rs.getTimestamp("start_time")),
                    fromTimestamp(rs.getTimestamp("end_time")),
                    rs.getObject("duration_ms", Long.class),
                    fromTimestamp(rs.getTimestamp("sla_time")),
                    fromTimestamp(rs.getTimestamp("estimated_start_time")),
                    Frequency.from(rs.getString("frequency")),
                    RunStatus.fromString(rs.getString("status")),
                    slaBand,
                    rs.getString("sla_breach_reason"),
                    rs.getString("correlation_id"),
                    rs.getString("run_number"),
                    rs.getObject("expected_duration_ms", Long.class)
            );
        };
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
                        .frequency(Frequency.from(rs.getString("frequency")))
                        .reportingDate(rs.getObject("reporting_date", LocalDate.class))
                        .startTime(fromTimestamp(rs.getTimestamp("start_time")))
                        .endTime(fromTimestamp(rs.getTimestamp("end_time")))
                        .durationMs(rs.getObject("duration_ms", Long.class))
                        .status(RunStatus.fromString(rs.getString("status")))
                        .slaTime(fromTimestamp(rs.getTimestamp("sla_time")))
                        .expectedDurationMs(rs.getObject("expected_duration_ms", Long.class))
                        .estimatedStartTime(fromTimestamp(rs.getTimestamp("estimated_start_time")))
                        .estimatedEndTime(fromTimestamp(rs.getTimestamp("estimated_end_time")))
                        .slaBand(rs.getString("sla_band") != null ? SlaBand.valueOf(rs.getString("sla_band")) : null)
                        .slaBreached(rs.getBoolean("sla_breached"))
                        .slaBreachReason(rs.getString("sla_breach_reason"))
                        .runNumber(rs.getString("run_number"))
                        .runType(rs.getString("run_type"))
                        .region(rs.getString("region"))
                        .correlationId(rs.getString("correlation_id"))
                        .createdAt(fromTimestamp(rs.getTimestamp("created_at")))
                        .updatedAt(fromTimestamp(rs.getTimestamp("updated_at")));

                if (includeJsonb) {
                    builder.runParameters(jsonbConverter.fromJsonb(rs.getObject("run_parameters")))
                           .additionalAttributes(jsonbConverter.fromJsonb(rs.getObject("additional_attributes")));
                }

                return builder.build();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map calculator run", e);
            }
        }
    }
}
