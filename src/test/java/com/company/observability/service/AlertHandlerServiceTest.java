package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.SlaEvaluationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertHandlerServiceTest {

    @Mock
    private SlaBreachEventRepository breachRepository;

    private SimpleMeterRegistry meterRegistry;
    private AlertHandlerService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AlertHandlerService(breachRepository, meterRegistry);
    }

    @Test
    void handleSlaBreachEvent_savesAndMarksAlertSent() {
        CalculatorRun run = baseRun();
        SlaEvaluationResult result = new SlaEvaluationResult(true, "Finished 10 minutes late", "HIGH");

        SlaBreachEvent saved = SlaBreachEvent.builder()
                .breachId(101L)
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .tenantId(run.getTenantId())
                .severity("HIGH")
                .alerted(false)
                .retryCount(0)
                .build();

        when(breachRepository.save(any(SlaBreachEvent.class))).thenReturn(saved);

        service.handleSlaBreachEvent(new SlaBreachedEvent(run, result));

        verify(breachRepository).save(any(SlaBreachEvent.class));

        ArgumentCaptor<SlaBreachEvent> updateCaptor = ArgumentCaptor.forClass(SlaBreachEvent.class);
        verify(breachRepository).update(updateCaptor.capture());
        SlaBreachEvent updated = updateCaptor.getValue();

        assertTrue(Boolean.TRUE.equals(updated.getAlerted()));
        assertEquals("SENT", updated.getAlertStatus());
        assertEquals(1.0, meterRegistry.get("sla.breaches.created").counter().count());
        assertEquals(1.0, meterRegistry.get("sla.alerts.sent").counter().count());
    }

    @Test
    void handleSlaBreachEvent_duplicateSaveSkipsAlertUpdate() {
        CalculatorRun run = baseRun();
        SlaEvaluationResult result = new SlaEvaluationResult(true, "Still running 5 minutes past SLA deadline", "LOW");

        when(breachRepository.save(any(SlaBreachEvent.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        service.handleSlaBreachEvent(new SlaBreachedEvent(run, result));

        verify(breachRepository).save(any(SlaBreachEvent.class));
        verify(breachRepository, never()).update(any(SlaBreachEvent.class));
        assertEquals(1.0, meterRegistry.get("sla.breaches.duplicate").counter().count());
    }

    private CalculatorRun baseRun() {
        return CalculatorRun.builder()
                .runId("run-1")
                .calculatorId("calc-1")
                .calculatorName("Calculator 1")
                .tenantId("tenant-1")
                .frequency(CalculatorFrequency.DAILY)
                .slaTime(Instant.parse("2026-02-22T05:15:00Z"))
                .endTime(Instant.parse("2026-02-22T05:25:00Z"))
                .durationMs(600000L)
                .slaBreachReason("breach")
                .build();
    }
}
