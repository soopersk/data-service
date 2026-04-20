package com.company.observability.service;

import com.company.observability.cache.RegionalBatchCacheService;
import com.company.observability.config.RegionalBatchProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.repository.CalculatorRunRepository.RegionalBatchTiming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegionalBatchServiceTest {

    @Mock
    private CalculatorRunRepository repository;

    @Mock
    private RegionalBatchCacheService cacheService;

    private RegionalBatchProperties properties;
    private RegionalBatchService service;

    private static final String TENANT = "tenant-1";
    private static final LocalDate DATE = LocalDate.of(2026, 4, 17);

    @BeforeEach
    void setUp() {
        properties = new RegionalBatchProperties();
        // Use a 2-region order for simplicity
        properties.setRegionOrder(List.of("AMER", "EURO"));
        service = new RegionalBatchService(repository, properties, cacheService);
    }

    // ── History cache — loaded at most once per request ─────────────────────

    @Test
    void getRegionalBatchStatus_historyCacheHit_doesNotQueryDatabase() {
        // All regions started today → no history needed for estimatedStart
        // But one region is still running → estimatedEnd needs history
        List<CalculatorRun> runs = List.of(
                batchRun("run-amer", "AMER", RunStatus.SUCCESS,
                        Instant.parse("2026-04-17T05:00:00Z"),
                        Instant.parse("2026-04-17T16:00:00Z")),
                batchRun("run-euro", "EURO", RunStatus.RUNNING,
                        Instant.parse("2026-04-17T06:00:00Z"),
                        null)
        );
        when(repository.findRegionalBatchRuns(TENANT, DATE, null)).thenReturn(runs);

        List<RegionalBatchTiming> cachedHistory = List.of(
                new RegionalBatchTiming("AMER", DATE.minusDays(1),
                        Instant.parse("2026-04-16T05:00:00Z"),
                        Instant.parse("2026-04-16T17:00:00Z")),
                new RegionalBatchTiming("EURO", DATE.minusDays(1),
                        Instant.parse("2026-04-16T06:00:00Z"),
                        Instant.parse("2026-04-16T18:00:00Z"))
        );
        when(cacheService.getHistory(TENANT, DATE, null)).thenReturn(cachedHistory);

        service.getRegionalBatchStatus(TENANT, DATE);

        // History was served from cache — DB method must NOT be called
        verify(repository, never()).findRegionalBatchHistory(anyString(), any(), anyInt(), any());
        // Cache should NOT be written again (it was a hit)
        verify(cacheService, never()).putHistory(any(), any(), any(), any());
    }

    @Test
    void getRegionalBatchStatus_historyCacheMiss_queriesDbAndStoresResult() {
        // RUNNING region means estimatedEnd must be computed from history
        List<CalculatorRun> runs = List.of(
                batchRun("run-amer", "AMER", RunStatus.RUNNING,
                        Instant.parse("2026-04-17T05:00:00Z"), null)
        );
        when(repository.findRegionalBatchRuns(TENANT, DATE, null)).thenReturn(runs);
        when(cacheService.getHistory(TENANT, DATE, null)).thenReturn(null);

        List<RegionalBatchTiming> dbHistory = List.of(
                new RegionalBatchTiming("AMER", DATE.minusDays(1),
                        Instant.parse("2026-04-16T05:00:00Z"),
                        Instant.parse("2026-04-16T17:00:00Z"))
        );
        when(repository.findRegionalBatchHistory(TENANT, DATE, 7, null)).thenReturn(dbHistory);

        service.getRegionalBatchStatus(TENANT, DATE);

        verify(repository).findRegionalBatchHistory(TENANT, DATE, 7, null);
        verify(cacheService).putHistory(TENANT, DATE, null, dbHistory);
    }

    @Test
    void getRegionalBatchStatus_historyLoadedOnlyOnce_evenWhenBothEstimationsNeedIt() {
        // No runs started yet → both estimatedStart AND estimatedEnd need history
        when(repository.findRegionalBatchRuns(TENANT, DATE, null)).thenReturn(List.of());
        when(cacheService.getHistory(TENANT, DATE, null)).thenReturn(null);

        List<RegionalBatchTiming> dbHistory = List.of(
                new RegionalBatchTiming("AMER", DATE.minusDays(1),
                        Instant.parse("2026-04-16T05:00:00Z"),
                        Instant.parse("2026-04-16T17:00:00Z"))
        );
        when(repository.findRegionalBatchHistory(TENANT, DATE, 7, null)).thenReturn(dbHistory);

        service.getRegionalBatchStatus(TENANT, DATE);

        // DB must be hit exactly once even though both median computations need history
        verify(repository, times(1)).findRegionalBatchHistory(anyString(), any(), anyInt(), any());
    }

    @Test
    void getRegionalBatchStatus_allRunsStartedAndCompleted_historyNeverLoaded() {
        // Actual overrides apply for both start and end → history is irrelevant
        List<CalculatorRun> runs = List.of(
                batchRun("run-amer", "AMER", RunStatus.SUCCESS,
                        Instant.parse("2026-04-17T05:00:00Z"),
                        Instant.parse("2026-04-17T16:00:00Z")),
                batchRun("run-euro", "EURO", RunStatus.SUCCESS,
                        Instant.parse("2026-04-17T06:00:00Z"),
                        Instant.parse("2026-04-17T17:00:00Z"))
        );
        when(repository.findRegionalBatchRuns(TENANT, DATE, null)).thenReturn(runs);

        service.getRegionalBatchStatus(TENANT, DATE);

        verify(cacheService, never()).getHistory(any(), any(), any());
        verify(repository, never()).findRegionalBatchHistory(anyString(), any(), anyInt(), any());
    }

    // ── Status counts and SLA logic ─────────────────────────────────────────

    @Test
    void getRegionalBatchStatus_countsRegionsByStatus() {
        // SLA is 17:45 CET = 15:45 UTC in April (CEST = UTC+2)
        // End at 14:00 UTC = 16:00 CEST → ON_TIME
        List<CalculatorRun> runs = List.of(
                batchRun("run-amer", "AMER", RunStatus.SUCCESS,
                        Instant.parse("2026-04-17T05:00:00Z"),
                        Instant.parse("2026-04-17T14:00:00Z"))
                // EURO has no run → NOT_STARTED
        );
        when(repository.findRegionalBatchRuns(TENANT, DATE, null)).thenReturn(runs);

        RegionalBatchService.RegionalBatchResult result =
                service.getRegionalBatchStatus(TENANT, DATE);

        assertThat(result.completedRegions()).isEqualTo(1);
        assertThat(result.runningRegions()).isEqualTo(0);
        assertThat(result.failedRegions()).isEqualTo(0);
        assertThat(result.totalRegions()).isEqualTo(2);

        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).status()).isEqualTo("ON_TIME");   // AMER
        assertThat(result.entries().get(1).status()).isEqualTo("NOT_STARTED"); // EURO
    }

    @Test
    void getRegionalBatchStatus_regionAfterSla_isDelayed() {
        // SLA at 17:45 CET. Run ends at 18:10 CET → DELAYED.
        Instant endAfterSla = Instant.parse("2026-04-17T16:10:00Z"); // 18:10 CEST
        List<CalculatorRun> runs = List.of(
                batchRun("run-amer", "AMER", RunStatus.SUCCESS,
                        Instant.parse("2026-04-17T05:00:00Z"), endAfterSla),
                batchRun("run-euro", "EURO", RunStatus.SUCCESS,
                        Instant.parse("2026-04-17T06:00:00Z"),
                        Instant.parse("2026-04-17T10:00:00Z"))
        );
        when(repository.findRegionalBatchRuns(TENANT, DATE, null)).thenReturn(runs);

        RegionalBatchService.RegionalBatchResult result =
                service.getRegionalBatchStatus(TENANT, DATE);

        assertThat(result.entries().get(0).status()).isEqualTo("DELAYED");
        assertThat(result.slaBreachedRegions()).containsExactly("AMER");
        assertThat(result.overallBreached()).isTrue();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CalculatorRun batchRun(String runId, String region, RunStatus status,
                                    Instant start, Instant end) {
        Map<String, Object> params = new HashMap<>();
        params.put("run_type", "BATCH");
        params.put("region", region);

        return CalculatorRun.builder()
                .runId(runId)
                .calculatorId("regional-batch-" + region.toLowerCase())
                .calculatorName("Regional Batch " + region)
                .tenantId(TENANT)
                .frequency(CalculatorFrequency.DAILY)
                .reportingDate(DATE)
                .startTime(start)
                .endTime(end)
                .durationMs(end != null ? end.toEpochMilli() - start.toEpochMilli() : null)
                .status(status)
                .runParameters(params)
                .createdAt(start)
                .build();
    }
}
