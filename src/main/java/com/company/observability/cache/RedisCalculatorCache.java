package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorStatusResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * Redis cache with proper enum support
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCalculatorCache {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    // Cache key prefixes
    private static final String RECENT_RUNS_ZSET = "obs:runs:zset:";
    private static final String STATUS_RESPONSE_HASH = "obs:status:hash:";
    private static final String RUNNING_SET = "obs:running";
    private static final String ACTIVE_BLOOM = "obs:active:bloom";

    // ================================================================
    // Cache key builders with enum support
    // ================================================================

    private String buildRecentRunsKey(String calculatorId, Frequency frequency) {
        return RECENT_RUNS_ZSET + calculatorId + ":" + frequency.name();
    }

    private String buildStatusHashKey(String calculatorId, Frequency frequency) {
        return STATUS_RESPONSE_HASH + calculatorId + ":" + frequency.name();
    }

    // ================================================================
    // WRITE-THROUGH: Cache data as it's written to database
    // ================================================================

    public void cacheRunOnWrite(CalculatorRun run) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Frequency frequency = run.getFrequency();
            String key = buildRecentRunsKey(run.getCalculatorId(), frequency);

            // Add to sorted set with timestamp as score
            redisTemplate.opsForZSet().add(key, run, run.getCreatedAt().toEpochMilli());

            // Keep only last 100 runs per calculator per frequency
            redisTemplate.opsForZSet().removeRange(key, 0, -101);

            // Dynamic TTL based on status and frequency
            Duration ttl = calculateSmartTTL(run, frequency);
            redisTemplate.expire(key, ttl);

            // Track if running
            RunStatus status = run.getStatus();
            String runningKey = run.getCalculatorId() + ":" + frequency.name();
            if (status == RunStatus.RUNNING) {
                redisTemplate.opsForSet().add(RUNNING_SET, runningKey);
                redisTemplate.expire(RUNNING_SET, Duration.ofHours(2));
            } else {
                redisTemplate.opsForSet().remove(RUNNING_SET, runningKey);
            }

            // Add to bloom filter
            addToBloomFilter(run.getCalculatorId());

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "write")
                    .tag("tier", "zset")
                    .register(meterRegistry));

            log.debug("event=cache.write outcome=success runId={} ttl={}", run.getRunId(), ttl);

        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "write")
                    .tag("tier", "zset")
                    .register(meterRegistry));
            log.warn("event=cache.write outcome=failure runId={} error={}", run.getRunId(), e.getMessage());
        }
    }

    /**
     * Update existing run in cache (for SLA breach updates)
     */
    public void updateRunInCache(CalculatorRun run) {
        try {
            Frequency frequency = run.getFrequency();
            String key = buildRecentRunsKey(run.getCalculatorId(), frequency);

            // Remove old version by value (safer than score-based removal)
            redisTemplate.opsForZSet().remove(key, run);

            // Add updated version
            redisTemplate.opsForZSet().add(key, run, run.getCreatedAt().toEpochMilli());

            log.debug("event=cache.write outcome=success action=update runId={}", run.getRunId());

        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure action=update runId={} error={}", run.getRunId(), e.getMessage());
        }
    }

    private Duration calculateSmartTTL(CalculatorRun run, Frequency frequency) {
        RunStatus status = run.getStatus();

        if (status == RunStatus.RUNNING) {
            return Duration.ofMinutes(5);
        }

        long minutesSinceCompletion = Duration.between(
                run.getEndTime() != null ? run.getEndTime() : run.getCreatedAt(),
                Instant.now()
        ).toMinutes();

        if (minutesSinceCompletion < 30) {
            return Duration.ofMinutes(15);
        }

        return frequency == Frequency.MONTHLY
                ? Duration.ofHours(4)
                : Duration.ofHours(1);
    }

    // ================================================================
    // READ-THROUGH: Get from cache or signal cache miss
    // ================================================================

    public Optional<List<CalculatorRun>> getRecentRuns(
            String calculatorId, Frequency frequency, int limit) {

        String key = buildRecentRunsKey(calculatorId, frequency);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Set<Object> runs = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);

            if (runs == null || runs.isEmpty()) {
                sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                        .tag("operation", "read")
                        .tag("tier", "zset")
                        .register(meterRegistry));
                return Optional.empty();
            }

            List<CalculatorRun> result = new ArrayList<>();
            for (Object obj : runs) {
                if (obj instanceof CalculatorRun) {
                    result.add((CalculatorRun) obj);
                }
            }

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "read")
                    .tag("tier", "zset")
                    .register(meterRegistry));

            log.debug("event=cache.read outcome=hit calculatorId={} frequency={} count={}",
                    calculatorId, frequency, result.size());
            return Optional.of(result);

        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "read")
                    .tag("tier", "zset")
                    .register(meterRegistry));
            log.warn("event=cache.read outcome=failure calculatorId={} error={}", calculatorId, e.getMessage());
            return Optional.empty();
        }
    }

    public void cacheStatusResponse(
            String calculatorId, Frequency frequency,
            int historyLimit, CalculatorStatusResponse response) {

        String hashKey = buildStatusHashKey(calculatorId, frequency);
        String field = String.valueOf(historyLimit);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            RunStatus currentStatus = response.current() != null
                    ? RunStatus.fromString(response.current().status())
                    : RunStatus.SUCCESS;

            Duration ttl = currentStatus == RunStatus.RUNNING
                    ? Duration.ofSeconds(30)
                    : Duration.ofSeconds(60);

            redisTemplate.opsForHash().put(hashKey, field, response);
            redisTemplate.expire(hashKey, ttl);

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "write")
                    .tag("tier", "hash")
                    .register(meterRegistry));

            log.debug("event=cache.write outcome=success calculatorId={} frequency={} historyLimit={} ttl={}",
                    calculatorId, frequency, historyLimit, ttl);

        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "write")
                    .tag("tier", "hash")
                    .register(meterRegistry));
            log.warn("event=cache.write outcome=failure calculatorId={} error={}", calculatorId, e.getMessage());
        }
    }

    public Optional<CalculatorStatusResponse> getStatusResponse(
            String calculatorId, Frequency frequency, int historyLimit) {

        String hashKey = buildStatusHashKey(calculatorId, frequency);
        String field = String.valueOf(historyLimit);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Object cached = redisTemplate.opsForHash().get(hashKey, field);

            if (cached instanceof CalculatorStatusResponse) {
                sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                        .tag("operation", "read")
                        .tag("tier", "hash")
                        .register(meterRegistry));
                log.debug("event=cache.read outcome=hit calculatorId={} frequency={} historyLimit={}",
                        calculatorId, frequency, historyLimit);
                return Optional.of((CalculatorStatusResponse) cached);
            }

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "read")
                    .tag("tier", "hash")
                    .register(meterRegistry));
            return Optional.empty();

        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "read")
                    .tag("tier", "hash")
                    .register(meterRegistry));
            log.warn("event=cache.read outcome=failure calculatorId={} error={}", calculatorId, e.getMessage());
            return Optional.empty();
        }
    }

    public void evictStatusResponse(String calculatorId, Frequency frequency) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Evict all history limits for this calculator+frequency (hash delete)
            String hashKey = buildStatusHashKey(calculatorId, frequency);
            redisTemplate.delete(hashKey);

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "evict")
                    .tag("tier", "hash")
                    .register(meterRegistry));

            log.debug("event=cache.evict outcome=success calculatorId={} frequency={}", calculatorId, frequency);
        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "evict")
                    .tag("tier", "hash")
                    .register(meterRegistry));
            log.warn("event=cache.evict outcome=failure calculatorId={} error={}", calculatorId, e.getMessage());
        }
    }

    public void evictAllFrequencies(String calculatorId) {
        try {
            evictStatusResponse(calculatorId, Frequency.DAILY);
            evictStatusResponse(calculatorId, Frequency.MONTHLY);
            log.debug("event=cache.evict outcome=success calculatorId={} scope=all_frequencies", calculatorId);
        } catch (Exception e) {
            log.warn("event=cache.evict outcome=failure calculatorId={} scope=all_frequencies error={}",
                    calculatorId, e.getMessage());
        }
    }

    public void evictRecentRuns(String calculatorId, Frequency frequency) {
        try {
            String key = buildRecentRunsKey(calculatorId, frequency);
            redisTemplate.delete(key);
            log.debug("event=cache.evict outcome=success calculatorId={} frequency={} tier=zset",
                    calculatorId, frequency);
        } catch (Exception e) {
            log.warn("event=cache.evict outcome=failure calculatorId={} frequency={} tier=zset error={}",
                    calculatorId, frequency, e.getMessage());
        }
    }

    // ================================================================
    // BATCH OPERATIONS
    // ================================================================

    @SuppressWarnings("unchecked")
    public Map<String, CalculatorStatusResponse> getBatchStatusResponses(
            List<String> calculatorIds, Frequency frequency, int historyLimit) {

        Map<String, CalculatorStatusResponse> results = new HashMap<>();
        String field = String.valueOf(historyLimit);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            RedisSerializer<String> keySerializer =
                    (RedisSerializer<String>) redisTemplate.getKeySerializer();
            RedisSerializer<Object> hashValueSerializer =
                    (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
            RedisSerializer<String> hashKeySerializer =
                    (RedisSerializer<String>) redisTemplate.getHashKeySerializer();

            List<Object> pipelined = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (String calcId : calculatorIds) {
                    String hashKey = buildStatusHashKey(calcId, frequency);
                    byte[] keyBytes = keySerializer.serialize(hashKey);
                    byte[] fieldBytes = hashKeySerializer.serialize(field);
                    if (keyBytes != null && fieldBytes != null) {
                        connection.hashCommands().hGet(keyBytes, fieldBytes);
                    }
                }
                return null;
            }, hashValueSerializer);

            if (pipelined != null) {
                for (int i = 0; i < calculatorIds.size(); i++) {
                    Object cached = pipelined.get(i);
                    if (cached instanceof CalculatorStatusResponse) {
                        results.put(calculatorIds.get(i), (CalculatorStatusResponse) cached);
                    }
                }
            }

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "read_batch")
                    .tag("tier", "hash")
                    .register(meterRegistry));

            log.debug("event=cache.read outcome=success operation=batch hit={} total={} frequency={} historyLimit={}",
                    results.size(), calculatorIds.size(), frequency, historyLimit);

        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "read_batch")
                    .tag("tier", "hash")
                    .register(meterRegistry));
            log.warn("event=cache.read outcome=failure operation=batch error={}", e.getMessage());
        }

        return results;
    }

    @SuppressWarnings("unchecked")
    public void cacheBatchStatusResponses(
            Map<String, CalculatorStatusResponse> responses,
            Frequency frequency, int historyLimit) {

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            RedisSerializer<String> keySerializer =
                    (RedisSerializer<String>) redisTemplate.getKeySerializer();

            RedisSerializer<Object> hashValueSerializer =
                    (RedisSerializer<Object>) redisTemplate.getHashValueSerializer();
            RedisSerializer<String> hashKeySerializer =
                    (RedisSerializer<String>) redisTemplate.getHashKeySerializer();

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                responses.forEach((calcId, response) -> {
                    String hashKey = buildStatusHashKey(calcId, frequency);
                    String field = String.valueOf(historyLimit);

                    RunStatus currentStatus = response.current() != null
                            ? RunStatus.fromString(response.current().status())
                            : RunStatus.SUCCESS;

                    Duration ttl = currentStatus == RunStatus.RUNNING
                            ? Duration.ofSeconds(30)
                            : Duration.ofSeconds(60);

                    byte[] keyBytes = keySerializer.serialize(hashKey);
                    byte[] fieldBytes = hashKeySerializer.serialize(field);
                    byte[] valueBytes = hashValueSerializer.serialize(response);

                    if (keyBytes != null && fieldBytes != null && valueBytes != null) {
                        connection.hashCommands().hSet(keyBytes, fieldBytes, valueBytes);
                        connection.keyCommands().expire(keyBytes, ttl.getSeconds());
                    }

                });
                return null;
            });

            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "write_batch")
                    .tag("tier", "hash")
                    .register(meterRegistry));

            log.debug("event=cache.write outcome=success operation=batch count={}", responses.size());

        } catch (Exception e) {
            sample.stop(Timer.builder(CACHE_REDIS_DURATION)
                    .tag("operation", "write_batch")
                    .tag("tier", "hash")
                    .register(meterRegistry));
            log.warn("event=cache.write outcome=failure operation=batch error={}", e.getMessage());
        }
    }

    // ================================================================
    // BLOOM FILTER & RUNNING STATE
    // ================================================================

    public boolean mightExist(String calculatorId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(ACTIVE_BLOOM, calculatorId)
            );
        } catch (Exception e) {
            return true;
        }
    }

    private void addToBloomFilter(String calculatorId) {
        try {
            redisTemplate.opsForSet().add(ACTIVE_BLOOM, calculatorId);
            redisTemplate.expire(ACTIVE_BLOOM, Duration.ofHours(24));
        } catch (Exception e) {
            log.warn("event=cache.write outcome=failure tier=bloom error={}", e.getMessage());
        }
    }

    public boolean isRunning(String calculatorId, Frequency frequency) {
        try {
            String member = calculatorId + ":" + frequency.name();
            return Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(RUNNING_SET, member)
            );
        } catch (Exception e) {
            return false;
        }
    }

    public Set<String> getRunningCalculators() {
        try {
            Set<Object> members = redisTemplate.opsForSet().members(RUNNING_SET);

            if (members == null) {
                return Collections.emptySet();
            }

            Set<String> result = new HashSet<>();
            for (Object member : members) {
                result.add(member.toString());
            }

            return result;

        } catch (Exception e) {
            log.warn("event=cache.read outcome=failure tier=running_set error={}", e.getMessage());
            return Collections.emptySet();
        }
    }

}
