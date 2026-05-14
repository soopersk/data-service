package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.repository.CalculatorRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculatorStateServiceTest {

    @Mock
    CalculatorRunRepository runRepository;

    @InjectMocks
    CalculatorStateService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 6);
    private static final CalculatorFrequency FREQ = CalculatorFrequency.DAILY;
    private static final Instant SLA_TIME = Instant.parse("2026-03-06T15:00:00Z");
    private static final Instant T_MINUS_3 = Instant.parse("2026-03-06T12:00:00Z");
    private static final Instant T_MINUS_2 = Instant.parse("2026-03-06T13:00:00Z");
    private static final Instant T_MINUS_1 = Instant.parse("2026-03-06T14:00:00Z");
    private static final Instant NOW       = Instant.parse("2026-03-06T14:45:00Z");

    @Test
    void returnsEmptyRunsForMissingCalculator() {
        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> result =
                service.getState("t1", DATE, FREQ, "1", List.of("missing-calc"));

        assertThat(result).containsKey("missing-calc");
        assertThat(result.get("missing-calc").runs()).isEmpty();
    }

    @Test
    void splitGroupsCollapsedIntoOneEntry() {
        CalculatorRun s1 = buildRun("cap", "r-s1", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_1, SLA_TIME);
        CalculatorRun s2 = buildRun("cap", "r-s2", RunStatus.FAILED,  "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun s3 = buildRun("cap", "r-s3", RunStatus.RUNNING, "WMAP", null, "1", "corr-1", T_MINUS_3, null,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(s1, s2, s3));

        var result = service.getState("t1", DATE, FREQ, "1", List.of("cap"));
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("RUNNING");   // worst-wins
        assertThat(entries.get(0).isRerun()).isFalse();              // splits != rerun
    }

    @Test
    void standaloneRerunDetectedByMultipleAttemptsInSameDimension() {
        CalculatorRun attempt1 = buildRun("cap", "r-1", RunStatus.FAILED,  "LDNL", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun attempt2 = buildRun("cap", "r-2", RunStatus.SUCCESS, "LDNL", null, "1", null, T_MINUS_1, NOW,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(attempt1, attempt2));

        var result = service.getState("t1", DATE, FREQ, "1", List.of("cap"));
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("SUCCESS");
        assertThat(entries.get(0).isRerun()).isTrue();
    }

    @Test
    void regionalRunsGroupedUnderOneCalculatorId() {
        List<CalculatorRun> dbRuns = List.of(
                buildRun("capital", "r-wmap", RunStatus.SUCCESS, "WMAP", null, "1", null, T_MINUS_3, T_MINUS_1, SLA_TIME),
                buildRun("capital", "r-wmde", RunStatus.SUCCESS, "WMDE", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME)
        );
        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(dbRuns);

        var result = service.getState("t1", DATE, FREQ, "1", List.of("capital"));
        var entry = result.get("capital");

        assertThat(entry.runs()).hasSize(2);
        assertThat(entry.runs()).extracting(CalculatorBatchRunsResponse.RunEntry::region)
                .containsExactlyInAnyOrder("WMAP", "WMDE");
    }

    private CalculatorRun buildRun(String calcId, String runId, RunStatus status,
                                   String region, String runType, String runNumber,
                                   String correlationId, Instant createdAt,
                                   Instant endTime, Instant slaTime) {
        CalculatorRun run = new CalculatorRun();
        run.setCalculatorId(calcId);
        run.setCalculatorName(calcId);
        run.setRunId(runId);
        run.setStatus(status);
        run.setRegion(region);
        run.setRunType(runType);
        run.setRunNumber(runNumber);
        run.setCorrelationId(correlationId);
        run.setCreatedAt(createdAt);
        run.setEndTime(endTime);
        run.setSlaTime(slaTime);
        run.setReportingDate(DATE);
        return run;
    }
}
