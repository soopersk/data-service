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
        String calculatorId,
        String tenantId,
        String frequency,
        long avgDurationMs,
        int avgStartMinUtc,
        int avgEndMinUtc,
        int totalRuns
) {
    @JsonCreator
    public CalculatorProfile(
            @JsonProperty("calculatorId") String calculatorId,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("frequency") String frequency,
            @JsonProperty("avgDurationMs") long avgDurationMs,
            @JsonProperty("avgStartMinUtc") int avgStartMinUtc,
            @JsonProperty("avgEndMinUtc") int avgEndMinUtc,
            @JsonProperty("totalRuns") int totalRuns) {
        this.calculatorId = calculatorId;
        this.tenantId = tenantId;
        this.frequency = frequency;
        this.avgDurationMs = avgDurationMs;
        this.avgStartMinUtc = avgStartMinUtc;
        this.avgEndMinUtc = avgEndMinUtc;
        this.totalRuns = totalRuns;
    }

    /** Build from summed aggregate columns, computing averages (0 when no runs). */
    public static CalculatorProfile fromSums(String calculatorId, String tenantId, String frequency,
                                             long sumDurationMs, long sumStartMinUtc, long sumEndMinUtc,
                                             int totalRuns) {
        if (totalRuns <= 0) {
            return new CalculatorProfile(calculatorId, tenantId, frequency, 0, 0, 0, 0);
        }
        return new CalculatorProfile(
                calculatorId, tenantId, frequency,
                sumDurationMs / totalRuns,
                (int) (sumStartMinUtc / totalRuns),
                (int) (sumEndMinUtc / totalRuns),
                totalRuns);
    }

    public boolean hasSufficientSamples(int minSampleSize) {
        return totalRuns >= minSampleSize && avgDurationMs > 0;
    }
}
