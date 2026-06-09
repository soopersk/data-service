package com.company.observability.service;

import com.company.observability.config.AggregationProperties;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.repository.DailyAggregateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculatorProfileServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private DailyAggregateRepository dailyAggregateRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CalculatorProfileService service;

    private static final String KEY = "obs:profile:calc-1:DAILY";
    private final CalculatorProfile profile =
            new CalculatorProfile("calc-1", "DAILY", null, 600_000L, 300, 360, 10);

    @BeforeEach
    void setUp() {
        service = new CalculatorProfileService(
                redisTemplate, objectMapper, dailyAggregateRepository,
                new DurationBasedSlaProperties(), new AggregationProperties(), new SimpleMeterRegistry());
    }

    private String json(CalculatorProfile p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void cacheHit_returnsCachedProfile_withoutDbCall() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(json(profile));

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY);

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
        verify(dailyAggregateRepository, never()).findProfile(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void cacheMiss_readsFromDb_andCaches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfile("calc-1", "DAILY", 30)).thenReturn(profile);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY);

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
        // non-empty profile → long TTL (26h default)
        verify(valueOps).set(eq(KEY), eq(json(profile)), eq(Duration.ofHours(26)));
    }

    @Test
    void emptyProfile_cachedWithShortTtl() {
        CalculatorProfile empty = new CalculatorProfile("calc-1", "DAILY", null, 0, 0, 0, 0);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenReturn(null);
        when(dailyAggregateRepository.findProfile("calc-1", "DAILY", 30)).thenReturn(empty);

        service.getProfile("calc-1", Frequency.DAILY);

        verify(valueOps).set(eq(KEY), eq(json(empty)), eq(Duration.ofMinutes(60)));
    }

    @Test
    void redisReadFailure_fallsBackToDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(KEY)).thenThrow(new RuntimeException("redis down"));
        when(dailyAggregateRepository.findProfile("calc-1", "DAILY", 30)).thenReturn(profile);

        CalculatorProfile result = service.getProfile("calc-1", Frequency.DAILY);

        assertThat(result.avgDurationMs()).isEqualTo(600_000L);
    }

    @Test
    void warm_writesProfileToCache() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.warm(profile);

        verify(valueOps).set(eq(KEY), eq(json(profile)), any(Duration.class));
    }
}
