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
 *
 * <p>Models the section → node → (matrix-column | calculator) hierarchy that
 * drives the redesigned UI. A {@link NodeConfig} is either:
 * <ul>
 *   <li><b>Simple</b> — has a single {@code calculatorId}, optionally with
 *       {@code displayLabels} for Portfolio-style multi-row rendering.</li>
 *   <li><b>Matrix</b> — has a {@link MatrixConfig} carrying either a REGION
 *       dimension (10 columns, runs come from {@code RegionalBatchService})
 *       or a TYPE dimension (e.g. OTC/ETD/SFT, runs come from
 *       {@code calculator_runs}).</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "observability.dashboard")
@Getter
@Setter
public class DashboardProperties {

    /** Milliseconds past SLA deadline that separates LATE from VERY_LATE. Default: 1 hour. */
    private long lateThresholdMs = 3_600_000L;

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

        /** Unique key, e.g. REGIONAL, PORTFOLIO, GROUP_PORTFOLIO, RISK_GOVERNED, CONSOLIDATION. */
        private String sectionKey;

        /** Human-readable label shown in the UI accordion header. */
        private String displayName;

        /** Controls the order sections appear top-to-bottom. */
        private int displayOrder;

        /** SLA deadline as a CET time-of-day (e.g. 17:45). */
        private LocalTime slaTimeCet;

        /** sectionKey of the upstream section this one depends on, or null if independent. */
        private String dependsOn;

        /** Empty list = applicable to all frequencies; otherwise filter by frequency name. */
        private List<String> frequencyApplicable = List.of();

        /** Nodes shown in this section. Each node is either simple or matrix. */
        private List<NodeConfig> nodes = List.of();
    }

    @Getter
    @Setter
    public static class NodeConfig {

        /** Stable identifier within the section, e.g. "REGIONAL_MATRIX", "PORTFOLIO". */
        private String nodeKey;

        /** Label rendered as the node header. */
        private String displayName;

        /** Controls the order nodes appear within a section. */
        private int displayOrder;

        /**
         * Non-empty for Portfolio-style nodes where one calculator run drives
         * multiple display rows in the UI (e.g. CAP 8, CAP 10, …).
         */
        private List<String> displayLabels = List.of();

        /**
         * Non-null = matrix node. Null = simple node (single-calculator);
         * in that case {@link #calculatorId} must be set.
         */
        private MatrixConfig matrixConfig;

        /** Used only when {@link #matrixConfig} is null. */
        private String calculatorId;
    }

    @Getter
    @Setter
    public static class MatrixConfig {

        /** "REGION" or "TYPE". */
        private String dimension;

        /** Label rendered for the (single) matrix row, e.g. "Run status". */
        private String rowLabel;

        /** Column definitions, in the order they should be rendered. */
        private List<MatrixColumnConfig> columns = List.of();
    }

    @Getter
    @Setter
    public static class MatrixColumnConfig {

        /** Column key rendered as header, e.g. "WMAP" or "OTC". */
        private String key;

        /** calculator_id that backs runs for this cell. */
        private String calculatorId;
    }
}
