package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.SlaEvaluationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaEvaluationServiceTest {

    // Defaults: lateBand=15m, veryLateBand=30m → band gap = 15m.
    private final DurationBasedSlaProperties props = new DurationBasedSlaProperties();
    private final SlaEvaluationService service = new SlaEvaluationService(new SimpleMeterRegistry(), props);

    private static final Instant START = Instant.parse("2026-02-22T04:00:00Z");
    // slaTime is the LATE edge → lateEdge duration = 60 min; veryLateEdge = 75 min.
    private static final Instant SLA = START.plusSeconds(60 * 60);

    private CalculatorRun run(RunStatus status, Long durationMs) {
        return CalculatorRun.builder()
                .status(status)
                .startTime(START)
                .slaTime(SLA)
                .durationMs(durationMs)
                .build();
    }

    @Test
    void onTime_whenDurationWithinLateEdge_noBreach() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 50L * 60 * 1000));
        assertFalse(result.isBreached());
        assertNull(result.getReason());
        assertNull(result.getSeverity());
    }

    @Test
    void onTime_atExactLateEdgeBoundary_noBreach() {
        // duration == lateEdge (60 min) → still ON_TIME
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 60L * 60 * 1000));
        assertFalse(result.isBreached());
    }

    @Test
    void late_whenBetweenLateAndVeryLateEdge_mediumSeverity() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 70L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals("MEDIUM", result.getSeverity());
        assertTrue(result.getReason().contains("LATE band"));
    }

    @Test
    void late_atExactVeryLateEdgeBoundary_mediumSeverity() {
        // duration == veryLateEdge (75 min) → still LATE
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 75L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals("MEDIUM", result.getSeverity());
    }

    @Test
    void veryLate_whenBeyondVeryLateEdge_highSeverity() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.SUCCESS, 80L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals("HIGH", result.getSeverity());
        assertTrue(result.getReason().contains("VERY_LATE band"));
    }

    @Test
    void failedRun_isCritical_regardlessOfDuration() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.FAILED, 1L));
        assertTrue(result.isBreached());
        assertEquals("CRITICAL", result.getSeverity());
        assertTrue(result.getReason().contains("Run status: FAILED"));
    }

    @Test
    void timeoutRun_isCritical() {
        SlaEvaluationResult result = service.evaluateSla(run(RunStatus.TIMEOUT, 80L * 60 * 1000));
        assertTrue(result.isBreached());
        assertEquals("CRITICAL", result.getSeverity());
    }

    @Test
    void ungraded_whenNoDeadlineResolved_treatedAsOnTime() {
        CalculatorRun run = CalculatorRun.builder()
                .status(RunStatus.SUCCESS)
                .startTime(START)
                .slaTime(null)            // no derived deadline
                .durationMs(999L * 60 * 1000)
                .build();
        SlaEvaluationResult result = service.evaluateSla(run);
        assertFalse(result.isBreached());
        assertNull(result.getSeverity());
    }
}

