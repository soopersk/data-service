package com.company.observability.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "observability")
@Getter
@Setter
public class CalculatorAliasProperties {

    /**
     * Maps UI alias → list of real calculator_name values in the database.
     * Configured per Spring profile (dev / prod). Any name not present in the map
     * is treated as a real database name and passed through unchanged.
     *
     * Example YAML:
     * <pre>
     * observability:
     *   calculator-aliases:
     *     capital: [capitalcalc, capitalcalcmedium]
     *     portfolio: [portfoliocalc]
     * </pre>
     */
    private Map<String, List<String>> calculatorAliases = new HashMap<>();
}
