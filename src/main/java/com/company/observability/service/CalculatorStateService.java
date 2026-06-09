package com.company.observability.service;

import com.company.observability.cache.CalculatorStateCacheService;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.SlaBand;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private final SlaProperties slaProperties;
    private final CalculatorStateCacheService stateCache;
    private final CalculatorProfileService profileService;
    private final BusinessCalendarService businessCalendar;

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
                    name -> buildEntry(name, runsByName.getOrDefault(name, List.of()), reportingDate, frequency, rn),
                    (a, b) -> a,
                    java.util.LinkedHashMap::new
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
                                       LocalDate reportingDate, Frequency frequency, String runNumber) {
        if (runs.isEmpty()) {
            return buildNotStartedEntry(calculatorName, reportingDate, frequency, runNumber);
        }

        // Phase 1: collapse parallel splits (shared correlationId) into one RunEntry each
        Map<String, List<CalculatorRun>> splitGroups = runs.stream()
                .filter(r -> r.getCorrelationId() != null)
                .collect(Collectors.groupingBy(CalculatorRun::getCorrelationId));

        List<RunEntry> splitEntries = splitGroups.values().stream()
                .map(group -> collapseSplitGroup(group, calculatorName))
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
                    return toRunEntry(latest, calculatorName);
                })
                .toList();

        String calculatorId = runs.stream().findFirst().map(CalculatorRun::getCalculatorId).orElse(null);
        List<RunEntry> allEntries = Stream.concat(splitEntries.stream(), standaloneEntries.stream()).toList();
        return new CalculatorEntry(calculatorName, calculatorId, allEntries);
    }

    /**
     * Build a synthetic "not started" entry for a calculator that has no run on the queried date.
     * Uses the cached rolling profile (30-day average) as the primary source, falling back to
     * the most recent run's stored estimated values projected onto the queried date.
     * Returns an empty entry only for brand-new calculators with no history at all.
     */
    private CalculatorEntry buildNotStartedEntry(String name, LocalDate date, Frequency freq,
                                                  String runNumber) {
        // 1. Try run_number-scoped profile (Redis-cached 26h — very cheap); calculatorId not available here
        CalculatorProfile profile = profileService.getProfile(name, freq, runNumber);
        if (profile.hasSufficientSamples(slaProps.getMinSampleSize())) {
            Instant estStart = TimeUtils.instantFromUtcMinuteOfDay(date, profile.avgStartMinUtc());
            Instant estEnd = estStart.plusMillis(profile.avgDurationMs());
            log.debug("event=batch_runs.not_started source=profile calculator={} date={}", name, date);
            // No historical slaTime available from profile alone — sla projection omitted
            return entryWithSyntheticRun(name, null, estStart, estEnd, profile.avgDurationMs(), null);
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
                // Re-anchor historical sla deadline to the target reporting date + runNumber
                Instant projectedSla = r.getSlaTime() != null
                        ? projectSlaTime(r.getSlaTime(), date, runNumber) : null;
                log.debug("event=batch_runs.not_started source=latest_run calculator={} date={}", name, date);
                return entryWithSyntheticRun(name, r.getCalculatorId(), estStart, estEnd,
                        r.getExpectedDurationMs(), projectedSla);
            }
        }

        // 3. Brand-new calculator with no history — return empty (same as before)
        log.debug("event=batch_runs.not_started source=none calculator={} date={}", name, date);
        return new CalculatorEntry(name, null, List.of());
    }

    /**
     * Re-anchors a historical frozen SLA deadline onto a new reporting date using the
     * business-calendar formula. Extracts the time-of-day from the historical instant in the
     * configured timezone (correct for DST) and advances {@code targetDate} by
     * {@code parseRunNumber(runNumber)} business days.
     */
    private Instant projectSlaTime(Instant historicalSlaTime, LocalDate targetDate, String runNumber) {
        ZoneId zone = ZoneId.of(slaProperties.getSlaTimezone());
        LocalTime clockTime = historicalSlaTime.atZone(zone).toLocalTime();
        int n = SlaBaselineResolver.parseRunNumber(runNumber);
        LocalDate executionDate = businessCalendar.nextBusinessDay(targetDate, n);
        return ZonedDateTime.of(executionDate, clockTime, zone).toInstant();
    }

    private CalculatorEntry entryWithSyntheticRun(String name, String calculatorId,
                                                   Instant estStart, Instant estEnd,
                                                   long expectedMs, Instant projectedSla) {
        CalculatorDimensionService.SlaEval sla =
                CalculatorDimensionService.evaluateSlaStatus(estEnd, slaProps.bandGapMs());
        RunEntry synthetic = RunEntry.builder()
                .status("NOT_STARTED")
                .slaStatus(sla.slaStatus())
                .slaBreached(sla.slaBreached())
                .estimatedStartTime(estStart)
                .estimatedEndTime(estEnd)
                .expectedDurationMs(expectedMs)
                .sla(projectedSla)
                .isRerun(false)
                .build();
        return new CalculatorEntry(name, calculatorId, List.of(synthetic));
    }

    private RunEntry collapseSplitGroup(List<CalculatorRun> splits, String entryName) {
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

        // Pick worst band across splits (VERY_LATE > LATE > ON_TIME > null)
        SlaBand worstBand = splits.stream()
                .map(CalculatorRun::getSlaBand)
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(b -> b == SlaBand.VERY_LATE ? 2 : b == SlaBand.LATE ? 1 : 0))
                .orElse(null);
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
        rep.setSlaBand(worstBand);
        rep.setSlaBreachReason(breachReason.isBlank() ? null : breachReason);
        rep.setSlaTime(first.getSlaTime());
        rep.setExpectedDurationMs(first.getExpectedDurationMs());
        rep.setEstimatedStartTime(first.getEstimatedStartTime());
        rep.setEstimatedEndTime(first.getEstimatedEndTime());
        rep.setRerun(false);  // parallel splits are not sequential reruns

        return toRunEntry(rep, entryName);
    }

    private RunEntry toRunEntry(CalculatorRun run, String entryName) {
        // Populate calculatorName on RunEntry only when the run originates from a
        // differently-named real calculator (multi-alias merge). Null otherwise → omitted in JSON.
        String runCalcName = run.getCalculatorName() != null
                && !run.getCalculatorName().equals(entryName)
                ? run.getCalculatorName() : null;

        return RunEntry.builder()
                .calculatorName(runCalcName)
                .runId(run.getRunId())
                .region(run.getRegion())
                .runType(run.getRunType())
                .status(run.getStatus().name())
                .slaStatus(run.getSlaBand() != null ? run.getSlaBand().name() : "ON_TIME")
                .slaBreached(run.isSlaBreached() ? Boolean.TRUE : null)
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .estimatedStartTime(run.getEstimatedStartTime())
                .estimatedEndTime(run.getEstimatedEndTime())
                .sla(run.getSlaTime())
                .durationMs(run.getDurationMs())
                .expectedDurationMs(run.getExpectedDurationMs())
                .slaBreachReason(run.getSlaBreachReason())
                .isRerun(run.isRerun())
                .build();
    }
}
