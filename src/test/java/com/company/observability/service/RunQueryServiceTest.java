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

import com.company.observability.exception.DomainNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
        CalculatorStatusResponse cachedResponse = new CalculatorStatusResponse(
                "Calculator 1", Instant.now(),
                new RunStatusInfo("run-1", "RUNNING", null, null, null, null, null, null, null, null, null),
                List.of());

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

    // ---------------------------------------------------------------
    // getCalculatorStatus — additional coverage
    // ---------------------------------------------------------------

    @Test
    void getStatus_cacheHit_repositoryNeverQueried() {
        CalculatorStatusResponse cached = statusResponse("calc-1");
        when(redisCache.getStatusResponse("calc-1", "tenant-1", CalculatorFrequency.DAILY, 5))
                .thenReturn(java.util.Optional.of(cached));

        CalculatorStatusResponse result = service.getCalculatorStatus(
                "calc-1", "tenant-1", CalculatorFrequency.DAILY, 5, false);

        assertThat(result).isEqualTo(cached);
        verify(runRepository, never()).findRecentRuns(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void getStatus_bypassCache_alwaysQueriesDb_andNeverReadsCache() {
        CalculatorRun run = dbRun("calc-1", "run-1");
        when(runRepository.findRecentRuns("calc-1", "tenant-1", CalculatorFrequency.DAILY, 6))
                .thenReturn(List.of(run));

        service.getCalculatorStatus("calc-1", "tenant-1", CalculatorFrequency.DAILY, 5, true);

        verify(redisCache, never()).getStatusResponse(anyString(), anyString(), any(), anyInt());
        verify(runRepository).findRecentRuns("calc-1", "tenant-1", CalculatorFrequency.DAILY, 6);
    }

    @Test
    void getStatus_emptyDbResult_throwsDomainNotFoundException() {
        when(runRepository.findRecentRuns(anyString(), anyString(), any(), anyInt()))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                service.getCalculatorStatus("unknown", "tenant-1", CalculatorFrequency.DAILY, 5, true))
                .isInstanceOf(DomainNotFoundException.class)
                .hasMessageContaining("unknown");
    }

    // ---------------------------------------------------------------
    // getBatchCalculatorStatus — additional coverage
    // ---------------------------------------------------------------

    @Test
    void getBatch_allCacheHits_dbNeverQueried() {
        CalculatorStatusResponse r1 = statusResponse("calc-1");
        CalculatorStatusResponse r2 = statusResponse("calc-2");
        when(redisCache.getBatchStatusResponses(List.of("calc-1", "calc-2"), "tenant-1",
                CalculatorFrequency.DAILY, 5))
                .thenReturn(Map.of("calc-1", r1, "calc-2", r2));

        List<CalculatorStatusResponse> result = service.getBatchCalculatorStatus(
                List.of("calc-1", "calc-2"), "tenant-1", CalculatorFrequency.DAILY, 5, true);

        assertThat(result).hasSize(2);
        verify(runRepository, never()).findBatchRecentRunsDbOnly(anyList(), anyString(), any(), anyInt());
    }

    @Test
    void getBatch_allMisses_queriesDbAndCachesResults() {
        when(redisCache.getBatchStatusResponses(anyList(), anyString(), any(), anyInt()))
                .thenReturn(Map.of()); // no cache hits
        CalculatorRun run = dbRun("calc-1", "run-1");
        when(runRepository.findBatchRecentRunsDbOnly(anyList(), anyString(), any(), anyInt()))
                .thenReturn(Map.of("calc-1", List.of(run)));

        service.getBatchCalculatorStatus(
                List.of("calc-1"), "tenant-1", CalculatorFrequency.DAILY, 5, true);

        verify(runRepository).findBatchRecentRunsDbOnly(
                List.of("calc-1"), eq("tenant-1"), eq(CalculatorFrequency.DAILY), eq(6));
        verify(redisCache).cacheBatchStatusResponses(any(), eq("tenant-1"), eq(CalculatorFrequency.DAILY), eq(5));
    }

    @Test
    void getBatch_unknownCalculators_silentlyAbsent() {
        when(redisCache.getBatchStatusResponses(anyList(), anyString(), any(), anyInt()))
                .thenReturn(Map.of());
        // DB returns nothing for "calc-unknown"
        when(runRepository.findBatchRecentRunsDbOnly(anyList(), anyString(), any(), anyInt()))
                .thenReturn(Map.of());

        List<CalculatorStatusResponse> result = service.getBatchCalculatorStatus(
                List.of("calc-unknown"), "tenant-1", CalculatorFrequency.DAILY, 5, true);

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static CalculatorStatusResponse statusResponse(String calculatorId) {
        RunStatusInfo info = new RunStatusInfo(
                "run-" + calculatorId, "RUNNING",
                Instant.now(), null, null, null, null, null, null, false, null);
        return new CalculatorStatusResponse("Calculator " + calculatorId, Instant.now(), info, List.of());
    }

    private static CalculatorRun dbRun(String calculatorId, String runId) {
        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId(calculatorId)
                .calculatorName("Calculator " + calculatorId)
                .tenantId("tenant-1")
                .frequency(CalculatorFrequency.DAILY)
                .reportingDate(LocalDate.now())
                .startTime(Instant.now().minusSeconds(120))
                .status(RunStatus.RUNNING)
                .slaBreached(false)
                .createdAt(Instant.now())
                .build();
    }
}
