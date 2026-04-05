package com.company.observability.cache;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.event.RunCompletedEvent;
import com.company.observability.event.RunStartedEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.util.SlaEvaluationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private SetOperations<String, Object> setOperations;

    private AnalyticsCacheService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsCacheService(redisTemplate, new ObjectMapper(), new SimpleMeterRegistry());
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void onRunStarted_evictsOnlyRunPerfKeys() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String indexKey = "obs:analytics:index:calc-1:tenant-a";
        Set<Object> indexedKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:run-perf:calc-1:tenant-a:DAILY:30",
                "obs:analytics:runtime:calc-1:tenant-a:DAILY:30",
                "obs:analytics:trends:calc-1:tenant-a:30"
        ));

        when(setOperations.members(indexKey)).thenReturn(indexedKeys);

        service.onRunStarted(new RunStartedEvent(run));

        verify(redisTemplate).delete(List.of("obs:analytics:run-perf:calc-1:tenant-a:DAILY:30"));

        var removeCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(setOperations).remove(eq(indexKey), removeCaptor.capture());
        assertArrayEquals(new Object[]{"obs:analytics:run-perf:calc-1:tenant-a:DAILY:30"}, removeCaptor.getValue());
    }

    @Test
    void onRunCompleted_andOnSlaBreached_evictAllIndexedAnalyticsKeys() {
        CalculatorRun run = run("calc-1", "tenant-a");
        String indexKey = "obs:analytics:index:calc-1:tenant-a";
        Set<Object> indexedKeys = new LinkedHashSet<>(List.of(
                "obs:analytics:run-perf:calc-1:tenant-a:DAILY:30",
                "obs:analytics:runtime:calc-1:tenant-a:DAILY:30"
        ));

        when(setOperations.members(indexKey)).thenReturn(indexedKeys);

        service.onRunCompleted(new RunCompletedEvent(run));
        verify(redisTemplate).delete(List.of(
                "obs:analytics:run-perf:calc-1:tenant-a:DAILY:30",
                "obs:analytics:runtime:calc-1:tenant-a:DAILY:30",
                indexKey
        ));

        reset(redisTemplate, setOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(indexKey)).thenReturn(indexedKeys);

        service.onSlaBreached(new SlaBreachedEvent(run, new SlaEvaluationResult(true, "b", "HIGH")));
        verify(redisTemplate).delete(List.of(
                "obs:analytics:run-perf:calc-1:tenant-a:DAILY:30",
                "obs:analytics:runtime:calc-1:tenant-a:DAILY:30",
                indexKey
        ));
        verify(setOperations, never()).remove(any(), any());
    }

    private CalculatorRun run(String calculatorId, String tenantId) {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId(calculatorId)
                .calculatorName("Calculator")
                .tenantId(tenantId)
                .frequency(CalculatorFrequency.DAILY)
                .reportingDate(LocalDate.of(2026, 4, 5))
                .startTime(Instant.parse("2026-04-05T04:00:00Z"))
                .status(RunStatus.RUNNING)
                .build();
    }
}
