package com.company.observability.util;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;

import java.time.Instant;
import java.util.List;

/**
 * Single source of truth for run status classification across all domain services.
 *
 * <p>Produces one of six status strings (new vocabulary):
 * <ul>
 *   <li>{@link #ON_TIME}     — completed on or before SLA deadline</li>
 *   <li>{@link #LATE}        — completed after SLA, within {@code lateThresholdMs}</li>
 *   <li>{@link #VERY_LATE}   — completed after SLA, beyond {@code lateThresholdMs}</li>
 *   <li>{@link #FAILED}      — terminated without success (FAILED, TIMEOUT, CANCELLED)</li>
 *   <li>{@link #IN_PROGRESS} — currently running</li>
 *   <li>{@link #NOT_STARTED} — no run found</li>
 * </ul>
 *
 * <p>Legacy vocabulary ({@link #RUNNING}, {@link #DELAYED}) is kept as deprecated
 * constants so older callers compile unchanged via the two-arg {@link #classify(CalculatorRun, Instant)}.
 */
public final class RunStatusClassifier {

    // ── New six-value vocabulary ─────────────────────────────────────────────
    public static final String ON_TIME     = "ON_TIME";
    public static final String LATE        = "LATE";
    public static final String VERY_LATE   = "VERY_LATE";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String FAILED      = "FAILED";
    public static final String NOT_STARTED = "NOT_STARTED";

    // ── Legacy aliases (kept for backward compatibility) ─────────────────────
    /** @deprecated use {@link #IN_PROGRESS} */
    @Deprecated public static final String RUNNING = "RUNNING";
    /** @deprecated use {@link #LATE} or {@link #VERY_LATE} */
    @Deprecated public static final String DELAYED = "DELAYED";

    private RunStatusClassifier() {}

    /**
     * Classifies a single run using the new six-value vocabulary.
     *
     * @param run             the calculator run (null → NOT_STARTED)
     * @param slaDeadline     the SLA deadline (null → no deadline check; treats any completed run as ON_TIME)
     * @param lateThresholdMs ms above SLA deadline that separates LATE from VERY_LATE
     */
    public static String classify(CalculatorRun run, Instant slaDeadline, long lateThresholdMs) {
        if (run == null) return NOT_STARTED;
        if (run.getStatus() == RunStatus.RUNNING) return IN_PROGRESS;
        if (run.getStatus() != RunStatus.SUCCESS && run.getStatus().isTerminal()) return FAILED;
        if (run.getEndTime() != null && slaDeadline != null && run.getEndTime().isAfter(slaDeadline)) {
            long overdueMs = run.getEndTime().toEpochMilli() - slaDeadline.toEpochMilli();
            return overdueMs > lateThresholdMs ? VERY_LATE : LATE;
        }
        return ON_TIME;
    }

    /**
     * Legacy two-arg classify — keeps non-dashboard callers compiling unchanged.
     *
     * @deprecated migrate to {@link #classify(CalculatorRun, Instant, long)}.
     */
    @Deprecated
    public static String classify(CalculatorRun run, Instant slaDeadline) {
        if (run == null) return NOT_STARTED;
        if (run.getStatus() == RunStatus.RUNNING) return RUNNING;
        if (run.getStatus() != RunStatus.SUCCESS && run.getStatus().isTerminal()) return FAILED;
        if (run.getEndTime() != null && slaDeadline != null && run.getEndTime().isAfter(slaDeadline)) {
            return DELAYED;
        }
        return ON_TIME;
    }

    /**
     * Returns true when the run ended after the SLA deadline.
     *
     * @param run         the calculator run (null → false)
     * @param slaDeadline the SLA deadline (null → false)
     */
    public static boolean isSlaBreach(CalculatorRun run, Instant slaDeadline) {
        if (run == null || slaDeadline == null || run.getEndTime() == null) return false;
        return run.getStatus().isTerminal() && run.getEndTime().isAfter(slaDeadline);
    }

    /**
     * Returns ms past SLA, or null if there is no lateness to report.
     *
     * <ul>
     *   <li>LATE / VERY_LATE: {@code endTime - slaDeadline}</li>
     *   <li>FAILED with slaBreached=true: {@code (endTime|now) - slaDeadline}, only if positive</li>
     *   <li>otherwise: null</li>
     * </ul>
     */
    public static Long computeLatenessMs(
            String status, boolean slaBreached, Instant endTime, Instant slaDeadline) {
        if (slaDeadline == null) return null;
        if (LATE.equals(status) || VERY_LATE.equals(status) || DELAYED.equals(status)) {
            return endTime == null ? null : endTime.toEpochMilli() - slaDeadline.toEpochMilli();
        }
        if (FAILED.equals(status) && slaBreached) {
            Instant ref = endTime != null ? endTime : Instant.now();
            long diff = ref.toEpochMilli() - slaDeadline.toEpochMilli();
            return diff > 0 ? diff : null;
        }
        return null;
    }

    /**
     * Returns the worst status across a collection.
     *
     * <p>Priority: FAILED &gt; IN_PROGRESS &gt; VERY_LATE &gt; LATE &gt; NOT_STARTED &gt; ON_TIME.
     * Legacy aliases {@code RUNNING} and {@code DELAYED} are folded into
     * IN_PROGRESS and LATE respectively.
     */
    public static String worstStatus(List<String> statuses) {
        if (statuses.contains(FAILED))                                  return FAILED;
        if (statuses.contains(IN_PROGRESS) || statuses.contains(RUNNING)) return IN_PROGRESS;
        if (statuses.contains(VERY_LATE))                               return VERY_LATE;
        if (statuses.contains(LATE) || statuses.contains(DELAYED))      return LATE;
        if (statuses.contains(NOT_STARTED))                             return NOT_STARTED;
        return ON_TIME;
    }
}
