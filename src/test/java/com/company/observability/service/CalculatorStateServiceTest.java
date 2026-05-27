package com.company.observability.service;

import com.company.observability.cache.CalculatorStateCacheService;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.repository.CalculatorRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalculatorStateServiceTest {

    @Mock
    CalculatorRunRepository runRepository;

    @Mock
    CalculatorStateCacheService stateCache;

    @Mock
    CalculatorProfileService profileService;

    // Real DurationBasedSlaProperties — still needed for getMinSampleSize() (profile estimation path).
    CalculatorStateService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 6);
    private static final Frequency FREQ = Frequency.DAILY;
    private static final String FREQ_NAME = "DAILY";
    private static final Instant SLA_TIME = Instant.parse("2026-03-06T15:00:00Z");
    private static final Instant T_MINUS_3 = Instant.parse("2026-03-06T12:00:00Z");
    private static final Instant T_MINUS_2 = Instant.parse("2026-03-06T13:00:00Z");
    private static final Instant T_MINUS_1 = Instant.parse("2026-03-06T14:00:00Z");
    private static final Instant NOW       = Instant.parse("2026-03-06T14:45:00Z");

    // Zero-sample profile — not enough history to derive estimates.
    private static final CalculatorProfile NO_HISTORY_PROFILE =
            new CalculatorProfile("test-calc", "DAILY", 0, 0, 0, 0);

    @BeforeEach
    void setUp() {
        service = new CalculatorStateService(runRepository, new DurationBasedSlaProperties(), stateCache, profileService);
        // Default: cache returns no hits (all misses) so DB is called — matches all pre-existing tests
        lenient().when(stateCache.getEntries(any(), anyString(), any(), any()))
                .thenReturn(new HashMap<>());
        // Default: no profile history and no fallback run — empty-runs case returns empty entry
        lenient().when(profileService.getProfile(anyString(), any(Frequency.class)))
                .thenReturn(NO_HISTORY_PROFILE);
        lenient().when(runRepository.findLatestRunEstimatesByName(anyString(), any(Frequency.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void returnsEmptyRunsForMissingCalculator() {
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> result =
                service.getState(DATE, FREQ, "1",List.of("missing-calc"));

        assertThat(result).containsKey("missing-calc");
        assertThat(result.get("missing-calc").runs()).isEmpty();
    }

    @Test
    void splitGroupsCollapsedIntoOneEntry() {
        CalculatorRun s1 = buildRun("cap", "r-s1", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_1, SLA_TIME);
        CalculatorRun s2 = buildRun("cap", "r-s2", RunStatus.FAILED,  "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun s3 = buildRun("cap", "r-s3", RunStatus.RUNNING, "WMAP", null, "1", "corr-1", T_MINUS_3, null,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(s1, s2, s3));

        var result = service.getState(DATE, FREQ, "1",List.of("cap"));
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("RUNNING");   // worst-wins
        assertThat(entries.get(0).isRerun()).isFalse();              // splits != rerun
    }

    @Test
    void standaloneRerunDetectedByMultipleAttemptsInSameDimension() {
        CalculatorRun attempt1 = buildRun("cap", "r-1", RunStatus.FAILED,  "LDNL", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun attempt2 = buildRun("cap", "r-2", RunStatus.SUCCESS, "LDNL", null, "1", null, T_MINUS_1, NOW,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(attempt1, attempt2));

        var result = service.getState(DATE, FREQ, "1",List.of("cap"));
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
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(dbRuns);

        var result = service.getState(DATE, FREQ, "1",List.of("capital"));
        var entry = result.get("capital");

        assertThat(entry.runs()).hasSize(2);
        assertThat(entry.runs()).extracting(CalculatorBatchRunsResponse.RunEntry::region)
                .containsExactlyInAnyOrder("WMAP", "WMDE");
    }

    // ── Duration-based slaStatus tests ──────────────────────────────────────

    /**
     * A RUNNING run whose live job has set slaBreached=true must surface as LATE/VERY_LATE,
     * not ON_TIME. Previously the old classify() returned ON_TIME for every RUNNING run.
     */
    @Test
    void runningRunWithSlaBreachedTrueIsNotOnTime() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.RUNNING, "WMAP", null, "1", null,
                T_MINUS_3, null, SLA_TIME);
        run.setSlaBand(com.company.observability.domain.enums.SlaBand.LATE);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1",List.of("calc")).get("calc").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).slaStatus()).isNotEqualTo("ON_TIME");
    }

    /**
     * A completed run graded LATE by SlaEvaluationService → slaStatus surfaces as LATE.
     */
    @Test
    void completedRunWithLateBandIsLate() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.SUCCESS, "WMAP", null, "1", null,
                T_MINUS_3, SLA_TIME.plusSeconds(10 * 60), SLA_TIME);
        run.setSlaBand(com.company.observability.domain.enums.SlaBand.LATE);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1",List.of("calc")).get("calc").runs();

        assertThat(entries.get(0).slaStatus()).isEqualTo("LATE");
    }

    /**
     * A completed run graded VERY_LATE by SlaEvaluationService → slaStatus surfaces as VERY_LATE.
     */
    @Test
    void completedRunWithVeryLateBandIsVeryLate() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.SUCCESS, "WMAP", null, "1", null,
                T_MINUS_3, SLA_TIME.plusSeconds(20 * 60), SLA_TIME);
        run.setSlaBand(com.company.observability.domain.enums.SlaBand.VERY_LATE);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1",List.of("calc")).get("calc").runs();

        assertThat(entries.get(0).slaStatus()).isEqualTo("VERY_LATE");
    }

    /**
     * expectedDurationMs must be propagated into RunEntry for standalone runs.
     */
    @Test
    void expectedDurationMsPropagatedForStandaloneRun() {
        CalculatorRun run = buildRun("calc", "r-1", RunStatus.SUCCESS, "WMAP", null, "1", null,
                T_MINUS_3, T_MINUS_1, SLA_TIME);
        run.setExpectedDurationMs(5_400_000L);  // 90 min expected

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(run));

        var entries = service.getState(DATE, FREQ, "1",List.of("calc")).get("calc").runs();

        assertThat(entries.get(0).expectedDurationMs()).isEqualTo(5_400_000L);
    }

    /**
     * expectedDurationMs must survive split-group collapse (collapseSplitGroup path).
     */
    @Test
    void expectedDurationMsPropagatedForSplitGroup() {
        CalculatorRun s1 = buildRun("cap", "r-s1", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1",
                T_MINUS_3, T_MINUS_1, SLA_TIME);
        s1.setExpectedDurationMs(7_200_000L);  // 120 min
        CalculatorRun s2 = buildRun("cap", "r-s2", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1",
                T_MINUS_2, T_MINUS_1, SLA_TIME);
        s2.setExpectedDurationMs(7_200_000L);

        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(s1, s2));

        var entries = service.getState(DATE, FREQ, "1",List.of("cap")).get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).expectedDurationMs()).isEqualTo(7_200_000L);
    }

    // ── Cache behaviour ─────────────────────────────────────────────────────

    @Test
    void fullCacheHit_skipsDbCall() {
        CalculatorEntry cachedEntry = new CalculatorEntry("cap", List.of());
        when(stateCache.getEntries(eq(DATE), eq(FREQ_NAME), isNull(), eq(List.of("cap"))))
                .thenReturn(Map.of("cap", cachedEntry));

        Map<String, CalculatorEntry> result = service.getState(DATE, FREQ, null, List.of("cap"));

        assertThat(result).containsKey("cap");
        verifyNoInteractions(runRepository);
    }

    @Test
    void partialCacheHit_queriesDbOnlyForMisses() {
        CalculatorRun run = buildRun("other", "r-1", RunStatus.SUCCESS, "WMAP", null, "1",
                null, T_MINUS_3, T_MINUS_1, SLA_TIME);
        CalculatorEntry cachedEntry = new CalculatorEntry("cap", List.of());

        when(stateCache.getEntries(eq(DATE), eq(FREQ_NAME), eq("1"), eq(List.of("cap", "other"))))
                .thenReturn(new HashMap<>(Map.of("cap", cachedEntry)));
        when(runRepository.findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), eq("1"), eq(List.of("other"))))
                .thenReturn(List.of(run));

        Map<String, CalculatorEntry> result = service.getState(DATE, FREQ, "1", List.of("cap", "other"));

        assertThat(result).containsKey("cap");
        assertThat(result).containsKey("other");
        // DB was only called for the miss ("other"), not for "cap"
        verify(runRepository).findAllRunsByDateAndDimension(DATE, FREQ, "1", List.of("other"));
    }

    @Test
    void absentCalculator_cachedAsEmptyEntry_toPreventRepeatDbHits() {
        when(runRepository.findAllRunsByDateAndDimension(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getState(DATE, FREQ, null, List.of("missing-calc"));

        // The empty-runs entry must be stored so a subsequent request hits cache, not DB
        verify(stateCache).putEntries(eq(DATE), eq(FREQ_NAME), isNull(), argThat(m ->
                m.containsKey("missing-calc") && m.get("missing-calc").runs().isEmpty()));
    }

    @Test
    void blankRunNumber_treatedAsNull_cachesUnder_all_sentinel() {
        when(runRepository.findAllRunsByDateAndDimension(any(), any(), isNull(), any()))
                .thenReturn(List.of());

        service.getState(DATE, FREQ, "  ", List.of("calc"));

        // Blank must normalise to null before reaching cache and repo
        verify(stateCache).getEntries(eq(DATE), eq(FREQ_NAME), isNull(), any());
        verify(runRepository).findAllRunsByDateAndDimension(eq(DATE), eq(FREQ), isNull(), any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

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
