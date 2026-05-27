package com.company.observability.service;

import com.company.observability.cache.SlaMonitoringCache;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.SlaEvaluationResult;
import com.company.observability.domain.enums.Frequency;
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
    private final SlaEvaluationService slaEvaluationService;
    private final SlaBaselineResolver slaBaselineResolver;
    private final CalculatorProfileService calculatorProfileService;
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

        Frequency frequency = Objects.requireNonNullElse(
                request.getFrequency(), Frequency.DAILY);

        // Fetch the calculator's cached rolling profile once (Redis-backed, no DB on warm cache).
        // It feeds both the SLA baseline and the estimated start/end fallbacks.
        CalculatorProfile profile = calculatorProfileService.getProfile(
                request.getCalculatorName(), frequency);

        // Derive SLA baseline + deadline once and reuse for persistence + estimated-end fallback.
        SlaBaselineResolver.SlaResolution slaResolution = slaBaselineResolver.resolve(request, frequency, profile);
        Instant slaDeadline = slaResolution.deadline();
        Long resolvedBaselineDurationMs = slaResolution.baselineDurationMs();

        Instant estimatedStartTime = resolveEstimatedStart(request, profile);
        Instant estimatedEndTime = resolveEstimatedEnd(request, estimatedStartTime, profile, slaResolution);

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
                .expectedDurationMs(resolvedBaselineDurationMs)
                .estimatedStartTime(estimatedStartTime)
                .estimatedEndTime(estimatedEndTime)
                .runNumber(resolveField(request.getRunNumber(), request.getRunParameters(), "run_number"))
                .runType(resolveField(request.getRunType(), request.getRunParameters(), "run_type"))
                .region(resolveField(request.getRegion(), request.getRunParameters(), "region"))
                .correlationId(request.getCorrelationId())
                .runParameters(request.getRunParameters())
                .additionalAttributes(request.getAdditionalAttributes())
                .slaBand(null)
                .slaBreachReason(null)
                .build();

        run = runRepository.upsert(run);

        // Register for live SLA monitoring (DAILY and MONTHLY) whenever a deadline was derived.
        if (liveTrackingEnabled && slaDeadline != null) {
            slaMonitoringCache.registerForSlaMonitoring(run);
        }

        eventPublisher.publishEvent(new RunStartedEvent(run));

        meterRegistry.counter(INGESTION_RUN_STARTED,
                "frequency", run.getFrequency().name()
        ).increment();

        log.info("event=run.start.persist outcome=success baselineMs={} slaDeadline={} liveTracking={}",
                resolvedBaselineDurationMs, slaDeadline, liveTrackingEnabled);

        return run;
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        Optional<CalculatorRun> runOpt = runRepository.findById(runId, request.getReportingDate());

        if (runOpt.isEmpty()) {
            throw new DomainNotFoundException("Run not found: " + runId + " for reportingDate=" + request.getReportingDate());
        }

        CalculatorRun run = runOpt.get();

        if (tenantId != null && !tenantId.equals(run.getTenantId())) {
            throw new DomainAccessDeniedException("Run " + runId + " does not belong to tenant " + tenantId);
        }

        var prev = MdcContextUtil.setCalculatorContext(run.getCalculatorId(), runId);
        try {
            return doCompleteRun(run, request);
        } finally {
            MdcContextUtil.restoreContext(prev);
        }
    }

    private CalculatorRun doCompleteRun(CalculatorRun run, CompleteRunRequest request) {
        if (run.getStatus() != RunStatus.RUNNING) {
            lifecycleLogger.emit(LifecycleEvent.RUN_COMPLETE_REJECTED, kv("reason", "duplicate"));
            meterRegistry.counter(INGESTION_RUN_DUPLICATE, "phase", "complete").increment();
            return run;
        }

        if (request.getEndTime().isBefore(run.getStartTime())) {
            throw new DomainValidationException("End time cannot be before start time");
        }

        // Capture any breach already set by live detection (band non-null → already breached)
        boolean alreadyBreached = run.getSlaBand() != null && run.getSlaBand().isBreached();
        String previousBreachReason = run.getSlaBreachReason();

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        CompletionStatus completionStatus = request.getStatus() != null
                ? request.getStatus()
                : CompletionStatus.SUCCESS;
        run.setStatus(completionStatus.toRunStatus());

        // Grade timing (independent of failure status)
        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);

        // Timing band: use live-detection band if already set, otherwise use on-write result
        if (!alreadyBreached && slaResult.getBand() != null) {
            run.setSlaBand(slaResult.getBand());
        }
        run.setSlaBreachReason(
                slaResult.getReason() != null ? slaResult.getReason() : previousBreachReason
        );

        // Compute breach flag before upsert so it is persisted to sla_breached
        boolean timingBreached = run.getSlaBand() != null && run.getSlaBand().isBreached();
        boolean failureBreached = run.getStatus() == RunStatus.FAILED || run.getStatus() == RunStatus.TIMEOUT;
        boolean isBreached = timingBreached || failureBreached;
        run.setSlaBreached(isBreached);

        run = runRepository.upsert(run);

        // Deregister from SLA monitoring
        slaMonitoringCache.deregisterFromSlaMonitoring(run.getRunId(), run.getTenantId(), run.getReportingDate());

        // calculator_sli_daily is populated by the nightly DailyAggregationJob, not per completion.

        meterRegistry.counter(INGESTION_RUN_COMPLETED,
                "frequency", run.getFrequency().name(),
                "status", run.getStatus().name(),
                "sla_breached", String.valueOf(isBreached)
        ).increment();

        boolean newlyBreached = !alreadyBreached && isBreached;
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
        if (request.getFrequency() == Frequency.MONTHLY) {
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

    /**
     * Estimated start precedence: request value (Airflow) → historical avg start (cached
     * profile) → actual start time.
     */
    private Instant resolveEstimatedStart(StartRunRequest request, CalculatorProfile profile) {
        if (request.getEstimatedStartTime() != null) {
            return request.getEstimatedStartTime();
        }
        if (profile != null && profile.totalRuns() > 0 && request.getStartTime() != null) {
            LocalDate startDateUtc = request.getStartTime().atZone(ZoneOffset.UTC).toLocalDate();
            return TimeUtils.instantFromUtcMinuteOfDay(startDateUtc, profile.avgStartMinUtc());
        }
        return request.getStartTime();
    }

    /**
     * Estimated end precedence: request value (Airflow) → resolved baseline fallback
     * (anchored to start for external inputs, estimatedStart for profile average) →
     * estimatedStart + historical avg duration (cached profile) → null.
     */
    private Instant resolveEstimatedEnd(
            StartRunRequest request,
            Instant estimatedStart,
            CalculatorProfile profile,
            SlaBaselineResolver.SlaResolution slaResolution
    ) {
        if (request.getEstimatedEndTime() != null) {
            return request.getEstimatedEndTime();
        }

        if (slaResolution != null && slaResolution.baselineDurationMs() != null) {
            boolean usingProfileAverage = request.getExpectedDurationMs() == null
                    && (request.getSlaTime() == null || request.getSlaTime().isBlank());
            if (usingProfileAverage && estimatedStart != null) {
                return estimatedStart.plusMillis(slaResolution.baselineDurationMs());
            }
            return TimeUtils.calculateEstimatedEndTime(request.getStartTime(), slaResolution.baselineDurationMs());
        }

        if (profile != null && profile.avgDurationMs() > 0 && estimatedStart != null) {
            return estimatedStart.plusMillis(profile.avgDurationMs());
        }
        return null;
    }
}
