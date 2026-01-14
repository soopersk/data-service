package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * Redis-based SLA timer using key expiry
 * When a calculator run starts, we set a Redis key with TTL = SLA duration
 * If the key expires before run completes, it triggers SLA breach detection
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisRunCache {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Start SLA timer using Redis key expiry (GT approach)
     * When key expires, Redis will publish expiration event
     */
    public void startSlaTimer(CalculatorRun run) {
        if (run.getSlaDurationMs() == null || run.getSlaDurationMs() <= 0) {
            log.debug("No SLA duration set for run {}, skipping timer", run.getRunId());
            return;
        }

        String key = "sla_timer:" + run.getRunId();

        // Store minimal data - just need the key to expire
        redisTemplate.opsForValue().set(
                key,
                run.getRunId(),
                Duration.ofMillis(run.getSlaDurationMs())
        );

        log.info("Started SLA timer for run {} with {}ms timeout",
                run.getRunId(), run.getSlaDurationMs());
    }

    /**
     * Cancel SLA timer when run completes normally
     */
    public void cancelSlaTimer(String runId) {
        String key = "sla_timer:" + runId;
        Boolean deleted = redisTemplate.delete(key);

        if (Boolean.TRUE.equals(deleted)) {
            log.debug("Cancelled SLA timer for run {}", runId);
        }
    }
}