package com.company.observability.cache;

import com.company.observability.dto.response.RegionalBatchStatusResponse;
import com.company.observability.repository.CalculatorRunRepository.RegionalBatchTiming;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Two-tier Redis cache for the regional batch dashboard endpoint.
 *
 * <p><strong>Tier 1 — History cache</strong>
 * Key: {@code obs:analytics:regional-batch:history:{tenantId}:{reportingDate}}
 * Stores the 7-day historical timing data used for median start/end estimation.
 * TTL: 24 hours. The history covers days <em>before</em> the reporting date,
 * so it is immutable and safe to cache for the lifetime of a calendar day.
 *
 * <p><strong>Tier 2 — Status response cache</strong>
 * Key: {@code obs:analytics:regional-batch:status:{tenantId}:{reportingDate}}
 * Stores the fully formatted {@link RegionalBatchStatusResponse}.
 * TTL is dynamic, selected by {@link #determineTtl(RegionalBatchStatusResponse)}:
 * <ul>
 *   <li>TERMINAL_CLEAN (all complete, no failures): 4 hours</li>
 *   <li>TERMINAL_WITH_FAILURES (all ended, some failed): 5 minutes</li>
 *   <li>ACTIVE (≥1 running): 30 seconds</li>
 *   <li>NOT_STARTED (nothing started yet): 60 seconds</li>
 * </ul>
 *
 * <p>All Redis exceptions are swallowed — the cache is best-effort; callers always
 * fall back to the database on a miss.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegionalBatchCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String HISTORY_KEY_PREFIX = "obs:analytics:regional-batch:history:";
    private static final String STATUS_KEY_PREFIX  = "obs:analytics:regional-batch:status:";

    static final Duration HISTORY_TTL             = Duration.ofHours(24);
    static final Duration TTL_TERMINAL_CLEAN       = Duration.ofHours(4);
    static final Duration TTL_TERMINAL_WITH_FAILURES = Duration.ofMinutes(5);
    static final Duration TTL_ACTIVE              = Duration.ofSeconds(30);
    static final Duration TTL_NOT_STARTED         = Duration.ofSeconds(60);

    // ── History cache ────────────────────────────────────────────────────────

    /**
     * Returns the cached 7-day history for the given tenant + date, or {@code null} on miss.
     * Backward-compatible overload — uses no run_number in the key.
     */
    public List<RegionalBatchTiming> getHistory(String tenantId, LocalDate reportingDate) {
        return getHistory(tenantId, reportingDate, null);
    }

    /**
     * Returns the cached 7-day history for the given tenant + date + runNumber,
     * or {@code null} on miss.
     *
     * @param runNumber "1", "2", or null (null means no run_number filter was applied)
     */
    public List<RegionalBatchTiming> getHistory(String tenantId, LocalDate reportingDate, String runNumber) {
        String key = historyKey(tenantId, reportingDate, runNumber);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("event=cache.read outcome=hit key={}", key);
                return objectMapper.convertValue(cached,
                        new TypeReference<List<RegionalBatchTiming>>() {});
            }
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    /**
     * Stores the 7-day history for the given tenant + date with a 24-hour TTL.
     * Backward-compatible overload — uses no run_number in the key.
     */
    public void putHistory(String tenantId, LocalDate reportingDate,
                           List<RegionalBatchTiming> history) {
        putHistory(tenantId, reportingDate, null, history);
    }

    /**
     * Stores the 7-day history for the given tenant + date + runNumber with a 24-hour TTL.
     *
     * @param runNumber "1", "2", or null
     */
    public void putHistory(String tenantId, LocalDate reportingDate, String runNumber,
                           List<RegionalBatchTiming> history) {
        String key = historyKey(tenantId, reportingDate, runNumber);
        try {
            redisTemplate.opsForValue().set(key, history, HISTORY_TTL);
            log.debug("event=cache.write outcome=success key={}", key);
        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    // ── Status response cache ────────────────────────────────────────────────

    /**
     * Returns the cached {@link RegionalBatchStatusResponse} for the given tenant + date,
     * or {@code null} on miss.
     */
    public RegionalBatchStatusResponse getStatusResponse(String tenantId, LocalDate reportingDate) {
        String key = statusKey(tenantId, reportingDate, null);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("event=cache.read outcome=hit key={}", key);
                if (cached instanceof RegionalBatchStatusResponse r) return r;
                return objectMapper.convertValue(cached, RegionalBatchStatusResponse.class);
            }
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    /**
     * Stores the {@link RegionalBatchStatusResponse} with a TTL determined by the
     * current region completion state.
     */
    public void putStatusResponse(String tenantId, LocalDate reportingDate,
                                  RegionalBatchStatusResponse response) {
        String key = statusKey(tenantId, reportingDate, null);
        Duration ttl = determineTtl(response);
        try {
            redisTemplate.opsForValue().set(key, response, ttl);
            log.debug("event=cache.write outcome=success key={} ttl={}", key, ttl);
        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    // ── TTL selection ────────────────────────────────────────────────────────

    /**
     * Selects the TTL based on the current state of the regional batches.
     *
     * <ul>
     *   <li>TERMINAL_CLEAN — all 10 regions ended, none failed → 4 hours (immutable for the day)</li>
     *   <li>TERMINAL_WITH_FAILURES — all ended but ≥1 failed → 5 min (re-run may happen)</li>
     *   <li>ACTIVE — ≥1 region still running → 30 seconds (state changing rapidly)</li>
     *   <li>NOT_STARTED — no runs at all yet → 60 seconds (nothing to update imminently)</li>
     * </ul>
     */
    Duration determineTtl(RegionalBatchStatusResponse response) {
        int notStarted = response.totalRegions()
                - response.completedRegions()
                - response.runningRegions()
                - response.failedRegions();

        boolean allTerminal = response.runningRegions() == 0 && notStarted == 0;

        if (!allTerminal) {
            return response.runningRegions() > 0 ? TTL_ACTIVE : TTL_NOT_STARTED;
        }
        return response.failedRegions() == 0 ? TTL_TERMINAL_CLEAN : TTL_TERMINAL_WITH_FAILURES;
    }

    // ── Key builders ─────────────────────────────────────────────────────────

    private String historyKey(String tenantId, LocalDate reportingDate, String runNumber) {
        String base = HISTORY_KEY_PREFIX + tenantId + ":" + reportingDate;
        return runNumber != null ? base + ":" + runNumber : base;
    }

    private String statusKey(String tenantId, LocalDate reportingDate, String runNumber) {
        String base = STATUS_KEY_PREFIX + tenantId + ":" + reportingDate;
        return runNumber != null ? base + ":" + runNumber : base;
    }
}
