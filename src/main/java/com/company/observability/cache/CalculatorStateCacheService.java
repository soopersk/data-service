package com.company.observability.cache;

import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * Per-calculator Redis cache for the {@code GET /api/v1/calculators/batch/runs} endpoint.
 *
 * <p>Key: {@code obs:state:{calculatorName}:{reportingDate}:{frequency}:{runNumber|all}}
 *
 * <p>TTL is state-aware:
 * <ul>
 *   <li>any RUNNING → 30 s</li>
 *   <li>empty / all NOT_STARTED → 60 s</li>
 *   <li>all terminal with any failure or slaBreached → 5 min</li>
 *   <li>all terminal clean → 4 h</li>
 * </ul>
 *
 * <p>Invalidation is TTL-only — no event listeners. The state-aware TTL bounds staleness to ≤30 s
 * while any run is active; once terminal the data is stable.
 *
 * <p>All Redis ops are best-effort: exceptions are swallowed and the caller falls back to DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorStateCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final String KEY_PREFIX = "obs:state:";

    static final Duration TTL_ANY_RUNNING            = Duration.ofSeconds(30);
    static final Duration TTL_NOT_STARTED            = Duration.ofSeconds(60);
    static final Duration TTL_TERMINAL_WITH_FAILURES = Duration.ofMinutes(5);
    static final Duration TTL_TERMINAL_CLEAN         = Duration.ofHours(4);

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Bulk get for the given calculator names. Returns only cache hits keyed by name;
     * misses are simply absent from the returned map.
     */
    public Map<String, CalculatorEntry> getEntries(
            LocalDate reportingDate, String frequency, String runNumber,
            List<String> calculatorNames) {

        Map<String, CalculatorEntry> hits = new HashMap<>();
        for (String name : calculatorNames) {
            String key = buildKey(name, reportingDate, frequency, runNumber);
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    CalculatorEntry entry = objectMapper.readValue(json, CalculatorEntry.class);
                    hits.put(name, entry);
                    meterRegistry.counter(CACHE_STATE_HIT, "calculator", name).increment();
                    log.debug("event=state.cache.read outcome=hit key={}", key);
                } else {
                    meterRegistry.counter(CACHE_STATE_MISS, "calculator", name).increment();
                    log.debug("event=state.cache.read outcome=miss key={}", key);
                }
            } catch (Exception e) {
                log.warn("event=state.cache.read outcome=failure key={} error={}", key, e.getMessage());
                meterRegistry.counter(CACHE_STATE_MISS, "calculator", name).increment();
            }
        }
        return hits;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Stores each entry with its own state-aware TTL.
     */
    public void putEntries(
            LocalDate reportingDate, String frequency, String runNumber,
            Map<String, CalculatorEntry> entries) {

        entries.forEach((name, entry) -> {
            String key = buildKey(name, reportingDate, frequency, runNumber);
            Duration ttl = determineTtl(entry);
            try {
                String json = objectMapper.writeValueAsString(entry);
                redisTemplate.opsForValue().set(key, json, ttl);
                log.debug("event=state.cache.write outcome=success key={} ttl={}", key, ttl);
            } catch (Exception e) {
                log.warn("event=state.cache.write outcome=failure key={} error={}", key, e.getMessage());
            }
        });
    }

    // ── TTL selection ─────────────────────────────────────────────────────────

    /**
     * State-aware TTL selection based on the run states in the entry list.
     */
    Duration determineTtl(CalculatorEntry entry) {
        List<RunEntry> runs = entry.runs();

        if (runs == null || runs.isEmpty()) {
            return TTL_NOT_STARTED;
        }

        // Synthetic not-started entry: status is null (omitted from JSON via @JsonInclude NON_NULL)
        if (runs.stream().anyMatch(r -> r.status() == null)) {
            return TTL_NOT_STARTED;
        }

        boolean anyRunning = runs.stream()
                .anyMatch(r -> "RUNNING".equals(r.status()));
        if (anyRunning) {
            return TTL_ANY_RUNNING;
        }

        boolean allNotStarted = runs.stream()
                .allMatch(r -> "NOT_STARTED".equals(r.status()));
        if (allNotStarted) {
            return TTL_NOT_STARTED;
        }

        boolean anyFailureOrBreach = runs.stream()
                .anyMatch(r -> ("LATE".equals(r.slaStatus()) || "VERY_LATE".equals(r.slaStatus()))
                        || "FAILED".equals(r.status())
                        || "TIMEOUT".equals(r.status()));

        return anyFailureOrBreach ? TTL_TERMINAL_WITH_FAILURES : TTL_TERMINAL_CLEAN;
    }

    // ── Key builder ───────────────────────────────────────────────────────────

    private String buildKey(String calculatorName, LocalDate reportingDate,
                            String frequency, String runNumber) {
        String rn = (runNumber == null) ? "all" : runNumber;
        return KEY_PREFIX + calculatorName + ":" + reportingDate + ":" + frequency + ":" + rn;
    }
}
