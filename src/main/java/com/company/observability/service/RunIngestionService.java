package com.company.observability.service;

import com.company.observability.cache.RedisCalculatorCache;
import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.dto.request.*;
import com.company.observability.event.*;
import com.company.observability.repository.*;
import com.company.observability.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunIngestionService {

    private final CalculatorRunRepository runRepository;
    private final DailyAggregateRepository dailyAggregateRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final RedisCalculatorCache redisCache;
    private final SlaMonitoringCache slaMonitoringCache;

    @Transactional
    public CalculatorRun startRun(StartRunRequest request, String tenantId) {
        // Check for existing run using partition key
        Optional<CalculatorRun> existing = runRepository.findById(
                request.getRunId(), request.getReportingDate());

        if (existing.isPresent()) {
            log.info("Duplicate start request for run {}", request.getRunId());
            meterRegistry.counter("calculator.runs.start.duplicate").increment();
            return existing.get();
        }

        log.info("Starting new run {} for calculator {} (reporting_date: {})",
                request.getRunId(), request.getCalculatorId(), request.getReportingDate());

        // Validate reporting_date matches frequency expectations
        validateReportingDate(request);

        Instant slaDeadline = TimeUtils.calculateSlaDeadline(
                request.getStartTime(), request.getSlaTimeCet());

        Instant estimatedEndTime = null;
        if (request.getExpectedDurationMs() != null) {
            estimatedEndTime = TimeUtils.calculateEstimatedEndTime(
                    request.getStartTime(), request.getExpectedDurationMs());
        }

        CalculatorRun run = CalculatorRun.builder()
                .runId(request.getRunId())
                .calculatorId(request.getCalculatorId())
                .calculatorName(request.getCalculatorName())
                .tenantId(tenantId)
                .frequency(request.getFrequency())
                .reportingDate(request.getReportingDate())
                .startTime(request.getStartTime())
                .startHourCet(TimeUtils.calculateCetHour(request.getStartTime()))
                .status("RUNNING")
                .slaTime(slaDeadline)
                .expectedDurationMs(request.getExpectedDurationMs())
                .estimatedStartTime(request.getStartTime())
                .estimatedEndTime(estimatedEndTime)
                .runParameters(request.getRunParameters())
                .slaBreached(false)
                .build();

        run = runRepository.upsert(run);

        // Register for live SLA monitoring
        slaMonitoringCache.registerForSlaMonitoring(run);

        eventPublisher.publishEvent(new RunStartedEvent(run));

        meterRegistry.counter("calculator.runs.started",
                "calculator", run.getCalculatorId(),
                "frequency", run.getFrequency()
        ).increment();

        log.info("Run {} started (reporting_date: {}, SLA deadline: {})",
                run.getRunId(), run.getReportingDate(), slaDeadline);

        return run;
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        // Try to find with recent reporting dates first
        Optional<CalculatorRun> runOpt = findRecentRun(runId);

        if (runOpt.isEmpty()) {
            throw new RuntimeException("Run not found: " + runId);
        }

        CalculatorRun run = runOpt.get();

        if (!run.getTenantId().equals(tenantId)) {
            throw new RuntimeException("Access denied to run " + runId + " for tenant " + tenantId);
        }

        if (!"RUNNING".equals(run.getStatus())) {
            log.info("Run {} already completed", runId);
            meterRegistry.counter("calculator.runs.complete.duplicate").increment();
            return run;
        }

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        run.setEndHourCet(TimeUtils.calculateCetHour(request.getEndTime()));
        run.setStatus(request.getStatus() != null ? request.getStatus() : "SUCCESS");

        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(slaResult.isBreached());
        run.setSlaBreachReason(slaResult.getReason());

        run = runRepository.upsert(run);

        // Deregister from SLA monitoring
        slaMonitoringCache.deregisterFromSlaMonitoring(runId);

        updateDailyAggregate(run);

        meterRegistry.counter("calculator.runs.completed",
                "calculator", run.getCalculatorId(),
                "frequency", run.getFrequency(),
                "status", run.getStatus(),
                "sla_breached", String.valueOf(run.getSlaBreached())
        ).increment();

        if (slaResult.isBreached()) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        } else {
            eventPublisher.publishEvent(new RunCompletedEvent(run));
        }

        log.info("Run {} completed (reporting_date: {})", runId, run.getReportingDate());

        return run;
    }

    /**
     * Find recent run by ID (checks recent partitions)
     */
    private Optional<CalculatorRun> findRecentRun(String runId) {
        // Try last 7 days of partitions
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate reportingDate = today.minusDays(i);
            Optional<CalculatorRun> run = runRepository.findById(runId, reportingDate);
            if (run.isPresent()) {
                return run;
            }
        }

        // Fallback to full scan (slower)
        return runRepository.findById(runId);
    }

    /**
     * Validate reporting_date matches frequency expectations
     */
    private void validateReportingDate(StartRunRequest request) {
        if ("MONTHLY".equalsIgnoreCase(request.getFrequency())) {
            // MONTHLY runs should have end-of-month reporting date
            LocalDate reportingDate = request.getReportingDate();
            LocalDate nextDay = reportingDate.plusDays(1);

            if (nextDay.getMonth() == reportingDate.getMonth()) {
                log.warn("MONTHLY run {} has non-end-of-month reporting_date: {}",
                        request.getRunId(), reportingDate);
                // Don't fail - just warn
            }
        }
    }

    private void updateDailyAggregate(CalculatorRun run) {
        try {
            dailyAggregateRepository.upsertDaily(
                    run.getCalculatorId(),
                    run.getTenantId(),
                    run.getReportingDate(),
                    run.getStatus(),
                    run.getSlaBreached(),
                    run.getDurationMs(),
                    TimeUtils.calculateCetMinute(run.getStartTime()),
                    TimeUtils.calculateCetMinute(run.getEndTime())
            );
        } catch (Exception e) {
            log.error("Failed to update daily aggregate", e);
        }
    }
}