package com.company.observability.util;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MDC context management with snapshot/restore semantics.
 * Every "set" method returns a snapshot of prior MDC state.
 * The only way to undo context is restoreContext(snapshot).
 */
public final class MdcContextUtil {

    private MdcContextUtil() {}

    /**
     * Set calculatorId and runId in MDC. Returns snapshot of prior state.
     */
    public static Map<String, String> setCalculatorContext(String calculatorId, String runId) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        if (snapshot == null) {
            snapshot = new HashMap<>();
        }
        MDC.put("calculatorId", calculatorId != null ? calculatorId : "-");
        MDC.put("runId", runId != null ? runId : "-");
        return snapshot;
    }

    /**
     * Set job context in MDC (for scheduled jobs). Returns snapshot of prior state.
     * Sets a synthetic requestId and tenant=system.
     */
    public static Map<String, String> setJobContext(String jobName) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        if (snapshot == null) {
            snapshot = new HashMap<>();
        }
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", "job-" + shortId);
        MDC.put("tenant", "system");
        return snapshot;
    }

    /**
     * Restore MDC to a previous snapshot. Keys present in current MDC
     * but absent from the snapshot are removed.
     */
    public static void restoreContext(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }
}
