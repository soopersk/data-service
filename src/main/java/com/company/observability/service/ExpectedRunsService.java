package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pads a calculator's run list to its full declared set of expected runs.
 *
 * <p>An alias is region-dimensioned if it appears in {@code observability.calculator.regions},
 * or run-type-dimensioned if it appears in {@code observability.calculator.run-types}. For such
 * aliases the entry is padded to exactly one {@link RunEntry} per declared value, in declared
 * order; missing values get a synthetic NOT_STARTED placeholder. Aliases in neither map pass
 * through unchanged.
 */
@Service
@RequiredArgsConstructor
public class ExpectedRunsService {

    private final CalculatorProperties props;

    /**
     * For each alias with a declared region/run-type set, pads its {@link CalculatorEntry} to the
     * full declared dimension set. Unconfigured aliases pass through unchanged.
     */
    public Map<String, CalculatorEntry> padToExpected(Map<String, CalculatorEntry> calculators) {
        Map<String, List<String>> regions = props.getRegions();
        Map<String, List<String>> runTypes = props.getRunTypes();
        if (regions.isEmpty() && runTypes.isEmpty()) {
            return calculators;
        }

        Map<String, CalculatorEntry> result = new LinkedHashMap<>(calculators);
        for (Map.Entry<String, CalculatorEntry> entry : calculators.entrySet()) {
            String alias = entry.getKey();
            List<String> declaredValues;
            boolean isRegion;
            if (regions.containsKey(alias)) {
                declaredValues = regions.get(alias);
                isRegion = true;
            } else if (runTypes.containsKey(alias)) {
                declaredValues = runTypes.get(alias);
                isRegion = false;
            } else {
                continue;
            }
            result.put(alias, pad(entry.getValue(), declaredValues, isRegion));
        }
        return result;
    }

    private CalculatorEntry pad(CalculatorEntry existing, List<String> declaredValues, boolean isRegion) {
        // Index real runs by their dimension value. The not-started synthetic built upstream has a
        // null dimension value, so it is excluded here and reused below as a cloning template.
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
                    .map(byDimension::get)
                    .filter(Objects::nonNull)
                    .toList();
            return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), ordered);
        }

        // Template = the upstream not-started synthetic (carries projected SLA + estimates), if any.
        // Absent (partial batch, or no-history calc) → a bare NOT_STARTED placeholder.
        RunEntry template = existing.runs().stream()
                .filter(r -> r.region() == null && r.runType() == null)
                .findFirst()
                .orElse(null);

        List<RunEntry> padded = new ArrayList<>(declaredValues.size());
        for (String dimValue : declaredValues) {
            RunEntry actual = byDimension.get(dimValue);
            padded.add(actual != null ? actual : placeholder(dimValue, isRegion, template));
        }
        return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), padded);
    }

    private static RunEntry placeholder(String dimValue, boolean isRegion, RunEntry template) {
        RunEntry.RunEntryBuilder builder = template != null
                ? template.toBuilder()
                : RunEntry.builder()
                        .status("NOT_STARTED")
                        .slaStatus("ON_TIME")
                        .isRerun(false);
        return builder
                .region(isRegion ? dimValue : null)
                .runType(isRegion ? null : dimValue)
                .build();
    }

    private static String dimensionValue(RunEntry run, boolean isRegion) {
        return isRegion ? run.region() : run.runType();
    }

    // ── Shared SLA grading for synthetic not-started entries (used by CalculatorStateService) ──

    record SlaEval(String slaStatus, Boolean slaBreached) {}

    static SlaEval evaluateSlaStatus(Instant deadline, long bandGapMs) {
        if (deadline == null) {
            return new SlaEval("ON_TIME", false);
        }
        Instant now = Instant.now();
        if (!now.isAfter(deadline)) {
            return new SlaEval("ON_TIME", false);
        }
        if (!now.isAfter(deadline.plusMillis(bandGapMs))) {
            return new SlaEval("LATE", true);
        }
        return new SlaEval("VERY_LATE", true);
    }
}
