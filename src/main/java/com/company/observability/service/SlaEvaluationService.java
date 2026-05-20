package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.util.SlaEvaluationResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static com.company.observability.util.ObservabilityConstants.SLA_EVALUATION_DURATION;

/**
 * Grades a completed run against its frozen, duration-derived SLA deadline.
 *
 * <p>{@code slaTime} is the ON_TIME upper edge (entry into LATE) that
 * {@code SlaBaselineResolver} computed at run start. Because that edge already encodes
 * {@code buffered + lateBand}, the bands are recovered purely from the run's actual duration:
 * <pre>
 *   lateEdgeMs     = slaTime - startTime          (= buffered + lateBand)
 *   veryLateEdgeMs = lateEdgeMs + bandGap         (= buffered + veryLateBand)
 *   actual <= lateEdgeMs       -> ON_TIME    (no breach,    severity null)
 *   actual <= veryLateEdgeMs   -> LATE       (breach,       MEDIUM)
 *   actual >  veryLateEdgeMs   -> VERY_LATE  (breach,       HIGH)
 *   FAILED / TIMEOUT           -> breach,    CRITICAL  (regardless of duration)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaEvaluationService {

    private final MeterRegistry meterRegistry;
    private final DurationBasedSlaProperties props;

    public SlaEvaluationResult evaluateSla(CalculatorRun run) {
        Timer.Sample sample = Timer.start(meterRegistry);
        SlaEvaluationResult result = classify(run);
        sample.stop(Timer.builder(SLA_EVALUATION_DURATION)
                .tag("result", result.isBreached() ? "breached" : "clear")
                .register(meterRegistry));
        return result;
    }

    private SlaEvaluationResult classify(CalculatorRun run) {
        // Terminal failure short-circuits the duration bands.
        if (run.getStatus() == RunStatus.FAILED || run.getStatus() == RunStatus.TIMEOUT) {
            return new SlaEvaluationResult(true, "Run status: " + run.getStatus(), "CRITICAL");
        }

        // Ungraded: no derived deadline (no history / no estimate) → treat as on-time.
        if (run.getSlaTime() == null || run.getStartTime() == null || run.getDurationMs() == null) {
            return new SlaEvaluationResult(false, null, null);
        }

        long lateEdgeMs = Duration.between(run.getStartTime(), run.getSlaTime()).toMillis();
        long veryLateEdgeMs = lateEdgeMs + props.bandGapMs();
        long actualMs = run.getDurationMs();

        if (actualMs <= lateEdgeMs) {
            return new SlaEvaluationResult(false, null, null);
        }

        long minutesLate = (actualMs - lateEdgeMs) / 60_000L;
        if (actualMs <= veryLateEdgeMs) {
            return new SlaEvaluationResult(true,
                    String.format("Finished %d minutes late (LATE band)", minutesLate), "MEDIUM");
        }
        return new SlaEvaluationResult(true,
                String.format("Finished %d minutes late (VERY_LATE band)", minutesLate), "HIGH");
    }
}
