package com.company.observability.cache;

import com.company.observability.dto.response.RegionalBatchStatusResponse;
import com.company.observability.dto.response.SlaIndicator;
import com.company.observability.dto.response.TimeReference;
import com.company.observability.repository.CalculatorRunRepository.RegionalBatchTiming;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegionalBatchCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private RegionalBatchCacheService service;

    private static final LocalDate DATE = LocalDate.of(2026, 4, 17);

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        service = new RegionalBatchCacheService(redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── History cache ────────────────────────────────────────────────

    @Test
    void putHistory_storesWithTwentyFourHourTtl() {
        List<RegionalBatchTiming> history = List.of(
                new RegionalBatchTiming("AMER", DATE.minusDays(1),
                        Instant.parse("2026-04-16T05:00:00Z"),
                        Instant.parse("2026-04-16T17:00:00Z"))
        );

        service.putHistory(DATE,history);

        verify(valueOps).set(
                eq("obs:analytics:regional-batch:history:2026-04-17"),
                any(),
                eq(Duration.ofHours(24)));
    }

    @Test
    void getHistory_cacheMiss_returnsNull() {
        when(valueOps.get("obs:analytics:regional-batch:history:2026-04-17"))
                .thenReturn(null);

        List<RegionalBatchTiming> result = service.getHistory(DATE);

        assertThat(result).isNull();
    }

    @Test
    void getHistory_cacheHit_returnsDeserializedTimings() {
        // Simulate what Jackson returns when deserializing from Redis: list of LinkedHashMaps
        List<RegionalBatchTiming> stored = List.of(
                new RegionalBatchTiming("AMER", DATE.minusDays(1),
                        Instant.parse("2026-04-16T05:00:00Z"),
                        Instant.parse("2026-04-16T17:00:00Z"))
        );
        // Store and retrieve using the service's own serialization to simulate the round-trip
        service.putHistory(DATE,stored);
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(valueOps).set(any(), captor.capture(), any());

        Object captured = captor.getValue();
        when(valueOps.get("obs:analytics:regional-batch:history:2026-04-17"))
                .thenReturn(captured);

        List<RegionalBatchTiming> result = service.getHistory(DATE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).region()).isEqualTo("AMER");
    }

    @Test
    void getHistory_redisException_returnsNull() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("Redis down"));

        assertThat(service.getHistory(DATE)).isNull();
    }

    @Test
    void putHistory_redisException_doesNotThrow() {
        doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(any(), any(), any());

        assertThatNoException().isThrownBy(
                () -> service.putHistory(DATE, List.of()));
    }

    // ── Status response cache ────────────────────────────────────────

    @Test
    void getStatusResponse_cacheMiss_returnsNull() {
        when(valueOps.get("obs:analytics:regional-batch:status:2026-04-17"))
                .thenReturn(null);

        assertThat(service.getStatusResponse(DATE)).isNull();
    }

    @Test
    void getStatusResponse_cacheHit_returnsDeserializedResponse() {
        RegionalBatchStatusResponse response = minimalResponse(10, 10, 0, 0);

        // Capture what was stored
        service.putStatusResponse(DATE,response);
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(valueOps).set(any(), captor.capture(), any());

        when(valueOps.get("obs:analytics:regional-batch:status:2026-04-17"))
                .thenReturn(captor.getValue());

        RegionalBatchStatusResponse result = service.getStatusResponse(DATE);

        assertThat(result).isNotNull();
        assertThat(result.totalRegions()).isEqualTo(10);
        assertThat(result.completedRegions()).isEqualTo(10);
    }

    @Test
    void getStatusResponse_redisException_returnsNull() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("Redis down"));

        assertThat(service.getStatusResponse(DATE)).isNull();
    }

    @Test
    void putStatusResponse_redisException_doesNotThrow() {
        doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(any(), any(), any());

        assertThatNoException().isThrownBy(
                () -> service.putStatusResponse(DATE, minimalResponse(10, 10, 0, 0)));
    }

    // ── Smart TTL selection ──────────────────────────────────────────

    @Test
    void determineTtl_allCompleteNoFailures_returnsFourHours() {
        // 10 total, 10 completed, 0 running, 0 failed → TERMINAL_CLEAN
        RegionalBatchStatusResponse r = minimalResponse(10, 10, 0, 0);
        assertThat(service.determineTtl(r)).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void determineTtl_allTerminalWithFailures_returnsFiveMinutes() {
        // 10 total, 8 completed, 0 running, 2 failed → TERMINAL_WITH_FAILURES
        RegionalBatchStatusResponse r = minimalResponse(10, 8, 0, 2);
        assertThat(service.determineTtl(r)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void determineTtl_activelyRunning_returnsThirtySeconds() {
        // 10 total, 5 completed, 2 running, 0 failed → ACTIVE
        RegionalBatchStatusResponse r = minimalResponse(10, 5, 2, 0);
        assertThat(service.determineTtl(r)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void determineTtl_nothingStarted_returnsSixtySeconds() {
        // 10 total, 0 completed, 0 running, 0 failed → NOT_STARTED
        RegionalBatchStatusResponse r = minimalResponse(10, 0, 0, 0);
        assertThat(service.determineTtl(r)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void determineTtl_partialWithNotStarted_usesActiveWhenRunning() {
        // 10 total, 3 completed, 1 running, 0 failed → 6 NOT_STARTED → ACTIVE (has running)
        RegionalBatchStatusResponse r = minimalResponse(10, 3, 1, 0);
        assertThat(service.determineTtl(r)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void putStatusResponse_usesCorrectTtlForTerminalClean() {
        RegionalBatchStatusResponse r = minimalResponse(10, 10, 0, 0);

        service.putStatusResponse(DATE,r);

        verify(valueOps).set(any(), any(), eq(Duration.ofHours(4)));
    }

    @Test
    void putStatusResponse_usesCorrectTtlForActive() {
        RegionalBatchStatusResponse r = minimalResponse(10, 5, 2, 0);

        service.putStatusResponse(DATE,r);

        verify(valueOps).set(any(), any(), eq(Duration.ofSeconds(30)));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Minimal response with controllable region counts.
     * NOT_STARTED = totalRegions - completed - running - failed.
     */
    private RegionalBatchStatusResponse minimalResponse(
            int total, int completed, int running, int failed) {
        return new RegionalBatchStatusResponse(
                DATE,
                "Fri 17 Apr 2026",
                new SlaIndicator(Instant.parse("2026-04-17T15:45:00Z"), false),
                new TimeReference(Instant.parse("2026-04-17T03:00:00Z"), "ASIA", false),
                new TimeReference(Instant.parse("2026-04-17T15:30:00Z"), "EURO", false),
                total, completed, running, failed,
                List.of(),
                List.of()
        );
    }
}
