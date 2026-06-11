package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.config.SlaProperties;
import com.company.observability.domain.CalculatorProfile;
import com.company.observability.domain.enums.Frequency;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.util.TimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pads a calculator's run list to its full declared set of expected runs.
 *
 * <p>For partial batches (Case B), missing dimension values receive per-dimension estimates
 * sourced from the dimension-scoped profile and grade against the calculator-level SLA deadline
 * shared with the real sibling runs.
 */
@Service
@RequiredArgsConstructor
public class ExpectedRunsService {

    private final CalculatorProperties props;
    private final CalculatorProfileService profileService;
    private final SlaProperties slaProps;

    /**
     * For each alias with a declared region/run-type set, pads its {@link CalculatorEntry} to the
     * full declared dimension set. Missing values get per-dimension estimates and are graded
     * against the calculator-level SLA deadline. Unconfigured aliases pass through unchanged.
     */
    public Map<String, CalculatorEntry> padToExpected(Map<String, CalculatorEntry> calculators,
                                                       LocalDate reportingDate, Frequency frequency,
                                                       String runNumber) {
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
            result.put(alias, pad(entry.getValue(), alias, declaredValues, isRegion,
                    reportingDate, frequency, runNumber));
        }
        return result;
    }

    private CalculatorEntry pad(CalculatorEntry existing, String alias, List<String> declaredValues,
                                 boolean isRegion, LocalDate reportingDate, Frequency frequency,
                                 String runNumber) {
        // Index real runs by their dimension value; null-dimension runs (upstream synthetic template) excluded.
        Map<String, RunEntry> byDimension = existing.runs().stream()
                .filter(r -> dimensionValue(r, isRegion) != null)
                .collect(Collectors.toMap(
                        r -> dimensionValue(r, isRegion),
                        r -> r,
                        (a, b) -> a
                ));

        if (byDimension.size() == declaredValues.size()) {
            List<RunEntry> ordered = declaredValues.stream()
                    .map(byDimension::get)
                    .filter(Objects::nonNull)
                    .toList();
            return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), ordered);
        }

        // Calculator-level deadline: sibling runs' frozen SLA first (Case B), then template's projected SLA (Case A).
        Instant calculatorDeadline = existing.runs().stream()
                .map(RunEntry::sla)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // Template = the upstream not-started synthetic (null region/runType), carries projected SLA + estimates.
        RunEntry template = existing.runs().stream()
                .filter(r -> r.region() == null && r.runType() == null)
                .findFirst()
                .orElse(null);

        if (calculatorDeadline == null && template != null) {
            calculatorDeadline = template.sla();
        }

        // Profiles are keyed by real calculator_name, not alias.
        String realName = props.getAliases().getOrDefault(alias, List.of(alias)).get(0);
        // DAILY: recover the real T+N offset from the calculator deadline (reportingDate→deadline
        // distance) so placeholder estimates anchor on the right execution date; else run_number.
        int offsetDays = SlaBaselineResolver.parseRunNumber(runNumber);
        if (frequency != Frequency.MONTHLY && calculatorDeadline != null) {
            ZoneId zone = ZoneId.of(slaProps.getSlaTimezone());
            int derivedN = TimeUtils.businessDaysBetween(
                    reportingDate, calculatorDeadline.atZone(zone).toLocalDate());
            if (derivedN >= 1) {
                offsetDays = derivedN;
            }
        }
        LocalDate executionDate = TimeUtils.nextBusinessDay(reportingDate, offsetDays);

        final Instant deadline = calculatorDeadline;
        List<RunEntry> padded = new ArrayList<>(declaredValues.size());
        for (String dimValue : declaredValues) {
            RunEntry actual = byDimension.get(dimValue);
            padded.add(actual != null ? actual
                    : placeholder(dimValue, isRegion, template, realName, frequency, runNumber,
                                  executionDate, deadline));
        }
        return new CalculatorEntry(existing.calculatorName(), existing.calculatorId(), padded);
    }

    private RunEntry placeholder(String dimValue, boolean isRegion, RunEntry template,
                                  String realName, Frequency frequency, String runNumber,
                                  LocalDate executionDate, Instant calculatorDeadline) {
        // Estimates: dimension-scoped profile → template estimates → none.
        Instant estStart = null;
        Instant estEnd = null;
        Long expectedMs = null;

        CalculatorProfile dimProfile = profileService.getProfile(realName, frequency, runNumber, dimValue);
        if (dimProfile.hasSufficientSamples(slaProps.getMinSampleSize())) {
            estStart = TimeUtils.instantFromUtcMinuteOfDay(executionDate, dimProfile.avgStartMinUtc());
            estEnd = estStart.plusMillis(dimProfile.avgDurationMs());
            expectedMs = dimProfile.avgDurationMs();
        } else if (template != null) {
            estStart = template.estimatedStartTime();
            estEnd = template.estimatedEndTime();
            expectedMs = template.expectedDurationMs();
        }

        // Grade against calculator-level deadline; fall back to estEnd only when no deadline is derivable.
        Instant gradeAgainst = calculatorDeadline != null ? calculatorDeadline : estEnd;
        SlaEval eval = evaluateSlaStatus(gradeAgainst, slaProps.bandGapMs());

        return RunEntry.builder()
                .status("NOT_STARTED")
                .slaStatus(eval.slaStatus())
                .slaBreached(eval.slaBreached() ? Boolean.TRUE : null)
                .estimatedStartTime(estStart)
                .estimatedEndTime(estEnd)
                .expectedDurationMs(expectedMs)
                .sla(calculatorDeadline)
                .region(isRegion ? dimValue : null)
                .runType(isRegion ? null : dimValue)
                .isRerun(false)
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
