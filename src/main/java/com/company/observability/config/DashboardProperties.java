package com.company.observability.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for the unified calculator dashboard endpoint.
 * Defines the display order, SLA deadlines, dependency chain, and calculator IDs
 * for each section shown on the Capital Calculation Insight dashboard.
 */
@Component
@ConfigurationProperties(prefix = "observability.dashboard")
@Getter
@Setter
public class DashboardProperties {

    private List<SectionConfig> sections = List.of();

    /**
     * Validates the dependency graph on startup:
     * <ul>
     *   <li>Every {@code dependsOn} reference must point to a declared section key.</li>
     *   <li>The dependency graph must be acyclic.</li>
     * </ul>
     * Fails fast with a clear message rather than producing silent runtime errors.
     */
    @PostConstruct
    public void validate() {
        if (sections.isEmpty()) return;

        Set<String> knownKeys = sections.stream()
                .map(SectionConfig::getSectionKey)
                .collect(Collectors.toSet());

        // 1. All dependsOn references must point to a known section
        for (SectionConfig section : sections) {
            if (section.getDependsOn() != null && !knownKeys.contains(section.getDependsOn())) {
                throw new IllegalStateException(
                        "Dashboard config error: section '" + section.getSectionKey() +
                        "' depends on unknown section '" + section.getDependsOn() + "'");
            }
        }

        // 2. No cycles — walk each chain until null or revisit
        Map<String, String> deps = sections.stream()
                .filter(s -> s.getDependsOn() != null)
                .collect(Collectors.toMap(SectionConfig::getSectionKey, SectionConfig::getDependsOn));

        for (String startKey : deps.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = startKey;
            while (current != null) {
                if (!visited.add(current)) {
                    throw new IllegalStateException(
                            "Dashboard config error: circular dependency detected involving section '"
                            + current + "'");
                }
                current = deps.get(current);
            }
        }
    }

    @Getter
    @Setter
    public static class SectionConfig {

        /** Unique key for this section, e.g. REGIONAL, PORTFOLIO, GROUP_PORTFOLIO, RISK_GOVERNED, CONSOLIDATION. */
        private String sectionKey;

        /** Human-readable label shown in the UI accordion header. */
        private String displayName;

        /** Controls the order sections appear top-to-bottom in the dashboard. */
        private int displayOrder;

        /** SLA deadline as a CET time-of-day (e.g. 17:45). */
        private LocalTime slaTimeCet;

        /**
         * sectionKey of the upstream section this one depends on, or null if independent.
         * Example: PORTFOLIO depends on REGIONAL.
         */
        private String dependsOn;

        /**
         * Display labels rendered by the frontend as separate rows, all backed by the
         * same single calculator run. Used by Portfolio to show 5 CAP rows.
         */
        private List<String> displayLabels = List.of();

        /** Calculator definitions for this section. Empty for REGIONAL (uses region-order config). */
        private List<CalculatorConfig> calculators = List.of();
    }

    @Getter
    @Setter
    public static class CalculatorConfig {

        /** Matches the calculator_id column in calculator_runs. */
        private String calculatorId;

        /** Label shown in the UI for this calculator row. */
        private String displayName;

        /**
         * True for calculators that have multiple sub-runs (e.g. Modelled Exposure
         * with OTC, ETD, SFT). Drives the sub-run button rendering in the UI.
         */
        private boolean hasSubRuns = false;

        /** Sub-run definitions when hasSubRuns = true. */
        private List<SubRunConfig> subRuns = List.of();
    }

    @Getter
    @Setter
    public static class SubRunConfig {

        /** Short key displayed as the button label, e.g. "OTC", "ETD", "SFT". */
        private String subRunKey;

        /** Matches the calculator_id column for this specific sub-run. */
        private String calculatorId;
    }
}
