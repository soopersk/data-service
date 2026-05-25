package com.company.observability.service;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.repository.DailyAggregateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Cache-aside access to slowly-changing {@link CalculatorProfile}s (avg runtime, avg start/end).
 *
 * <p>Profiles are warmed nightly by {@code DailyAggregationJob}; on a cache miss the profile is
 * read once from {@code calculator_sli_daily} and cached. This removes the per-run-start DB
 * query that the SLA baseline and estimated start/end previously incurred.
 *
 * <p>Resilient like {@code AnalyticsCacheService}: Redis failures degrade to a DB read and
 * never throw.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorProfileService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DailyAggregateRepository dailyAggregateRepository;
    private final DurationBasedSlaProperties slaProperties;
    private final AggregationProperties aggregationProperties;
    private final MeterRegistry meterRegistry;

    private static final String PROFILE_PREFIX = "obs:profile:";

    /** Cache-aside read. Never throws; falls back to a DB read (and a zero-sample profile on error). */
    public CalculatorProfile getProfile(String calculatorName, Frequency frequency) {
        String key = key(calculatorName, frequency);

        CalculatorProfile cached = readFromCache(key);
        if (cached != null) {
            meterRegistry.counter("obs.profile.cache", "result", "hit").increment();
            return cached;
        }
        meterRegistry.counter("obs.profile.cache", "result", "miss").increment();

        CalculatorProfile profile = dailyAggregateRepository.findProfile(
                calculatorName, frequency.name(), slaProperties.lookbackDays(frequency));
        writeToCache(key, profile);
        return profile;
    }

    /** Warm a precomputed profile into the cache (called by the nightly job). */
    public void warm(CalculatorProfile profile) {
        writeToCache(key(profile.calculatorName(),
                Frequency.from(profile.frequency())), profile);
    }

    private CalculatorProfile readFromCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, CalculatorProfile.class);
            }
        } catch (Exception e) {
            log.warn("event=profile.cache.read outcome=failure key={} error={}", key, e.getMessage());
        }
        return null;
    }

    private void writeToCache(String key, CalculatorProfile profile) {
        // Short TTL for "no history yet" so newly-active calculators are picked up sooner.
        Duration ttl = profile.totalRuns() > 0
                ? Duration.ofHours(aggregationProperties.getProfileCacheTtlHours())
                : Duration.ofMinutes(aggregationProperties.getEmptyProfileCacheTtlMinutes());
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(profile), ttl);
        } catch (Exception e) {
            log.warn("event=profile.cache.write outcome=failure key={} error={}", key, e.getMessage());
        }
    }

    private String key(String calculatorName, Frequency frequency) {
        return PROFILE_PREFIX + calculatorName + ":" + frequency.name();
    }
}
