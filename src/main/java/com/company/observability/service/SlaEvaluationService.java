package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.SlaEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class SlaEvaluationService {
    
    public SlaEvaluationResult evaluateSla(CalculatorRun run) {
        List<String> breachReasons = new ArrayList<>();
        
        // Check 1: Duration exceeded
        if (run.getSlaDurationMs() != null && run.getDurationMs() != null) {
            if (run.getDurationMs() > run.getSlaDurationMs()) {
                breachReasons.add(String.format(
                    "Duration exceeded: %dms > %dms", 
                    run.getDurationMs(), 
                    run.getSlaDurationMs()
                ));
            }
        }
        
        // Check 2: End time exceeded target CET hour
        if (run.getSlaEndHourCet() != null && run.getEndHourCet() != null) {
            if (run.getEndHourCet().compareTo(run.getSlaEndHourCet()) > 0) {
                breachReasons.add(String.format(
                    "End time exceeded: CET %s > target %s", 
                    run.getEndHourCet(), 
                    run.getSlaEndHourCet()
                ));
            }
        }
        
        // Check 3: Run failed
        if ("FAILED".equals(run.getStatus()) || "TIMEOUT".equals(run.getStatus())) {
            breachReasons.add("Run status: " + run.getStatus());
        }
        
        boolean breached = !breachReasons.isEmpty();
        String reason = breached ? String.join("; ", breachReasons) : null;
        
        return new SlaEvaluationResult(breached, reason, determineSeverity(run, breachReasons));
    }
    
    private String determineSeverity(CalculatorRun run, List<String> breachReasons) {
        if (breachReasons.isEmpty()) {
            return null;
        }
        
        if ("FAILED".equals(run.getStatus())) {
            return "CRITICAL";
        }
        
        if (run.getSlaDurationMs() != null && run.getDurationMs() != null) {
            double overage = (double) run.getDurationMs() / run.getSlaDurationMs();
            if (overage > 2.0) return "CRITICAL";
            if (overage > 1.5) return "HIGH";
            if (overage > 1.2) return "MEDIUM";
        }
        
        return "LOW";
    }
}