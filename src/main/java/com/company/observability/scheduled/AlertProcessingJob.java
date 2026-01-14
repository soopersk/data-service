package com.company.observability.scheduled;

import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.service.AzureMonitorAlertSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Optional: Process pending alerts in batches
 * Useful if real-time event processing fails
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.alert.batch-processing.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class AlertProcessingJob {

    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender alertSender;

    /**
     * Process pending alerts every 5 minutes
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    @Transactional
    public void processPendingAlerts() {
        log.debug("Checking for pending alerts");

        List<SlaBreachEvent> pendingBreaches = breachRepository.findUnalertedBreaches(100);

        if (pendingBreaches.isEmpty()) {
            log.debug("No pending alerts to process");
            return;
        }

        log.info("Processing {} pending alerts", pendingBreaches.size());

        int successCount = 0;
        int failureCount = 0;

        for (SlaBreachEvent breach : pendingBreaches) {
            try {
                // Create a minimal CalculatorRun object for alerting
                com.company.observability.domain.CalculatorRun run =
                        com.company.observability.domain.CalculatorRun.builder()
                                .runId(breach.getRunId())
                                .calculatorId(breach.getCalculatorId())
                                .calculatorName(breach.getCalculatorName())
                                .slaBreachReason(breach.getBreachType())
                                .build();

                alertSender.sendAlert(breach, run);

                breach.setAlerted(true);
                breach.setAlertedAt(Instant.now());
                breach.setAlertStatus("SENT");
                breachRepository.update(breach);

                successCount++;

            } catch (Exception e) {
                log.error("Failed to send alert for breach {}", breach.getBreachId(), e);

                breach.setAlertStatus("FAILED");
                breachRepository.update(breach);

                failureCount++;
            }
        }

        log.info("Alert processing completed: {} succeeded, {} failed",
                successCount, failureCount);
    }
}