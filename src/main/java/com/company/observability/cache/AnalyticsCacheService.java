package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

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
                return convertCached(cached, responseType);
            }
        } catch (Exception e) {
            log.warn("Redis read failed for key {}, falling back to DB", key, e);
        }
        return null;
    }

    public <T> T getFromCache(String keyPrefix, String calculatorId, String tenantId,
                              String frequency, int days, Class<T> responseType) {
        String key = buildKey(keyPrefix, calculatorId, tenantId, frequency, days);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return convertCached(cached, responseType);
            }
        } catch (Exception e) {
            log.warn("Redis read failed for key {}, falling back to DB", key, e);
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
            log.debug("Cached analytics response: {}", key);
        } catch (Exception e) {
            log.warn("Redis write failed for key {}", key, e);
        }
    }

    public void putInCache(String keyPrefix, String calculatorId, String tenantId,
                           String frequency, int days, Object response) {
        String key = buildKey(keyPrefix, calculatorId, tenantId, frequency, days);
        String indexKey = buildIndexKey(calculatorId, tenantId);
        try {
            redisTemplate.opsForValue().set(key, response, DEFAULT_TTL);
            trackKey(indexKey, key);
            log.debug("Cached analytics response: {}", key);
        } catch (Exception e) {
            log.warn("Redis write failed for key {}", key, e);
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
                log.debug("Evicted {} analytics cache keys for calculator {}",
                        indexedKeys.size(), run.getCalculatorId());
            }
        } catch (Exception e) {
            log.warn("Failed to evict analytics cache for calculator {}",
                    run.getCalculatorId(), e);
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
