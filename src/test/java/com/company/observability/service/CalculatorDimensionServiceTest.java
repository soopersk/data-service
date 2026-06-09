package com.company.observability.service;

import com.company.observability.config.CalculatorAliasProperties;
import com.company.observability.config.CalculatorDimensionProperties;
import com.company.observability.config.CalculatorDimensionProperties.DimensionConfig;
import com.company.observability.config.CalculatorDimensionProperties.DimensionType;
import com.company.observability.config.DurationBasedSlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculatorDimensionServiceTest {

    private static final List<String> ALL_REGIONS =
            List.of("WMAP", "WMDE", "ASIA", "WMUS", "AUNZ", "WMCH", "ZURI", "LDNL", "AMER", "EURO");
    private static final LocalDate DATE = LocalDate.of(2026, 6, 8);
    private static final Frequency FREQ = Frequency.DAILY;

    @Mock CalculatorProfileService profileService;

    CalculatorDimensionProperties dimensionProps;
    CalculatorAliasProperties aliasProps;
    DurationBasedSlaProperties slaProps;
    CalculatorDimensionService service;

    @BeforeEach
    void setUp() {
        dimensionProps = new CalculatorDimensionProperties();
        aliasProps = new CalculatorAliasProperties();
        slaProps = new DurationBasedSlaProperties();

        DimensionConfig capitalConfig = new DimensionConfig();
        capitalConfig.setType(DimensionType.REGION);
        capitalConfig.setValues(ALL_REGIONS);
        dimensionProps.getCalculatorDimensions().put("capital", capitalConfig);

        service = new CalculatorDimensionService(dimensionProps, aliasProps, profileService, slaProps);
    }

    // -----------------------------------------------------------------------
    // Padding: partial → full dimension set
    // -----------------------------------------------------------------------

    @Test
    void padsDimensionsToFullSetWhenPartialRunsPresent() {
        // 7 of 10 regions have actual DB runs
        List<RunEntry> sevenRuns = List.of(
                realEntry("WMAP"), realEntry("WMDE"), realEntry("ASIA"),
                realEntry("WMUS"), realEntry("AUNZ"), realEntry("WMCH"), realEntry("ZURI")
        );
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", sevenRuns);

        when(profileService.getProfile(anyString(), any(Frequency.class), any()))
                .thenReturn(new CalculatorProfile("capitalcalc", "DAILY", null, 3_600_000, 480, 540, 10));

        Map<String, CalculatorEntry> result =
                service.padDimensions(Map.of("capital", existing), DATE, FREQ, null);

        CalculatorEntry padded = result.get("capital");
        assertThat(padded.runs()).hasSize(10);
    }

    @Test
    void syntheticEntriesHaveNotStartedStatusAndCorrectDimension() {
        List<RunEntry> twoRuns = List.of(realEntry("WMAP"), realEntry("WMDE"));
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", twoRuns);

        when(profileService.getProfile(anyString(), any(Frequency.class), any()))
                .thenReturn(new CalculatorProfile("capitalcalc", "DAILY", null, 3_600_000, 480, 540, 10));

        Map<String, CalculatorEntry> result =
                service.padDimensions(Map.of("capital", existing), DATE, FREQ, null);

        List<RunEntry> runs = result.get("capital").runs();

        // Declared order: WMAP first, then WMDE, then the 8 synthetic entries
        assertThat(runs.get(0).region()).isEqualTo("WMAP");
        assertThat(runs.get(0).status()).isEqualTo("SUCCESS"); // real entry untouched

        assertThat(runs.get(1).region()).isEqualTo("WMDE");
        assertThat(runs.get(1).status()).isEqualTo("SUCCESS"); // real entry untouched

        // All remaining 8 are synthetic
        for (int i = 2; i < 10; i++) {
            assertThat(runs.get(i).status()).isEqualTo("NOT_STARTED");
            assertThat(runs.get(i).isRerun()).isFalse();
            assertThat(ALL_REGIONS).contains(runs.get(i).region());
        }
    }

    @Test
    void declaredOrderPreservedAcrossRealAndSyntheticEntries() {
        // Only AMER and EURO present — last two in declared order
        List<RunEntry> twoLastRuns = List.of(realEntry("AMER"), realEntry("EURO"));
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", twoLastRuns);

        when(profileService.getProfile(anyString(), any(Frequency.class), any()))
                .thenReturn(new CalculatorProfile("capitalcalc", "DAILY", null, 3_600_000, 480, 540, 10));

        Map<String, CalculatorEntry> result =
                service.padDimensions(Map.of("capital", existing), DATE, FREQ, null);

        List<RunEntry> runs = result.get("capital").runs();
        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
        assertThat(runs.get(8).status()).isEqualTo("SUCCESS"); // AMER at index 8
        assertThat(runs.get(9).status()).isEqualTo("SUCCESS"); // EURO at index 9
    }

    @Test
    void unconfiguredAliasPassesThroughUnchanged() {
        List<RunEntry> runs = List.of(realEntry("some-value"));
        CalculatorEntry existing = new CalculatorEntry("portfolio", "pid", runs);

        Map<String, CalculatorEntry> result =
                service.padDimensions(Map.of("portfolio", existing), DATE, FREQ, null);

        assertThat(result.get("portfolio").runs()).hasSize(1);
        assertThat(result.get("portfolio").runs().get(0).region()).isEqualTo("some-value");
    }

    @Test
    void allTenDimensionsPresentReturnsInDeclaredOrder() {
        List<RunEntry> allRuns = ALL_REGIONS.stream().map(this::realEntry).toList();
        CalculatorEntry existing = new CalculatorEntry("capital", "calc-id", allRuns);

        Map<String, CalculatorEntry> result =
                service.padDimensions(Map.of("capital", existing), DATE, FREQ, null);

        List<RunEntry> runs = result.get("capital").runs();
        assertThat(runs).hasSize(10);
        assertThat(runs).extracting(RunEntry::region).containsExactlyElementsOf(ALL_REGIONS);
    }

    // -----------------------------------------------------------------------
    // evaluateSlaStatus branch coverage
    // -----------------------------------------------------------------------

    @Test
    void evaluateSlaStatus_futureEnd_returnsOnTime() {
        Instant futureEnd = Instant.now().plusSeconds(3600);
        CalculatorDimensionService.SlaEval eval =
                CalculatorDimensionService.evaluateSlaStatus(futureEnd, slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("ON_TIME");
        assertThat(eval.slaBreached()).isFalse();
    }

    @Test
    void evaluateSlaStatus_pastEndWithinBandGap_returnsLate() {
        // bandGap default = (30 - 15) minutes = 15 minutes = 900_000ms
        // Put end 5 minutes in the past → within the band gap
        Instant recentPastEnd = Instant.now().minusSeconds(300);
        CalculatorDimensionService.SlaEval eval =
                CalculatorDimensionService.evaluateSlaStatus(recentPastEnd, slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("LATE");
        assertThat(eval.slaBreached()).isTrue();
    }

    @Test
    void evaluateSlaStatus_pastEndBeyondBandGap_returnsVeryLate() {
        // Put end 30+ minutes in the past → beyond the band gap
        Instant oldPastEnd = Instant.now().minusSeconds(2000);
        CalculatorDimensionService.SlaEval eval =
                CalculatorDimensionService.evaluateSlaStatus(oldPastEnd, slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("VERY_LATE");
        assertThat(eval.slaBreached()).isTrue();
    }

    @Test
    void evaluateSlaStatus_nullEnd_returnsOnTime() {
        CalculatorDimensionService.SlaEval eval =
                CalculatorDimensionService.evaluateSlaStatus(null, slaProps.bandGapMs());
        assertThat(eval.slaStatus()).isEqualTo("ON_TIME");
        assertThat(eval.slaBreached()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RunEntry realEntry(String region) {
        return RunEntry.builder()
                .region(region)
                .status("SUCCESS")
                .slaStatus("ON_TIME")
                .isRerun(false)
                .build();
    }
}
