package com.company.observability.service;

import com.company.observability.cache.AnalyticsCacheService;
import com.company.observability.domain.DailyAggregate;
import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.Severity;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(
                dailyAggregateRepository,
                slaBreachEventRepository,
                calculatorRunRepository,
                cacheService
        );
    }

    @Test
    void getSlaBreachDetails_returnsNextCursorWhenMoreRowsExist() {
        Instant t1 = Instant.parse("2026-02-20T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-20T09:00:00Z");
        Instant t3 = Instant.parse("2026-02-20T08:00:00Z");

        when(slaBreachEventRepository.findByCalculatorIdKeyset(
                eq("calc-1"), eq("tenant-1"), eq(30), isNull(),
                isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        breach(101L, t1),
                        breach(100L, t2),
                        breach(99L, t3)
                ));
        when(slaBreachEventRepository.countByCalculatorIdAndPeriod("calc-1", "tenant-1", 30, null))
                .thenReturn(3L);

        PagedResponse<SlaBreachDetailResponse> response = service.getSlaBreachDetails(
                "calc-1", "tenant-1", 30, null, 0, 2, null
        );

        assertEquals(2, response.content().size());
        assertNotNull(response.nextCursor());
    }

    @Test
    void getSlaBreachDetails_usesLegacyOffsetModeForExplicitPageWithoutCursor() {
        when(slaBreachEventRepository.findByCalculatorIdPaginated(
                eq("calc-1"), eq("tenant-1"), eq(30), isNull(), eq(2), eq(3)))
                .thenReturn(List.of());
        when(slaBreachEventRepository.countByCalculatorIdAndPeriod("calc-1", "tenant-1", 30, null))
                .thenReturn(0L);

        service.getSlaBreachDetails("calc-1", "tenant-1", 30, null, 1, 2, null);

        verify(slaBreachEventRepository).findByCalculatorIdPaginated(
                "calc-1", "tenant-1", 30, null, 2, 3);
        verify(slaBreachEventRepository).countByCalculatorIdAndPeriod("calc-1", "tenant-1", 30, null);
    }

    @Test
    void getSlaSummary_usesAggregatedBreachQueriesAndBuildsSummary() {
        LocalDate day = LocalDate.of(2026, 2, 20);
        DailyAggregate aggregate = new DailyAggregate(
                "calc-1", "tenant-1", day,
                5, 4, 1, 1200L, 360, 390, null);

        when(dailyAggregateRepository.findRecentAggregates("calc-1", "tenant-1", 30))
                .thenReturn(List.of(aggregate));
        when(slaBreachEventRepository.countBySeverity("calc-1", "tenant-1", 30))
                .thenReturn(Map.of("HIGH", 2));
        when(slaBreachEventRepository.countByType("calc-1", "tenant-1", 30))
                .thenReturn(Map.of("TIME_EXCEEDED", 2));
        when(slaBreachEventRepository.findWorstSeverityByDay("calc-1", "tenant-1", 30))
                .thenReturn(Map.of(day, "HIGH"));

        SlaSummaryResponse response = service.getSlaSummary("calc-1", "tenant-1", 30);

        assertEquals(2, response.totalBreaches());
        assertEquals(1, response.redDays());
    }

    @Test
    void getTrends_usesAggregatedWorstSeverityByDay() {
        LocalDate day = LocalDate.of(2026, 2, 20);
        DailyAggregate aggregate = new DailyAggregate(
                "calc-1", "tenant-1", day,
                3, 3, 1, 800L, 360, 372, null);

        when(dailyAggregateRepository.findRecentAggregates("calc-1", "tenant-1", 7))
                .thenReturn(List.of(aggregate));
        when(slaBreachEventRepository.countBySeverity("calc-1", "tenant-1", 7))
                .thenReturn(Map.of("MEDIUM", 1));
        when(slaBreachEventRepository.countByType("calc-1", "tenant-1", 7))
                .thenReturn(Map.of("TIME_EXCEEDED", 1));
        when(slaBreachEventRepository.findWorstSeverityByDay("calc-1", "tenant-1", 7))
                .thenReturn(Map.of(day, "MEDIUM"));

        TrendAnalyticsResponse response = service.getTrends("calc-1", "tenant-1", 7);

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
                start, end, 180000L, null, null,
                slaTime, start, CalculatorFrequency.DAILY,
                RunStatus.SUCCESS, false, null, null);

        RunWithSlaStatus breachedRun = new RunWithSlaStatus(
                "run-2", "calc-1", "Calculator One", day.plusDays(1),
                start, end, 180000L, null, null,
                slaTime, start, CalculatorFrequency.DAILY,
                RunStatus.SUCCESS, true, "Time exceeded", Severity.HIGH);

        RunWithSlaStatus runningRun = new RunWithSlaStatus(
                "run-3", "calc-1", "Calculator One", day.plusDays(2),
                start, null, null, null, null,
                slaTime, start, CalculatorFrequency.DAILY,
                RunStatus.RUNNING, false, null, null);

        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", "tenant-1", CalculatorFrequency.DAILY, 30))
                .thenReturn(List.of(slaMetRun, breachedRun, runningRun));

        RunPerformanceData result = service.getRunPerformanceData(
                "calc-1", "tenant-1", 30, CalculatorFrequency.DAILY);

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
        assertEquals("SLA_MET", point1.slaStatus());
        assertEquals(false, point1.slaBreached());

        RunPerformanceData.RunDataPoint point2 = result.runs().get(1);
        assertEquals("SUCCESS", point2.status());
        assertEquals("VERY_LATE", point2.slaStatus());
        assertEquals(true, point2.slaBreached());

        RunPerformanceData.RunDataPoint point3 = result.runs().get(2);
        assertEquals("RUNNING", point3.status());
        assertEquals("RUNNING", point3.slaStatus());
        assertNull(point3.endTime());
        assertNull(point3.durationMs());

        assertEquals(start, result.estimatedStartTime());
        assertEquals(slaTime, result.slaTime());
    }

    @Test
    void getRunPerformanceData_emptyRuns_returnsZeroedResponse() {
        when(calculatorRunRepository.findRunsWithSlaStatus(
                "calc-1", "tenant-1", CalculatorFrequency.DAILY, 7))
                .thenReturn(List.of());

        RunPerformanceData result = service.getRunPerformanceData(
                "calc-1", "tenant-1", 7, CalculatorFrequency.DAILY);

        assertEquals("calc-1", result.calculatorId());
        assertNull(result.calculatorName());
        assertEquals(0, result.totalRuns());
        assertEquals(0, result.runningRuns());
        assertEquals(0L, result.meanDurationMs());
        assertTrue(result.runs().isEmpty());
        assertNull(result.estimatedStartTime());
        assertNull(result.slaTime());
    }

    private SlaBreachEvent breach(long breachId, Instant createdAt) {
        return SlaBreachEvent.builder()
                .breachId(breachId)
                .runId("run-" + breachId)
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .breachType(BreachType.TIME_EXCEEDED)
                .severity(Severity.HIGH)
                .createdAt(createdAt)
                .build();
    }
}
