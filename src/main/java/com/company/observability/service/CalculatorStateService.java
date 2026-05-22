package com.company.observability.service;

import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.RunStatusClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CalculatorStateService {

    private final CalculatorRunRepository runRepository;
    private final DurationBasedSlaProperties slaProps;

    // Lower index = worse status (worst-wins ordering mirrors LogicalRunGrouper)
    private static final List<RunStatus> STATUS_PRECEDENCE = List.of(
            RunStatus.RUNNING, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED, RunStatus.SUCCESS);

    public Map<String, CalculatorEntry> getState(
            LocalDate reportingDate,
            CalculatorFrequency frequency,
            String runNumber,
            List<String> calculatorNames) {

        Map<String, List<CalculatorRun>> runsByName = runRepository
                .findAllRunsByDateAndDimension(reportingDate, frequency, runNumber, calculatorNames)
                .stream()
                .collect(Collectors.groupingBy(CalculatorRun::getCalculatorName));

        return calculatorNames.stream().collect(Collectors.toMap(
                name -> name,
                name -> buildEntry(name, runsByName.getOrDefault(name, List.of()))
        ));
    }

    private CalculatorEntry buildEntry(String calculatorName, List<CalculatorRun> runs) {
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
