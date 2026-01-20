package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.util.SlaEvaluationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class SlaEvaluationService {

    /**
     * UPDATED: Evaluate SLA based on absolute time deadline (CET)
     */
    public SlaEvaluationResult evaluateSla(CalculatorRun run) {
        List<String> breachReasons = new ArrayList<>();

        // Check 1: End time exceeded SLA deadline (absolute time in CET)
        if (run.getSlaTime() != null && run.getEndTime() != null) {
            if (run.getEndTime().isAfter(run.getSlaTime())) {
                long delaySeconds = java.time.Duration.between(
                        run.getSlaTime(),
                        run.getEndTime()
                ).getSeconds();

                breachReasons.add(String.format(
                        "Finished %d minutes late (SLA: %s, Actual: %s)",
                        delaySeconds / 60,
                        run.getSlaTime(),
                        run.getEndTime()
                ));
            }
        }

        // Check 2: Still running past SLA deadline
        if (run.getSlaTime() != null && "RUNNING".equals(run.getStatus())) {
            Instant now = Instant.now();
            if (now.isAfter(run.getSlaTime())) {
                long delaySeconds = java.time.Duration.between(
                        run.getSlaTime(),
                        now
                ).getSeconds();

                breachReasons.add(String.format(
                        "Still running %d minutes past SLA deadline",
                        delaySeconds / 60
                ));
            }
        }

        // Check 3: Duration exceeded expected duration (separate from SLA)
        if (run.getExpectedDurationMs() != null && run.getDurationMs() != null) {
            if (run.getDurationMs() > run.getExpectedDurationMs() * 1.5) { // 50% over
                breachReasons.add(String.format(
                        "Duration significantly exceeded: %dms vs expected %dms",
                        run.getDurationMs(),
                        run.getExpectedDurationMs()
                ));
            }
        }

        // Check 4: Run failed
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

        // Check how late the run finished
        if (run.getSlaTime() != null && run.getEndTime() != null) {
            long delayMinutes = java.time.Duration.between(
                    run.getSlaTime(),
                    run.getEndTime()
            ).toMinutes();

            if (delayMinutes > 60) return "CRITICAL";  // More than 1 hour late
            if (delayMinutes > 30) return "HIGH";      // 30-60 minutes late
            if (delayMinutes > 15) return "MEDIUM";    // 15-30 minutes late
            return "LOW";                               // Less than 15 minutes late
        }

        return "MEDIUM";
    }
}