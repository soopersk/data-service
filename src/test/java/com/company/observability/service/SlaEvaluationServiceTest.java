package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.util.SlaEvaluationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaEvaluationServiceTest {

    private final SlaEvaluationService service = new SlaEvaluationService();

    @Test
    void evaluateSla_returnsNoBreachForOnTimeSuccessfulRun() {
        Instant sla = Instant.parse("2026-02-22T05:15:00Z");
        CalculatorRun run = CalculatorRun.builder()
                .status(RunStatus.SUCCESS)
                .slaTime(sla)
                .endTime(sla.minusSeconds(60))
                .durationMs(1000L)
                .expectedDurationMs(1000L)
                .build();

        SlaEvaluationResult result = service.evaluateSla(run);

        assertFalse(result.isBreached());
        assertNull(result.getReason());
        assertNull(result.getSeverity());
    }

    @Test
    void evaluateSla_marksHighSeverityForLateCompletion() {
        Instant sla = Instant.parse("2026-02-22T05:15:00Z");
        CalculatorRun run = CalculatorRun.builder()
                .status(RunStatus.SUCCESS)
                .slaTime(sla)
                .endTime(sla.plusSeconds(40 * 60))
                .build();

        SlaEvaluationResult result = service.evaluateSla(run);

        assertTrue(result.isBreached());
        assertNotNull(result.getReason());
        assertTrue(result.getReason().contains("Finished"));
        assertTrue(result.getReason().contains("late"));
        assertTrue("HIGH".equals(result.getSeverity()) || "CRITICAL".equals(result.getSeverity()));
    }

    @Test
    void evaluateSla_marksCriticalForFailedRun() {
        CalculatorRun run = CalculatorRun.builder()
                .status(RunStatus.FAILED)
                .build();

        SlaEvaluationResult result = service.evaluateSla(run);

        assertTrue(result.isBreached());
        assertNotNull(result.getReason());
        assertTrue(result.getReason().contains("Run status: FAILED"));
        assertTrue("CRITICAL".equals(result.getSeverity()));
    }
}
