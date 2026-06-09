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
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CalculatorDimensionService {

    private final CalculatorDimensionProperties dimensionProps;
    private final CalculatorAliasProperties aliasProps;
    private final CalculatorProfileService profileService;
    private final DurationBasedSlaProperties slaProps;

    /**
     * For each alias with a declared dimension set, pads its CalculatorEntry to contain exactly
     * N RunEntries in declared order — one per dimension value. Missing dimensions get synthetic
     * NOT_STARTED entries. Unconfigured aliases pass through unchanged.
     */
    public Map<String, CalculatorEntry> padDimensions(
            Map<String, CalculatorEntry> calculators,
            LocalDate reportingDate,
            Frequency frequency,
            String runNumber) {

        Map<String, DimensionConfig> configs = dimensionProps.getCalculatorDimensions();
        if (configs.isEmpty()) {
            return calculators;
        }

        Map<String, CalculatorEntry> result = new LinkedHashMap<>(calculators);
        for (Map.Entry<String, CalculatorEntry> entry : calculators.entrySet()) {
            String alias = entry.getKey();
            DimensionConfig config = configs.get(alias);
            if (config == null) {
                continue;
            }
            result.put(alias, pad(alias, entry.getValue(), config, reportingDate, frequency, runNumber));
        }
        return result;
    }

    private CalculatorEntry pad(String alias, CalculatorEntry existing,
                                DimensionConfig config, LocalDate reportingDate, Frequency frequency,
                                String runNumber) {
        List<String> declaredValues = config.getValues();
        boolean isRegion = config.getType() == DimensionType.REGION;

        // Index actual runs by their dimension value
        Map<String, RunEntry> byDimension = existing.runs().stream()
                .filter(r -> dimensionValue(r, isRegion) != null)
                .collect(Collectors.toMap(
                        r -> dimensionValue(r, isRegion),
                        r -> r,
                        (a, b) -> a  // keep first on collision
                ));

        if (byDimension.size() == declaredValues.size()) {
            // All dimensions present — reorder to declared order and return
            List<RunEntry> ordered = declaredValues.stream()
                    .map(v -> byDimension.getOrDefault(v, null))
                    .filter(Objects::nonNull)
                    .toList();
            return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), ordered);
        }

        // Resolve run_number-scoped profile for accurate synthetic entry estimation
        String firstRealName = aliasProps.getCalculatorAliases()
                .getOrDefault(alias, List.of(alias))
                .get(0);
        CalculatorProfile profile = profileService.getProfile(firstRealName, frequency, runNumber);

        Instant estStart = null;
        Instant estEnd = null;
        Long avgDurationMs = null;
        if (profile.hasSufficientSamples(slaProps.getMinSampleSize())) {
            estStart = TimeUtils.instantFromUtcMinuteOfDay(reportingDate, profile.avgStartMinUtc());
            estEnd = estStart.plusMillis(profile.avgDurationMs());
            avgDurationMs = profile.avgDurationMs();
        }

        List<RunEntry> padded = new ArrayList<>(declaredValues.size());
        long bandGapMs = slaProps.bandGapMs();
        for (String dimValue : declaredValues) {
            RunEntry actual = byDimension.get(dimValue);
            if (actual != null) {
                padded.add(actual);
            } else {
                padded.add(buildSyntheticEntry(dimValue, isRegion, estStart, estEnd, avgDurationMs, bandGapMs));
            }
        }

        return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), padded);
    }

    private RunEntry buildSyntheticEntry(String dimValue, boolean isRegion,
                                         Instant estStart, Instant estEnd, Long avgDurationMs,
                                         long bandGapMs) {
        SlaEval sla = evaluateSlaStatus(estEnd, bandGapMs);
        return RunEntry.builder()
                .region(isRegion ? dimValue : null)
                .runType(isRegion ? null : dimValue)
                .status("NOT_STARTED")
                .slaStatus(sla.slaStatus())
                .slaBreached(sla.slaBreached())
                .estimatedStartTime(estStart)
                .estimatedEndTime(estEnd)
                .expectedDurationMs(avgDurationMs)
                .isRerun(false)
                .build();
    }

    private static String dimensionValue(RunEntry run, boolean isRegion) {
        return isRegion ? run.region() : run.runType();
    }

    record SlaEval(String slaStatus, Boolean slaBreached) {}

    static SlaEval evaluateSlaStatus(Instant estimatedEnd, long bandGapMs) {
        if (estimatedEnd == null) {
            return new SlaEval("ON_TIME", false);
        }
        Instant now = Instant.now();
        if (!now.isAfter(estimatedEnd)) {
            return new SlaEval("ON_TIME", false);
        }
        if (!now.isAfter(estimatedEnd.plusMillis(bandGapMs))) {
            return new SlaEval("LATE", true);
        }
        return new SlaEval("VERY_LATE", true);
    }
}
