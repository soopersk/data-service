package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * LIVE SLA MONITORING using Redis Sorted Set
 * Tracks all running calculators with their SLA deadlines
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringCache {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${observability.sla.live-tracking.enabled:true}")
    private boolean liveTrackingEnabled;

    // Sorted set: score = SLA deadline timestamp (epoch millis)
    private static final String SLA_DEADLINES_ZSET = "obs:sla:deadlines";

    // Hash: runId -> minimal run info
    private static final String SLA_RUN_INFO_HASH = "obs:sla:run_info";

    /**
     * Register a calculator run for SLA monitoring
     * Called when run starts
     */
    public void registerForSlaMonitoring(CalculatorRun run) {
        if (!liveTrackingEnabled) {
            log.debug("Live SLA tracking disabled, skipping monitoring for run {}", run.getRunId());
            return;
        }

        if (run.getSlaTime() == null) {
            log.debug("No SLA time for run {}, skipping monitoring", run.getRunId());
            return;
        }

        if (run.getStatus() != RunStatus.RUNNING) {
            log.debug("Run {} not in RUNNING status, skipping SLA monitoring", run.getRunId());
            return;
        }

        try {
            String runKey = buildRunKey(run.getTenantId(), run.getRunId(), run.getReportingDate());

            // Calculate score (SLA deadline as epoch millis)
            long slaDeadlineScore = run.getSlaTime().toEpochMilli();

            // Create minimal run info for quick lookups
            Map<String, Object> runInfo = new HashMap<>();
            runInfo.put("runKey", runKey);
            runInfo.put("runId", run.getRunId());
            runInfo.put("calculatorId", run.getCalculatorId());
            runInfo.put("calculatorName", run.getCalculatorName());
            runInfo.put("tenantId", run.getTenantId());
            runInfo.put("reportingDate", run.getReportingDate() != null ? run.getReportingDate().toString() : null);
            runInfo.put("startTime", run.getStartTime().toEpochMilli());
            runInfo.put("slaTime", run.getSlaTime().toEpochMilli());

            String runInfoJson = objectMapper.writeValueAsString(runInfo);

            // Add to sorted set (score = SLA deadline)
            redisTemplate.opsForZSet().add(
                    SLA_DEADLINES_ZSET,
                    runKey,
                    slaDeadlineScore
            );

            // Store run info in hash for quick retrieval
            redisTemplate.opsForHash().put(
                    SLA_RUN_INFO_HASH,
                    runKey,
                    runInfoJson
            );

            // Set TTL on both structures (24 hours safety)
            redisTemplate.expire(SLA_DEADLINES_ZSET, Duration.ofHours(24));
            redisTemplate.expire(SLA_RUN_INFO_HASH, Duration.ofHours(24));

            log.debug("Registered run {} for SLA monitoring (deadline: {})",
                    run.getRunId(), run.getSlaTime());

        } catch (Exception e) {
            log.error("Failed to register run for SLA monitoring", e);
        }
    }

    /**
     * Deregister a run (called when run completes)
     */
    public void deregisterFromSlaMonitoring(String runId, String tenantId, LocalDate reportingDate) {
        try {
            String runKey = buildRunKey(tenantId, runId, reportingDate);
            redisTemplate.opsForZSet().remove(SLA_DEADLINES_ZSET, runKey);
            redisTemplate.opsForHash().delete(SLA_RUN_INFO_HASH, runKey);

            log.debug("Deregistered run {} from SLA monitoring", runId);

        } catch (Exception e) {
            log.error("Failed to deregister run from SLA monitoring", e);
        }
    }

    /**
     * Get all runs that have exceeded their SLA deadline
     * Score range: -∞ to NOW
     */
    public List<Map<String, Object>> getBreachedRuns() {
        try {
            long now = Instant.now().toEpochMilli();

            // Get all runs with SLA deadline <= NOW
            Set<Object> breachedRunKeys = redisTemplate.opsForZSet()
                    .rangeByScore(SLA_DEADLINES_ZSET, 0, now);

            if (breachedRunKeys == null || breachedRunKeys.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> breachedRuns = new ArrayList<>();

            // Fetch run info for each breached run
            for (Object runKeyObj : breachedRunKeys) {
                String runKey = runKeyObj.toString();
                Object runInfoJson = redisTemplate.opsForHash().get(SLA_RUN_INFO_HASH, runKey);

                if (runInfoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> runInfo = objectMapper.readValue(
                            runInfoJson.toString(),
                            Map.class
                    );
                    breachedRuns.add(runInfo);
                }
            }

            log.debug("Found {} runs that exceeded SLA deadline", breachedRuns.size());

            return breachedRuns;

        } catch (Exception e) {
            log.error("Failed to get breached runs", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get runs approaching SLA deadline (within next N minutes)
     */
    public List<Map<String, Object>> getApproachingSlaRuns(int minutesAhead) {
        try {
            long now = Instant.now().toEpochMilli();
            long threshold = Instant.now().plus(Duration.ofMinutes(minutesAhead)).toEpochMilli();

            // Get runs with SLA deadline between NOW and NOW+N minutes
            Set<Object> approachingRunKeys = redisTemplate.opsForZSet()
                    .rangeByScore(SLA_DEADLINES_ZSET, now, threshold);

            if (approachingRunKeys == null || approachingRunKeys.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> approachingRuns = new ArrayList<>();

            for (Object runKeyObj : approachingRunKeys) {
                String runKey = runKeyObj.toString();
                Object runInfoJson = redisTemplate.opsForHash().get(SLA_RUN_INFO_HASH, runKey);

                if (runInfoJson != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> runInfo = objectMapper.readValue(
                            runInfoJson.toString(),
                            Map.class
                    );
                    approachingRuns.add(runInfo);
                }
            }

            log.debug("Found {} runs approaching SLA deadline (within {} min)",
                    approachingRuns.size(), minutesAhead);

            return approachingRuns;

        } catch (Exception e) {
            log.error("Failed to get approaching SLA runs", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get count of currently monitored runs
     */
    public long getMonitoredRunCount() {
        try {
            Long count = redisTemplate.opsForZSet().size(SLA_DEADLINES_ZSET);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("Failed to get monitored run count", e);
            return 0;
        }
    }

    /**
     * Get next SLA deadline time
     */
    public Optional<Instant> getNextSlaDeadline() {
        try {
            // Get the run with the earliest (smallest score) SLA deadline
            Set<Object> nextRun = redisTemplate.opsForZSet().range(SLA_DEADLINES_ZSET, 0, 0);

            if (nextRun == null || nextRun.isEmpty()) {
                return Optional.empty();
            }

            String runId = nextRun.iterator().next().toString();
            Double score = redisTemplate.opsForZSet().score(SLA_DEADLINES_ZSET, runId);

            if (score != null) {
                return Optional.of(Instant.ofEpochMilli(score.longValue()));
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("Failed to get next SLA deadline", e);
            return Optional.empty();
        }
    }

    private String buildRunKey(String tenantId, String runId, LocalDate reportingDate) {
        String tenant = tenantId != null ? tenantId : "unknown-tenant";
        String date = reportingDate != null ? reportingDate.toString() : "unknown-date";
        String id = runId != null ? runId : "unknown-run";
        return tenant + ":" + id + ":" + date;
    }
}
