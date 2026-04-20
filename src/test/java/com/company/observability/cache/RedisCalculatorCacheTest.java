package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.util.TestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisCalculatorCache}.
 *
 * <p>Strategy: Mockito only — verifies that the correct Redis commands are
 * issued with the correct arguments. Smart TTL logic is tested indirectly
 * via {@code cacheRunOnWrite} by capturing the {@code Duration} passed to
 * {@code redisTemplate.expire()}.
 */
@ExtendWith(MockitoExtension.class)
class RedisCalculatorCacheTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ZSetOperations<String, Object> zSetOps;

    @Mock
    private SetOperations<String, Object> setOps;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private RedisCalculatorCache cache;

    @BeforeEach
    void setUp() {
        cache = new RedisCalculatorCache(redisTemplate, new SimpleMeterRegistry());
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    // ---------------------------------------------------------------
    // Smart TTL — tested via EXPIRE argument captured from cacheRunOnWrite
    // ---------------------------------------------------------------

    @Nested
    class SmartTtl {

        @Test
        void running_returns5MinuteTtl() {
            CalculatorRun run = TestFixtures.aRunningRun();

            cache.cacheRunOnWrite(run);

            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate, atLeastOnce()).expire(anyString(), ttlCaptor.capture());
            // The first expire call on the ZSET key should be 5 minutes
            Duration zsetTtl = ttlCaptor.getAllValues().get(0);
            assertThat(zsetTtl).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        void completedRecently_returns15MinuteTtl() {
            // endTime = 10 minutes ago → still within the 30-minute "recent" window
            CalculatorRun run = completedRun(CalculatorFrequency.DAILY,
                    Instant.now().minus(Duration.ofMinutes(10)));

            cache.cacheRunOnWrite(run);

            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate, atLeastOnce()).expire(anyString(), ttlCaptor.capture());
            Duration zsetTtl = ttlCaptor.getAllValues().get(0);
            assertThat(zsetTtl).isEqualTo(Duration.ofMinutes(15));
        }

        @Test
        void oldCompletedDaily_returns1HourTtl() {
            CalculatorRun run = completedRun(CalculatorFrequency.DAILY,
                    Instant.now().minus(Duration.ofHours(2)));

            cache.cacheRunOnWrite(run);

            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate, atLeastOnce()).expire(anyString(), ttlCaptor.capture());
            Duration zsetTtl = ttlCaptor.getAllValues().get(0);
            assertThat(zsetTtl).isEqualTo(Duration.ofHours(1));
        }

        @Test
        void oldCompletedMonthly_returns4HourTtl() {
            CalculatorRun run = completedRun(CalculatorFrequency.MONTHLY,
                    Instant.now().minus(Duration.ofHours(2)));

            cache.cacheRunOnWrite(run);

            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate, atLeastOnce()).expire(anyString(), ttlCaptor.capture());
            Duration zsetTtl = ttlCaptor.getAllValues().get(0);
            assertThat(zsetTtl).isEqualTo(Duration.ofHours(4));
        }

        @Test
        void nullEndTime_fallsBackToCreatedAt_forTtlCalculation() {
            // endTime = null, createdAt = 2h ago → should pick the 1h (DAILY old) bucket
            CalculatorRun run = CalculatorRun.builder()
                    .runId(TestFixtures.DEFAULT_RUN_ID)
                    .calculatorId(TestFixtures.DEFAULT_CALC_ID)
                    .tenantId(TestFixtures.DEFAULT_TENANT_ID)
                    .frequency(CalculatorFrequency.DAILY)
                    .reportingDate(TestFixtures.DEFAULT_DATE)
                    .startTime(TestFixtures.DEFAULT_START)
                    .status(RunStatus.SUCCESS)
                    .endTime(null)
                    .createdAt(Instant.now().minus(Duration.ofHours(2)))
                    .build();

            cache.cacheRunOnWrite(run);

            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(redisTemplate, atLeastOnce()).expire(anyString(), ttlCaptor.capture());
            Duration zsetTtl = ttlCaptor.getAllValues().get(0);
            assertThat(zsetTtl).isEqualTo(Duration.ofHours(1));
        }

        private CalculatorRun completedRun(CalculatorFrequency frequency, Instant endTime) {
            return CalculatorRun.builder()
                    .runId(TestFixtures.DEFAULT_RUN_ID)
                    .calculatorId(TestFixtures.DEFAULT_CALC_ID)
                    .tenantId(TestFixtures.DEFAULT_TENANT_ID)
                    .frequency(frequency)
                    .reportingDate(TestFixtures.DEFAULT_DATE)
                    .startTime(TestFixtures.DEFAULT_START)
                    .status(RunStatus.SUCCESS)
                    .endTime(endTime)
                    .createdAt(TestFixtures.DEFAULT_START)
                    .build();
        }
    }

    // ---------------------------------------------------------------
    // cacheRunOnWrite — running set membership
    // ---------------------------------------------------------------

    @Nested
    class RunningSetMembership {

        @Test
        void runningRun_addsToRunningSet() {
            CalculatorRun run = TestFixtures.aRunningRun();

            cache.cacheRunOnWrite(run);

            String expectedMember = run.getCalculatorId() + ":" + run.getTenantId() + ":DAILY";
            verify(setOps).add(eq("obs:running"), eq(expectedMember));
        }

        @Test
        void completedRun_removesFromRunningSet() {
            CalculatorRun run = TestFixtures.aCompletedRun();

            cache.cacheRunOnWrite(run);

            String expectedMember = run.getCalculatorId() + ":" + run.getTenantId() + ":DAILY";
            verify(setOps).remove(eq("obs:running"), eq(expectedMember));
        }
    }

    // ---------------------------------------------------------------
    // cacheRunOnWrite — ZSet trim
    // ---------------------------------------------------------------

    @Test
    void cacheRunOnWrite_trimsZsetToTop100() {
        CalculatorRun run = TestFixtures.aRunningRun();

        cache.cacheRunOnWrite(run);

        // removeRange(key, 0, -101) keeps the top 100 by score (newest)
        verify(zSetOps).removeRange(anyString(), eq(0L), eq(-101L));
    }

    // ---------------------------------------------------------------
    // getRecentRuns — exception resilience
    // ---------------------------------------------------------------

    @Test
    void getRecentRuns_onRedisException_returnsEmpty() {
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        Optional<java.util.List<CalculatorRun>> result =
                cache.getRecentRuns("calc-1", "tenant-1", CalculatorFrequency.DAILY, 5);

        assertThat(result).isEmpty();
    }

    @Test
    void getRecentRuns_whenNullReturned_returnsEmpty() {
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(null);

        Optional<java.util.List<CalculatorRun>> result =
                cache.getRecentRuns("calc-1", "tenant-1", CalculatorFrequency.DAILY, 5);

        assertThat(result).isEmpty();
    }

    @Test
    void getRecentRuns_whenEmptySetReturned_returnsEmpty() {
        when(zSetOps.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());

        Optional<java.util.List<CalculatorRun>> result =
                cache.getRecentRuns("calc-1", "tenant-1", CalculatorFrequency.DAILY, 5);

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------
    // mightExist — fail-open bloom filter
    // ---------------------------------------------------------------

    @Test
    void mightExist_onRedisException_returnsTrue() {
        when(setOps.isMember(anyString(), any())).thenThrow(new RuntimeException("timeout"));

        boolean result = cache.mightExist("calc-1");

        assertThat(result).isTrue();
    }

    @Test
    void mightExist_whenNotInBloom_returnsFalse() {
        when(setOps.isMember(eq("obs:active:bloom"), eq("calc-1"))).thenReturn(false);

        boolean result = cache.mightExist("calc-1");

        assertThat(result).isFalse();
    }

    // ---------------------------------------------------------------
    // evictAllFrequencies
    // ---------------------------------------------------------------

    @Test
    void evictAllFrequencies_deletesBothFrequencyHashKeys() {
        cache.evictAllFrequencies("calc-1", "tenant-1");

        // Should delete the status hash key for both DAILY and MONTHLY
        verify(redisTemplate).delete(eq("obs:status:hash:calc-1:tenant-1:DAILY"));
        verify(redisTemplate).delete(eq("obs:status:hash:calc-1:tenant-1:MONTHLY"));
    }

    // ---------------------------------------------------------------
    // isRunning
    // ---------------------------------------------------------------

    @Test
    void isRunning_onRedisException_returnsFalse() {
        when(setOps.isMember(anyString(), any())).thenThrow(new RuntimeException("timeout"));

        boolean result = cache.isRunning("calc-1", "tenant-1", CalculatorFrequency.DAILY);

        assertThat(result).isFalse();
    }

    // ---------------------------------------------------------------
    // updateRunInCache
    // ---------------------------------------------------------------

    @Test
    void updateRunInCache_removesOldAndAddsNew() {
        CalculatorRun run = TestFixtures.aRunningRun();

        cache.updateRunInCache(run);

        String expectedKey = "obs:runs:zset:calc-1:tenant-1:DAILY";
        verify(zSetOps).remove(eq(expectedKey), eq(run));
        verify(zSetOps).add(eq(expectedKey), eq(run), anyDouble());
    }
}
