package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.response.PagedResponse;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.dto.response.SlaBreachDetailResponse;
import com.company.observability.dto.response.SlaSummaryResponse;
import com.company.observability.dto.response.TrendAnalyticsResponse;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.repository.DailyAggregateRepository;
import com.company.observability.repository.SlaBreachEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.observability.config.CalculatorProperties;


@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private DailyAggregateRepository dailyAggregateRepository;
    @Mock
    private SlaBreachEventRepository slaBreachEventRepository;
    @Mock
    private CalculatorRunRepository calculatorRunRepository;
    @Mock
    private AnalyticsCacheService cacheService;
    @Mock
    private CalculatorProfileService calculatorProfileService;

    private AnalyticsService service;

    // Passthrough resolver — any unknown name maps to itself (no aliases configured)
    private final CalculatorNameResolver passthroughResolver = passthroughResolver();

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(
                dailyAggregateRepository,
                slaBreachEventRepository,
                calculatorRunRepository,
                cacheService,
                calculatorProfileService,
                new com.company.observability.config.SlaProperties(),
                passthroughResolver
        );
    }

    private static CalculatorNameResolver passthroughResolver() {
        CalculatorProperties props = new CalculatorProperties();
        // empty map → all names pass through unchanged
        return new CalculatorNameResolver(props);
    }

    @Test
    void getSlaBreachDetails_returnsNextCursorWhenMoreRowsExist() {
        Instant t1 = Instant.parse("2026-02-20T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-20T09:00:00Z");
        Instant t3 = Instant.parse("2026-02-20T08:00:00Z");

        when(slaBreachEventRepository.findByCalculatorIdKeyset(
                eq("calc-1"), eq(30), isNull(),
                isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        breach(101L, t1),
                        breach(100L, t2),
                        breach(99L, t3)
                ));
        when(slaBreachEventRepository.countByCalculatorIdAndPeriod("calc-1", 30, null))
                .thenReturn(3L);

        PagedResponse<SlaBreachDetailResponse> response = service.getSlaBreachDetails(
                "calc-1", 30, null, 0, 2, null
        );

        assertEquals(2, response.content().size());
        assertNotNull(response.nextCursor());
    }

    @Test
    void getSlaBreachDetails_usesLegacyOffsetModeForExplicitPageWithoutCursor() {
        when(slaBreachEventRepository.findByCalculatorIdPaginated(
                eq("calc-1"), eq(30), isNull(), eq(2), eq(3)))
                .thenReturn(List.of());
        when(slaBreachEventRepository.countByCalculatorIdAndPeriod("calc-1", 30, null))
                .thenReturn(0L);

        service.getSlaBreachDetails("calc-1", 30, null, 1, 2, null);

        verify(slaBreachEventRepository).findByCalculatorIdPaginated(
                "calc-1", 30, null, 2, 3);
        verify(slaBreachEventRepository).countByCalculatorIdAndPeriod("calc-1", 30, null);
    }

    @Test
    void getSlaSummary_usesAggregatedBreachQueriesAndBuildsSummary() {
        LocalDate day = LocalDate.of(2026, 2, 20);
        DailyAggregate aggregate = new DailyAggregate(
                "calc-1", day,
                5, 4, 1, 1200L, 360, 390, null);

        when(dailyAggregateRepository.findRecentAggregates("calc-1", 30))
                .thenReturn(List.of(aggregate));
        when(slaBreachEventRepository.countByBand("calc-1", 30))
                .thenReturn(Map.of("VERY_LATE", 2));
        when(slaBreachEventRepository.countByType("calc-1", 30))
                .thenReturn(Map.of("TIME_EXCEEDED", 2));
        when(slaBreachEventRepository.findWorstDayHealthByDay("calc-1", 30))
                .thenReturn(Map.of(day, "VERY_LATE"));

        SlaSummaryResponse response = service.getSlaSummary("calc-1", 30);

        assertEquals(2, response.totalBreaches());
        assertEquals(1, response.redDays());
    }

    @Test
    void getTrends_usesAggregatedWorstBandByDay() {
        LocalDate day = LocalDate.of(2026, 2, 20);
        DailyAggregate aggregate = new DailyAggregate(
                "calc-1", day,
                3, 3, 1, 800L, 360, 372, null);

        when(dailyAggregateRepository.findRecentAggregates("calc-1", 7))
                .thenReturn(List.of(aggregate));
        when(slaBreachEventRepository.countByBand("calc-1", 7))
                .thenReturn(Map.of("LATE", 1));
        when(slaBreachEventRepository.countByType("calc-1", 7))
                .thenReturn(Map.of("TIME_EXCEEDED", 1));
        when(slaBreachEventRepository.findWorstDayHealthByDay("calc-1", 7))
                .thenReturn(Map.of(day, "LATE"));

        TrendAnalyticsResponse response = service.getTrends("calc-1", 7);

        assertEquals(1, response.trends().size());
        assertEquals("AMBER", response.trends().get(0).slaStatus());
    }

    @Test
    void getRunPerformanceData_includesRunningRows_butExcludesThemFromSlaCounts() {
        LocalDate day = LocalDate.of(2026, 2, 21);
        Instant start = Instant.parse("2026-02-21T04:00:00Z");
        Instant end = Instant.parse("2026-02-21T04:03:00Z");
        Instant slaTime = Instant.parse("2026-02-21T06:15:00Z");

        RunWithSlaStatus slaMetRun = new RunWithSlaStatus(
                "run-1", "calc-1", "Calculator One", day,
                start, end, 180000L,
                slaTime, start, Frequency.DAILY,
                RunStatus.SUCCESS, null, null, null, null, null);

        RunWithSlaStatus breachedRun = new RunWithSlaStatus(
                "run-2", "calc-1", "Calculator One", day.plusDays(1),
                start, end, 180000L,
                slaTime, start, Frequency.DAILY,
                RunStatus.SUCCESS, SlaBand.VERY_LATE, "Time exceeded", null, null, null);

        RunWithSlaStatus runningRun = new RunWithSlaStatus(
                "run-3", "calc-1", "Calculator One", day.plusDays(2),
                start, null, null,
                slaTime, start, Frequency.DAILY,
                RunStatus.RUNNING, null, null, null, null, null);

        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 30))
                .thenReturn(List.of(slaMetRun, breachedRun, runningRun));

        RunPerformanceData result = service.getRunPerformanceData(
                "calc-1", 30, Frequency.DAILY);

        assertEquals("calc-1", result.calculatorId());
        assertEquals("Calculator One", result.calculatorName());
        assertEquals("DAILY", result.frequency());
        assertEquals(30, result.periodDays());
        assertEquals(180000L, result.meanDurationMs());
        assertEquals(2, result.totalRuns());
        assertEquals(1, result.runningRuns());
        assertEquals(1, result.slaMetCount());
        assertEquals(0, result.lateCount());
        assertEquals(1, result.veryLateCount());

        assertEquals(3, result.runs().size());
        RunPerformanceData.RunDataPoint point1 = result.runs().get(0);
        assertEquals("run-1", point1.runId());
        assertEquals(start, point1.startTime());
        assertEquals(end, point1.endTime());
        assertEquals("SUCCESS", point1.status());
        assertEquals("ON_TIME", point1.slaStatus());
        assertEquals("ON_TIME", point1.slaBand());

        RunPerformanceData.RunDataPoint point2 = result.runs().get(1);
        assertEquals("SUCCESS", point2.status());
        assertEquals("VERY_LATE", point2.slaStatus());
        assertEquals("VERY_LATE", point2.slaBand());

        RunPerformanceData.RunDataPoint point3 = result.runs().get(2);
        assertEquals("RUNNING", point3.status());
        assertEquals("ON_TIME", point3.slaStatus());
        assertNull(point3.endTime());
        assertNull(point3.durationMs());

        assertEquals(start, result.estimatedStartTime());
        assertEquals(slaTime, result.slaTime());
    }

    @Test
    void getRunPerformanceData_emptyRuns_returnsZeroedResponse() {
        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 7))
                .thenReturn(List.of());

        RunPerformanceData result = service.getRunPerformanceData(
                "calc-1", 7, Frequency.DAILY);

        assertEquals("calc-1", result.calculatorId());
        assertNull(result.calculatorName());
        assertEquals(0, result.totalRuns());
        assertEquals(0, result.runningRuns());
        assertEquals(0L, result.meanDurationMs());
        assertTrue(result.runs().isEmpty());
        assertNull(result.estimatedStartTime());
        assertNull(result.slaTime());
    }

    @Test
    void getRunExecutions_splitRunsAppearAsIndependentRows() {
        LocalDate day = LocalDate.of(2026, 5, 11);
        Instant start1 = Instant.parse("2026-05-11T03:59:50Z");
        Instant end1 = Instant.parse("2026-05-11T04:08:10Z");
        Instant start2 = Instant.parse("2026-05-11T04:00:05Z");
        Instant end2 = Instant.parse("2026-05-11T04:15:45Z");
        Instant slaTime = Instant.parse("2026-05-11T06:30:00Z");

        RunWithSlaStatus split1 = new RunWithSlaStatus(
                "run-split-1", "calc-1", "Portfolio", day,
                start1, end1, 500000L,
                slaTime, start1, Frequency.DAILY,
                RunStatus.SUCCESS, null, null, "corr-1", "1", 300000L);

        RunWithSlaStatus split2 = new RunWithSlaStatus(
                "run-split-2", "calc-1", "Portfolio", day,
                start2, end2, 940000L,
                slaTime, start2, Frequency.DAILY,
                RunStatus.SUCCESS, SlaBand.VERY_LATE, "Time exceeded", "corr-1", "1", 300000L);

        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 30, null))
                .thenReturn(List.of(split1, split2));
        // No usable profile → envelope reference lines fall back to latest-run values.
        when(calculatorProfileService.getProfile("Portfolio", Frequency.DAILY))
                .thenReturn(new com.company.observability.domain.CalculatorProfile(
                        "Portfolio", "DAILY", null, null, 0, 0, 0, 0));

        RunPerformanceData result = service.getRunExecutions("calc-1", 30, Frequency.DAILY);

        assertEquals(2, result.runs().size());
        assertNull(result.runs().get(0).subRunIds());
        assertNull(result.runs().get(1).subRunIds());
        assertEquals("run-split-1", result.runs().get(0).runId());
        assertEquals("run-split-2", result.runs().get(1).runId());
        assertEquals("ON_TIME", result.runs().get(0).slaStatus());
        assertEquals("VERY_LATE", result.runs().get(1).slaStatus());
        assertEquals((500000L + 940000L) / 2, result.meanDurationMs());
        assertEquals(1, result.slaMetCount());
        assertEquals(1, result.veryLateCount());
        assertEquals(0, result.runningRuns());
    }

    @Test
    void getRunExecutions_emptyRuns_returnsZeroedResponse() {
        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 7, null))
                .thenReturn(List.of());

        RunPerformanceData result = service.getRunExecutions("calc-1", 7, Frequency.DAILY);

        assertEquals("calc-1", result.calculatorId());
        assertNull(result.calculatorName());
        assertEquals(0, result.totalRuns());
        assertTrue(result.runs().isEmpty());
        assertNull(result.estimatedStartTime());
        assertNull(result.slaTime());
    }

    @Test
    void getRunExecutions_referenceLinesUsesStoredSlaTimeWhenPresent() {
        // With sufficient profile samples and a frozen deadline: estStart comes from profile avg,
        // slaTime reference line is the stored absolute deadline (not a re-derived buffered value).
        LocalDate day = LocalDate.of(2026, 5, 11);
        Instant start = Instant.parse("2026-05-11T05:00:00Z");
        Instant end = Instant.parse("2026-05-11T05:30:00Z");
        Instant runSla = Instant.parse("2026-05-11T06:30:00Z"); // stored clock-time deadline

        RunWithSlaStatus run = new RunWithSlaStatus(
                "run-1", "calc-1", "Portfolio", day,
                start, end, 1_800_000L,
                runSla, start, Frequency.DAILY,
                RunStatus.SUCCESS, null, null, null, "1", 300000L);

        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 30, null))
                .thenReturn(List.of(run));
        // avg start = 270 min UTC (04:30); 10 samples → trusted.
        when(calculatorProfileService.getProfile("Portfolio", Frequency.DAILY))
                .thenReturn(new com.company.observability.domain.CalculatorProfile(
                        "Portfolio", "DAILY", null, null, 3_600_000L, 270, 330, 10));

        RunPerformanceData result = service.getRunExecutions("calc-1", 30, Frequency.DAILY);

        Instant expectedStart = Instant.parse("2026-05-11T04:30:00Z"); // day @ 270 min UTC
        assertEquals(expectedStart, result.estimatedStartTime());
        // Clock-time mode: deadline reference = stored slaTime, not a re-buffered duration
        assertEquals(runSla, result.slaTime());
    }

    @Test
    void getRunExecutions_referenceLinesBuffersProfileAverageWhenNoFrozenDeadline() {
        // No frozen deadline (ungraded run, slaTime == null) but sufficient profile samples:
        // the reference deadline is synthesized as buffered profile avg.
        LocalDate day = LocalDate.of(2026, 5, 11);
        Instant start = Instant.parse("2026-05-11T05:00:00Z");
        Instant end = Instant.parse("2026-05-11T05:30:00Z");

        RunWithSlaStatus run = new RunWithSlaStatus(
                "run-1", "calc-1", "Portfolio", day,
                start, end, 1_800_000L,
                null, start, Frequency.DAILY,   // slaTime == null → no frozen deadline
                RunStatus.SUCCESS, null, null, null, "1", 300000L);

        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", Frequency.DAILY, 30, null))
                .thenReturn(List.of(run));
        when(calculatorProfileService.getProfile("Portfolio", Frequency.DAILY))
                .thenReturn(new com.company.observability.domain.CalculatorProfile(
                        "Portfolio", "DAILY", null, null, 3_600_000L, 270, 330, 10));

        RunPerformanceData result = service.getRunExecutions("calc-1", 30, Frequency.DAILY);

        // estStart = day @ 04:30 UTC; buffered = 3_600_000*1.2 + 15m = 72m + 15m = 87m
        Instant expectedStart = Instant.parse("2026-05-11T04:30:00Z");
        assertEquals(expectedStart, result.estimatedStartTime());
        assertEquals(expectedStart.plusMillis(87L * 60 * 1000), result.slaTime());
    }

    // ── getRunExecutionsByName — cache behaviour ──────────────────────────────

    @Test
    void getRunExecutionsByName_cacheHit_skipsDb() {
        RunPerformanceData cached = new RunPerformanceData(
                "cap", "Capital", "DAILY", 30, 0L, 0, 0, 0, 0, 0,
                List.of(), null, null);
        when(cacheService.getFromCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), isNull(),
                any(LocalDate.class), eq(RunPerformanceData.class)))
                .thenReturn(cached);

        RunPerformanceData result = service.getRunExecutionsByName("cap", 30, Frequency.DAILY, null, LocalDate.now());

        assertEquals(cached, result);
        verifyNoInteractions(calculatorRunRepository);
    }

    @Test
    void getRunExecutionsByName_cacheMiss_queriesDbAndPopulatesCache() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("cap"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class)))
                .thenReturn(List.of());

        service.getRunExecutionsByName("cap", 30, Frequency.DAILY, null, LocalDate.now());

        verify(calculatorRunRepository)
                .findRunsByName(eq("cap"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class));
        verify(cacheService).putInCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), isNull(), any(LocalDate.class), any());
    }

    @Test
    void getRunExecutionsByName_runNumberDistinguishesKeys() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), eq("2"), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(
                eq("cap"), any(), anyInt(), eq("2"), any(LocalDate.class)))
                .thenReturn(List.of());

        service.getRunExecutionsByName("cap", 30, Frequency.DAILY, "2", LocalDate.now());

        verify(cacheService).getFromCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), eq("2"), any(LocalDate.class), any());
        verify(cacheService).putInCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), eq("2"), any(LocalDate.class), any());
    }

    @Test
    void getRunExecutionsByName_blankRunNumber_normalisedToNull() {
        when(cacheService.getFromCache(any(), any(), any(), anyInt(), isNull(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(any(), any(), anyInt(), isNull(), any(LocalDate.class)))
                .thenReturn(List.of());

        service.getRunExecutionsByName("cap", 30, Frequency.DAILY, "  ", LocalDate.now());

        verify(cacheService).getFromCache(
                eq("executions"), eq("cap"), eq("DAILY"), eq(30), isNull(), any(LocalDate.class), any());
        verify(calculatorRunRepository)
                .findRunsByName(eq("cap"), eq(Frequency.DAILY), eq(30), isNull(), any(LocalDate.class));
    }

    @Test
    void getRunExecutionsByName_multiAlias_mergesRunsFromAllRealCalculators() {
        CalculatorProperties props = new CalculatorProperties();
        props.setAliases(Map.of(
                "capital", List.of("capitalcalc", "capitalcalcmedium")
        ));
        AnalyticsService aliasService = new AnalyticsService(
                dailyAggregateRepository,
                slaBreachEventRepository,
                calculatorRunRepository,
                cacheService,
                calculatorProfileService,
                new com.company.observability.config.SlaProperties(),
                new CalculatorNameResolver(props)
        );

        when(cacheService.getFromCache(any(), eq("capital"), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);

        LocalDate day = LocalDate.of(2026, 6, 1);
        Instant start = Instant.parse("2026-06-01T05:00:00Z");
        Instant end = Instant.parse("2026-06-01T06:00:00Z");

        RunWithSlaStatus run1 = new RunWithSlaStatus(
                "run-1", "id-1", "capitalcalc", day,
                start, end, 3_600_000L, null, start,
                Frequency.DAILY, RunStatus.SUCCESS, null, null, null, null, null);
        RunWithSlaStatus run2 = new RunWithSlaStatus(
                "run-2", "id-2", "capitalcalcmedium", day,
                start, end, 1_800_000L, null, start,
                Frequency.DAILY, RunStatus.SUCCESS, null, null, null, null, null);

        when(calculatorRunRepository.findRunsByName(eq("capitalcalc"), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of(run1));
        when(calculatorRunRepository.findRunsByName(eq("capitalcalcmedium"), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of(run2));
        when(calculatorProfileService.getProfile(any(), any()))
                .thenReturn(new com.company.observability.domain.CalculatorProfile("capitalcalc", "DAILY", null, null, 0, 0, 0, 0));

        RunPerformanceData result = aliasService.getRunExecutionsByName("capital", 30, Frequency.DAILY, null, LocalDate.now());

        assertEquals("capital", result.calculatorId());
        assertEquals(2, result.runs().size());
        verify(calculatorRunRepository).findRunsByName(eq("capitalcalc"), any(), anyInt(), any(), any(LocalDate.class));
        verify(calculatorRunRepository).findRunsByName(eq("capitalcalcmedium"), any(), anyInt(), any(), any(LocalDate.class));
        verify(cacheService).putInCache(eq("executions"), eq("capital"), any(), anyInt(), any(), any(LocalDate.class), any());
    }

    @Test
    void getRunExecutionsByName_singleAlias_queriesRealNameOnly() {
        CalculatorProperties props = new CalculatorProperties();
        props.setAliases(Map.of("portfolio", List.of("portfoliocalc")));
        AnalyticsService aliasService = new AnalyticsService(
                dailyAggregateRepository,
                slaBreachEventRepository,
                calculatorRunRepository,
                cacheService,
                calculatorProfileService,
                new com.company.observability.config.SlaProperties(),
                new CalculatorNameResolver(props)
        );

        when(cacheService.getFromCache(any(), eq("portfolio"), any(), anyInt(), any(), any(), any()))
                .thenReturn(null);
        when(calculatorRunRepository.findRunsByName(eq("portfoliocalc"), any(), anyInt(), any(), any(LocalDate.class)))
                .thenReturn(List.of());

        aliasService.getRunExecutionsByName("portfolio", 30, Frequency.DAILY, null, LocalDate.now());

        verify(calculatorRunRepository).findRunsByName(eq("portfoliocalc"), any(), anyInt(), any(), any(LocalDate.class));
        verify(calculatorRunRepository, never()).findRunsByName(eq("portfolio"), any(), anyInt(), any(), any(LocalDate.class));
        verify(cacheService).putInCache(eq("executions"), eq("portfolio"), any(), anyInt(), any(), any(LocalDate.class), any());
    }

    private SlaBreachEvent breach(long breachId, Instant createdAt) {
        return SlaBreachEvent.builder()
                .breachId(breachId)
                .runId("run-" + breachId)
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .breachType(BreachType.TIME_EXCEEDED)
                .createdAt(createdAt)
                .build();
    }
}
