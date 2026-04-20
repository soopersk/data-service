package com.company.observability.service;

import com.company.observability.cache.RegionalBatchCacheService;
import com.company.observability.config.RegionalBatchProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.repository.CalculatorRunRepository.RegionalBatchTiming;
import com.company.observability.util.RunStatusClassifier;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RegionalBatchService {

    private static final ZoneId CET_ZONE = ZoneId.of("Europe/Amsterdam");
    private static final int LOOKBACK_DAYS = 7;

    private final CalculatorRunRepository calculatorRunRepository;
    private final RegionalBatchProperties properties;
    private final RegionalBatchCacheService cacheService;


    /**
     * Backward-compatible overload — no run_number filter applied.
     * Used by the existing regional-batch-status endpoint.
     */
    public RegionalBatchResult getRegionalBatchStatus(String tenantId, LocalDate reportingDate) {
        return getRegionalBatchStatus(tenantId, reportingDate, null);
    }

    /**
     * Returns the regional batch status for a given reporting date and run number.
     * When {@code runNumber} is null, no run_number filter is applied (backward compat).
     *
     * @param runNumber e.g. "1" or "2" — pass null to skip the filter
     */
    public RegionalBatchResult getRegionalBatchStatus(String tenantId, LocalDate reportingDate, String runNumber) {
        List<CalculatorRun> runs = calculatorRunRepository.findRegionalBatchRuns(tenantId, reportingDate, runNumber);

        // Build map keyed by region (from run_parameters)
        Map<String, CalculatorRun> runsByRegion = new LinkedHashMap<>();
        for (CalculatorRun run : runs) {
            String region = extractRegion(run);
            if (region != null) {
                runsByRegion.put(region.toUpperCase(), run);
            }
        }

        Instant slaDeadline = TimeUtils.calculateSlaDeadline(reportingDate, properties.getOverallSlaTimeCet());

        // Build region entries and track actuals
        List<RegionEntry> entries        = new ArrayList<>();
        List<String>      slaBreachedRegions = new ArrayList<>();
        Instant actualEarliestStart      = null;
        String  actualEarliestStartRegion = null;
        Instant actualLatestEnd          = null;
        String  actualLatestEndRegion    = null;
        int     completedCount = 0;
        int     runningCount   = 0;
        int     failedCount    = 0;

        for (String region : properties.getRegionOrder()) {
            CalculatorRun run = runsByRegion.get(region);

            if (run == null) {
                entries.add(new RegionEntry(region, null, RunStatusClassifier.NOT_STARTED, false));
                continue;
            }

            String  status            = RunStatusClassifier.classify(run, slaDeadline);
            boolean regionSlaBreached = RunStatusClassifier.isSlaBreach(run, slaDeadline);
            entries.add(new RegionEntry(region, run, status, regionSlaBreached));

            if (regionSlaBreached) {
                slaBreachedRegions.add(region);
            }

            switch (status) {
                case RunStatusClassifier.FAILED  -> failedCount++;
                case RunStatusClassifier.RUNNING -> runningCount++;
                default                          -> completedCount++; // ON_TIME or DELAYED
            }

            if (run.getStartTime() != null) {
                if (actualEarliestStart == null || run.getStartTime().isBefore(actualEarliestStart)) {
                    actualEarliestStart = run.getStartTime();
                    actualEarliestStartRegion = region;
                }
            }
            if (run.getEndTime() != null && run.getStatus().isTerminal()) {
                if (actualLatestEnd == null || run.getEndTime().isAfter(actualLatestEnd)) {
                    actualLatestEnd = run.getEndTime();
                    actualLatestEndRegion = region;
                }
            }
        }

        // Compute estimated start/end with actual override.
        // History is loaded at most once per call — only when at least one estimation
        // path actually needs it (i.e., when actual override is not available).
        boolean allCompleted       = completedCount + failedCount == properties.getRegionOrder().size();
        boolean needsHistoryStart  = actualEarliestStart == null;
        boolean needsHistoryEnd    = !(allCompleted && actualLatestEnd != null);

        List<RegionalBatchTiming> history = null;
        if (needsHistoryStart || needsHistoryEnd) {
            history = loadHistory(tenantId, reportingDate, runNumber);
        }

        EstimatedTime estStart = (actualEarliestStart != null)
                ? new EstimatedTime(actualEarliestStart, actualEarliestStartRegion, true)
                : computeMedianStart(history, reportingDate);

        EstimatedTime estEnd = (allCompleted && actualLatestEnd != null)
                ? new EstimatedTime(actualLatestEnd, actualLatestEndRegion, true)
                : computeMedianEnd(history, reportingDate);

        boolean overallBreached = !slaBreachedRegions.isEmpty();

        log.debug("event=regional_batch.status tenant_id={} reporting_date={} total={} completed={} running={} failed={} breached={} est_start_actual={} est_end_actual={}",
                tenantId, reportingDate, properties.getRegionOrder().size(),
                completedCount, runningCount, failedCount, overallBreached,
                estStart != null && estStart.actual(), estEnd != null && estEnd.actual());

        return new RegionalBatchResult(
                reportingDate,
                slaDeadline,
                overallBreached,
                estStart,
                estEnd,
                properties.getRegionOrder().size(),
                completedCount,
                runningCount,
                failedCount,
                entries,
                slaBreachedRegions
        );
    }

    // ── History loading with cache ────────────────────────────────────────

    /**
     * Loads 7-day history for median estimation.
     * Checks the Redis history cache first; falls back to DB and stores the result on miss.
     * The history is for days <em>before</em> {@code reportingDate}, so it is immutable
     * and safe to cache for 24 hours.
     *
     * <p>Note: called at most once per {@link #getRegionalBatchStatus} invocation because
     * each estimation branch (start/end) short-circuits to the actual value when available.
     * When both estimates are needed (nothing started yet), the two {@code loadHistory()}
     * calls within the same request both hit the Redis cache after the first populates it.
     */
    private List<RegionalBatchTiming> loadHistory(String tenantId, LocalDate reportingDate, String runNumber) {
        List<RegionalBatchTiming> cached = cacheService.getHistory(tenantId, reportingDate, runNumber);
        if (cached != null) {
            return cached;
        }
        List<RegionalBatchTiming> history = calculatorRunRepository
                .findRegionalBatchHistory(tenantId, reportingDate, LOOKBACK_DAYS, runNumber);
        cacheService.putHistory(tenantId, reportingDate, runNumber, history);
        return history;
    }

    // ── Median estimation ─────────────────────────────────────────────────

    private EstimatedTime computeMedianStart(List<RegionalBatchTiming> history, LocalDate reportingDate) {
        if (history == null || history.isEmpty()) return null;

        Map<LocalDate, List<RegionalBatchTiming>> byDate = history.stream()
                .filter(t -> t.startTime() != null)
                .collect(Collectors.groupingBy(RegionalBatchTiming::reportingDate));

        List<Long>                 dailyEarliestStartSeconds = new ArrayList<>();
        Map<LocalDate, String>     dailyEarliestRegion       = new HashMap<>();

        for (var entry : byDate.entrySet()) {
            RegionalBatchTiming earliest = entry.getValue().stream()
                    .min(Comparator.comparing(RegionalBatchTiming::startTime))
                    .orElse(null);
            if (earliest != null) {
                dailyEarliestStartSeconds.add(toCetSecondsOfDay(earliest.startTime()));
                dailyEarliestRegion.put(entry.getKey(), earliest.region());
            }
        }

        if (dailyEarliestStartSeconds.isEmpty()) return null;

        long    medianSeconds = median(dailyEarliestStartSeconds);
        Instant projected     = reportingDate.atStartOfDay(CET_ZONE).toInstant().plusSeconds(medianSeconds);
        String  basedOn       = mostFrequent(dailyEarliestRegion.values());

        return new EstimatedTime(projected, basedOn, false);
    }

    private EstimatedTime computeMedianEnd(List<RegionalBatchTiming> history, LocalDate reportingDate) {
        if (history == null || history.isEmpty()) return null;

        Map<LocalDate, List<RegionalBatchTiming>> byDate = history.stream()
                .filter(t -> t.endTime() != null)
                .collect(Collectors.groupingBy(RegionalBatchTiming::reportingDate));

        List<Long>             dailyLatestEndSeconds = new ArrayList<>();
        Map<LocalDate, String> dailyLatestRegion     = new HashMap<>();

        for (var entry : byDate.entrySet()) {
            RegionalBatchTiming latest = entry.getValue().stream()
                    .max(Comparator.comparing(RegionalBatchTiming::endTime))
                    .orElse(null);
            if (latest != null) {
                dailyLatestEndSeconds.add(toCetSecondsOfDay(latest.endTime()));
                dailyLatestRegion.put(entry.getKey(), latest.region());
            }
        }

        if (dailyLatestEndSeconds.isEmpty()) return null;

        long    medianSeconds = median(dailyLatestEndSeconds);
        Instant projected     = reportingDate.atStartOfDay(CET_ZONE).toInstant().plusSeconds(medianSeconds);
        String  basedOn       = mostFrequent(dailyLatestRegion.values());

        return new EstimatedTime(projected, basedOn, false);
    }

    private long toCetSecondsOfDay(Instant instant) {
        return instant.atZone(CET_ZONE).toLocalTime().toSecondOfDay();
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }
        return sorted.get(mid);
    }

    private static String mostFrequent(Collection<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String extractRegion(CalculatorRun run) {
        return run.getRegion();
    }

    // ── Result records ────────────────────────────────────────────────────

    public record EstimatedTime(Instant time, String basedOn, boolean actual) {}

    public record RegionalBatchResult(
            LocalDate reportingDate,
            Instant   slaDeadline,
            boolean   overallBreached,
            EstimatedTime estimatedStart,
            EstimatedTime estimatedEnd,
            int  totalRegions,
            int  completedRegions,
            int  runningRegions,
            int  failedRegions,
            List<RegionEntry> entries,
            List<String> slaBreachedRegions
    ) {}

    public record RegionEntry(
            String        region,
            CalculatorRun run,
            String        status,
            boolean       slaBreached
    ) {}
}
