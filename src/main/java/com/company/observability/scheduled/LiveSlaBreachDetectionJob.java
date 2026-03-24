package com.company.observability.scheduled;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.event.SlaBreachedEvent;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.MdcContextUtil;
import com.company.observability.util.SlaEvaluationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.company.observability.util.ObservabilityConstants.*;

/**
 * LIVE SLA BREACH DETECTION (every 15 seconds)
 * Checks Redis sorted set for runs past SLA deadline
 * Much faster than database polling, near real-time detection
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        value = {"observability.sla.live-detection.enabled", "observability.sla.live-tracking.enabled"},
        havingValue = "true",
        matchIfMissing = true
)
public class LiveSlaBreachDetectionJob {

    private final SlaMonitoringCache slaMonitoringCache;
    private final CalculatorRunRepository runRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger approachingRunsGauge = new AtomicInteger(0);
    private final AtomicInteger lastBreachesGauge = new AtomicInteger(0);
    private final AtomicLong activeRunsGauge = new AtomicLong(0L);

    @Value("${observability.sla.live-detection.interval-ms:15000}")
    private long detectionIntervalMs;

    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge(SLA_APPROACHING_COUNT, approachingRunsGauge);
        meterRegistry.gauge(SLA_DETECTION_LAST_BREACHES, lastBreachesGauge);
        meterRegistry.gauge(SLA_MONITORING_ACTIVE, activeRunsGauge);
    }

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
        Map<String, String> snapshot = MdcContextUtil.setJobContext("live-sla-detection");
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<Map<String, Object>> breachedRuns = slaMonitoringCache.getBreachedRuns();

            if (breachedRuns.isEmpty()) {
                log.debug("event=sla.live_detection outcome=none_found");
                recordMetrics(sample, 0);
                return;
            }

            log.warn("event=sla.live_detection outcome=breaches_found count={}", breachedRuns.size());

            int processedCount = 0;

            for (Map<String, Object> runInfo : breachedRuns) {
                String runId = (String) runInfo.get("runId");
                String tenantId = (String) runInfo.get("tenantId");
                String reportingDateStr = (String) runInfo.get("reportingDate");
                LocalDate reportingDate = null;
                if (reportingDateStr != null) {
                    reportingDate = LocalDate.parse(reportingDateStr);
                }

                Map<String, String> runSnapshot = MdcContextUtil.setCalculatorContext(
                        (String) runInfo.get("calculatorId"), runId);

                try {
                    Optional<CalculatorRun> runOpt = reportingDate != null
                            ? runRepository.findById(runId, reportingDate)
                            : runRepository.findById(runId);

                    if (runOpt.isEmpty()) {
                        log.warn("event=sla.live_detection.run_lookup outcome=not_found runId={}", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        continue;
                    }

                    CalculatorRun run = runOpt.get();

                    if (run.getStatus() != RunStatus.RUNNING) {
                        log.debug("event=sla.live_detection.run_check outcome=already_completed runId={}", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        continue;
                    }

                    if (Boolean.TRUE.equals(run.getSlaBreached())) {
                        log.debug("event=sla.live_detection.run_check outcome=already_breached runId={}", runId);
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        continue;
                    }

                    String breachReason = buildBreachReason(run);
                    int updated = runRepository.markSlaBreached(
                            runId, breachReason, run.getReportingDate());

                    if (updated > 0) {
                        String severity = determineSeverity(run);
                        SlaEvaluationResult result = new SlaEvaluationResult(
                                true,
                                breachReason,
                                severity
                        );

                        run.setSlaBreached(true);
                        run.setSlaBreachReason(breachReason);

                        eventPublisher.publishEvent(new SlaBreachedEvent(run, result));
                        slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate);
                        processedCount++;

                        log.warn("event=sla.live_breach outcome=marked runId={} reason={} severity={}",
                                runId, breachReason, severity);

                        meterRegistry.counter(SLA_BREACH_LIVE_DETECTED,
                                "severity", severity
                        ).increment();
                    }

                } catch (Exception e) {
                    log.error("event=sla.live_detection.run_process outcome=failure runId={}", runId, e);
                } finally {
                    MdcContextUtil.restoreContext(runSnapshot);
                }
            }

            log.info("event=sla.live_detection.completed outcome=success processed={} total={}",
                    processedCount, breachedRuns.size());

            recordMetrics(sample, processedCount);

        } catch (Exception e) {
            log.error("event=sla.live_detection outcome=failure", e);
            meterRegistry.counter(SLA_DETECTION_FAILURE).increment();
        } finally {
            MdcContextUtil.restoreContext(snapshot);
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
        Map<String, String> snapshot = MdcContextUtil.setJobContext("sla-early-warning");

        try {
            List<Map<String, Object>> approachingRuns =
                    slaMonitoringCache.getApproachingSlaRuns(10);

            if (!approachingRuns.isEmpty()) {
                log.info("event=sla.early_warning outcome=runs_approaching count={}", approachingRuns.size());

                for (Map<String, Object> runInfo : approachingRuns) {
                    log.warn("event=sla.early_warning.run runId={} calculator={} minutesUntilSla={}",
                            runInfo.get("runId"),
                            runInfo.get("calculatorName"),
                            calculateMinutesUntilSla(runInfo));
                }
            }
            approachingRunsGauge.set(approachingRuns.size());

        } catch (Exception e) {
            log.error("event=sla.early_warning outcome=failure", e);
        } finally {
            MdcContextUtil.restoreContext(snapshot);
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

    private void recordMetrics(Timer.Sample sample, int breachedCount) {
        meterRegistry.counter(SLA_DETECTION_EXECUTION).increment();
        sample.stop(meterRegistry.timer(SLA_DETECTION_DURATION));

        lastBreachesGauge.set(breachedCount);

        long monitoredCount = slaMonitoringCache.getMonitoredRunCount();
        activeRunsGauge.set(monitoredCount);
    }
}
