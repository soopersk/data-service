package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectedRunsServiceTest {

    private static final List<String> ALL_REGIONS =
            List.of("WMAP", "WMDE", "ASIA", "WMUS", "AUNZ", "WMCH", "ZURI", "LDNL", "AMER", "EURO");
    private static final List<String> ALL_TYPES = List.of("ETD", "OTC", "SFT");

    private final DurationBasedSlaProperties slaProps = new DurationBasedSlaProperties();

    private CalculatorProperties props;
    private ExpectedRunsService service;

    @BeforeEach
    void setUp() {
        props = new CalculatorProperties();
        props.setRegions(Map.of("capital", ALL_REGIONS));
        props.setRunTypes(Map.of("modelled-exposure", ALL_TYPES));
        service = new ExpectedRunsService(props);
    }

    // ── Region-dimensioned padding ─────────────────────────────────────────────

    @Test
    void padsRegionsToFullSetWhenPartialRunsPresent() {
        List<RunEntry> sevenRuns = List.of(
                realRegionEntry("WMAP"), realRegionEntry("WMDE"), realRegionEntry("ASIA"),
                realRegionEntry("WMUS"), realRegionEntry("AUNZ"), realRegionEntry("WMCH"), realRegionEntry("ZURI")
        );
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", sevenRuns);

        CalculatorEntry padded = service.padToExpected(Map.of("capital", existing)).get("capital");

        assertThat(padded.runs()).hasSize(10);
        assertThat(padded.runs()).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
    }

    @Test
    void syntheticPlaceholdersAreNotStartedWithCorrectRegion() {
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("WMAP"), realRegionEntry("WMDE")));

        List<RunEntry> runs = service.padToExpected(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs.get(0).region()).isEqualTo("WMAP");
        assertThat(runs.get(0).status()).isEqualTo("SUCCESS");   // real entry untouched
        assertThat(runs.get(1).region()).isEqualTo("WMDE");
        assertThat(runs.get(1).status()).isEqualTo("SUCCESS");
        for (int i = 2; i < 10; i++) {
            assertThat(runs.get(i).status()).isEqualTo("NOT_STARTED");
            assertThat(runs.get(i).isRerun()).isFalse();
            assertThat(ALL_REGIONS).contains(runs.get(i).region());
        }
    }

    @Test
    void declaredOrderPreservedAcrossRealAndSyntheticEntries() {
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id",
                List.of(realRegionEntry("AMER"), realRegionEntry("EURO")));

        List<RunEntry> runs = service.padToExpected(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
        assertThat(runs.get(8).status()).isEqualTo("SUCCESS");   // AMER at index 8
        assertThat(runs.get(9).status()).isEqualTo("SUCCESS");   // EURO at index 9
    }

    @Test
    void allTenRegionsPresentReturnsInDeclaredOrder() {
        List<RunEntry> allRuns = ALL_REGIONS.stream().map(this::realRegionEntry).toList();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", allRuns);

        List<RunEntry> runs = service.padToExpected(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(10);
        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
    }

    // ── Run-type-dimensioned padding ───────────────────────────────────────────

    @Test
    void padsRunTypesToFullSet_settingRunTypeNotRegion() {
        CalculatorEntry existing = new CalculatorEntry("modelled-exposure", "calc-id",
                List.of(realRunTypeEntry("ETD")));

        List<RunEntry> runs = service.padToExpected(Map.of("modelled-exposure", existing))
                .get("modelled-exposure").runs();

        assertThat(runs).extracting(RunEntry::runType).containsExactlyElementsOf(ALL_TYPES);
        // Placeholders set runType, never region
        assertThat(runs).allSatisfy(r -> assertThat(r.region()).isNull());
        assertThat(runs.get(1).status()).isEqualTo("NOT_STARTED");
        assertThat(runs.get(2).status()).isEqualTo("NOT_STARTED");
    }

    // ── Template reuse (zero-run bug fix) ──────────────────────────────────────

    @Test
    void zeroRuns_reusesUpstreamSyntheticAsTemplate_preservingProjectedSla() {
        Instant projectedSla = Instant.parse("2026-02-23T22:00:00Z");
        Instant estEnd = Instant.parse("2026-02-23T20:00:00Z");
        // Upstream (CalculatorStateService) not-started synthetic: null region/runType, carries SLA.
        RunEntry template = RunEntry.builder()
                .status("NOT_STARTED")
                .slaStatus("ON_TIME")
                .estimatedEndTime(estEnd)
                .sla(projectedSla)
                .isRerun(false)
                .build();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", List.of(template));

        List<RunEntry> runs = service.padToExpected(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(10);
        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
        // Every placeholder carries the projected SLA + estimates from the template
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("NOT_STARTED");
            assertThat(r.sla()).isEqualTo(projectedSla);
            assertThat(r.estimatedEndTime()).isEqualTo(estEnd);
        });
    }

    @Test
    void zeroRunsWithNoTemplate_emitsBarePlaceholders() {
        // Brand-new calc: upstream returned an empty entry (no synthetic template).
        CalculatorEntry existing = new CalculatorEntry("capital", null, List.of());

        List<RunEntry> runs = service.padToExpected(Map.of("capital", existing)).get("capital").runs();

        assertThat(runs).hasSize(10);
        assertThat(runs).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("NOT_STARTED");
            assertThat(r.slaStatus()).isEqualTo("ON_TIME");
            assertThat(r.sla()).isNull();
        });
    }

    @Test
    void unconfiguredAliasPassesThroughUnchanged() {
        CalculatorEntry existing = new CalculatorEntry("portfolio", "pid",
                List.of(realRegionEntry("some-value")));

        CalculatorEntry result = service.padToExpected(Map.of("portfolio", existing)).get("portfolio");

        assertThat(result.runs()).hasSize(1);
        assertThat(result.runs().get(0).region()).isEqualTo("some-value");
    }

    @Test
    void noDimensionConfig_returnsInputUnchanged() {
        ExpectedRunsService bare = new ExpectedRunsService(new CalculatorProperties());
        Map<String, CalculatorEntry> input = Map.of(
                "capital", new CalculatorEntry("capital", "id", List.of(realRegionEntry("WMAP"))));

        assertThat(bare.padToExpected(input)).isSameAs(input);
    }

    // ── evaluateSlaStatus branch coverage ──────────────────────────────────────

    @Test
    void evaluateSlaStatus_futureDeadline_returnsOnTime() {
        var eval = ExpectedRunsService.evaluateSlaStatus(Instant.now().plusSeconds(3600), slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("ON_TIME");
        assertThat(eval.slaBreached()).isFalse();
    }

    @Test
    void evaluateSlaStatus_pastDeadlineWithinBandGap_returnsLate() {
        var eval = ExpectedRunsService.evaluateSlaStatus(Instant.now().minusSeconds(300), slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("LATE");
        assertThat(eval.slaBreached()).isTrue();
    }

    @Test
    void evaluateSlaStatus_pastDeadlineBeyondBandGap_returnsVeryLate() {
        var eval = ExpectedRunsService.evaluateSlaStatus(Instant.now().minusSeconds(2000), slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("VERY_LATE");
        assertThat(eval.slaBreached()).isTrue();
    }

    @Test
    void evaluateSlaStatus_nullDeadline_returnsOnTime() {
        var eval = ExpectedRunsService.evaluateSlaStatus(null, slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("ON_TIME");
        assertThat(eval.slaBreached()).isFalse();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private RunEntry realRegionEntry(String region) {
        return RunEntry.builder().region(region).status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
    }

    private RunEntry realRunTypeEntry(String runType) {
        return RunEntry.builder().runType(runType).status("SUCCESS").slaStatus("ON_TIME").isRerun(false).build();
    }
}
