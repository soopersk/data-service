package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Unified response for the Calculator Dashboard endpoint.
 *
 * <p>Returns all dashboard sections (Regional, Portfolio, Group Portfolio,
 * Risk Governed, Consolidation, …) for a given reporting date, frequency, and
 * run number in a single response. The UI polls this every 60 seconds.
 *
 * <h3>Section units</h3>
 * <ul>
 *   <li><b>Regional</b> — one node containing a {@link StatusMatrix} with
 *       {@code columnDimension="REGION"} and 10 columns (WMAP, WMDE, …).
 *       Each cell is one regional run.</li>
 *   <li><b>Portfolio</b> — one simple {@link DashboardNode} backed by a single
 *       run. The frontend renders 5 visual rows using {@code displayLabels}.</li>
 *   <li><b>Modelled Exposure / Gemini Hedge</b> — one node each with a
 *       {@code StatusMatrix}, {@code columnDimension="TYPE"}, columns
 *       {@code [OTC, ETD, SFT]}.</li>
 *   <li><b>Group Portfolio / Consolidation</b> — single simple nodes.</li>
 * </ul>
 *
 * <h3>Status vocabulary</h3>
 * <p>{@code ON_TIME}, {@code LATE}, {@code VERY_LATE}, {@code IN_PROGRESS},
 * {@code FAILED}, {@code NOT_STARTED}.
 */
public record CalculatorDashboardResponse(
        LocalDate reportingDate,
        String frequency,                      // "DAILY" or "MONTHLY"
        int runNumber,                         // 1 or 2
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant generatedAt,
        List<DashboardSection> sections        // ordered by displayOrder
) {

    /**
     * One accordion panel on the dashboard.
     *
     * @param dependency null for sections with no upstream dependency
     */
    public record DashboardSection(
            String sectionKey,
            String displayName,
            int displayOrder,
            SlaIndicator sla,
            DependencyStatus dependency,
            SectionSummary summary,
            List<DashboardNode> nodes
    ) {}

    /**
     * Upstream dependency state for sections that depend on another section.
     */
    public record DependencyStatus(
            String dependsOnSection,
            boolean dependencyMet,
            String statusLabel
    ) {}

    /**
     * Aggregate counts and estimated timeline for a section.
     * <p>
     * For REGION-matrix sections, counts reflect cells (10). For TYPE-matrix
     * sections and simple nodes, counts reflect nodes.
     *
     * @param status worst status across all units, using the priority
     *               FAILED &gt; IN_PROGRESS &gt; VERY_LATE &gt; LATE &gt; NOT_STARTED &gt; ON_TIME
     * @param latenessMs worst lateness across all units; null if none are late
     */
    public record SectionSummary(
            int totalCount,
            int completedCount,
            int inProgressCount,
            int failedCount,
            int notStartedCount,
            String status,
            Long latenessMs,
            TimeReference estimatedStart,
            TimeReference estimatedEnd
    ) {}

    /**
     * A node within a section. Either a simple single-calculator node
     * (with optional {@code displayLabels}) or a matrix node (with non-null
     * {@code matrix}).
     */
    public record DashboardNode(
            String nodeKey,
            String displayName,
            int displayOrder,
            String runId,                              // null for matrix nodes
            String status,                             // null for REGION-matrix parent (cells carry status)
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant startTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant endTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant estimatedStartTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant estimatedEndTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant nextRunTime,
            Long durationMs,
            Long latenessMs,
            boolean slaBreached,
            List<String> displayLabels,                // null unless Portfolio-style multi-row
            StatusMatrix matrix,                       // null for simple nodes
            List<LastRunIndicator> lastRuns
    ) {}

    /**
     * Matrix payload for a node. Single-row today; the {@code rows} list lets
     * us add extra rows later without a contract change.
     */
    public record StatusMatrix(
            String columnDimension,                    // "REGION" or "TYPE"
            List<String> columns,
            List<MatrixRow> rows
    ) {}

    public record MatrixRow(
            String rowKey,
            String label,
            List<MatrixCell> cells
    ) {}

    public record MatrixCell(
            String key,                                // "WMAP", "OTC", …
            String runId,
            String status,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant startTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant endTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant estimatedStartTime,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
            Instant estimatedEndTime,
            Long durationMs,
            Long latenessMs,
            boolean slaBreached
    ) {}

    /**
     * One colored dot in the "Last runs" strip.
     * {@code status} is one of {@code ON_TIME}, {@code LATE}, {@code FAILED}.
     */
    public record LastRunIndicator(
            LocalDate reportingDate,
            String status
    ) {}
}
