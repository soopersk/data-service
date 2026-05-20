package com.company.observability.service.projection;

import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.enums.SlaStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups physical calculator runs that share a correlationId into one logical run.
 * Runs with no correlationId are treated as singletons and pass through unchanged.
 *
 * <p>Grouping rules:
 * <ul>
 *   <li>Status precedence (worst-wins): RUNNING > FAILED > TIMEOUT > CANCELLED > SUCCESS
 *   <li>Wall-clock duration: MAX(end_time) - MIN(start_time); null if any split is RUNNING
 *   <li>SLA breach: OR across splits (any breach = logical breach)
 *   <li>SLA status precedence: VERY_LATE > LATE > ON_TIME
 *   <li>reportingDate: MIN across splits (supports cross-midnight splits)
 * </ul>
 */
public final class LogicalRunGrouper {

    private LogicalRunGrouper() {}

    public record LogicalRun(
            String runId,           // representative runId (earliest by createdAt)
            String calculatorId,
            String calculatorName,
            String tenantId,
            String frequency,
            LocalDate reportingDate,
            Instant startTime,
            Instant endTime,        // null if any split is RUNNING
            Long wallClockDurationMs,
            String status,
            Boolean slaBreached,
            String slaBreachReason,
            String slaStatus,
            Instant slaTime,
            Instant estimatedStartTime,
            List<String> subRunIds,  // all physical runIds in this logical group; null/empty for singletons
            String runNumber,
            Long expectedDurationMs
    ) {}

    /**
     * Groups a list of RunWithSlaStatus entries by correlationId.
     * Input must be ordered by (reporting_date ASC, created_at ASC) — repository guarantees this.
     */
    public static List<LogicalRun> groupWithSla(List<RunWithSlaStatus> runs) {
        if (runs.isEmpty()) {
            return Collections.emptyList();
        }

        // Partition into groups keyed by (correlationId) or (runId) for singletons.
        // LinkedHashMap preserves first-seen order so output ordering is stable.
        Map<String, List<RunWithSlaStatus>> groups = new LinkedHashMap<>();
        for (RunWithSlaStatus run : runs) {
            String key = run.correlationId() != null ? run.correlationId() : run.runId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(run);
        }

        return groups.values().stream()
                .map(LogicalRunGrouper::collapse)
                .collect(Collectors.toList());
    }

    private static LogicalRun collapse(List<RunWithSlaStatus> splits) {
        if (splits.size() == 1) {
            return toSingleton(splits.get(0));
        }

        // First split in list is earliest (repository orders ASC by created_at)
        RunWithSlaStatus first = splits.get(0);

        String representativeRunId = first.runId();
        LocalDate reportingDate = splits.stream()
                .map(RunWithSlaStatus::reportingDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(first.reportingDate());

        Instant startTime = splits.stream()
                .map(RunWithSlaStatus::startTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(first.startTime());

        RunStatus worstStatus = worstStatus(splits);

        // Wall-clock end is null while any split is still running
        Instant endTime = null;
        Long wallClockMs = null;
        if (worstStatus != RunStatus.RUNNING) {
            endTime = splits.stream()
                    .map(RunWithSlaStatus::endTime)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            if (endTime != null && startTime != null) {
                wallClockMs = endTime.toEpochMilli() - startTime.toEpochMilli();
            }
        }

        boolean anyBreached = splits.stream().anyMatch(r -> Boolean.TRUE.equals(r.slaBreached()));

        String breachReason = splits.stream()
                .map(RunWithSlaStatus::slaBreachReason)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("; "));
        if (breachReason.isBlank()) breachReason = null;

        String slaStatus = worstSlaStatus(splits);

        List<String> subRunIds = splits.stream()
                .map(RunWithSlaStatus::runId)
                .collect(Collectors.toList());

        return new LogicalRun(
                representativeRunId,
                first.calculatorId(),
                first.calculatorName(),
                null, // tenantId not in RunWithSlaStatus — not needed for projections
                first.frequency() != null ? first.frequency().name() : null,
                reportingDate,
                startTime,
                endTime,
                wallClockMs,
                worstStatus.name(),
                anyBreached,
                breachReason,
                slaStatus,
                first.slaTime(),
                first.estimatedStartTime(),
                subRunIds,
                splits.get(0).runNumber(),
                splits.get(0).expectedDurationMs()
        );
    }

    private static LogicalRun toSingleton(RunWithSlaStatus run) {
        String slaStatus = classifySlaStatus(run);
        Long wallClockMs = run.status() != RunStatus.RUNNING ? run.durationMs() : null;
        return new LogicalRun(
                run.runId(),
                run.calculatorId(),
                run.calculatorName(),
                null,
                run.frequency() != null ? run.frequency().name() : null,
                run.reportingDate(),
                run.startTime(),
                run.status() != RunStatus.RUNNING ? run.endTime() : null,
                wallClockMs,
                run.status().name(),
                run.slaBreached(),
                run.slaBreachReason(),
                slaStatus,
                run.slaTime(),
                run.estimatedStartTime(),
                null,
                run.runNumber(),
                run.expectedDurationMs()
        );
    }

    // ── Precedence helpers ──────────────────────────────────────────────────────

    private static final List<RunStatus> STATUS_PRECEDENCE = List.of(
            RunStatus.RUNNING, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED, RunStatus.SUCCESS
    );

    private static RunStatus worstStatus(List<RunWithSlaStatus> splits) {
        return splits.stream()
                .map(RunWithSlaStatus::status)
                .min(Comparator.comparingInt(STATUS_PRECEDENCE::indexOf))
                .orElse(RunStatus.SUCCESS);
    }

    private static final List<String> SLA_PRECEDENCE = List.of(
            SlaStatus.VERY_LATE.name(), SlaStatus.LATE.name(), SlaStatus.ON_TIME.name()
    );

    private static String worstSlaStatus(List<RunWithSlaStatus> splits) {
        return splits.stream()
                .map(LogicalRunGrouper::classifySlaStatus)
                .min(Comparator.comparingInt(s -> {
                    int idx = SLA_PRECEDENCE.indexOf(s);
                    return idx < 0 ? SLA_PRECEDENCE.size() : idx;
                }))
                .orElse(SlaStatus.ON_TIME.name());
    }

    private static String classifySlaStatus(RunWithSlaStatus run) {
        if (run.status() == RunStatus.RUNNING || !Boolean.TRUE.equals(run.slaBreached())) return SlaStatus.ON_TIME.name();
        if (run.severity() == null) return SlaStatus.LATE.name();
        return switch (run.severity()) {
            case CRITICAL, HIGH -> SlaStatus.VERY_LATE.name();
            default -> SlaStatus.LATE.name();
        };
    }
}
