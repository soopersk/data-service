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
        assertTrue(result.schedule().isEmpty());
        assertEquals(0, result.slaSummary().totalRuns());
        assertTrue(result.runs().isEmpty());
        assertNull(result.referenceLines());
    }

    @Test
    void getPerformanceCard_buildsScheduleFromDistinctRunSlots() {
        // 2026-02-21 04:00 UTC = 05:00 CET (UTC+1 in winter)
        Instant estStart = Instant.parse("2026-02-21T04:00:00Z");
        // 2026-02-21 05:15 UTC = 06:15 CET
        Instant slaTime  = Instant.parse("2026-02-21T05:15:00Z");
        Instant start    = Instant.parse("2026-02-21T04:15:00Z");
        Instant end      = Instant.parse("2026-02-21T04:18:00Z");

        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calculator One", "DAILY", 30, 180000L,
                2, 1, 1, 1, 0,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-1", LocalDate.of(2026, 2, 21),
                                start, end, 180000L, "SUCCESS", false, "SLA_MET", null,
                                estStart, slaTime),
                        new RunPerformanceData.RunDataPoint(
                                "run-2", LocalDate.of(2026, 2, 22),
                                start, end, 180000L, "SUCCESS", true, "LATE", null,
                                estStart, slaTime),
                        new RunPerformanceData.RunDataPoint(
                                "run-3", LocalDate.of(2026, 2, 23),
                                start, null, null, "RUNNING", false, "RUNNING", null,
                                estStart, slaTime)
                ),
                estStart, slaTime);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 30, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 30, CalculatorFrequency.DAILY);

        assertEquals("Calculator One", result.calculatorName());

        // Single distinct schedule slot → run1
        assertEquals(1, result.schedule().size());
        PerformanceCardResponse.ScheduleEntry slot = result.schedule().get(0);
        assertEquals("run1", slot.runKey());
        assertEquals("05:00", slot.estimatedStartTime());
        assertEquals("06:15", slot.sla());

        assertEquals(2, result.slaSummary().totalRuns());
        assertEquals(0.0, result.slaSummary().veryLatePct());

        assertEquals(3, result.runs().size());
        PerformanceCardResponse.RunBar bar1 = result.runs().get(0);
        assertEquals("run-1", bar1.runId());
        assertEquals("SLA_MET", bar1.slaStatus());
        assertEquals(180000L, bar1.durationMs());
        assertNotNull(bar1.startTime());
        assertNotNull(bar1.endTime());
        assertEquals("RUNNING", result.runs().get(2).slaStatus());

        assertNotNull(result.referenceLines());
        // estStart = 05:00 CET → 5.0; slaTime = 06:15 CET → 6.25
        assertEquals(5.0, result.referenceLines().slaStartHourCet());
        assertEquals(6.25, result.referenceLines().slaEndHourCet());
    }

    @Test
    void getPerformanceCard_multipleScheduleSlots_labelledInOrder() {
        // Two distinct schedule slots: 10:00 CET and 20:00 CET
        Instant estStart1 = Instant.parse("2026-02-21T09:00:00Z"); // 10:00 CET
        Instant slaTime1  = Instant.parse("2026-02-21T09:45:00Z"); // 10:45 CET
        Instant estStart2 = Instant.parse("2026-02-21T19:00:00Z"); // 20:00 CET
        Instant slaTime2  = Instant.parse("2026-02-21T19:15:00Z"); // 20:15 CET

        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calc", "DAILY", 7, 0L,
                2, 0, 2, 0, 0,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-a", LocalDate.of(2026, 2, 21),
                                estStart1, slaTime1, 2700000L, "SUCCESS", false, "SLA_MET", null,
                                estStart1, slaTime1),
                        new RunPerformanceData.RunDataPoint(
                                "run-b", LocalDate.of(2026, 2, 21),
                                estStart2, slaTime2, 900000L, "SUCCESS", false, "SLA_MET", null,
                                estStart2, slaTime2)
                ),
                estStart1, slaTime1);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 7, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 7, CalculatorFrequency.DAILY);

        assertEquals(2, result.schedule().size());
        assertEquals("run1", result.schedule().get(0).runKey());
        assertEquals("10:00", result.schedule().get(0).estimatedStartTime());
        assertEquals("10:45", result.schedule().get(0).sla());
        assertEquals("run2", result.schedule().get(1).runKey());
        assertEquals("20:00", result.schedule().get(1).estimatedStartTime());
        assertEquals("20:15", result.schedule().get(1).sla());
    }

    @Test
    void getPerformanceCard_nullTimestamps_handledGracefully() {
        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calc", "DAILY", 7, 0L,
                1, 0, 1, 0, 0,
                List.of(new RunPerformanceData.RunDataPoint(
                        "run-1", null, null, null, null, "SUCCESS", false, "SLA_MET", null,
                        null, null)),
                null, null);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 7, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 7, CalculatorFrequency.DAILY);

        assertEquals(1, result.runs().size());
        PerformanceCardResponse.RunBar bar = result.runs().get(0);
        assertNull(bar.reportingDate());
        assertNull(bar.startTime());
        assertNull(bar.endTime());
        assertTrue(result.schedule().isEmpty());
        assertNull(result.referenceLines());
    }

    @Test
    void getPerformanceCard_allRunningRows_keepsZeroPercentages() {
        Instant estStart = Instant.parse("2026-02-21T04:00:00Z");
        Instant slaTime  = Instant.parse("2026-02-21T06:15:00Z");

        RunPerformanceData data = new RunPerformanceData(
                "calc-1", "Calculator One", "DAILY", 3, 0L,
                0, 2, 0, 0, 0,
                List.of(
                        new RunPerformanceData.RunDataPoint(
                                "run-1", LocalDate.of(2026, 2, 21),
                                Instant.parse("2026-02-21T04:00:00Z"),
                                null, null, "RUNNING", false, "RUNNING", null,
                                estStart, slaTime),
                        new RunPerformanceData.RunDataPoint(
                                "run-2", LocalDate.of(2026, 2, 22),
                                Instant.parse("2026-02-22T04:00:00Z"),
                                null, null, "RUNNING", false, "RUNNING", null,
                                estStart, slaTime)
                ),
                estStart, slaTime);

        when(analyticsService.getRunPerformanceData("calc-1", "t1", 3, CalculatorFrequency.DAILY))
                .thenReturn(data);

        PerformanceCardResponse result = projection
                .getPerformanceCard("calc-1", "t1", 3, CalculatorFrequency.DAILY);

        assertEquals(0, result.slaSummary().totalRuns());
        assertEquals(0.0, result.slaSummary().veryLatePct());
        assertEquals("RUNNING", result.runs().get(0).slaStatus());
    }
}
