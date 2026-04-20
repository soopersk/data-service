package com.company.observability.cache;

import com.company.observability.dto.response.CalculatorDashboardResponse;
import com.company.observability.dto.response.CalculatorDashboardResponse.DashboardSection;
import com.company.observability.dto.response.CalculatorDashboardResponse.SectionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Two-tier Redis cache for the unified calculator dashboard endpoint.
 *
 * <p><strong>Tier 1 — Status response cache</strong>
 * Key: {@code obs:analytics:dashboard:status:{tenantId}:{reportingDate}:{frequency}:{runNumber}}
 * Stores the fully formatted {@link CalculatorDashboardResponse}.
 * TTL is dynamic, selected by {@link #determineTtl(CalculatorDashboardResponse)}:
 * <ul>
 *   <li>ALL_TERMINAL_CLEAN (every section complete, no failures): 4 hours</li>
 *   <li>ALL_TERMINAL_WITH_FAILURES (all ended, ≥1 failed anywhere): 5 minutes</li>
 *   <li>ANY_RUNNING (≥1 running in any section): 30 seconds</li>
 *   <li>ALL_NOT_STARTED (nothing started yet): 60 seconds</li>
 * </ul>
 *
 * <p>All Redis exceptions are swallowed — the cache is best-effort; callers always
 * fall back to the database on a miss.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STATUS_KEY_PREFIX =
            "obs:analytics:dashboard:status:";

    static final Duration TTL_TERMINAL_CLEAN        = Duration.ofHours(4);
    static final Duration TTL_TERMINAL_WITH_FAILURES = Duration.ofMinutes(5);
    static final Duration TTL_ANY_RUNNING           = Duration.ofSeconds(30);
    static final Duration TTL_NOT_STARTED           = Duration.ofSeconds(60);

    // ── Status response cache ────────────────────────────────────────────────

    /**
     * Returns the cached {@link CalculatorDashboardResponse} for the given parameters,
     * or {@code null} on miss.
     */
    public CalculatorDashboardResponse getStatusResponse(
            String tenantId, LocalDate reportingDate, String frequency, int runNumber) {
        String key = statusKey(tenantId, reportingDate, frequency, runNumber);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.debug("event=cache.read outcome=hit key={}", key);
                if (cached instanceof CalculatorDashboardResponse r) return r;
                return objectMapper.convertValue(cached, CalculatorDashboardResponse.class);
            }
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    /**
     * Stores the {@link CalculatorDashboardResponse} with a TTL determined by the
     * current completion state across all sections.
     */
    public void putStatusResponse(
            String tenantId, LocalDate reportingDate, String frequency, int runNumber,
            CalculatorDashboardResponse response) {
        String key = statusKey(tenantId, reportingDate, frequency, runNumber);
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
     * Scans all section summaries to determine the overall state and appropriate TTL.
     *
     * <ul>
     *   <li>ANY_RUNNING — ≥1 running in any section → 30 seconds</li>
     *   <li>ALL_NOT_STARTED — nothing started across all sections → 60 seconds</li>
     *   <li>ALL_TERMINAL_WITH_FAILURES — all terminal but ≥1 failed → 5 minutes</li>
     *   <li>ALL_TERMINAL_CLEAN — all complete, zero failures → 4 hours</li>
     * </ul>
     */
    Duration determineTtl(CalculatorDashboardResponse response) {
        List<DashboardSection> sections = response.sections();
        if (sections == null || sections.isEmpty()) {
            return TTL_NOT_STARTED;
        }

        int totalRunning = 0;
        int totalFailed = 0;
        int totalNotStarted = 0;
        int totalCompleted = 0;

        for (DashboardSection section : sections) {
            SectionSummary s = section.summary();
            if (s == null) continue;
            totalRunning    += s.runningCount();
            totalFailed     += s.failedCount();
            totalNotStarted += s.notStartedCount();
            totalCompleted  += s.completedCount();
        }

        if (totalRunning > 0) {
            return TTL_ANY_RUNNING;
        }
        if (totalCompleted == 0 && totalFailed == 0) {
            return TTL_NOT_STARTED;
        }
        // All terminal (no running, no not-started... wait, not-started could still exist
        // for sections whose dependencies haven't been met yet — those count as terminal
        // for TTL purposes since the state won't change until upstream progresses)
        if (totalRunning == 0 && totalNotStarted == 0) {
            return totalFailed > 0 ? TTL_TERMINAL_WITH_FAILURES : TTL_TERMINAL_CLEAN;
        }
        // Some not-started but nothing running → upstream dependency not yet met,
        // use same cadence as NOT_STARTED
        return TTL_NOT_STARTED;
    }

    // ── Key builders ─────────────────────────────────────────────────────────

    private String statusKey(String tenantId, LocalDate reportingDate, String frequency, int runNumber) {
        return STATUS_KEY_PREFIX + tenantId + ":" + reportingDate + ":" + frequency + ":" + runNumber;
    }
}
