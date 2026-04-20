package com.company.observability.service.projection;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.dto.response.PerformanceCardResponse;
import com.company.observability.dto.response.RunPerformanceData;
import com.company.observability.service.AnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceCardProjectionTest {

    @Mock
    private AnalyticsService analyticsService;

    private PerformanceCardProjection projection;

    @BeforeEach
    void setUp() {
        projection = new PerformanceCardProjection(analyticsService);
    }

    @Test
    void getPerformanceCard_emptyRuns_returnsEmptyProjection() {
        RunPerformanceData data = new RunPerformanceData(
                "calc-1", null, "DAILY", 30, 0L,
                0, 0, 0, 0, 0,
                Collections.emptyList(), null, null);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 30, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 30, CalculatorFrequency.DAILY);

        assertEquals("calc-1", result.calculatorId());
        assertNull(result.calculatorName());
        assertNull(result.meanDurationFormatted());
        assertEquals(0, result.slaSummary().totalRuns());
        assertTrue(result.runs().isEmpty());
        assertNull(result.referenceLines());
    }

    @Test
    void getPerformanceCard_formatsRunBarsWithCetTimesAndDuration() {
        // 2026-02-21 04:15 UTC = 05:15 CET (CET = UTC+1 in winter)
        Instant start = Instant.parse("2026-02-21T04:15:00Z");
        // 2026-02-21 04:18 UTC = 05:18 CET
        Instant end = Instant.parse("2026-02-21T04:18:00Z");
        Instant estStart = Instant.parse("2026-02-21T04:00:00Z");
        Instant slaTime = Instant.parse("2026-02-21T05:15:00Z");

        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calculator One", "DAILY", 30, 180000L,
                2, 1, 1, 1, 0,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-1", LocalDate.of(2026, 2, 21),
                                start, end, 180000L, "SUCCESS", false, "SLA_MET"),
                        new RunPerformanceData.RunDataPoint(
                                "run-2", LocalDate.of(2026, 2, 22),
                                start, end, 180000L, "SUCCESS", true, "LATE"),
                        new RunPerformanceData.RunDataPoint(
                                "run-3", LocalDate.of(2026, 2, 23),
                                start, null, null, "RUNNING", false, "RUNNING")
                ),
                estStart, slaTime);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 30, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 30, CalculatorFrequency.DAILY);

        assertEquals("3mins 0s", result.meanDurationFormatted());
        assertEquals("Calculator One", result.calculatorName());

        assertNotNull(result.schedule());
        assertEquals("DAILY", result.schedule().frequency());
        assertNotNull(result.schedule().estimatedStartTimeCet());

        assertEquals(2, result.slaSummary().totalRuns());
        assertEquals(50.0, result.slaSummary().slaMetPct());
        assertEquals(50.0, result.slaSummary().latePct());
        assertEquals(0.0, result.slaSummary().veryLatePct());

        assertEquals(3, result.runs().size());
        PerformanceCardResponse.RunBar bar1 = result.runs().get(0);
        assertEquals("run-1", bar1.runId());
        assertEquals("SLA_MET", bar1.slaStatus());
        assertEquals(180000L, bar1.durationMs());
        assertEquals("3mins 0s", bar1.durationFormatted());
        assertNotNull(bar1.dateFormatted());
        assertNotNull(bar1.startHourCet());
        assertNotNull(bar1.endHourCet());
        assertTrue(bar1.startTimeCet().endsWith(" CET"));
        assertTrue(bar1.endTimeCet().endsWith(" CET"));
        assertEquals("RUNNING", result.runs().get(2).slaStatus());

        assertNotNull(result.referenceLines());
        assertNotNull(result.referenceLines().slaStartHourCet());
        assertNotNull(result.referenceLines().slaEndHourCet());
    }

    @Test
    void getPerformanceCard_nullTimestamps_handledGracefully() {
        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calc", "DAILY", 7, 0L,
                1, 0, 1, 0, 0,
                List.of(new RunPerformanceData.RunDataPoint(
                        "run-1", null, null, null, null, "SUCCESS", false, "SLA_MET")),
                null, null);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 7, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 7, CalculatorFrequency.DAILY);

        assertEquals(1, result.runs().size());
        PerformanceCardResponse.RunBar bar = result.runs().get(0);
        assertNull(bar.dateFormatted());
        assertNull(bar.startHourCet());
        assertNull(bar.endHourCet());
        assertNull(bar.startTimeCet());
        assertNull(bar.endTimeCet());
        assertNull(bar.durationFormatted());
    }

    @Test
    void getPerformanceCard_durationFormattingCoversAllRanges() {
        RunPerformanceData hourData = new RunPerformanceData(
                "calc-1", "Calc", "DAILY", 30, 7200000L,
                1, 0, 1, 0, 0,
                List.of(new RunPerformanceData.RunDataPoint(
                        "run-1", LocalDate.of(2026, 2, 21),
                        Instant.parse("2026-02-21T04:00:00Z"),
                        Instant.parse("2026-02-21T06:00:00Z"),
                        7200000L, "SUCCESS", false, "SLA_MET")),
                Instant.parse("2026-02-21T04:00:00Z"),
                Instant.parse("2026-02-21T06:15:00Z"));

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 30, CalculatorFrequency.DAILY))
                .thenReturn(hourData);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 30, CalculatorFrequency.DAILY);

        assertEquals("2hrs 0mins", result.meanDurationFormatted());
        assertEquals("2hrs 0mins", result.runs().get(0).durationFormatted());
    }

    @Test
    void getPerformanceCard_allRunningRows_keepsZeroPercentages() {
        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calculator One", "DAILY", 3, 0L,
                0, 2, 0, 0, 0,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-1", LocalDate.of(2026, 2, 21),
                                Instant.parse("2026-02-21T04:00:00Z"),
                                null, null, "RUNNING", false, "RUNNING"),
                        new RunPerformanceData.RunDataPoint(
                                "run-2", LocalDate.of(2026, 2, 22),
                                Instant.parse("2026-02-22T04:00:00Z"),
                                null, null, "RUNNING", false, "RUNNING")
                ),
                Instant.parse("2026-02-21T04:00:00Z"),
                Instant.parse("2026-02-21T06:15:00Z"));

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 3, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 3, CalculatorFrequency.DAILY);

        assertEquals(0, result.slaSummary().totalRuns());
        assertEquals(0.0, result.slaSummary().slaMetPct());
        assertEquals(0.0, result.slaSummary().latePct());
        assertEquals(0.0, result.slaSummary().veryLatePct());
        assertEquals("RUNNING", result.runs().get(0).slaStatus());
    }
}
