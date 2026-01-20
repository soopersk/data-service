package com.company.observability.scheduled;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.SlaEvaluationResult;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * LIVE SLA BREACH DETECTION (every 15 seconds)
 * Checks Redis sorted set for runs past SLA deadline
 * Much faster than database polling, near real-time detection
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = "observability.sla.live-detection.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LiveSlaBreachDetectionJob {

    private final SlaMonitoringCache slaMonitoringCache;
    private final CalculatorRunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${observability.sla.live-detection.interval-ms:15000}")
    private long detectionIntervalMs;

    /**
     * Check for SLA breaches every 15 seconds (near real-time)
     * Uses Redis sorted set for fast lookups
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.live-detection.interval-ms:15000}",
            initialDelayString = "${observability.sla.live-detection.initial-delay-ms:10000}"
    )
    @Transactional
    public void detectLiveSlaBreaches() {
        Instant startTime = Instant.now();

        try {
            // Get runs that exceeded SLA from Redis (very fast)
            List<Map<String, Object>> breachedRuns = slaMonitoringCache.getBreachedRuns();

            if (breachedRuns.isEmpty()) {
                log.debug("No live SLA breaches detected");
                recordMetrics(0, Duration.between(startTime, Instant.now()));
                return;
            }

            log.warn("LIVE DETECTION: Found {} runs past SLA deadline", breachedRuns.size());

            int processedCount = 0;

            for (Map<String, Object> runInfo : breachedRuns) {
                String runId = (String) runInfo.get("runId");

                try {
                    // Verify run is still RUNNING in database
                    Optional<CalculatorRun> runOpt = runRepository.findById(runId);

                    if (runOpt.isEmpty()) {
                        log.warn("Run {} not found in database, deregistering", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);
                        continue;
                    }

                    CalculatorRun run = runOpt.get();

                    // Check if already marked as breached or completed
                    if (!"RUNNING".equals(run.getStatus())) {
                        log.debug("Run {} already completed, deregistering", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);
                        continue;
                    }

                    if (Boolean.TRUE.equals(run.getSlaBreached())) {
                        log.debug("Run {} already marked as breached", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);
                        continue;
                    }

                    // Mark as breached in database
                    String breachReason = buildBreachReason(run);
                    int updated = runRepository.markSlaBreached(runId, breachReason);

                    if (updated > 0) {
                        // Publish breach event
                        String severity = determineSeverity(run);
                        SlaEvaluationResult result = new SlaEvaluationResult(
                                true,
                                breachReason,
                                severity
                        );

                        run.setSlaBreached(true);
                        run.setSlaBreachReason(breachReason);

                        eventPublisher.publishEvent(new SlaBreachedEvent(run, result));

                        // Deregister (no longer need to monitor)
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId);

                        processedCount++;

                        log.warn("LIVE BREACH: Run {} marked as breached ({})", runId, breachReason);

                        meterRegistry.counter("sla.breaches.live_detected",
                                "calculator", run.getCalculatorId(),
                                "severity", severity
                        ).increment();
                    }

                } catch (Exception e) {
                    log.error("Failed to process live SLA breach for run {}", runId, e);
                }
            }

            Duration executionTime = Duration.between(startTime, Instant.now());
            log.info("Live SLA detection completed: {}/{} breaches processed in {}ms",
                    processedCount, breachedRuns.size(), executionTime.toMillis());

            recordMetrics(processedCount, executionTime);

        } catch (Exception e) {
            log.error("Live SLA breach detection job failed", e);
            meterRegistry.counter("sla.breach.live_detection.failures").increment();
        }
    }

    /**
     * Also check for runs approaching SLA (early warning)
     */
    @Scheduled(
            fixedDelayString = "${observability.sla.early-warning.interval-ms:60000}",
            initialDelayString = "30000"
    )
    public void detectApproachingSla() {
        try {
            // Get runs that will breach SLA in next 10 minutes
            List<Map<String, Object>> approachingRuns =
                    slaMonitoringCache.getApproachingSlaRuns(10);

            if (!approachingRuns.isEmpty()) {
                log.info("EARLY WARNING: {} runs approaching SLA deadline (within 10 min)",
                        approachingRuns.size());

                for (Map<String, Object> runInfo : approachingRuns) {
                    log.warn("Run {} approaching SLA: calculator={}, deadline in ~{} min",
                            runInfo.get("runId"),
                            runInfo.get("calculatorName"),
                            calculateMinutesUntilSla(runInfo));
                }

                meterRegistry.gauge("sla.approaching.count", approachingRuns.size());
            }

        } catch (Exception e) {
            log.error("Failed to detect approaching SLA runs", e);
        }
    }

    private String buildBreachReason(CalculatorRun run) {
        long delayMinutes = Duration.between(run.getSlaTime(), Instant.now()).toMinutes();
        return String.format(
                "Still running %d minutes past SLA deadline (detected live via Redis monitoring)",
                delayMinutes
        );
    }

    private String determineSeverity(CalculatorRun run) {
        long delayMinutes = Duration.between(run.getSlaTime(), Instant.now()).toMinutes();

        if (delayMinutes > 120) return "CRITICAL";
        if (delayMinutes > 60) return "HIGH";
        if (delayMinutes > 30) return "MEDIUM";
        return "LOW";
    }

    private long calculateMinutesUntilSla(Map<String, Object> runInfo) {
        long slaTime = ((Number) runInfo.get("slaTime")).longValue();
        long now = Instant.now().toEpochMilli();
        return (slaTime - now) / 60000;
    }

    private void recordMetrics(int breachedCount, Duration executionTime) {
        meterRegistry.counter("sla.breach.live_detection.runs").increment();
        meterRegistry.timer("sla.breach.live_detection.duration").record(executionTime);

        if (breachedCount > 0) {
            meterRegistry.gauge("sla.breach.live_detection.last_breaches", breachedCount);
        }

        // Record monitored run count
        long monitoredCount = slaMonitoringCache.getMonitoredRunCount();
        meterRegistry.gauge("sla.monitoring.active_runs", monitoredCount);
    }
}