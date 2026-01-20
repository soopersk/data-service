package com.company.observability.service;

import com.company.observability.domain.*;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.SlaEvaluationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * FIXED: Idempotent alert handling with circuit breaker
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertHandlerService {

    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender azureAlertSender;
    private final MeterRegistry meterRegistry;

    @EventListener
    @Async
    @Transactional
    public void handleSlaBreachEvent(SlaBreachedEvent event) {
        CalculatorRun run = event.getRun();
        SlaEvaluationResult result = event.getResult();

        log.warn("Processing SLA breach for run {}: {}", run.getRunId(), result.getReason());

        SlaBreachEvent breach = SlaBreachEvent.builder()
                .runId(run.getRunId())
                .calculatorId(run.getCalculatorId())
                .calculatorName(run.getCalculatorName())
                .tenantId(run.getTenantId())
                .breachType(determineBreachType(result.getReason()))
                .expectedValue(calculateExpectedValue(run))
                .actualValue(calculateActualValue(run))
                .severity(result.getSeverity())
                .alerted(false)
                .alertStatus("PENDING")
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        SlaBreachEvent savedBreach;

        try {
            // FIXED: Save with idempotency - will throw exception if duplicate
            savedBreach = breachRepository.save(breach);

            meterRegistry.counter("sla.breaches.created",
                    "calculator", run.getCalculatorId(),
                    "severity", result.getSeverity()
            ).increment();

        } catch (DuplicateKeyException e) {
            log.warn("SLA breach already recorded for run {}, skipping duplicate alert",
                    run.getRunId());

            meterRegistry.counter("sla.breaches.duplicate",
                    "calculator", run.getCalculatorId()
            ).increment();

            return; // Idempotent - exit gracefully
        }

        // Send alert with retry and circuit breaker
        sendAlertWithRetry(savedBreach, run);
    }

    @Retry(name = "azureMonitorAlert", fallbackMethod = "alertSendFallback")
    @CircuitBreaker(name = "azureMonitorAlert", fallbackMethod = "alertSendFallback")
    private void sendAlertWithRetry(SlaBreachEvent breach, CalculatorRun run) {
        try {
            azureAlertSender.sendAlert(breach, run);

            breach.setAlerted(true);
            breach.setAlertedAt(Instant.now());
            breach.setAlertStatus("SENT");
            breachRepository.update(breach);

            meterRegistry.counter("sla.alerts.sent",
                    "calculator", run.getCalculatorId(),
                    "severity", breach.getSeverity()
            ).increment();

            log.info("Alert sent successfully for breach {}", breach.getBreachId());

        } catch (Exception e) {
            log.error("Failed to send alert for breach {}", breach.getBreachId(), e);

            breach.setAlertStatus("FAILED");
            breach.setRetryCount(breach.getRetryCount() + 1);
            breach.setLastError(e.getMessage());
            breachRepository.update(breach);

            meterRegistry.counter("sla.alerts.failed",
                    "calculator", run.getCalculatorId()
            ).increment();

            throw e; // Re-throw to trigger retry/circuit breaker
        }
    }

    /**
     * Fallback when Azure Monitor is unavailable
     */
    private void alertSendFallback(SlaBreachEvent breach, CalculatorRun run, Exception e) {
        log.error("Azure Monitor unavailable for breach {}, marking for retry: {}",
                breach.getBreachId(), e.getMessage());

        breach.setAlertStatus("PENDING");
        breach.setRetryCount(breach.getRetryCount() + 1);
        breach.setLastError("Circuit open: " + e.getMessage());
        breachRepository.update(breach);

        meterRegistry.counter("sla.alerts.circuit_open",
                "calculator", run.getCalculatorId()
        ).increment();

        // Batch job will retry later
    }

    private String determineBreachType(String reason) {
        if (reason == null) return "UNKNOWN";
        if (reason.contains("Finished") && reason.contains("late")) return "TIME_EXCEEDED";
        if (reason.contains("Still running")) return "STILL_RUNNING_PAST_SLA";
        if (reason.contains("Duration") && reason.contains("exceeded")) return "DURATION_EXCEEDED";
        if (reason.contains("FAILED")) return "FAILED";
        if (reason.contains("TIMEOUT")) return "TIMEOUT";
        return "UNKNOWN";
    }

    private Long calculateExpectedValue(CalculatorRun run) {
        if (run.getSlaTime() != null) {
            return run.getSlaTime().getEpochSecond();
        } else if (run.getExpectedDurationMs() != null) {
            return run.getExpectedDurationMs();
        }
        return null;
    }

    private Long calculateActualValue(CalculatorRun run) {
        if (run.getEndTime() != null && run.getSlaTime() != null) {
            return run.getEndTime().getEpochSecond();
        } else if (run.getDurationMs() != null) {
            return run.getDurationMs();
        }
        return null;
    }
}