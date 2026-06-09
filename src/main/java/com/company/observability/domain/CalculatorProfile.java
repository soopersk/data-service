package com.company.observability.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Slowly-changing per-calculator rolling profile, computed once per day from
 * {@code calculator_sli_daily} and cached in Redis. Serves both the SLA baseline
 * (avgDurationMs) and the estimated start/end fallback (avgStartMinUtc/avgEndMinUtc).
 *
 * <p>{@code totalRuns == 0} is a valid "no history" sentinel.
 */
public record CalculatorProfile(
        String calculatorName,
        String frequency,
        String runNumber,
        long avgDurationMs,
        int avgStartMinUtc,
        int avgEndMinUtc,
        int totalRuns
) {
    @JsonCreator
    public CalculatorProfile(
            @JsonProperty("calculatorName") String calculatorName,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("runNumber") String runNumber,
            @JsonProperty("avgDurationMs") long avgDurationMs,
            @JsonProperty("avgStartMinUtc") int avgStartMinUtc,
            @JsonProperty("avgEndMinUtc") int avgEndMinUtc,
            @JsonProperty("totalRuns") int totalRuns) {
        this.calculatorName = calculatorName;
        this.frequency = frequency;
        this.runNumber = runNumber;
        this.avgDurationMs = avgDurationMs;
        this.avgStartMinUtc = avgStartMinUtc;
        this.avgEndMinUtc = avgEndMinUtc;
        this.totalRuns = totalRuns;
    }

    /**
     * Build from summed aggregate columns, computing averages (0 when no runs).
     *
     * @param runNumber null for blended (cross-run_number) profiles; "1" or "2" for cycle-scoped.
     */
    public static CalculatorProfile fromSums(String calculatorName, String frequency, String runNumber,
                                             long sumDurationMs, long sumStartMinUtc, long sumEndMinUtc,
                                             int totalRuns) {
        if (totalRuns <= 0) {
            return new CalculatorProfile(calculatorName, frequency, runNumber, 0, 0, 0, 0);
        }
        return new CalculatorProfile(
                calculatorName, frequency, runNumber,
                sumDurationMs / totalRuns,
                (int) (sumStartMinUtc / totalRuns),
                (int) (sumEndMinUtc / totalRuns),
                totalRuns);
    }

    public boolean hasSufficientSamples(int minSampleSize) {
        return totalRuns >= minSampleSize && avgDurationMs > 0;
    }
}
