package com.company.observability.service;

import com.company.observability.config.DashboardProperties;
import com.company.observability.config.DashboardProperties.MatrixColumnConfig;
import com.company.observability.config.DashboardProperties.MatrixConfig;
import com.company.observability.config.DashboardProperties.NodeConfig;
import com.company.observability.config.DashboardProperties.SectionConfig;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.repository.CalculatorRunRepository.HistoricalRunStatus;
import com.company.observability.service.RegionalBatchService.EstimatedTime;
import com.company.observability.service.RegionalBatchService.RegionEntry;
import com.company.observability.service.RegionalBatchService.RegionalBatchResult;
import com.company.observability.util.RunStatusClassifier;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core orchestrator for the unified calculator dashboard.
 *
 * <p>Builds a domain-level {@link DashboardResult} from configuration plus a
 * small set of DB queries:
 * <ol>
 *   <li>{@code findRegionalBatchRuns} via {@link RegionalBatchService} — REGION matrix</li>
 *   <li>{@code findDashboardCalculatorRuns} — all simple/TYPE-matrix calculators in one shot</li>
 *   <li>{@code findDashboardCalculatorHistory} — last-run dots (5 partitions)</li>
 *   <li>{@code findNextRunEstimatedStarts} — populates {@code nextRunTime}</li>
 * </ol>
 *
 * <p>Sections are built in {@code displayOrder}; dependency resolution reads the
 * already-built upstream summary inline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private static final int HISTORY_LOOKBACK_DAYS = 5;

    private final DashboardProperties dashboardProperties;
    private final CalculatorRunRepository calculatorRunRepository;
    private final RegionalBatchService regionalBatchService;

    // ── Public API ────────────────────────────────────────────────────────────

    public DashboardResult buildDashboard(
            String tenantId, LocalDate reportingDate, String frequency, int runNumber) {

        long lateThreshold = dashboardProperties.getLateThresholdMs();
        String frequencyStr = CalculatorFrequency.from(frequency).name();
        String runNumberStr = String.valueOf(runNumber);

        // 1. Filter sections by frequency
        List<SectionConfig> applicableSections = dashboardProperties.getSections().stream()
                .filter(s -> s.getFrequencyApplicable().isEmpty()
                          || s.getFrequencyApplicable().contains(frequencyStr))
                .sorted(Comparator.comparingInt(SectionConfig::getDisplayOrder))
                .toList();

        // 2. Collect non-regional calculator IDs (REGION matrix is served via RegionalBatchService)
        List<String> allCalcIds = collectNonRegionalCalculatorIds(applicableSections);

        // 3. Batched DB queries
        Map<String, CalculatorRun> runsByCalcId = allCalcIds.isEmpty()
                ? Map.of()
                : calculatorRunRepository.findDashboardCalculatorRuns(
                        tenantId, reportingDate, frequencyStr, runNumberStr, allCalcIds);

        Map<String, List<HistoricalRunStatus>> historyByCalcId = allCalcIds.isEmpty()
                ? Map.of()
                : calculatorRunRepository.findDashboardCalculatorHistory(
                            tenantId, reportingDate, HISTORY_LOOKBACK_DAYS,
                            frequencyStr, runNumberStr, allCalcIds)
                        .stream()
                        .collect(Collectors.groupingBy(HistoricalRunStatus::calculatorId));

        Map<String, Instant> nextRunByCalcId = allCalcIds.isEmpty()
                ? Map.of()
                : calculatorRunRepository.findNextRunEstimatedStarts(
                        tenantId, reportingDate, frequencyStr, runNumberStr, allCalcIds);

        // 4. Regional matrix data (uses its own cache internally)
        boolean regionalNeeded = applicableSections.stream()
                .flatMap(s -> s.getNodes().stream())
                .anyMatch(this::isRegionMatrix);
        RegionalBatchResult regional = regionalNeeded
                ? regionalBatchService.getRegionalBatchStatus(tenantId, reportingDate, runNumberStr)
                : null;

        // 5. Build sections, accumulating summaries for dependency resolution
        Map<String, SectionSummary> summaryByKey = new LinkedHashMap<>();
        List<SectionResult> sections = new ArrayList<>();

        for (SectionConfig sectionConfig : applicableSections) {
            SectionResult sectionResult = buildSection(
                    sectionConfig, regional, runsByCalcId, historyByCalcId,
                    nextRunByCalcId, summaryByKey, reportingDate, lateThreshold);
            summaryByKey.put(sectionConfig.getSectionKey(), sectionResult.summary());
            sections.add(sectionResult);
        }

        log.debug("event=dashboard.build tenant_id={} reporting_date={} frequency={} run_number={} sections={}",
                tenantId, reportingDate, frequencyStr, runNumber, sections.size());

        return new DashboardResult(reportingDate, frequencyStr, runNumber, Instant.now(), sections);
    }

    // ── Section builder ───────────────────────────────────────────────────────

    private SectionResult buildSection(
            SectionConfig config,
            RegionalBatchResult regional,
            Map<String, CalculatorRun> runsByCalcId,
            Map<String, List<HistoricalRunStatus>> historyByCalcId,
            Map<String, Instant> nextRunByCalcId,
            Map<String, SectionSummary> builtSummaries,
            LocalDate reportingDate,
            long lateThreshold) {

        Instant slaDeadline = TimeUtils.calculateSlaDeadline(reportingDate, config.getSlaTimeCet());
        DependencyResult dependency = resolveDependency(config, builtSummaries);

        List<NodeResult> nodes = config.getNodes().stream()
                .sorted(Comparator.comparingInt(NodeConfig::getDisplayOrder))
                .map(nc -> buildNode(nc, regional, runsByCalcId, historyByCalcId,
                        nextRunByCalcId, slaDeadline, lateThreshold))
                .toList();

        SectionSummary summary = computeSectionSummary(nodes, slaDeadline);

        boolean slaBreached = summary.failedCount() > 0
                || summary.latenessMs() != null
                || (summary.inProgressCount() > 0 && Instant.now().isAfter(slaDeadline));

        return new SectionResult(
                config.getSectionKey(), config.getDisplayName(), config.getDisplayOrder(),
                slaDeadline, slaBreached, dependency, summary, nodes);
    }

    // ── Node builders (simple / REGION matrix / TYPE matrix) ──────────────────

    private NodeResult buildNode(
            NodeConfig nodeConfig,
            RegionalBatchResult regional,
            Map<String, CalculatorRun> runsByCalcId,
            Map<String, List<HistoricalRunStatus>> historyByCalcId,
            Map<String, Instant> nextRunByCalcId,
            Instant slaDeadline,
            long lateThreshold) {

        MatrixConfig mc = nodeConfig.getMatrixConfig();
        if (mc == null) {
            return buildSimpleNode(nodeConfig, runsByCalcId, historyByCalcId,
                    nextRunByCalcId, slaDeadline, lateThreshold);
        }
        if ("REGION".equals(mc.getDimension())) {
            return buildRegionMatrixNode(nodeConfig, regional, slaDeadline, lateThreshold);
        }
        return buildTypeMatrixNode(nodeConfig, runsByCalcId, slaDeadline, lateThreshold);
    }

    private NodeResult buildSimpleNode(
            NodeConfig nodeConfig,
            Map<String, CalculatorRun> runsByCalcId,
            Map<String, List<HistoricalRunStatus>> historyByCalcId,
            Map<String, Instant> nextRunByCalcId,
            Instant slaDeadline, long lateThreshold) {

        CalculatorRun run = runsByCalcId.get(nodeConfig.getCalculatorId());
        String status = RunStatusClassifier.classify(run, slaDeadline, lateThreshold);
        boolean breach = RunStatusClassifier.isSlaBreach(run, slaDeadline);
        Long lateness = RunStatusClassifier.computeLatenessMs(
                status, breach, run != null ? run.getEndTime() : null, slaDeadline);

        List<String> labels = nodeConfig.getDisplayLabels().isEmpty()
                ? null : nodeConfig.getDisplayLabels();
        List<HistoricalRunStatus> hist =
                historyByCalcId.getOrDefault(nodeConfig.getCalculatorId(), List.of());

        return new NodeResult(
                nodeConfig.getNodeKey(), nodeConfig.getDisplayName(), nodeConfig.getDisplayOrder(),
                run, status, breach, lateness,
                run != null ? run.getEstimatedStartTime() : null,
                run != null ? run.getEstimatedEndTime()   : null,
                nextRunByCalcId.get(nodeConfig.getCalculatorId()),
                labels, null, hist);
    }

    private NodeResult buildRegionMatrixNode(
            NodeConfig nodeConfig, RegionalBatchResult regional,
            Instant slaDeadline, long lateThreshold) {

        MatrixConfig mc = nodeConfig.getMatrixConfig();
        List<String> colKeys = mc.getColumns().stream()
                .map(MatrixColumnConfig::getKey).toList();

        Map<String, RegionEntry> byRegion = regional == null ? Map.of()
                : regional.entries().stream()
                        .collect(Collectors.toMap(RegionEntry::region, e -> e, (a, b) -> a));

        List<MatrixCellResult> cells = colKeys.stream().map(key -> {
            RegionEntry entry = byRegion.get(key);
            CalculatorRun run = entry != null ? entry.run() : null;
            String status = RunStatusClassifier.classify(run, slaDeadline, lateThreshold);
            boolean breach = RunStatusClassifier.isSlaBreach(run, slaDeadline);
            Long lateness = RunStatusClassifier.computeLatenessMs(
                    status, breach, run != null ? run.getEndTime() : null, slaDeadline);
            return new MatrixCellResult(key, run, status, breach, lateness);
        }).toList();

        MatrixRowResult row = new MatrixRowResult(nodeConfig.getNodeKey(), mc.getRowLabel(), cells);
        MatrixResult matrix = new MatrixResult("REGION", colKeys, List.of(row));

        // Parent node carries no run/status — cells do.
        return new NodeResult(
                nodeConfig.getNodeKey(), nodeConfig.getDisplayName(), nodeConfig.getDisplayOrder(),
                null, null, false, null, null, null, null, null, matrix, List.of());
    }

    private NodeResult buildTypeMatrixNode(
            NodeConfig nodeConfig,
            Map<String, CalculatorRun> runsByCalcId,
            Instant slaDeadline, long lateThreshold) {

        MatrixConfig mc = nodeConfig.getMatrixConfig();
        List<String> colKeys = mc.getColumns().stream()
                .map(MatrixColumnConfig::getKey).toList();

        List<MatrixCellResult> cells = mc.getColumns().stream().map(col -> {
            CalculatorRun run = runsByCalcId.get(col.getCalculatorId());
            String status = RunStatusClassifier.classify(run, slaDeadline, lateThreshold);
            boolean breach = RunStatusClassifier.isSlaBreach(run, slaDeadline);
            Long lateness = RunStatusClassifier.computeLatenessMs(
                    status, breach, run != null ? run.getEndTime() : null, slaDeadline);
            return new MatrixCellResult(col.getKey(), run, status, breach, lateness);
        }).toList();

        MatrixRowResult row = new MatrixRowResult(nodeConfig.getNodeKey(), mc.getRowLabel(), cells);
        MatrixResult matrix = new MatrixResult("TYPE", colKeys, List.of(row));

        // Parent status = worst across cells; lateness = max lateness across cells
        String worstStatus = RunStatusClassifier.worstStatus(
                cells.stream().map(MatrixCellResult::status).toList());
        Long worstLateness = cells.stream().map(MatrixCellResult::latenessMs)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(null);

        return new NodeResult(
                nodeConfig.getNodeKey(), nodeConfig.getDisplayName(), nodeConfig.getDisplayOrder(),
                null, worstStatus, false, worstLateness, null, null, null, null, matrix, List.of());
    }

    // ── Section summary, dependency, time derivation ──────────────────────────

    private SectionSummary computeSectionSummary(List<NodeResult> nodes, Instant slaDeadline) {
        int total = 0, completed = 0, inProgress = 0, failed = 0, notStarted = 0;
        List<String> allStatuses = new ArrayList<>();
        List<Long> allLatenesses = new ArrayList<>();

        for (NodeResult node : nodes) {
            if (node.matrix() != null && "REGION".equals(node.matrix().columnDimension())) {
                // REGION matrix: count cells
                for (MatrixRowResult rowR : node.matrix().rows()) {
                    for (MatrixCellResult cell : rowR.cells()) {
                        total++;
                        allStatuses.add(cell.status());
                        if (cell.latenessMs() != null) allLatenesses.add(cell.latenessMs());
                        switch (cell.status()) {
                            case RunStatusClassifier.FAILED      -> failed++;
                            case RunStatusClassifier.IN_PROGRESS -> inProgress++;
                            case RunStatusClassifier.NOT_STARTED -> notStarted++;
                            default                              -> completed++;
                        }
                    }
                }
            } else {
                // TYPE matrix or simple node: count as 1 unit
                total++;
                String s = node.status() != null ? node.status() : RunStatusClassifier.NOT_STARTED;
                allStatuses.add(s);
                if (node.latenessMs() != null) allLatenesses.add(node.latenessMs());
                switch (s) {
                    case RunStatusClassifier.FAILED      -> failed++;
                    case RunStatusClassifier.IN_PROGRESS -> inProgress++;
                    case RunStatusClassifier.NOT_STARTED -> notStarted++;
                    default                              -> completed++;
                }
            }
        }

        String sectionStatus = RunStatusClassifier.worstStatus(allStatuses);
        Long worstLateness = allLatenesses.stream().max(Long::compareTo).orElse(null);

        EstimatedTime estStart = deriveSectionStart(nodes);
        EstimatedTime estEnd   = deriveSectionEnd(nodes, total, completed, failed);

        return new SectionSummary(total, completed, inProgress, failed, notStarted,
                sectionStatus, worstLateness, estStart, estEnd);
    }

    private DependencyResult resolveDependency(
            SectionConfig config, Map<String, SectionSummary> builtSummaries) {

        if (config.getDependsOn() == null) return null;

        String upstreamKey = config.getDependsOn();
        SectionSummary upstream = builtSummaries.get(upstreamKey);
        String upstreamName = friendlyName(upstreamKey);

        if (upstream == null) {
            return new DependencyResult(upstreamKey, false, "Waiting for " + upstreamName);
        }

        boolean hasInProgress = upstream.inProgressCount() > 0;
        boolean hasNotStarted = upstream.notStartedCount() > 0;
        boolean hasFailed     = upstream.failedCount() > 0;
        boolean allTerminal   = !hasInProgress && !hasNotStarted;

        if (!allTerminal) {
            return new DependencyResult(upstreamKey, false, "Waiting for " + upstreamName);
        }
        if (hasFailed) {
            return new DependencyResult(upstreamKey, false, "Blocked by " + upstreamName + " failure");
        }
        return new DependencyResult(upstreamKey, true, "Ready");
    }

    private String friendlyName(String sectionKey) {
        return dashboardProperties.getSections().stream()
                .filter(s -> sectionKey.equals(s.getSectionKey()))
                .findFirst()
                .map(SectionConfig::getDisplayName)
                .orElse(sectionKey);
    }

    /** Earliest actual run start across all nodes/cells, or null. */
    private EstimatedTime deriveSectionStart(List<NodeResult> nodes) {
        Instant earliest = null;
        String label = null;
        for (NodeResult node : nodes) {
            for (CalculatorRun run : runsOf(node)) {
                if (run != null && run.getStartTime() != null
                        && (earliest == null || run.getStartTime().isBefore(earliest))) {
                    earliest = run.getStartTime();
                    label = node.displayName();
                }
            }
        }
        return earliest != null ? new EstimatedTime(earliest, label, true) : null;
    }

    /** Latest actual run end across all nodes/cells, only when ALL units are terminal. */
    private EstimatedTime deriveSectionEnd(List<NodeResult> nodes, int total, int completed, int failed) {
        if ((completed + failed) != total) return null;

        Instant latest = null;
        String label = null;
        for (NodeResult node : nodes) {
            for (CalculatorRun run : runsOf(node)) {
                if (run != null && run.getEndTime() != null && run.getStatus().isTerminal()
                        && (latest == null || run.getEndTime().isAfter(latest))) {
                    latest = run.getEndTime();
                    label = node.displayName();
                }
            }
        }
        return latest != null ? new EstimatedTime(latest, label, true) : null;
    }

    /** All CalculatorRun instances backing a node (1 for simple, N for matrix). */
    private List<CalculatorRun> runsOf(NodeResult node) {
        if (node.matrix() == null) {
            return node.run() != null ? List.of(node.run()) : List.of();
        }
        return node.matrix().rows().stream()
                .flatMap(r -> r.cells().stream())
                .map(MatrixCellResult::run)
                .filter(Objects::nonNull)
                .toList();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isRegionMatrix(NodeConfig n) {
        return n.getMatrixConfig() != null && "REGION".equals(n.getMatrixConfig().getDimension());
    }

    /**
     * Collects calculator IDs for non-REGION nodes (REGION runs are loaded via
     * {@link RegionalBatchService}).
     */
    private List<String> collectNonRegionalCalculatorIds(List<SectionConfig> sections) {
        return sections.stream()
                .flatMap(s -> s.getNodes().stream())
                .flatMap(n -> {
                    if (n.getMatrixConfig() == null) {
                        return n.getCalculatorId() != null
                                ? Stream.of(n.getCalculatorId()) : Stream.empty();
                    }
                    if ("REGION".equals(n.getMatrixConfig().getDimension())) {
                        return Stream.empty();
                    }
                    return n.getMatrixConfig().getColumns().stream()
                            .map(MatrixColumnConfig::getCalculatorId);
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    // ── Domain result records ─────────────────────────────────────────────────

    public record DashboardResult(
            LocalDate reportingDate,
            String frequency,
            int runNumber,
            Instant generatedAt,
            List<SectionResult> sections
    ) {}

    public record SectionResult(
            String sectionKey,
            String displayName,
            int displayOrder,
            Instant slaDeadline,
            boolean slaBreached,
            DependencyResult dependency,
            SectionSummary summary,
            List<NodeResult> nodes
    ) {}

    public record SectionSummary(
            int totalCount,
            int completedCount,
            int inProgressCount,
            int failedCount,
            int notStartedCount,
            String status,
            Long latenessMs,
            EstimatedTime estimatedStart,
            EstimatedTime estimatedEnd
    ) {}

    public record DependencyResult(
            String dependsOnSection,
            boolean met,
            String label
    ) {}

    public record NodeResult(
            String nodeKey,
            String displayName,
            int displayOrder,
            CalculatorRun run,
            String status,
            boolean slaBreached,
            Long latenessMs,
            Instant estimatedStartTime,
            Instant estimatedEndTime,
            Instant nextRunTime,
            List<String> displayLabels,
            MatrixResult matrix,
            List<HistoricalRunStatus> lastRuns
    ) {}

    public record MatrixResult(
            String columnDimension,
            List<String> columns,
            List<MatrixRowResult> rows
    ) {}

    public record MatrixRowResult(
            String rowKey,
            String label,
            List<MatrixCellResult> cells
    ) {}

    public record MatrixCellResult(
            String key,
            CalculatorRun run,
            String status,
            boolean slaBreached,
            Long latenessMs
    ) {}
}
