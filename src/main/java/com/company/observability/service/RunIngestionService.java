package com.company.observability.service;

import com.company.observability.cache.RedisRunCache;
import com.company.observability.domain.*;
import com.company.observability.dto.request.*;
import com.company.observability.event.*;
import com.company.observability.exception.*;
import com.company.observability.repository.*;
import com.company.observability.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class RunIngestionService {

    private final CalculatorRunRepository runRepository;
    private final CalculatorRepository calculatorRepository;
    private final DailyAggregateRepository dailyAggregateRepository; // GT Enhancement
    private final SlaEvaluationService slaEvaluationService;
    private final RedisRunCache redisRunCache; // GT Enhancement
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CalculatorRun startRun(StartRunRequest request, String tenantId) {
        log.info("Starting run {} for calculator {} in tenant {}",
                request.getRunId(), request.getCalculatorId(), tenantId);

        Calculator calculator = calculatorRepository.findById(request.getCalculatorId())
                .orElseThrow(() -> new CalculatorNotFoundException(request.getCalculatorId()));

        CalculatorRun run = CalculatorRun.builder()
                .runId(request.getRunId())
                .calculatorId(request.getCalculatorId())
                .calculatorName(calculator.getName())
                .tenantId(tenantId)
                .frequency(calculator.getFrequency())
                .startTime(request.getStartTime())
                .startHourCet(TimeUtils.calculateCetHour(request.getStartTime()))
                .status("RUNNING")
                .slaDurationMs(calculator.getSlaTargetDurationMs())
                .slaEndHourCet(calculator.getSlaTargetEndHourCet())
                .runParameters(request.getRunParameters())
                .slaBreached(false)
                .build();

        runRepository.upsert(run);

        // GT Enhancement: Start Redis SLA timer
        redisRunCache.startSlaTimer(run);

        eventPublisher.publishEvent(new RunStartedEvent(run));

        log.info("Run {} started for {} calculator at CET hour {} with SLA timer",
                run.getRunId(), calculator.getFrequency(), run.getStartHourCet());

        return run;
    }

    @Transactional
    public CalculatorRun completeRun(String runId, CompleteRunRequest request, String tenantId) {
        log.info("Completing run {} in tenant {}", runId, tenantId);

        CalculatorRun run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));

        if (!run.getTenantId().equals(tenantId)) {
            throw new TenantAccessDeniedException(tenantId, runId);
        }

        long durationMs = Duration.between(run.getStartTime(), request.getEndTime()).toMillis();

        run.setEndTime(request.getEndTime());
        run.setDurationMs(durationMs);
        run.setEndHourCet(TimeUtils.calculateCetHour(request.getEndTime()));
        run.setStatus(request.getStatus() != null ? request.getStatus() : "SUCCESS");

        // GT Enhancement: Cancel Redis SLA timer (run completed normally)
        redisRunCache.cancelSlaTimer(runId);

        // CLD: Comprehensive SLA evaluation
        SlaEvaluationResult slaResult = slaEvaluationService.evaluateSla(run);
        run.setSlaBreached(slaResult.isBreached());
        run.setSlaBreachReason(slaResult.getReason());

        runRepository.upsert(run);

        // GT Enhancement: Update daily aggregates
        updateDailyAggregate(run);

        log.info("Run {} completed: duration={}ms, CET end={}, SLA breached={}",
                runId, durationMs, run.getEndHourCet(), slaResult.isBreached());

        if (slaResult.isBreached()) {
            eventPublisher.publishEvent(new SlaBreachedEvent(run, slaResult));
        } else {
            eventPublisher.publishEvent(new RunCompletedEvent(run));
        }

        return run;
    }

    /**
     * GT Enhancement: Update daily aggregate metrics atomically
     */
    private void updateDailyAggregate(CalculatorRun run) {
        try {
            dailyAggregateRepository.upsertDaily(
                    run.getCalculatorId(),
                    run.getTenantId(),
                    TimeUtils.getCetDate(run.getStartTime()),
                    run.getStatus(),
                    run.getSlaBreached(),
                    run.getDurationMs(),
                    TimeUtils.calculateCetMinute(run.getStartTime()),
                    TimeUtils.calculateCetMinute(run.getEndTime())
            );
            log.debug("Updated daily aggregate for calculator {}", run.getCalculatorId());
        } catch (Exception e) {
            log.error("Failed to update daily aggregate", e);
            // Don't fail the main transaction
        }
    }
}