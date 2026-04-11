package com.company.observability.cache;

import com.company.observability.config.RedisCacheConfig;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.dto.response.RunStatusInfo;
import com.company.observability.util.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link RedisCalculatorCache} against a real Redis
 * instance started via Testcontainers.
 *
 * <p>The {@link RedisCacheConfig} reads host/port from {@code System.getenv()},
 * so a {@link TestConfiguration} overrides the connection factory bean to use
 * the properties registered by {@link RedisIntegrationTestBase#registerRedisProperties}.
 *
 * <p>Each test begins with a {@code FLUSHALL} to guarantee isolation.
 */
@SpringBootTest(classes = {RedisCacheConfig.class, RedisCalculatorCache.class})
@Import(RedisCalculatorCacheIntegrationTest.TestRedisConfig.class)
class RedisCalculatorCacheIntegrationTest extends RedisIntegrationTestBase {

    @TestConfiguration
    static class TestRedisConfig {

        @Bean
        @Primary
        LettuceConnectionFactory redisConnectionFactory(
                @Value("${spring.data.redis.host}") String host,
                @Value("${spring.data.redis.port}") int port) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private RedisCalculatorCache cache;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void flushAll() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ---------------------------------------------------------------
    // Round-trip: write → read
    // ---------------------------------------------------------------

    @Test
    void cacheAndRetrieve_roundTrip() {
        CalculatorRun run = TestFixtures.aRunningRun();

        cache.cacheRunOnWrite(run);

        Optional<List<CalculatorRun>> result =
                cache.getRecentRuns(run.getCalculatorId(), run.getTenantId(), CalculatorFrequency.DAILY, 5);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).getRunId()).isEqualTo(run.getRunId());
    }

    // ---------------------------------------------------------------
    // ZSet trim: only top-100 retained
    // ---------------------------------------------------------------

    @Test
    void cacheRunOnWrite_keepsTop100_dropsOldest() {
        // Insert 105 runs with monotonically increasing createdAt scores
        Instant base = Instant.parse("2026-04-10T04:00:00Z");
        for (int i = 0; i < 105; i++) {
            CalculatorRun run = CalculatorRun.builder()
                    .runId("run-" + i)
                    .calculatorId(TestFixtures.DEFAULT_CALC_ID)
                    .tenantId(TestFixtures.DEFAULT_TENANT_ID)
                    .frequency(CalculatorFrequency.DAILY)
                    .reportingDate(TestFixtures.DEFAULT_DATE)
                    .startTime(base)
                    .status(com.company.observability.domain.enums.RunStatus.RUNNING)
                    .slaBreached(false)
                    .createdAt(base.plusSeconds(i))   // unique score per run
                    .build();
            cache.cacheRunOnWrite(run);
        }

        // retrieving 200 to get all; ZSet should have trimmed to 100
        Optional<List<CalculatorRun>> result =
                cache.getRecentRuns(TestFixtures.DEFAULT_CALC_ID, TestFixtures.DEFAULT_TENANT_ID,
                        CalculatorFrequency.DAILY, 200);

        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(100);
        // Newest run (run-104) should be first (descending by score)
        assertThat(result.get().get(0).getRunId()).isEqualTo("run-104");
    }

    // ---------------------------------------------------------------
    // Running set membership
    // ---------------------------------------------------------------

    @Test
    void runningSetMembership_updatesOnStatusChange() {
        CalculatorRun running = TestFixtures.aRunningRun();
        cache.cacheRunOnWrite(running);

        assertThat(cache.isRunning(
                running.getCalculatorId(), running.getTenantId(), CalculatorFrequency.DAILY))
                .isTrue();

        // Now cache the same calculator as completed
        CalculatorRun completed = TestFixtures.aCompletedRun(
                running.getRunId(), running.getCalculatorId(), running.getTenantId());
        cache.cacheRunOnWrite(completed);

        assertThat(cache.isRunning(
                running.getCalculatorId(), running.getTenantId(), CalculatorFrequency.DAILY))
                .isFalse();
    }

    // ---------------------------------------------------------------
    // Smart TTL — key expiry
    // ---------------------------------------------------------------

    @Test
    void smartTtl_runningRun_keyHasTtlOfAtMost5Minutes() {
        CalculatorRun run = TestFixtures.aRunningRun();
        cache.cacheRunOnWrite(run);

        String zsetKey = "obs:runs:zset:" + run.getCalculatorId()
                + ":" + run.getTenantId() + ":DAILY";
        Long ttlSeconds = redisTemplate.getExpire(zsetKey, TimeUnit.SECONDS);

        assertThat(ttlSeconds).isNotNull()
                .isGreaterThan(0)
                .isLessThanOrEqualTo(300); // 5 minutes
    }

    // ---------------------------------------------------------------
    // updateRunInCache — replaces entry, ZSet size unchanged
    // ---------------------------------------------------------------

    @Test
    void updateRunInCache_replacesEntryPreservingZsetSize() {
        CalculatorRun run = TestFixtures.aRunningRun();
        cache.cacheRunOnWrite(run);

        // Update: mark as breached (mutate the object)
        run.setSlaBreached(true);
        run.setSlaBreachReason("Still running past SLA deadline");
        cache.updateRunInCache(run);

        String zsetKey = "obs:runs:zset:" + run.getCalculatorId()
                + ":" + run.getTenantId() + ":DAILY";
        Long size = redisTemplate.opsForZSet().size(zsetKey);
        assertThat(size).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // Bloom filter
    // ---------------------------------------------------------------

    @Test
    void bloomFilter_mightExist_trueAfterCachingRun() {
        CalculatorRun run = TestFixtures.aRunningRun();
        cache.cacheRunOnWrite(run);

        assertThat(cache.mightExist(run.getCalculatorId())).isTrue();
    }

    @Test
    void bloomFilter_mightExist_falseForUnknownCalculator() {
        // Nothing cached — bloom filter set is empty
        assertThat(cache.mightExist("never-cached-calc")).isFalse();
    }

    // ---------------------------------------------------------------
    // Batch status responses — pipelined round-trip
    // ---------------------------------------------------------------

    @Test
    void batchStatusResponsePipeline_roundTrip() {
        CalculatorStatusResponse r1 = statusResponse("calc-1");
        CalculatorStatusResponse r2 = statusResponse("calc-2");

        cache.cacheBatchStatusResponses(
                Map.of("calc-1", r1, "calc-2", r2),
                TestFixtures.DEFAULT_TENANT_ID, CalculatorFrequency.DAILY, 5);

        Map<String, CalculatorStatusResponse> hits = cache.getBatchStatusResponses(
                List.of("calc-1", "calc-2"),
                TestFixtures.DEFAULT_TENANT_ID, CalculatorFrequency.DAILY, 5);

        assertThat(hits).containsKeys("calc-1", "calc-2");
        assertThat(hits.get("calc-1").calculatorName()).isEqualTo("Calculator calc-1");
        assertThat(hits.get("calc-2").calculatorName()).isEqualTo("Calculator calc-2");
    }

    @Test
    void batchStatusResponsePipeline_missingId_notInResult() {
        cache.cacheBatchStatusResponses(
                Map.of("calc-1", statusResponse("calc-1")),
                TestFixtures.DEFAULT_TENANT_ID, CalculatorFrequency.DAILY, 5);

        Map<String, CalculatorStatusResponse> hits = cache.getBatchStatusResponses(
                List.of("calc-1", "calc-missing"),
                TestFixtures.DEFAULT_TENANT_ID, CalculatorFrequency.DAILY, 5);

        assertThat(hits).containsKey("calc-1");
        assertThat(hits).doesNotContainKey("calc-missing");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static CalculatorStatusResponse statusResponse(String calculatorId) {
        RunStatusInfo current = new RunStatusInfo(
                "run-" + calculatorId, "RUNNING",
                Instant.parse("2026-04-10T05:00:00Z"),
                null, null, null, null, null, null, false, null);
        return new CalculatorStatusResponse(
                "Calculator " + calculatorId,
                Instant.now(), current, List.of());
    }
}
