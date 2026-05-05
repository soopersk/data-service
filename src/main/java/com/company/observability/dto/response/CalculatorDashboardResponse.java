package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Unified response for the Calculator Dashboard endpoint.
 * <p>
 * Returns all five accordion sections (Regional, Portfolio, Group Portfolio,
 * Risk Governed, Consolidation) for a given reporting date, frequency, and
 * run number in a single response. The UI polls this every 60 seconds.
 *
 * <h3>Special section handling</h3>
 * <ul>
 *   <li><b>Regional</b> — {@code calculators} contains 10 entries (one per region);
 *       each entry's {@code calculatorName} is the region code (WMAP, WMDE, …).</li>
 *   <li><b>Portfolio</b> — 1 {@code CalculatorEntry}. The frontend renders this as
 *       5 identical rows using the {@code displayLabels} list returned in the
 *       section config from the application configuration.</li>
 *   <li><b>Modelled Exposure</b> (under Risk Governed) — 1 {@code CalculatorEntry}
 *       whose {@code subRuns} list contains 3 items (OTC, ETD, SFT). The overall
 *       {@code status} reflects the worst status across the 3 sub-runs.</li>
 * </ul>
 */
public record CalculatorDashboardResponse(
        LocalDate reportingDate,
        String frequency,                     // "DAILY" or "MONTHLY"
        int runNumber,                        // 1 or 2
        List<DashboardSection> sections       // ordered by displayOrder
) {

    /**
     * One accordion panel on the dashboard (e.g. Regional, Portfolio).
     *
     * @param dependency null for sections with no upstream dependency (REGIONAL, RISK_GOVERNED)
     */
    /**
     * @param displayLabels labels for sections that render multiple rows per calculator entry
     *                      (e.g. Portfolio renders 5 rows using this list); null for all other sections
     */
    public record DashboardSection(
            String sectionKey,                // "REGIONAL", "PORTFOLIO", etc.
            String displayName,               // "Regional", "Portfolio", etc.
            int displayOrder,
            SectionSla sla,
            DependencyStatus dependency,
            SectionSummary summary,
            List<CalculatorEntry> calculators,
            List<String> displayLabels
    ) {}

    /**
     * SLA deadline and breach status for a section.
     * All timestamp values are UTC instants serialized as YYYY-MM-DDThh:mm:ss.sssZ.
     */
    public record SectionSla(
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant deadlineTime,
            boolean breached
    ) {}

    /**
     * Upstream dependency state for sections that depend on another section completing first.
     * <p>
     * The UI uses {@code dependencyMet} to decide whether to gray out the section body
     * and renders {@code statusLabel} as muted text in the header, e.g.:
     * <pre>Portfolio  SLA: 18:30 CET · Waiting for Regional</pre>
     */
    public record DependencyStatus(
            String dependsOnSection,          // "REGIONAL"
            boolean dependencyMet,            // true when upstream is fully terminal (no running/not-started)
            String statusLabel                // "Waiting for Regional" / "Ready" / "Blocked by Regional failure"
    ) {}

    /**
     * Aggregate counts and estimated timeline for a section.
     * Drives the section header status icon (green / amber / red / gray / pulsing).
     */
    public record SectionSummary(
            int totalCalculators,
            int completedCount,
            int runningCount,
            int failedCount,
            int notStartedCount,
            TimeReference estimatedStart,
            TimeReference estimatedEnd
    ) {}

    /**
     * A single calculator row within an expanded section.
     *
     * @param subRuns null for most calculators; 3 items for Modelled Exposure (OTC/ETD/SFT)
     * @param lastRuns last 5 historical reporting dates with their run status (for the dots)
     */
    public record CalculatorEntry(
            String calculatorId,
            String calculatorName,            // "Gemini Hedge", region code "WMAP", etc.
            String runId,                     // null if NOT_STARTED
            String status,                    // ON_TIME, DELAYED, FAILED, RUNNING, NOT_STARTED
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant startTime,                // null if not started
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant endTime,                  // null if RUNNING or NOT_STARTED
            Long durationMs,
            boolean slaBreached,
            List<SubRunStatus> subRuns,
            List<LastRunIndicator> lastRuns
    ) {}

    /**
     * One of the OTC / ETD / SFT sub-run buttons inside a Modelled Exposure row.
     */
    public record SubRunStatus(
            String subRunKey,                 // "OTC", "ETD", "SFT"
            String runId,
            String status,                    // ON_TIME, DELAYED, FAILED, RUNNING, NOT_STARTED
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant startTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant endTime,
            Long durationMs,
            boolean slaBreached
    ) {}

    /**
     * One colored dot in the "Last runs: ○○○○○" strip.
     * Frontend uses {@code status} to color the dot and {@code reportingDate} for the tooltip.
     */
    public record LastRunIndicator(
            LocalDate reportingDate,
            String status                     // ON_TIME, DELAYED, FAILED
    ) {}

}
