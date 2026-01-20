package com.company.observability.repository;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Partition-aware repository optimized for reporting_date queries
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class CalculatorRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RedisCalculatorCache redisCache;

    private static final String SELECT_BASE = """
        SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
               start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
               status, sla_time, expected_duration_ms, 
               estimated_start_time, estimated_end_time,
               sla_breached, sla_breach_reason,
               run_parameters, created_at, updated_at
        FROM calculator_runs
        """;

    /**
     * Find recent runs with partition pruning
     * DAILY: reporting_date in last 2-3 days
     * MONTHLY: reporting_date = end of month
     */
    public List<CalculatorRun> findRecentRuns(
            String calculatorId, String tenantId, String frequency, int limit) {

        // Check bloom filter
        if (!redisCache.mightExist(calculatorId)) {
            log.debug("Bloom filter miss for calculator {}", calculatorId);
            return queryAndCacheRecentRuns(calculatorId, tenantId, frequency, limit);
        }

        // Try Redis sorted set
        Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                calculatorId, tenantId, limit);

        if (cached.isPresent()) {
            log.debug("REDIS HIT: Recent runs for {}", calculatorId);
            return cached.get();
        }

        // Cache miss - query database
        log.debug("REDIS MISS: Querying database for {}", calculatorId);
        return queryAndCacheRecentRuns(calculatorId, tenantId, frequency, limit);
    }

    /**
     * Query with partition pruning based on frequency
     */
    private List<CalculatorRun> queryAndCacheRecentRuns(
            String calculatorId, String tenantId, String frequency, int limit) {

        String sql = buildPartitionPrunedQuery(frequency);

        List<CalculatorRun> runs = jdbcTemplate.query(
                sql,
                new CalculatorRunRowMapper(),
                calculatorId, tenantId, frequency, limit
        );

        // Populate Redis cache
        if (!runs.isEmpty()) {
            runs.forEach(redisCache::cacheRunOnWrite);
            log.debug("Populated Redis cache with {} runs for {}", runs.size(), calculatorId);
        }

        return runs;
    }

    /**
     * Build SQL with optimal partition pruning
     */
    private String buildPartitionPrunedQuery(String frequency) {
        if ("DAILY".equalsIgnoreCase(frequency)) {
            // DAILY: Last 3 days only (prunes to 3 partitions)
            return SELECT_BASE + """
                WHERE calculator_id = ? 
                AND tenant_id = ? 
                AND frequency = ?
                AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'
                AND reporting_date <= CURRENT_DATE
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT ?
                """;
        } else {
            // MONTHLY: End of month dates only in last 13 months (prunes to ~13 partitions)
            return SELECT_BASE + """
                WHERE calculator_id = ? 
                AND tenant_id = ? 
                AND frequency = ?
                AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
                AND reporting_date >= CURRENT_DATE - INTERVAL '13 months'
                ORDER BY reporting_date DESC, created_at DESC
                LIMIT ?
                """;
        }
    }

    /**
     * Batch query with partition pruning
     */
    public Map<String, List<CalculatorRun>> findBatchRecentRuns(
            List<String> calculatorIds, String tenantId, String frequency, int limit) {

        if (calculatorIds == null || calculatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<CalculatorRun>> result = new HashMap<>();
        List<String> cacheMisses = new ArrayList<>();

        // Check Redis for each calculator
        for (String calculatorId : calculatorIds) {
            Optional<List<CalculatorRun>> cached = redisCache.getRecentRuns(
                    calculatorId, tenantId, limit);

            if (cached.isPresent()) {
                result.put(calculatorId, cached.get());
            } else {
                cacheMisses.add(calculatorId);
            }
        }

        log.debug("BATCH: {} Redis hits, {} misses", result.size(), cacheMisses.size());

        // Query database for cache misses
        if (!cacheMisses.isEmpty()) {
            Map<String, List<CalculatorRun>> dbResults = queryBatchFromDatabase(
                    cacheMisses, tenantId, frequency, limit);

            // Cache the results
            dbResults.forEach((calcId, runs) -> {
                runs.forEach(redisCache::cacheRunOnWrite);
            });

            result.putAll(dbResults);
        }

        return result;
    }

    /**
     * Single optimized batch query with partition pruning
     */
    private Map<String, List<CalculatorRun>> queryBatchFromDatabase(
            List<String> calculatorIds, String tenantId, String frequency, int limit) {

        String placeholders = String.join(",", Collections.nCopies(calculatorIds.size(), "?"));
        String partitionFilter = buildPartitionFilter(frequency);

        String sql = String.format("""
            SELECT * FROM (
                SELECT run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                       start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                       status, sla_time, expected_duration_ms,
                       estimated_start_time, estimated_end_time,
                       sla_breached, sla_breach_reason,
                       run_parameters, created_at, updated_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY calculator_id 
                           ORDER BY reporting_date DESC, created_at DESC
                       ) as rn
                FROM calculator_runs
                WHERE calculator_id IN (%s)
                AND tenant_id = ?
                AND frequency = ?
                %s
            ) ranked
            WHERE rn <= ?
            ORDER BY calculator_id, reporting_date DESC, created_at DESC
            """, placeholders, partitionFilter);

        Object[] params = new Object[calculatorIds.size() + 3];
        for (int i = 0; i < calculatorIds.size(); i++) {
            params[i] = calculatorIds.get(i);
        }
        params[calculatorIds.size()] = tenantId;
        params[calculatorIds.size() + 1] = frequency;
        params[calculatorIds.size() + 2] = limit;

        List<CalculatorRun> allRuns = jdbcTemplate.query(sql, new CalculatorRunRowMapper(), params);

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
    private String buildPartitionFilter(String frequency) {
        if ("DAILY".equalsIgnoreCase(frequency)) {
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
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(Instant.now());
        }
        run.setUpdatedAt(Instant.now());

        String sql = """
            INSERT INTO calculator_runs (
                run_id, calculator_id, calculator_name, tenant_id, frequency, reporting_date,
                start_time, end_time, duration_ms, start_hour_cet, end_hour_cet,
                status, sla_time, expected_duration_ms,
                estimated_start_time, estimated_end_time,
                sla_breached, sla_breach_reason,
                run_parameters, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (run_id, reporting_date) DO UPDATE SET
                end_time = EXCLUDED.end_time,
                duration_ms = EXCLUDED.duration_ms,
                end_hour_cet = EXCLUDED.end_hour_cet,
                status = EXCLUDED.status,
                sla_breached = EXCLUDED.sla_breached,
                sla_breach_reason = EXCLUDED.sla_breach_reason,
                updated_at = EXCLUDED.updated_at
            RETURNING *
            """;

        try {
            CalculatorRun savedRun = jdbcTemplate.queryForObject(sql, new CalculatorRunRowMapper(),
                    run.getRunId(),
                    run.getCalculatorId(),
                    run.getCalculatorName(),
                    run.getTenantId(),
                    run.getFrequency(),
                    run.getReportingDate(),
                    run.getStartTime() != null ? Timestamp.from(run.getStartTime()) : null,
                    run.getEndTime() != null ? Timestamp.from(run.getEndTime()) : null,
                    run.getDurationMs(),
                    run.getStartHourCet(),
                    run.getEndHourCet(),
                    run.getStatus(),
                    run.getSlaTime() != null ? Timestamp.from(run.getSlaTime()) : null,
                    run.getExpectedDurationMs(),
                    run.getEstimatedStartTime() != null ? Timestamp.from(run.getEstimatedStartTime()) : null,
                    run.getEstimatedEndTime() != null ? Timestamp.from(run.getEstimatedEndTime()) : null,
                    run.getSlaBreached(),
                    run.getSlaBreachReason(),
                    run.getRunParameters(),
                    Timestamp.from(run.getCreatedAt()),
                    Timestamp.from(run.getUpdatedAt())
            );

            // Write-through cache
            redisCache.cacheRunOnWrite(savedRun);

            log.debug("Upserted and cached run {}", savedRun.getRunId());

            return savedRun;

        } catch (Exception e) {
            log.error("Failed to upsert run {}", run.getRunId(), e);
            throw new RuntimeException("Failed to save calculator run", e);
        }
    }

    /**
     * Find by run_id with partition key hint
     */
    public Optional<CalculatorRun> findById(String runId, LocalDate reportingDate) {
        String sql = SELECT_BASE + " WHERE run_id = ? AND reporting_date = ?";
        List<CalculatorRun> results = jdbcTemplate.query(
                sql, new CalculatorRunRowMapper(), runId, reportingDate);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find by run_id without partition key (slower - scans multiple partitions)
     */
    public Optional<CalculatorRun> findById(String runId) {
        String sql = SELECT_BASE + " WHERE run_id = ? ORDER BY reporting_date DESC LIMIT 1";
        List<CalculatorRun> results = jdbcTemplate.query(sql, new CalculatorRunRowMapper(), runId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Mark SLA breached with partition awareness
     */
    public int markSlaBreached(String runId, String breachReason, LocalDate reportingDate) {
        return jdbcTemplate.update("""
            UPDATE calculator_runs
            SET sla_breached = true,
                sla_breach_reason = ?,
                updated_at = NOW()
            WHERE run_id = ? 
              AND reporting_date = ?
              AND status = 'RUNNING'
              AND sla_breached = false
            """, breachReason, runId, reportingDate);
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
            """, Integer.class);

        return count != null ? count : 0;
    }

    /**
     * Get partition statistics for monitoring
     */
    public List<Map<String, Object>> getPartitionStatistics() {
        return jdbcTemplate.queryForList("SELECT * FROM get_partition_statistics()");
    }

    private static class CalculatorRunRowMapper implements RowMapper<CalculatorRun> {
        @Override
        public CalculatorRun mapRow(ResultSet rs, int rowNum) {
            try {
                return CalculatorRun.builder()
                        .runId(rs.getString("run_id"))
                        .calculatorId(rs.getString("calculator_id"))
                        .calculatorName(rs.getString("calculator_name"))
                        .tenantId(rs.getString("tenant_id"))
                        .frequency(rs.getString("frequency"))
                        .reportingDate(rs.getObject("reporting_date", LocalDate.class))
                        .startTime(getInstant(rs, "start_time"))
                        .endTime(getInstant(rs, "end_time"))
                        .durationMs(rs.getObject("duration_ms", Long.class))
                        .startHourCet(rs.getBigDecimal("start_hour_cet"))
                        .endHourCet(rs.getBigDecimal("end_hour_cet"))
                        .status(rs.getString("status"))
                        .slaTime(getInstant(rs, "sla_time"))
                        .expectedDurationMs(rs.getObject("expected_duration_ms", Long.class))
                        .estimatedStartTime(getInstant(rs, "estimated_start_time"))
                        .estimatedEndTime(getInstant(rs, "estimated_end_time"))
                        .slaBreached(rs.getBoolean("sla_breached"))
                        .slaBreachReason(rs.getString("sla_breach_reason"))
                        .runParameters(rs.getString("run_parameters"))
                        .createdAt(getInstant(rs, "created_at"))
                        .updatedAt(getInstant(rs, "updated_at"))
                        .build();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to map calculator run", e);
            }
        }

        private static Instant getInstant(ResultSet rs, String columnName) throws SQLException {
            Timestamp timestamp = rs.getTimestamp(columnName);
            return timestamp != null ? timestamp.toInstant() : null;
        }
    }
}