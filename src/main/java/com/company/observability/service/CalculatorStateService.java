package com.company.observability.service;

import com.company.observability.cache.CalculatorStateCacheService;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.RunStatusClassifier;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class CalculatorStateService {

    private final CalculatorRunRepository runRepository;
    private final DurationBasedSlaProperties slaProps;
    private final CalculatorStateCacheService stateCache;
    private final CalculatorProfileService profileService;

    // Lower index = worse status (worst-wins ordering mirrors LogicalRunGrouper)
    private static final List<RunStatus> STATUS_PRECEDENCE = List.of(
            RunStatus.RUNNING, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED, RunStatus.SUCCESS);

    public Map<String, CalculatorEntry> getState(
            LocalDate reportingDate,
            Frequency frequency,
            String runNumber,
            List<String> calculatorNames) {

        // Normalize blank → null so empty ?run_number= means "all runs" (not filter on empty string)
        String rn = (runNumber == null || runNumber.isBlank()) ? null : runNumber;
        String freqName = frequency.name();

        // 1. Cache read — partial hits are fine
        Map<String, CalculatorEntry> cached = stateCache.getEntries(reportingDate, freqName, rn, calculatorNames);

        // 2. Determine misses
        List<String> missNames = calculatorNames.stream()
                .filter(name -> !cached.containsKey(name))
                .toList();

        log.debug("event=batch_runs.state cacheHits={} cacheMisses={} reportingDate={} frequency={}",
                cached.size(), missNames.size(), reportingDate, freqName);

        // 3. DB call only for misses
        if (!missNames.isEmpty()) {
            log.debug("event=batch_runs.db_fetch outcome=start misses={} reportingDate={} frequency={}",
                    missNames, reportingDate, freqName);

            Map<String, List<CalculatorRun>> runsByName = runRepository
                    .findAllRunsByDateAndDimension(reportingDate, frequency, rn, missNames)
                    .stream()
                    .collect(Collectors.groupingBy(CalculatorRun::getCalculatorName));

            log.debug("event=batch_runs.db_fetch outcome=complete fetchedCalculators={}", runsByName.size());

            Map<String, CalculatorEntry> freshEntries = missNames.stream().collect(Collectors.toMap(
                    name -> name,
                    name -> buildEntry(name, runsByName.getOrDefault(name, List.of()), reportingDate, frequency)
            ));

            // 4. Cache fresh entries (including not-started entries so absent names don't re-hit DB)
            stateCache.putEntries(reportingDate, freqName, rn, freshEntries);
            cached.putAll(freshEntries);
        }

        // Return in the original requested order
        return calculatorNames.stream().collect(Collectors.toMap(
                name -> name,
                cached::get
        ));
    }

    private CalculatorEntry buildEntry(String calculatorName, List<CalculatorRun> runs,
                                       LocalDate reportingDate, Frequency frequency) {
        if (runs.isEmpty()) {
            return buildNotStartedEntry(calculatorName, reportingDate, frequency);
        }

        // Phase 1: collapse parallel splits (shared correlationId) into one RunEntry each
        Map<String, List<CalculatorRun>> splitGroups = runs.stream()
                .filter(r -> r.getCorrelationId() != null)
                .collect(Collectors.groupingBy(CalculatorRun::getCorrelationId));

        List<RunEntry> splitEntries = splitGroups.values().stream()
                .map(this::collapseSplitGroup)
                .toList();

        // Phase 2: deduplicate sequential reruns (null correlationId) by (region, runType) — latest wins
        List<RunEntry> standaloneEntries = runs.stream()
                .filter(r -> r.getCorrelationId() == null)
                .collect(Collectors.groupingBy(r ->
                        Objects.toString(r.getRegion(), "") + ":" + Objects.toString(r.getRunType(), "")))
                .values().stream()
                .map(group -> {
                    CalculatorRun latest = group.stream()
                            .max(Comparator.comparing(CalculatorRun::getCreatedAt))
                            .orElseThrow();
                    latest.setRerun(group.size() > 1);
                    return toRunEntry(latest);
                })
                .toList();

        List<RunEntry> allEntries = Stream.concat(splitEntries.stream(), standaloneEntries.stream()).toList();
        return new CalculatorEntry(calculatorName, allEntries);
    }

    /**
     * Build a synthetic "not started" entry for a calculator that has no run on the queried date.
     * Uses the cached rolling profile (30-day average) as the primary source, falling back to
     * the most recent run's stored estimated values projected onto the queried date.
     * Returns an empty entry only for brand-new calculators with no history at all.
     */
    private CalculatorEntry buildNotStartedEntry(String name, LocalDate date, Frequency freq) {
        // 1. Try profile (Redis-cached 26h — very cheap)
        CalculatorProfile profile = profileService.getProfile(name, freq);
        if (profile.hasSufficientSamples(slaProps.getMinSampleSize())) {
            Instant estStart = TimeUtils.instantFromUtcMinuteOfDay(date, profile.avgStartMinUtc());
            Instant estEnd = estStart.plusMillis(profile.avgDurationMs());
            log.debug("event=batch_runs.not_started source=profile calculator={} date={}", name, date);
            return entryWithSyntheticRun(name, estStart, estEnd, profile.avgDurationMs());
        }

        // 2. Fallback: most recent run's stored estimates, projected onto the queried date
        Optional<CalculatorRun> latest = runRepository.findLatestRunEstimatesByName(name, freq);
        if (latest.isPresent()) {
            CalculatorRun r = latest.get();
            if (r.getEstimatedStartTime() != null && r.getExpectedDurationMs() != null) {
                int minuteOfDay = (int) Duration.between(
                        r.getEstimatedStartTime().truncatedTo(ChronoUnit.DAYS),
                        r.getEstimatedStartTime()).toMinutes();
                Instant estStart = TimeUtils.instantFromUtcMinuteOfDay(date, minuteOfDay);
                Instant estEnd = estStart.plusMillis(r.getExpectedDurationMs());
                log.debug("event=batch_runs.not_started source=latest_run calculator={} date={}", name, date);
                return entryWithSyntheticRun(name, estStart, estEnd, r.getExpectedDurationMs());
            }
        }

        // 3. Brand-new calculator with no history — return empty (same as before)
        log.debug("event=batch_runs.not_started source=none calculator={} date={}", name, date);
        return new CalculatorEntry(name, List.of());
    }

    private CalculatorEntry entryWithSyntheticRun(String name, Instant estStart, Instant estEnd, long expectedMs) {
        RunEntry synthetic = RunEntry.builder()
                .slaStatus("ON_TIME")
                .estimatedStartTime(estStart)
                .estimatedEndTime(estEnd)
                .expectedDurationMs(expectedMs)
                .slaBreached(false)
                .isRerun(false)
                .build();
        return new CalculatorEntry(name, List.of(synthetic));
    }

    private RunEntry collapseSplitGroup(List<CalculatorRun> splits) {
        CalculatorRun first = splits.stream()
                .min(Comparator.comparing(CalculatorRun::getCreatedAt))
                .orElseThrow();

        RunStatus worstStatus = splits.stream()
                .map(CalculatorRun::getStatus)
                .min(Comparator.comparingInt(STATUS_PRECEDENCE::indexOf))
                .orElse(RunStatus.SUCCESS);

        Instant startTime = splits.stream().map(CalculatorRun::getStartTime)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant endTime = worstStatus == RunStatus.RUNNING ? null :
                splits.stream().map(CalculatorRun::getEndTime)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        Long durationMs = startTime != null && endTime != null
                ? endTime.toEpochMilli() - startTime.toEpochMilli() : null;

        boolean slaBreached = splits.stream().anyMatch(r -> Boolean.TRUE.equals(r.getSlaBreached()));
        String breachReason = splits.stream().map(CalculatorRun::getSlaBreachReason)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("; "));

        CalculatorRun rep = new CalculatorRun();
        rep.setRunId(first.getRunId());
        rep.setCalculatorId(first.getCalculatorId());
        rep.setCalculatorName(first.getCalculatorName());
        rep.setRegion(first.getRegion());
        rep.setRunType(first.getRunType());
        rep.setRunNumber(first.getRunNumber());
        rep.setStatus(worstStatus);
        rep.setStartTime(startTime);
        rep.setEndTime(endTime);
        rep.setDurationMs(durationMs);
        rep.setSlaBreached(slaBreached);
        rep.setSlaBreachReason(breachReason.isBlank() ? null : breachReason);
        rep.setSlaTime(first.getSlaTime());
        rep.setExpectedDurationMs(first.getExpectedDurationMs());
        rep.setEstimatedStartTime(first.getEstimatedStartTime());
        rep.setEstimatedEndTime(first.getEstimatedEndTime());
        rep.setRerun(false);  // parallel splits are not sequential reruns

        return toRunEntry(rep);
    }

    private RunEntry toRunEntry(CalculatorRun run) {
        String slaStatus = RunStatusClassifier.classifyDurationBased(run, slaProps.bandGapMs(), Instant.now());

        return RunEntry.builder()
                .runId(run.getRunId())
                .region(run.getRegion())
                .runType(run.getRunType())
                .status(run.getStatus().name())
                .slaStatus(slaStatus)
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .estimatedStartTime(run.getEstimatedStartTime())
                .estimatedEndTime(run.getEstimatedEndTime())
                .sla(run.getSlaTime())
                .durationMs(run.getDurationMs())
                .expectedDurationMs(run.getExpectedDurationMs())
                .slaBreached(run.getSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .isRerun(run.isRerun())
                .build();
    }
}
