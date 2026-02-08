package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.SlaEvaluationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * FIXED: Use TransactionalEventListener to prevent alerts before DB commit
 * FIXED: Reduced cardinality in metrics
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertHandlerService {

    private final SlaBreachEventRepository breachRepository;
    private final MeterRegistry meterRegistry;

    /**
     * FIXED: Only send alerts after transaction commits
     * FIXED: New transaction for alert processing
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            savedBreach = breachRepository.save(breach);

            // FIXED: Reduced cardinality - no calculatorId
            meterRegistry.counter("sla.breaches.created",
                    Tags.of(
                            Tag.of("severity", result.getSeverity()),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

        } catch (DuplicateKeyException e) {
            log.warn("SLA breach already recorded for run {}, skipping duplicate alert",
                    run.getRunId());

            meterRegistry.counter("sla.breaches.duplicate",
                    Tags.of(
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

            return;
        }

        // Phase-1: simple alerting via logs
        sendSimpleAlert(savedBreach, run);
    }

    private void sendSimpleAlert(SlaBreachEvent breach, CalculatorRun run) {
        try {
            log.warn("SLA breach alert: runId={}, calculator={}, severity={}, reason={}",
                    breach.getRunId(),
                    breach.getCalculatorName(),
                    breach.getSeverity(),
                    run.getSlaBreachReason());

            breach.setAlerted(true);
            breach.setAlertedAt(Instant.now());
            breach.setAlertStatus("SENT");
            breachRepository.update(breach);

            // FIXED: Reduced cardinality
            meterRegistry.counter("sla.alerts.sent",
                    Tags.of(
                            Tag.of("severity", breach.getSeverity()),
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

            log.info("Alert sent successfully for breach {}", breach.getBreachId());

        } catch (Exception e) {
            log.error("Failed to send alert for breach {}", breach.getBreachId(), e);

            breach.setAlertStatus("FAILED");
            breach.setRetryCount(breach.getRetryCount() + 1);
            breach.setLastError(e.getMessage());
            breachRepository.update(breach);

            meterRegistry.counter("sla.alerts.failed",
                    Tags.of(
                            Tag.of("frequency", run.getFrequency().name())
                    )
            ).increment();

            throw e;
        }
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
