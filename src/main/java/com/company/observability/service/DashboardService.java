package com.company.observability.service;

import com.company.observability.config.DashboardProperties;
import com.company.observability.config.DashboardProperties.CalculatorConfig;
import com.company.observability.config.DashboardProperties.SectionConfig;
import com.company.observability.config.DashboardProperties.SubRunConfig;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.RunStatus;
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

/**
 * Core orchestrator for the unified calculator dashboard.
 *
 * <p>One request → at most 4 DB queries (2 on cache hit):
 * <ol>
 *   <li>{@code findRegionalBatchRuns} — 1 partition</li>
 *   <li>{@code findDashboardCalculatorRuns} — 1 partition (all non-regional calcs)</li>
 *   <li>{@code findRegionalBatchHistory} — 7 partitions (only on cache miss)</li>
 *   <li>{@code findDashboardCalculatorHistory} — 5 partitions (only on cache miss)</li>
 * </ol>
 *
 * <p>Sections are built in {@code displayOrder}. Dependency resolution is done
 * inline: each section reads the already-built summary of its upstream section.
 * Returns domain-level {@link DashboardResult} records — no formatting here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private static final int HISTORY_LOOKBACK_DAYS = 5;
    private static final String REGIONAL_SECTION_KEY = "REGIONAL";

    private final DashboardProperties dashboardProperties;
    private final CalculatorRunRepository calculatorRunRepository;
    private final RegionalBatchService regionalBatchService;

    // ── Public API ────────────────────────────────────────────────────────────

    public DashboardResult buildDashboard(
            String tenantId, LocalDate reportingDate, String frequency, int runNumber) {

        String runNumberStr = String.valueOf(runNumber);

        // 1. Collect all non-regional calculator IDs for the batch DB query
        List<String> nonRegionalCalcIds = collectNonRegionalCalculatorIds();

        // 2. Single DB query for all non-regional runs (1 partition scan)
        Map<String, CalculatorRun> runsByCalculatorId = nonRegionalCalcIds.isEmpty()
                ? Map.of()
                : calculatorRunRepository.findDashboardCalculatorRuns(
                        tenantId, reportingDate, frequency, runNumberStr, nonRegionalCalcIds);

        // 3. Regional section via existing service (uses its own cache internally)
        RegionalBatchResult regionalResult =
                regionalBatchService.getRegionalBatchStatus(tenantId, reportingDate, runNumberStr);

        // 4. Historical run dots for non-regional calcs (1 query, ~5 partitions)
        List<HistoricalRunStatus> nonRegionalHistory = nonRegionalCalcIds.isEmpty()
                ? List.of()
                : calculatorRunRepository.findDashboardCalculatorHistory(
                        tenantId, reportingDate, HISTORY_LOOKBACK_DAYS,
                        frequency, runNumberStr, nonRegionalCalcIds);

        Map<String, List<HistoricalRunStatus>> historyByCalcId = nonRegionalHistory.stream()
                .collect(Collectors.groupingBy(HistoricalRunStatus::calculatorId));

        // 5. Build sections in order, accumulating summaries for dependency resolution
        Map<String, SectionSummary> summaryByKey = new LinkedHashMap<>();
        List<SectionResult> sections = new ArrayList<>();

        List<SectionConfig> orderedSections = dashboardProperties.getSections().stream()
                .sorted(Comparator.comparingInt(SectionConfig::getDisplayOrder))
                .toList();

        for (SectionConfig sectionConfig : orderedSections) {
            SectionResult sectionResult;
            if (REGIONAL_SECTION_KEY.equals(sectionConfig.getSectionKey())) {
                sectionResult = buildRegionalSection(sectionConfig, regionalResult);
            } else {
                sectionResult = buildNonRegionalSection(
                        sectionConfig, runsByCalculatorId, historyByCalcId,
                        reportingDate, frequency, tenantId, runNumberStr, summaryByKey);
            }
            summaryByKey.put(sectionConfig.getSectionKey(), sectionResult.summary());
            sections.add(sectionResult);
        }

        log.debug("event=dashboard.build tenant_id={} reporting_date={} frequency={} run_number={} sections={}",
                tenantId, reportingDate, frequency, runNumber, sections.size());

        return new DashboardResult(reportingDate, frequency, runNumber, sections);
    }

    // ── Section builders ──────────────────────────────────────────────────────

    private SectionResult buildRegionalSection(SectionConfig config, RegionalBatchResult regional) {
        Instant slaDeadline = TimeUtils.calculateSlaDeadline(
                regional.reportingDate(), config.getSlaTimeCet());

        // Convert RegionEntries to CalculatorEntryResult list
        List<CalculatorEntryResult> entries = new ArrayList<>();
        for (RegionEntry entry : regional.entries()) {
            entries.add(new CalculatorEntryResult(
                    entry.run() != null ? entry.run().getCalculatorId() : null,
                    entry.region(),
                    entry.run(),
                    entry.status(),
                    entry.slaBreached(),
                    List.of(),   // no sub-runs for regional
                    List.of()    // regional doesn't use last-run dots
            ));
        }

        SectionSummary summary = new SectionSummary(
                regional.totalRegions(),
                regional.completedRegions(),
                regional.runningRegions(),
                regional.failedRegions(),
                regional.totalRegions() - regional.completedRegions()
                        - regional.runningRegions() - regional.failedRegions(),
                regional.estimatedStart(),
                regional.estimatedEnd()
        );

        return new SectionResult(
                config.getSectionKey(),
                config.getDisplayName(),
                config.getDisplayOrder(),
                slaDeadline,
                regional.overallBreached(),
                null,  // no dependency for REGIONAL
                summary,
                entries,
                null   // no displayLabels for REGIONAL
        );
    }

    private SectionResult buildNonRegionalSection(
            SectionConfig config,
            Map<String, CalculatorRun> runsByCalculatorId,
            Map<String, List<HistoricalRunStatus>> historyByCalcId,
            LocalDate reportingDate, String frequency, String tenantId, String runNumber,
            Map<String, SectionSummary> builtSummaries) {

        Instant slaDeadline = TimeUtils.calculateSlaDeadline(reportingDate, config.getSlaTimeCet());

        // Dependency
        DependencyResult dependency = resolveDependency(config, builtSummaries);

        // Build each calculator entry
        List<CalculatorEntryResult> entries = new ArrayList<>();
        int completedCount = 0, runningCount = 0, failedCount = 0, notStartedCount = 0;

        for (CalculatorConfig calcConfig : config.getCalculators()) {
            CalculatorEntryResult entry;
            if (calcConfig.isHasSubRuns()) {
                entry = buildSubRunEntry(calcConfig, runsByCalculatorId, historyByCalcId, slaDeadline);
            } else {
                CalculatorRun run = runsByCalculatorId.get(calcConfig.getCalculatorId());
                List<HistoricalRunStatus> history =
                        historyByCalcId.getOrDefault(calcConfig.getCalculatorId(), List.of());
                String status = RunStatusClassifier.classify(run, slaDeadline);
                boolean slaBreached = RunStatusClassifier.isSlaBreach(run, slaDeadline);
                entry = new CalculatorEntryResult(
                        calcConfig.getCalculatorId(),
                        calcConfig.getDisplayName(),
                        run,
                        status,
                        slaBreached,
                        null,
                        history
                );
            }
            entries.add(entry);

            switch (entry.status()) {
                case RunStatusClassifier.FAILED      -> failedCount++;
                case RunStatusClassifier.RUNNING     -> runningCount++;
                case RunStatusClassifier.NOT_STARTED -> notStartedCount++;
                default                              -> completedCount++;  // ON_TIME or DELAYED
            }
        }

        // Section has no estimated start/end (unlike Regional which has median history)
        // Derive from actual run times across all entries
        EstimatedTime estStart = deriveSectionStart(entries);
        EstimatedTime estEnd = deriveSectionEnd(entries, config.getCalculators().size(),
                completedCount, failedCount);

        boolean slaBreached = completedCount > 0 && entries.stream()
                .anyMatch(CalculatorEntryResult::slaBreached);

        SectionSummary summary = new SectionSummary(
                config.getCalculators().size(),
                completedCount, runningCount, failedCount, notStartedCount,
                estStart, estEnd
        );

        List<String> displayLabels = config.getDisplayLabels().isEmpty() ? null : config.getDisplayLabels();
        return new SectionResult(
                config.getSectionKey(),
                config.getDisplayName(),
                config.getDisplayOrder(),
                slaDeadline,
                slaBreached,
                dependency,
                summary,
                entries,
                displayLabels
        );
    }

    /**
     * Builds a CalculatorEntry for Modelled Exposure (or similar): one parent entry
     * with sub-run buttons. The parent status = worst status across all sub-runs.
     */
    private CalculatorEntryResult buildSubRunEntry(
            CalculatorConfig calcConfig,
            Map<String, CalculatorRun> runsByCalculatorId,
            Map<String, List<HistoricalRunStatus>> historyByCalcId,
            Instant slaDeadline) {

        List<SubRunResult> subRuns = new ArrayList<>();
        for (SubRunConfig subRunConfig : calcConfig.getSubRuns()) {
            CalculatorRun run = runsByCalculatorId.get(subRunConfig.getCalculatorId());
            String status = RunStatusClassifier.classify(run, slaDeadline);
            boolean breach = RunStatusClassifier.isSlaBreach(run, slaDeadline);
            subRuns.add(new SubRunResult(subRunConfig.getSubRunKey(), run, status, breach));
        }

        // Parent status = worst across sub-runs
        String parentStatus = RunStatusClassifier.worstStatus(subRuns.stream().map(SubRunResult::status).toList());

        // Parent run = the first sub-run's run for start/end time display
        // (Modelled Exposure as a whole doesn't have a single run_id)
        CalculatorRun representativeRun = subRuns.stream()
                .map(SubRunResult::run)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        // Aggregate history from all sub-run calculator IDs (parent ID is never in the map).
        // Deduplicate by reporting date, keeping the worst status per date.
        Map<LocalDate, HistoricalRunStatus> historyByDate = new LinkedHashMap<>();
        for (SubRunConfig sub : calcConfig.getSubRuns()) {
            for (HistoricalRunStatus h : historyByCalcId.getOrDefault(sub.getCalculatorId(), List.of())) {
                historyByDate.merge(h.reportingDate(), h, (existing, candidate) -> {
                    RunStatus cs = RunStatus.fromString(candidate.status());
                    if (!cs.isSuccessful() && cs.isTerminal()) return candidate;
                    if (candidate.slaBreached()) return candidate;
                    return existing;
                });
            }
        }
        List<HistoricalRunStatus> history = historyByDate.values().stream()
                .sorted(Comparator.comparing(HistoricalRunStatus::reportingDate).reversed())
                .limit(HISTORY_LOOKBACK_DAYS)
                .toList();

        boolean parentSlaBreached = subRuns.stream().anyMatch(SubRunResult::slaBreached);

        return new CalculatorEntryResult(
                calcConfig.getCalculatorId(),
                calcConfig.getDisplayName(),
                representativeRun,
                parentStatus,
                parentSlaBreached,
                subRuns,
                history
        );
    }

    // ── Dependency resolution ─────────────────────────────────────────────────

    private DependencyResult resolveDependency(
            SectionConfig config, Map<String, SectionSummary> builtSummaries) {

        if (config.getDependsOn() == null) return null;

        String upstreamKey = config.getDependsOn();
        SectionSummary upstream = builtSummaries.get(upstreamKey);
        String upstreamName = friendlyName(upstreamKey);

        if (upstream == null) {
            // upstream section not found in config — treat as not met
            return new DependencyResult(upstreamKey, false, "Waiting for " + upstreamName);
        }

        boolean hasRunning = upstream.runningCount() > 0;
        boolean hasNotStarted = upstream.notStartedCount() > 0;
        boolean hasFailed = upstream.failedCount() > 0;
        boolean allTerminal = !hasRunning && !hasNotStarted;

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

    // ── Section start/end estimation ──────────────────────────────────────────

    /**
     * Derives estimated section start from the earliest actual start across all entries.
     * Returns null if no runs have started yet.
     */
    private EstimatedTime deriveSectionStart(List<CalculatorEntryResult> entries) {
        Instant earliest = null;
        String region = null;
        for (CalculatorEntryResult e : entries) {
            CalculatorRun run = effectiveRun(e);
            if (run != null && run.getStartTime() != null) {
                if (earliest == null || run.getStartTime().isBefore(earliest)) {
                    earliest = run.getStartTime();
                    region = e.calculatorName();
                }
            }
        }
        return earliest != null ? new EstimatedTime(earliest, region, true) : null;
    }

    /**
     * Derives estimated section end from the latest actual end across terminal entries.
     * Only returns an actual end when ALL calculators are terminal (complete/failed).
     */
    private EstimatedTime deriveSectionEnd(
            List<CalculatorEntryResult> entries, int total, int completed, int failed) {

        boolean allTerminal = (completed + failed) == total;
        if (!allTerminal) return null;

        Instant latest = null;
        String region = null;
        for (CalculatorEntryResult e : entries) {
            CalculatorRun run = effectiveRun(e);
            if (run != null && run.getEndTime() != null && run.getStatus().isTerminal()) {
                if (latest == null || run.getEndTime().isAfter(latest)) {
                    latest = run.getEndTime();
                    region = e.calculatorName();
                }
            }
        }
        return latest != null ? new EstimatedTime(latest, region, true) : null;
    }

    /** For sub-run entries, pick the first non-null sub-run's CalculatorRun. */
    private CalculatorRun effectiveRun(CalculatorEntryResult entry) {
        if (entry.subRuns() != null && !entry.subRuns().isEmpty()) {
            return entry.subRuns().stream()
                    .map(SubRunResult::run)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return entry.run();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Collects all non-regional calculator IDs (including sub-run IDs). */
    private List<String> collectNonRegionalCalculatorIds() {
        List<String> ids = new ArrayList<>();
        for (SectionConfig section : dashboardProperties.getSections()) {
            if (REGIONAL_SECTION_KEY.equals(section.getSectionKey())) continue;
            for (CalculatorConfig calc : section.getCalculators()) {
                if (calc.isHasSubRuns()) {
                    calc.getSubRuns().forEach(sub -> ids.add(sub.getCalculatorId()));
                } else {
                    ids.add(calc.getCalculatorId());
                }
            }
        }
        return ids;
    }

    // ── Domain result records ─────────────────────────────────────────────────

    public record DashboardResult(
            LocalDate reportingDate,
            String frequency,
            int runNumber,
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
            List<CalculatorEntryResult> entries,
            List<String> displayLabels
    ) {}

    public record SectionSummary(
            int totalCalculators,
            int completedCount,
            int runningCount,
            int failedCount,
            int notStartedCount,
            EstimatedTime estimatedStart,
            EstimatedTime estimatedEnd
    ) {}

    public record DependencyResult(
            String dependsOnSection,
            boolean met,
            String label
    ) {}

    public record CalculatorEntryResult(
            String calculatorId,
            String calculatorName,
            CalculatorRun run,
            String status,
            boolean slaBreached,
            List<SubRunResult> subRuns,
            List<HistoricalRunStatus> lastRuns
    ) {}

    public record SubRunResult(
            String subRunKey,
            CalculatorRun run,
            String status,
            boolean slaBreached
    ) {}
}
