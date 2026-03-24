package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorStatusResponse;
import com.company.observability.dto.response.RunStatusInfo;
import com.company.observability.repository.CalculatorRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunQueryServiceTest {

    @Mock
    private CalculatorRunRepository runRepository;
    @Mock
    private RedisCalculatorCache redisCache;

    private RunQueryService service;

    @BeforeEach
    void setUp() {
        service = new RunQueryService(runRepository, redisCache, new SimpleMeterRegistry());
    }

    @Test
    void getBatchCalculatorStatus_usesDbOnlyPathForResponseCacheMisses() {
        CalculatorStatusResponse cachedResponse = CalculatorStatusResponse.builder()
                .calculatorName("Calculator 1")
                .lastRefreshed(Instant.now())
                .current(new RunStatusInfo("run-1", "RUNNING", null, null, null, null, null, null, null, null, null))
                .history(List.of())
                .build();

        when(redisCache.getBatchStatusResponses(
                List.of("calc-1", "calc-2"), "tenant-1", CalculatorFrequency.DAILY, 2))
                .thenReturn(Map.of("calc-1", cachedResponse));

        CalculatorRun dbRun = CalculatorRun.builder()
                .runId("run-2")
                .calculatorId("calc-2")
                .calculatorName("Calculator 2")
                .tenantId("tenant-1")
                .frequency(CalculatorFrequency.DAILY)
                .reportingDate(LocalDate.now())
                .status(RunStatus.SUCCESS)
                .startTime(Instant.now().minusSeconds(120))
                .endTime(Instant.now().minusSeconds(60))
                .durationMs(60000L)
                .slaBreached(false)
                .createdAt(Instant.now())
                .build();

        when(runRepository.findBatchRecentRunsDbOnly(anyList(), eq("tenant-1"), eq(CalculatorFrequency.DAILY), anyInt()))
                .thenReturn(Map.of("calc-2", List.of(dbRun)));

        List<CalculatorStatusResponse> result = service.getBatchCalculatorStatus(
                List.of("calc-1", "calc-2"),
                "tenant-1",
                CalculatorFrequency.DAILY,
                2,
                true
        );

        assertEquals(2, result.size());
        verify(runRepository).findBatchRecentRunsDbOnly(anyList(), eq("tenant-1"), eq(CalculatorFrequency.DAILY), eq(3));
        verify(runRepository, never()).findBatchRecentRuns(anyList(), eq("tenant-1"), eq(CalculatorFrequency.DAILY), anyInt());
    }
}
