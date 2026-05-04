package com.company.observability.service;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.CompletionStatus;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.request.*;
import com.company.observability.exception.DomainAccessDeniedException;
import com.company.observability.exception.DomainNotFoundException;
import com.company.observability.exception.DomainValidationException;
import com.company.observability.event.*;
import com.company.observability.logging.LifecycleEvent;
import com.company.observability.logging.LifecycleLogger;
import com.company.observability.repository.*;
import com.company.observability.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.company.observability.util.ObservabilityConstants.*;
import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunIngestionService {

    private final CalculatorRunRepository runRepository;
    private final DailyAggregateRepository dailyAggregateRepository;
    private final SlaEvaluationService slaEvaluationService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final SlaMonitoringCache slaMonitoringCache;
    private final LifecycleLogger lifecycleLogger;

    @Value("${observability.sla.live-tracking.enabled:true}")
    private boolean liveTrackingEnabled;

    @Transactional
    public CalculatorRun startRun(StartRunRequest request, String tenantId) {
        var prev = MdcContextUtil.setCalculatorContext(request.getCalculatorId(), request.getRunId());
        try {
            return doStartRun(request, tenantId);
        } finally {
            MdcContextUtil.restoreContext(prev);
        }
    }

    private CalculatorRun doStartRun(StartRunRequest request, String tenantId) {
        // Check for existing run using partition key
        Optional<CalculatorRun> existing = runRepository.findById(
                request.getRunId(), request.getReportingDate());

        if (existing.isPresent()) {
            lifecycleLogger.emit(LifecycleEvent.RUN_START_REJECTED, kv("reason", "duplicate"));
            meterRegistry.counter(INGESTION_RUN_DUPLICATE, "phase", "start").increment();
            return existing.get();
        }

        lifecycleLogger.emit(LifecycleEvent.RUN_START_SUCCESS,
                kv("freq", request.getFrequency()), kv("reportingDate", request.getReportingDate()));

        // Validate reporting_date matches frequency expectations
        validateReportingDate(request);

        CalculatorFrequency frequency = Objects.requireNonNullElse(
                request.getFrequency(), CalculatorFrequency.DAILY);

        Instant slaDeadline = null;
        if (frequency == CalculatorFrequency.DAILY) {
            slaDeadline = request.getSlaTime();
        }

        boolean breachedAtStart = false;
        String breachReasonAtStart = null;
        if (slaDeadline != null && request.getStartTime() != null
                && request.getStartTime().isAfter(slaDeadline)) {
            breachedAtStart = true;
            breachReasonAtStart = String.format(
                    "Start time %s is after SLA deadline %s (reporting_date=%s)",
                    request.getStartTime(),
                    slaDeadline,
                    request.getReportingDate()
            );
            log.warn("event=run.start.sla_check outcome=failure reason=start_after_deadline");
        }

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
                .frequency(frequency)
                .reportingDate(request.getReportingDate())
                .startTime(request.getStartTime())
                .status(RunStatus.RUNNING)
                .slaTime(slaDeadline)
                .expectedDurationMs(request.getExpectedDurationMs())
                .estimatedStartTime(request.getStartTime())
                .estimatedEndTime(estimatedEndTime)
                .runNumber(resolveField(request.getRunNumber(), request.getRunParameters(), "run_number"))
                .runType(resolveField(request.getRunType(), request.getRunParameters(), "run_type"))
                .region(resolveField(request.getRegion(), request.getRunParameters(), "region"))
                .runParameters(request.getRunParameters())
                .additionalAttributes(request.getAdditionalAttributes())
                .slaBreached(breachedAtStart)
                .slaBreachReason(breachedAtStart ? breachReasonAtStart : null)
                .build();

        run = runRepository.upsert(run);

        // Register for live SLA monitoring (daily only, feature toggle, not already breached)
        if (liveTrackingEnabled
                && frequency == CalculatorFrequency.DAILY
                && slaDeadline != null
                && !breachedAtStart) {
            slaMonitoringCache.registerForSlaMonitoring(run);
        }

        if (breachedAtStart) {
            SlaEvaluationResult result = new SlaEvaluationResult(
                    true,
                    breachReasonAtStart,
                    "HIGH"
            );
            eventPublisher.publishEvent(new SlaBreachedEvent(run, result));
        }

        eventPublisher.publishEvent(new RunStartedEvent(run));

        meterRegistry.counter(INGESTION_RUN_STARTED,
                "frequency", run.getFrequency().name()
        ).increment();

        log.info("event=run.start.persist outcome=success slaDeadline={} liveTracking={}",
                slaDeadline, liveTrackingEnabled);

        return run;
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        Optional<CalculatorRun> runOpt = runRepository.findById(runId, request.getReportingDate());

        if (runOpt.isEmpty()) {
            throw new DomainNotFoundException("Run not found: " + runId + " for reportingDate=" + request.getReportingDate());
        }

        CalculatorRun run = runOpt.get();

        var prev = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), runId);
        try {
            return doCompleteRun(run, request, tenantId);
        } finally {
            MdcContextUtil.restoreContext(prev);
        }
    }

    private CalculatorRun doCompleteRun(CalculatorRun run, CompleteRunRequest request, String tenantId) {
        if (!run.getTenantId().equals(tenantId)) {
            throw new DomainAccessDeniedException("Access denied to run " + run.getRunId() + " for tenant " + tenantId);
        }

        if (run.getStatus() != RunStatus.RUNNING) {
            lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_REJECTED, kv("reason", "duplicate"));
            meterRegistry.counter(INGESTION_RUN_DUPLICATE, "phase", "complete").increment();
            return run;
        }

        if (request.getEndTime().isBefore(run.getStartTime())) {
            throw new DomainValidationException("End time cannot be before start time");
        }

        boolean alreadyBreached = Boolean.TRUE.equals(run.getSlaBreached());
        String previousBreachReason = run.getSlaBreachReason();

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        CompletionStatus completionStatus = request.getStatus() != null
                ? request.getStatus()
                : CompletionStatus.SUCCESS;
        run.setStatus(completionStatus.toRunStatus());

        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(alreadyBreached || slaResult.isBreached());
        run.setSlaBreachReason(
                slaResult.getReason() != null ? slaResult.getReason() : previousBreachReason
        );

        run = runRepository.upsert(run);

        // Deregister from SLA monitoring
        slaMonitoringCache.deregisterFromSlaMonitoring(run.getRunId(), run.getTenantId(), run.getReportingDate());

        updateDailyAggregate(run);

        meterRegistry.counter(INGESTION_RUN_COMPLETED,
                "frequency", run.getFrequency().name(),
                "status", run.getStatus().name(),
                "sla_breached", String.valueOf(run.getSlaBreached())
        ).increment();

        boolean newlyBreached = !alreadyBreached && slaResult.isBreached();
        if (newlyBreached) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        } else {
            eventPublisher.publishEvent(new RunCompletedEvent(run));
        }

        lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_SUCCESS, kv("reportingDate", run.getReportingDate()));

        return run;
    }

    /**
     * Validate reporting_date matches frequency expectations
     */
    private void validateReportingDate(StartRunRequest request) {
        if (request.getFrequency() == CalculatorFrequency.MONTHLY) {
            // MONTHLY runs should have end-of-month reporting date
            LocalDate reportingDate = request.getReportingDate();
            LocalDate nextDay = reportingDate.plusDays(1);

            if (nextDay.getMonth() == reportingDate.getMonth()) {
                log.warn("event=run.validate.reporting_date outcome=rejected reason=non_eom_monthly reportingDate={}",
                        reportingDate);
            }
        }
    }

    /**
     * Resolves a promoted field: top-level request field takes precedence,
     * falls back to runParameters map for backward compatibility.
     */
    private String resolveField(String topLevel, Map<String, Object> params, String key) {
        if (topLevel != null) return topLevel;
        if (params == null) return null;
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    private void updateDailyAggregate(CalculatorRun run) {
        try {
            int startMinUtc = run.getStartTime().atZone(java.time.ZoneOffset.UTC).getHour() * 60
                    + run.getStartTime().atZone(java.time.ZoneOffset.UTC).getMinute();
            int endMinUtc = (run.getEndTime() != null)
                    ? run.getEndTime().atZone(java.time.ZoneOffset.UTC).getHour() * 60
                      + run.getEndTime().atZone(java.time.ZoneOffset.UTC).getMinute()
                    : 0;
            dailyAggregateRepository.upsertDaily(
                    run.getCalculatorId(),
                    run.getTenantId(),
                    run.getReportingDate(),
                    run.getStatus().name(),
                    run.getSlaBreached(),
                    run.getDurationMs(),
                    startMinUtc,
                    endMinUtc
            );
        } catch (Exception e) {
            log.error("event=daily_aggregate.upsert outcome=failure reportingDate={}",
                    run.getReportingDate(), e);
        }
    }
}
