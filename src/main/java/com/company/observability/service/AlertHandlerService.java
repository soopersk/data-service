// File: src/main/java/com/company/observability/service/AlertHandlerService.java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaBreachEvent;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.SlaBreachEventRepository;
import com.company.observability.util.SlaEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlertHandlerService {
    
    private final SlaBreachEventRepository breachRepository;
    private final AzureMonitorAlertSender azureAlertSender;
    
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
            .expectedValue(run.getSlaDurationMs())
            .actualValue(run.getDurationMs())
            .severity(result.getSeverity())
            .alerted(false)
            .alertStatus("PENDING")
            .createdAt(Instant.now())
            .build();
        
        SlaBreachEvent savedBreach = breachRepository.save(breach);
        
        try {
            azureAlertSender.sendAlert(savedBreach, run);
            
            savedBreach.setAlerted(true);
            savedBreach.setAlertedAt(Instant.now());
            savedBreach.setAlertStatus("SENT");
            breachRepository.save(savedBreach);
            
            log.info("Alert sent successfully for breach {}", savedBreach.getBreachId());
            
        } catch (Exception e) {
            log.error("Failed to send alert for breach {}", savedBreach.getBreachId(), e);
            
            savedBreach.setAlertStatus("FAILED");
            breachRepository.save(savedBreach);
        }
    }
    
    private String determineBreachType(String reason) {
        if (reason.contains("Duration exceeded")) return "DURATION_EXCEEDED";
        if (reason.contains("End time exceeded")) return "TIME_EXCEEDED";
        if (reason.contains("FAILED")) return "FAILED";
        if (reason.contains("TIMEOUT")) return "TIMEOUT";
        return "UNKNOWN";
    }
}