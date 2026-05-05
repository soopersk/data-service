package com.company.observability.service.projection;

import com.company.observability.domain.RunWithSlaStatus;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.Severity;
import com.company.observability.dto.enums.SlaStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogicalRunGrouperTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 5);
    private static final Instant T0 = Instant.parse("2026-05-05T04:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-05T04:10:00Z");
    private static final Instant T2 = Instant.parse("2026-05-05T04:20:00Z");
    private static final Instant T3 = Instant.parse("2026-05-05T04:30:00Z");
    private static final Instant SLA = Instant.parse("2026-05-05T05:00:00Z");

    private RunWithSlaStatus run(String runId, String correlationId, RunStatus status,
                                 Instant start, Instant end, boolean slaBreached, Severity severity) {
        return new RunWithSlaStatus(
                runId, "calc-1", "Calc One", DATE, start, end,
                end != null ? end.toEpochMilli() - start.toEpochMilli() : null,
                SLA, T0, CalculatorFrequency.DAILY, status,
                slaBreached, slaBreached ? "breached" : null,
                severity, correlationId);
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertThat(LogicalRunGrouper.groupWithSla(List.of())).isEmpty();
    }

    @Test
    void singleUngroupedRun_passesThroughAsSingleton() {
        RunWithSlaStatus r = run("run-A", null, RunStatus.SUCCESS, T0, T1, false, null);
        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(r));

        assertThat(result).hasSize(1);
        LogicalRunGrouper.LogicalRun lr = result.get(0);
        assertThat(lr.runId()).isEqualTo("run-A");
        assertThat(lr.status()).isEqualTo("SUCCESS");
        assertThat(lr.subRunIds()).isNull();
        assertThat(lr.wallClockDurationMs()).isEqualTo(T1.toEpochMilli() - T0.toEpochMilli());
    }

    @Test
    void singleSplitRun_correlationSetOneRun_passesThroughAsSingleton() {
        RunWithSlaStatus r = run("run-A", "corr-1", RunStatus.SUCCESS, T0, T1, false, null);
        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(r));

        assertThat(result).hasSize(1);
        // singleton path — subRunIds not populated for single-item groups
        assertThat(result.get(0).subRunIds()).isNull();
    }

    @Test
    void twoSplits_bothSuccess_collapseToOneLogicalRun() {
        RunWithSlaStatus a = run("run-A", "corr-1", RunStatus.SUCCESS, T0, T1, false, null);
        RunWithSlaStatus b = run("run-B", "corr-1", RunStatus.SUCCESS, T1, T2, false, null);

        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(a, b));

        assertThat(result).hasSize(1);
        LogicalRunGrouper.LogicalRun lr = result.get(0);
        assertThat(lr.status()).isEqualTo("SUCCESS");
        assertThat(lr.startTime()).isEqualTo(T0);
        assertThat(lr.endTime()).isEqualTo(T2);
        assertThat(lr.wallClockDurationMs()).isEqualTo(T2.toEpochMilli() - T0.toEpochMilli());
        assertThat(lr.slaBreached()).isFalse();
        assertThat(lr.subRunIds()).containsExactly("run-A", "run-B");
    }

    @Test
    void twoSplits_oneBreached_logicalIsBreached() {
        RunWithSlaStatus a = run("run-A", "corr-1", RunStatus.SUCCESS, T0, T1, false, null);
        RunWithSlaStatus b = run("run-B", "corr-1", RunStatus.SUCCESS, T1, T2, true, Severity.HIGH);

        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(a, b));

        assertThat(result).hasSize(1);
        LogicalRunGrouper.LogicalRun lr = result.get(0);
        assertThat(lr.slaBreached()).isTrue();
        assertThat(lr.slaStatus()).isEqualTo(SlaStatus.VERY_LATE.name());
    }

    @Test
    void threeSplits_oneRunning_logicalStatusIsRunning_endIsNull() {
        RunWithSlaStatus a = run("run-A", "corr-1", RunStatus.SUCCESS, T0, T1, false, null);
        RunWithSlaStatus b = run("run-B", "corr-1", RunStatus.SUCCESS, T1, T2, false, null);
        RunWithSlaStatus c = run("run-C", "corr-1", RunStatus.RUNNING, T2, null, false, null);

        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(a, b, c));

        assertThat(result).hasSize(1);
        LogicalRunGrouper.LogicalRun lr = result.get(0);
        assertThat(lr.status()).isEqualTo("RUNNING");
        assertThat(lr.endTime()).isNull();
        assertThat(lr.wallClockDurationMs()).isNull();
    }

    @Test
    void mixedFailures_failedBeatsTimeout() {
        RunWithSlaStatus a = run("run-A", "corr-1", RunStatus.TIMEOUT, T0, T1, true, Severity.HIGH);
        RunWithSlaStatus b = run("run-B", "corr-1", RunStatus.FAILED, T1, T2, true, Severity.CRITICAL);

        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(a, b));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void crossMidnightSplits_reportingDateIsMin() {
        LocalDate day1 = LocalDate.of(2026, 5, 5);
        LocalDate day2 = LocalDate.of(2026, 5, 6);

        RunWithSlaStatus a = new RunWithSlaStatus(
                "run-A", "calc-1", "Calc One", day1, T0, T1, 600_000L,
                SLA, T0, CalculatorFrequency.DAILY, RunStatus.SUCCESS,
                false, null, null, "corr-1");
        RunWithSlaStatus b = new RunWithSlaStatus(
                "run-B", "calc-1", "Calc One", day2, T2, T3, 600_000L,
                SLA, T0, CalculatorFrequency.DAILY, RunStatus.SUCCESS,
                false, null, null, "corr-1");

        List<LogicalRunGrouper.LogicalRun> result = LogicalRunGrouper.groupWithSla(List.of(a, b));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).reportingDate()).isEqualTo(day1);
    }

    @Test
    void ungroupedAndGroupedRuns_independentGroups() {
        RunWithSlaStatus standalone = run("run-Z", null, RunStatus.SUCCESS, T0, T1, false, null);
        RunWithSlaStatus splitA = run("run-A", "corr-1", RunStatus.SUCCESS, T0, T1, false, null);
        RunWithSlaStatus splitB = run("run-B", "corr-1", RunStatus.SUCCESS, T1, T2, false, null);

        List<LogicalRunGrouper.LogicalRun> result =
                LogicalRunGrouper.groupWithSla(List.of(standalone, splitA, splitB));

        assertThat(result).hasSize(2);
    }
}
