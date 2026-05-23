package com.company.observability.cache;

import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.company.observability.cache.CalculatorStateCacheService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalculatorStateCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private CalculatorStateCacheService service;

    private static final LocalDate DATE  = LocalDate.of(2026, 5, 1);
    private static final String    FREQ  = "DAILY";

    @BeforeEach
    void setUp() {
        service = new CalculatorStateCacheService(redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── TTL tiers ─────────────────────────────────────────────────────────────

    @Test
    void determineTtl_emptyRuns_returnsNotStarted() {
        CalculatorEntry entry = new CalculatorEntry("calc", List.of());
        assertThat(service.determineTtl(entry)).isEqualTo(TTL_NOT_STARTED);
    }

    @Test
    void determineTtl_anyRunning_returns30s() {
        CalculatorEntry entry = new CalculatorEntry("calc", List.of(runEntry("RUNNING", false)));
        assertThat(service.determineTtl(entry)).isEqualTo(TTL_ANY_RUNNING);
    }

    @Test
    void determineTtl_slaBreached_returnsTerminalWithFailures() {
        CalculatorEntry entry = new CalculatorEntry("calc", List.of(runEntry("SUCCESS", true)));
        assertThat(service.determineTtl(entry)).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_terminalFailure_returnsTerminalWithFailures() {
        CalculatorEntry entry = new CalculatorEntry("calc", List.of(runEntry("FAILED", false)));
        assertThat(service.determineTtl(entry)).isEqualTo(TTL_TERMINAL_WITH_FAILURES);
    }

    @Test
    void determineTtl_terminalClean_returns4h() {
        CalculatorEntry entry = new CalculatorEntry("calc", List.of(runEntry("SUCCESS", false)));
        assertThat(service.determineTtl(entry)).isEqualTo(TTL_TERMINAL_CLEAN);
    }

    // ── get/put round-trip ────────────────────────────────────────────────────

    @Test
    void putEntries_storesEachWithDynamicTtl() {
        CalculatorEntry entry = new CalculatorEntry("cap", List.of(runEntry("SUCCESS", false)));

        service.putEntries(DATE, FREQ, null, Map.of("cap", entry));

        String expectedKey = "obs:state:cap:" + DATE + ":DAILY:all";
        verify(valueOps).set(eq(expectedKey), eq(entry), eq(TTL_TERMINAL_CLEAN));
    }

    @Test
    void putEntries_withRunNumber_includesRunNumberInKey() {
        CalculatorEntry entry = new CalculatorEntry("cap", List.of(runEntry("SUCCESS", false)));

        service.putEntries(DATE, FREQ, "1", Map.of("cap", entry));

        String expectedKey = "obs:state:cap:" + DATE + ":DAILY:1";
        verify(valueOps).set(eq(expectedKey), eq(entry), eq(TTL_TERMINAL_CLEAN));
    }

    @Test
    void getEntries_cacheHit_returnsEntry() {
        String key = "obs:state:cap:" + DATE + ":DAILY:all";
        CalculatorEntry entry = new CalculatorEntry("cap", List.of());
        when(valueOps.get(key)).thenReturn(entry);

        Map<String, CalculatorEntry> result = service.getEntries(DATE, FREQ, null, List.of("cap"));

        assertThat(result).containsKey("cap");
    }

    @Test
    void getEntries_cacheMiss_returnsEmptyMap() {
        when(valueOps.get(anyString())).thenReturn(null);

        Map<String, CalculatorEntry> result = service.getEntries(DATE, FREQ, null, List.of("cap"));

        assertThat(result).isEmpty();
    }

    @Test
    void getEntries_redisFailure_swallowedAndReturnsMiss() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));

        // Should not throw — best-effort
        Map<String, CalculatorEntry> result = service.getEntries(DATE, FREQ, null, List.of("cap"));

        assertThat(result).isEmpty();
    }

    @Test
    void putEntries_redisFailure_swallowed() {
        CalculatorEntry entry = new CalculatorEntry("cap", List.of(runEntry("SUCCESS", false)));
        doThrow(new RuntimeException("Redis down"))
                .when(valueOps).set(anyString(), any(), any(Duration.class));

        // Should not throw — best-effort
        service.putEntries(DATE, FREQ, null, Map.of("cap", entry));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RunEntry runEntry(String status, boolean slaBreached) {
        return RunEntry.builder()
                .runId("r-1")
                .status(status)
                .slaBreached(slaBreached)
                .isRerun(false)
                .build();
    }
}
