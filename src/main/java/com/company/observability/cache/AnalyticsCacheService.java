package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.company.observability.util.ObservabilityConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final String ANALYTICS_PREFIX = "obs:analytics:";
    private static final String ANALYTICS_INDEX_PREFIX = "obs:analytics:index:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final Duration INDEX_TTL = Duration.ofHours(1);

    // ================================================================
    // Generic cache operations with Redis resilience
    // ================================================================

    public <T> T getFromCache(String keyPrefix, String calculatorId, String tenantId,
                              int days, Class<T> responseType) {
        String key = buildKey(keyPrefix, calculatorId, tenantId, days);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                meterRegistry.counter(CACHE_ANALYTICS_HIT, "prefix", keyPrefix).increment();
                log.debug("event=cache.read outcome=hit key={}", key);
                return convertCached(cached, responseType);
            }
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    public <T> T getFromCache(String keyPrefix, String calculatorId, String tenantId,
                              String frequency, int days, Class<T> responseType) {
        String key = buildKey(keyPrefix, calculatorId, tenantId, frequency, days);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                meterRegistry.counter(CACHE_ANALYTICS_HIT, "prefix", keyPrefix).increment();
                log.debug("event=cache.read outcome=hit key={}", key);
                return convertCached(cached, responseType);
            }
        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    public void putInCache(String keyPrefix, String calculatorId, String tenantId,
                           int days, Object response) {
        String key = buildKey(keyPrefix, calculatorId, tenantId, days);
        String indexKey = buildIndexKey(calculatorId, tenantId);
        try {
            redisTemplate.opsForValue().set(key, response, DEFAULT_TTL);
            trackKey(indexKey, key);
            log.debug("event=cache.write outcome=success key={}", key);
        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    public void putInCache(String keyPrefix, String calculatorId, String tenantId,
                           String frequency, int days, Object response) {
        String key = buildKey(keyPrefix, calculatorId, tenantId, frequency, days);
        String indexKey = buildIndexKey(calculatorId, tenantId);
        try {
            redisTemplate.opsForValue().set(key, response, DEFAULT_TTL);
            trackKey(indexKey, key);
            log.debug("event=cache.write outcome=success key={}", key);
        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    // ================================================================
    // Event-driven invalidation
    // ================================================================

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRunCompleted(RunCompletedEvent event) {
        evictForCalculator(event.getRun());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreached(SlaBreachedEvent event) {
        evictForCalculator(event.getRun());
    }

    private void evictForCalculator(CalculatorRun run) {
        String indexKey = buildIndexKey(run.getCalculatorId(), run.getTenantId());
        try {
            Set<Object> indexedKeys = redisTemplate.opsForSet().members(indexKey);
            if (indexedKeys != null && !indexedKeys.isEmpty()) {
                List<String> keysToDelete = indexedKeys.stream()
                        .map(Object::toString)
                        .collect(Collectors.toList());
                keysToDelete.add(indexKey);
                redisTemplate.delete(keysToDelete);
                meterRegistry.counter(CACHE_ANALYTICS_EVICTION).increment();
                log.debug("event=cache.evict outcome=success calculatorId={} keysEvicted={}",
                        run.getCalculatorId(), indexedKeys.size());
            }
        } catch (Exception e) {
            log.warn("event=cache.evict outcome=failure calculatorId={} error={}",
                    run.getCalculatorId(), e.getMessage());
        }
    }

    // ================================================================
    // Key builders
    // ================================================================

    private String buildKey(String prefix, String calculatorId, String tenantId, int days) {
        return ANALYTICS_PREFIX + prefix + ":" + calculatorId + ":" + tenantId + ":" + days;
    }

    private String buildKey(String prefix, String calculatorId, String tenantId,
                            String frequency, int days) {
        return ANALYTICS_PREFIX + prefix + ":" + calculatorId + ":" + tenantId
                + ":" + frequency + ":" + days;
    }

    private String buildIndexKey(String calculatorId, String tenantId) {
        return ANALYTICS_INDEX_PREFIX + calculatorId + ":" + tenantId;
    }

    private void trackKey(String indexKey, String key) {
        redisTemplate.opsForSet().add(indexKey, key);
        redisTemplate.expire(indexKey, INDEX_TTL);
    }

    private <T> T convertCached(Object cached, Class<T> responseType) {
        if (responseType.isInstance(cached)) {
            return responseType.cast(cached);
        }
        return objectMapper.convertValue(cached, responseType);
    }
}
