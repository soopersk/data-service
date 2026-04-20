package com.company.observability.util;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;

import java.time.Instant;
import java.util.List;

/**
 * Single source of truth for run status classification across all domain services.
 *
 * <p>Produces one of five status strings:
 * <ul>
 *   <li>{@link #ON_TIME}     — completed on or before SLA deadline</li>
 *   <li>{@link #DELAYED}     — completed after SLA deadline</li>
 *   <li>{@link #FAILED}      — terminated without success (FAILED, TIMEOUT, CANCELLED)</li>
 *   <li>{@link #RUNNING}     — currently in progress</li>
 *   <li>{@link #NOT_STARTED} — no run found</li>
 * </ul>
 *
 * <p>Statically importable — no instantiation required.
 */
public final class RunStatusClassifier {

    public static final String ON_TIME     = "ON_TIME";
    public static final String DELAYED     = "DELAYED";
    public static final String FAILED      = "FAILED";
    public static final String RUNNING     = "RUNNING";
    public static final String NOT_STARTED = "NOT_STARTED";

    private RunStatusClassifier() {}

    /**
     * Classifies a single run against its SLA deadline.
     *
     * @param run         the calculator run (null → NOT_STARTED)
     * @param slaDeadline the SLA deadline (null → no deadline check; treats any completed run as ON_TIME)
     */
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
     * Returns the worst status across a collection, using priority:
     * FAILED > RUNNING > DELAYED > NOT_STARTED > ON_TIME.
     */
    public static String worstStatus(List<String> statuses) {
        if (statuses.contains(FAILED))      return FAILED;
        if (statuses.contains(RUNNING))     return RUNNING;
        if (statuses.contains(DELAYED))     return DELAYED;
        if (statuses.contains(NOT_STARTED)) return NOT_STARTED;
        return ON_TIME;
    }
}
