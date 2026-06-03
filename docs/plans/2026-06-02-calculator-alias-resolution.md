# Calculator Alias Resolution Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow the UI to send simplified alias names (e.g., `capital`) that the service expands to the real environment-specific calculator names (e.g., `capitalcalc`, `capitalcalcmedium`, …) before querying — covering both `/api/v1/calculators/batch/runs` and `/api/v1/analytics/calculators/{name}/executions`.

**Architecture:**
- A `@ConfigurationProperties` bean (`CalculatorAliasProperties`) reads a `Map<String, List<String>>` from per-profile YAML. A stateless `CalculatorNameResolver` service does forward (alias → real names) and reverse (real name → alias, for cache invalidation) lookups. Both endpoints expand incoming names before querying, re-key responses by alias, and cache under the alias key. For multi-alias on `/batch/runs`, a `CalculatorEntry` is built per alias by merging all matching real-name entries; each `RunEntry` gains a `calculatorName` field so the UI can distinguish origins. For multi-alias on `/executions`, all runs are merged into a single `RunPerformanceData` keyed by the alias.

**Tech Stack:** Java 17, Spring Boot 3.5.9, `@ConfigurationProperties`, Lombok, JUnit 5, Mockito

---

## Task 1: CalculatorAliasProperties config bean

**Files:**
- Create: `src/main/java/com/company/observability/config/CalculatorAliasProperties.java`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-prod.yml`

**Step 1: Create the properties class**

```java
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
     * Resolved per Spring profile (dev / prod). Passthrough: any name not in the
     * map is treated as a real name already.
     */
    private Map<String, List<String>> calculatorAliases = new HashMap<>();
}
```

**Step 2: Add alias mappings to dev profile**

In `src/main/resources/application-dev.yml`, append:

```yaml
observability:
  calculator-aliases:
    capital: [capitalcalcdev]
    portfolio: [portfoliocalcdev]
    group-portfolio: [grportfoliocalcdev]
    modelled-exposure: [modelledexposurecalcdev]
    gemini-hedge: [geminihedgefundcalcdev]
    consolidation: [consenrichmentcalcdev]
    output-floor: [outputfloorcalcdev]
    sectoral-floor: [floorscalcdev]
    validation: [validationscalcdevonly]
    market-risk: [marketriskrwacalcdev]
```

**Step 3: Add alias mappings to prod profile**

In `src/main/resources/application-prod.yml`, append:

```yaml
observability:
  calculator-aliases:
    capital: [capitalcalc, capitalcalcmedium, capitalcalcsmall, capitalcalcextrasmall]
    portfolio: [portfoliocalc]
    group-portfolio: [grportfoliocalc]
    modelled-exposure: [modelledexposurecalc]
    gemini-hedge: [geminihedgefundcalc]
    consolidation: [consenrichmentcalc]
    output-floor: [outputfloorcalc]
    sectoral-floor: [floorscalc]
    validation: [validationscalculator]
    market-risk: [marketriskrwacalc]
```

**Step 4: Commit**

```bash
git add src/main/java/com/company/observability/config/CalculatorAliasProperties.java \
        src/main/resources/application-dev.yml \
        src/main/resources/application-prod.yml
git commit -m "feat: add CalculatorAliasProperties config for per-profile alias mappings"
```

---

## Task 2: CalculatorNameResolver service + unit tests

**Files:**
- Create: `src/main/java/com/company/observability/service/CalculatorNameResolver.java`
- Create: `src/test/java/com/company/observability/service/CalculatorNameResolverTest.java`

**Step 1: Write failing tests**

```java
package com.company.observability.service;

import com.company.observability.config.CalculatorAliasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorNameResolverTest {

    CalculatorNameResolver resolver;

    @BeforeEach
    void setUp() {
        CalculatorAliasProperties props = new CalculatorAliasProperties();
        props.setCalculatorAliases(Map.of(
                "capital", List.of("capitalcalc", "capitalcalcmedium"),
                "portfolio", List.of("portfoliocalc")
        ));
        resolver = new CalculatorNameResolver(props);
    }

    @Test
    void resolveKnownAlias_returnsRealNames() {
        assertThat(resolver.resolve("capital"))
                .containsExactly("capitalcalc", "capitalcalcmedium");
    }

    @Test
    void resolveKnownAlias_singleMapping() {
        assertThat(resolver.resolve("portfolio"))
                .containsExactly("portfoliocalc");
    }

    @Test
    void resolveUnknownName_passthroughAsSingleton() {
        assertThat(resolver.resolve("someothercalc"))
                .containsExactly("someothercalc");
    }

    @Test
    void resolveAll_expandsAliasesInOrder() {
        Map<String, List<String>> result = resolver.resolveAll(List.of("capital", "portfolio"));
        assertThat(result).containsKeys("capital", "portfolio");
        assertThat(result.get("capital")).containsExactly("capitalcalc", "capitalcalcmedium");
        assertThat(result.get("portfolio")).containsExactly("portfoliocalc");
    }

    @Test
    void findAliasFor_realNameBelongingToAlias_returnsAlias() {
        assertThat(resolver.findAliasFor("capitalcalc")).contains("capital");
        assertThat(resolver.findAliasFor("capitalcalcmedium")).contains("capital");
    }

    @Test
    void findAliasFor_realNameNotInAnyAlias_empty() {
        assertThat(resolver.findAliasFor("unknowncalc")).isEmpty();
    }

    @Test
    void isMultiAlias_capital_true() {
        assertThat(resolver.isMultiAlias("capital")).isTrue();
    }

    @Test
    void isMultiAlias_portfolio_false() {
        assertThat(resolver.isMultiAlias("portfolio")).isFalse();
    }
}
```

**Step 2: Run tests to verify they fail**

```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorNameResolverTest -pl . 2>&1 | tail -20
```
Expected: FAIL — `CalculatorNameResolver` not found.

**Step 3: Implement CalculatorNameResolver**

```java
package com.company.observability.service;

import com.company.observability.config.CalculatorAliasProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CalculatorNameResolver {

    private final CalculatorAliasProperties aliasProperties;

    /**
     * Resolves an alias to its list of real calculator_name values.
     * If the name is not a known alias, returns it as a singleton list (passthrough).
     */
    public List<String> resolve(String nameOrAlias) {
        List<String> mapped = aliasProperties.getCalculatorAliases().get(nameOrAlias);
        return mapped != null ? mapped : List.of(nameOrAlias);
    }

    /**
     * Expands a list of aliases/names to a map of alias → real names,
     * preserving insertion order.
     */
    public Map<String, List<String>> resolveAll(List<String> aliases) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String alias : aliases) {
            result.put(alias, resolve(alias));
        }
        return result;
    }

    /**
     * Reverse lookup: given a real calculator_name, returns the alias it belongs to.
     * Returns empty if the real name is not mapped under any alias.
     */
    public Optional<String> findAliasFor(String realName) {
        return aliasProperties.getCalculatorAliases().entrySet().stream()
                .filter(e -> e.getValue().contains(realName))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Returns true if this alias expands to more than one real calculator name.
     */
    public boolean isMultiAlias(String nameOrAlias) {
        return resolve(nameOrAlias).size() > 1;
    }
}
```

**Step 4: Run tests to verify they pass**

```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorNameResolverTest 2>&1 | tail -10
```
Expected: PASS (8 tests).

**Step 5: Commit**

```bash
git add src/main/java/com/company/observability/service/CalculatorNameResolver.java \
        src/test/java/com/company/observability/service/CalculatorNameResolverTest.java
git commit -m "feat: add CalculatorNameResolver for alias → real-name expansion"
```

---

## Task 3: Add calculatorName to RunEntry

**Files:**
- Modify: `src/main/java/com/company/observability/dto/response/CalculatorBatchRunsResponse.java`
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java`

**Context:** `RunEntry` currently has no `calculatorName` field. When a multi-alias (e.g., `capital`) groups runs from 4 real calculators into one `CalculatorEntry`, the UI needs this field to distinguish origins.

**Step 1: Add the field to RunEntry**

In `CalculatorBatchRunsResponse.java`, add `calculatorName` to the `RunEntry` record builder (existing fields remain unchanged — this is a new nullable field, JSON-serialized as `null` when absent via `@JsonInclude(NON_NULL)`):

```java
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunEntry(
        String calculatorName,      // ← new: real DB name; null when 1:1 alias (same as parent)
        String runId,
        String region,
        String runType,
        String status,
        String slaStatus,
        Instant startTime,
        Instant endTime,
        Instant estimatedStartTime,
        Instant estimatedEndTime,
        Instant sla,
        Long durationMs,
        Long expectedDurationMs,
        String slaBreachReason,
        boolean isRerun
) {}
```

**Step 2: Update toRunEntry in CalculatorStateService**

The `toRunEntry(CalculatorRun run)` method currently does not set `calculatorName`. The service must now accept the target `CalculatorEntry` name so it can decide whether to populate `RunEntry.calculatorName`. Since `toRunEntry` is private and called from `buildEntry`, and `buildEntry` knows the top-level `calculatorName` parameter, the cleanest change is:

In `CalculatorStateService.java`:
- Change `toRunEntry(CalculatorRun run)` to `toRunEntry(CalculatorRun run, String entryName)` where `entryName` is the `CalculatorEntry.calculatorName` (alias or real).
- Set `calculatorName` on `RunEntry` only when `run.getCalculatorName()` differs from `entryName` (i.e., it's a multi-alias merge); otherwise leave it `null` (clean JSON for 1:1 alias).

```java
private RunEntry toRunEntry(CalculatorRun run, String entryName) {
    // Only populate calculatorName on RunEntry when the run comes from a
    // differently-named real calculator (multi-alias expansion).
    String runCalcName = run.getCalculatorName() != null
            && !run.getCalculatorName().equals(entryName)
            ? run.getCalculatorName() : null;

    return RunEntry.builder()
            .calculatorName(runCalcName)
            .runId(run.getRunId())
            .region(run.getRegion())
            .runType(run.getRunType())
            .status(run.getStatus().name())
            .slaStatus(run.getSlaBand() != null ? run.getSlaBand().name() : "ON_TIME")
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .estimatedStartTime(run.getEstimatedStartTime())
            .estimatedEndTime(run.getEstimatedEndTime())
            .sla(run.getSlaTime())
            .durationMs(run.getDurationMs())
            .expectedDurationMs(run.getExpectedDurationMs())
            .slaBreachReason(run.getSlaBreachReason())
            .isRerun(run.isRerun())
            .build();
}
```

- Update all callers of `toRunEntry` in `CalculatorStateService` to pass the entry name. There are two callers:
  - In `buildEntry`: passes `calculatorName` parameter.
  - In `collapseSplitGroup`: called from `buildEntry` via `splitEntries`, needs to carry the `entryName`. Change `collapseSplitGroup` signature to `collapseSplitGroup(List<CalculatorRun> splits, String entryName)` and pass it through.

**Step 3: Confirm existing tests still pass**

```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorStateServiceTest 2>&1 | tail -10
```
Expected: PASS (all existing tests).

**Step 4: Commit**

```bash
git add src/main/java/com/company/observability/dto/response/CalculatorBatchRunsResponse.java \
        src/main/java/com/company/observability/service/CalculatorStateService.java
git commit -m "feat: add calculatorName field to RunEntry for multi-alias disambiguation"
```

---

## Task 4: Alias expansion in /batch/runs (RunQueryController + CalculatorStateService)

**Files:**
- Modify: `src/main/java/com/company/observability/controller/RunQueryController.java`
- Modify: `src/main/java/com/company/observability/service/CalculatorStateService.java`
- Modify: `src/test/java/com/company/observability/service/CalculatorStateServiceTest.java`

**Context:** The controller splits `keys` into a list of alias names. It must expand aliases to real names, call `getState` with all real names, then re-group the result map under the original alias keys.

**Step 1: Write failing tests in CalculatorStateServiceTest**

Add a test verifying that when the service is given a list of real names that include multiple from the same alias group, it builds distinct entries per real name (the grouping/merging by alias is the controller's job, not the service's):

```java
@Test
void getState_multipleRealNames_returnsDistinctEntries() {
    when(stateCache.getEntries(any(), any(), any(), any())).thenReturn(new HashMap<>());
    when(runRepository.findAllRunsByDateAndDimension(any(), any(), any(), any()))
            .thenReturn(List.of(
                    buildRun("capitalcalc", "run-1", RunStatus.SUCCESS),
                    buildRun("capitalcalcmedium", "run-2", RunStatus.RUNNING)
            ));
    when(profileService.getProfile(any(), any())).thenReturn(CalculatorProfile.empty());

    Map<String, CalculatorEntry> result = service.getState(
            DATE, FREQ, null, List.of("capitalcalc", "capitalcalcmedium"));

    assertThat(result).containsKeys("capitalcalc", "capitalcalcmedium");
    assertThat(result.get("capitalcalc").runs()).hasSize(1);
    assertThat(result.get("capitalcalcmedium").runs()).hasSize(1);
}
```

**Step 2: Run test to verify it passes** (the service already handles this correctly — no change needed to `getState` itself)

```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorStateServiceTest 2>&1 | tail -10
```
Expected: PASS.

**Step 3: Update RunQueryController.getBatchRuns to expand and re-group**

Inject `CalculatorNameResolver` into `RunQueryController`. Replace the existing `getBatchRuns` body:

```java
// In RunQueryController — inject alongside existing dependencies:
private final CalculatorNameResolver nameResolver;

@GetMapping("/batch/runs")
public ResponseEntity<CalculatorBatchRunsResponse> getBatchRuns(
        @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
        @RequestParam("reporting_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
        @RequestParam(defaultValue = "DAILY") String frequency,
        @RequestParam(value = "run_number", required = false) String runNumber,
        @Parameter(description = "Pipe-separated calculator alias or calculator_name values, e.g. capital|portfolio")
        @RequestParam @NotBlank String keys) {

    List<String> aliases = Arrays.stream(keys.split("\\|"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    if (aliases.isEmpty()) {
        throw new IllegalArgumentException("keys must contain at least one non-blank calculator name");
    }

    Frequency freq = Frequency.fromStrict(frequency);

    // Expand aliases → {alias: [realName, ...]}
    Map<String, List<String>> aliasToRealNames = nameResolver.resolveAll(aliases);

    // Flat list of all real names to query
    List<String> allRealNames = aliasToRealNames.values().stream()
            .flatMap(Collection::stream)
            .distinct()
            .toList();

    log.info("event=batch_runs.request outcome=accepted reportingDate={} frequency={} aliasCount={} realNameCount={} runNumber={}",
            reportingDate, freq, aliases.size(), allRealNames.size(), runNumber);

    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // Query by real names; result keyed by real name
        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> byRealName =
                calculatorStateService.getState(reportingDate, freq, runNumber, allRealNames);

        // Re-group by alias: merge entries from all real names under each alias key
        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> calculators =
                aliasToRealNames.entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mergeEntries(e.getKey(), e.getValue(), byRealName),
                        (a, b) -> a,
                        LinkedHashMap::new   // preserve request order
                ));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(new CalculatorBatchRunsResponse(
                        reportingDate, freq.name(), runNumber, Instant.now(), calculators));
    } finally {
        sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                "endpoint", "/calculators/batch/runs"));
    }
}

/**
 * Merges one or more CalculatorEntry objects (from different real calculator names)
 * into a single entry keyed by the alias.
 * For 1:1 aliases, simply re-keys the existing entry under the alias.
 * For multi-aliases, concatenates all RunEntry lists; calculatorId is set only
 * when all real calculators share the same id (unusual), otherwise null.
 */
private CalculatorBatchRunsResponse.CalculatorEntry mergeEntries(
        String alias,
        List<String> realNames,
        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> byRealName) {

    List<CalculatorBatchRunsResponse.CalculatorEntry> parts = realNames.stream()
            .map(byRealName::get)
            .filter(Objects::nonNull)
            .toList();

    if (parts.isEmpty()) {
        return new CalculatorBatchRunsResponse.CalculatorEntry(alias, null, List.of());
    }
    if (parts.size() == 1) {
        CalculatorBatchRunsResponse.CalculatorEntry single = parts.get(0);
        return new CalculatorBatchRunsResponse.CalculatorEntry(alias, single.calculatorId(), single.runs());
    }

    // Multi: merge runs; calculatorId only if all agree
    Set<String> ids = parts.stream()
            .map(CalculatorBatchRunsResponse.CalculatorEntry::calculatorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    String mergedId = ids.size() == 1 ? ids.iterator().next() : null;

    List<CalculatorBatchRunsResponse.RunEntry> allRuns = parts.stream()
            .flatMap(e -> e.runs().stream())
            .toList();

    return new CalculatorBatchRunsResponse.CalculatorEntry(alias, mergedId, allRuns);
}
```

Note: `LinkedHashMap` and `Objects` imports needed. `mergeEntries` is a private helper in the controller.

**Step 4: Verify existing tests still pass**

```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorStateServiceTest 2>&1 | tail -10
```

**Step 5: Commit**

```bash
git add src/main/java/com/company/observability/controller/RunQueryController.java \
        src/test/java/com/company/observability/service/CalculatorStateServiceTest.java
git commit -m "feat: expand calculator aliases in /batch/runs and re-group response by alias"
```

---

## Task 5: Alias expansion in /executions (AnalyticsService)

**Files:**
- Modify: `src/main/java/com/company/observability/service/AnalyticsService.java`
- Modify: `src/test/java/com/company/observability/service/AnalyticsServiceTest.java`

**Context:** `getRunExecutionsByName` must expand the alias, query all real names, merge, and cache under the alias key. The response's `calculatorName` field is set to the alias.

**Step 1: Write failing test for multi-alias merge**

In `AnalyticsServiceTest.java`, add:

```java
@Test
void getRunExecutionsByName_multiAlias_mergesAllRuns() {
    // Resolver returns 2 real names for "capital" alias
    CalculatorNameResolver resolver = mock(CalculatorNameResolver.class);
    when(resolver.resolve("capital")).thenReturn(List.of("capitalcalc", "capitalcalcmedium"));

    // Inject resolver (see setUp note below)
    // ...

    when(cacheService.getFromCache(eq(AnalyticsService.CACHE_EXECUTIONS),
            eq("capital"), anyString(), anyInt(), any(), eq(RunPerformanceData.class)))
            .thenReturn(null);

    RunWithSlaStatus run1 = buildRunWithSla("capitalcalc", RunStatus.SUCCESS, 1000L);
    RunWithSlaStatus run2 = buildRunWithSla("capitalcalcmedium", RunStatus.SUCCESS, 2000L);

    when(calculatorRunRepository.findRunsByName(eq("capitalcalc"), any(), anyInt(), any()))
            .thenReturn(List.of(run1));
    when(calculatorRunRepository.findRunsByName(eq("capitalcalcmedium"), any(), anyInt(), any()))
            .thenReturn(List.of(run2));

    RunPerformanceData result = service.getRunExecutionsByName("capital", 30, Frequency.DAILY, null);

    assertThat(result.calculatorId()).isEqualTo("capital");
    assertThat(result.runs()).hasSize(2);
    verify(calculatorRunRepository).findRunsByName(eq("capitalcalc"), any(), anyInt(), any());
    verify(calculatorRunRepository).findRunsByName(eq("capitalcalcmedium"), any(), anyInt(), any());
}

@Test
void getRunExecutionsByName_singleAlias_queriesSingleName() {
    CalculatorNameResolver resolver = mock(CalculatorNameResolver.class);
    when(resolver.resolve("portfolio")).thenReturn(List.of("portfoliocalc"));
    // ... similar setup
    // Verify only "portfoliocalc" is queried
}
```

Note: `AnalyticsService` constructor must be updated to accept `CalculatorNameResolver`. Update `setUp()` in `AnalyticsServiceTest` accordingly.

**Step 2: Update AnalyticsService constructor and getRunExecutionsByName**

Inject `CalculatorNameResolver` into `AnalyticsService`. Update `getRunExecutionsByName`:

```java
public RunPerformanceData getRunExecutionsByName(
        String calculatorName, int days, Frequency frequency, String runNumber) {

    String rn = (runNumber == null || runNumber.isBlank()) ? null : runNumber;

    // Check cache first (keyed by alias/name as supplied)
    RunPerformanceData cached = cacheService.getFromCache(
            CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn,
            RunPerformanceData.class);
    if (cached != null) {
        log.debug("event=executions.cache outcome=hit calculatorName={} frequency={} days={} runNumber={}",
                calculatorName, frequency, days, rn);
        return cached;
    }

    // Expand alias to real names
    List<String> realNames = nameResolver.resolve(calculatorName);

    log.debug("event=executions.db_fetch outcome=start calculatorName={} realNames={} frequency={} days={} runNumber={}",
            calculatorName, realNames, frequency, days, rn);

    // Fetch from all real calculator names and merge
    List<RunWithSlaStatus> rawRuns = realNames.stream()
            .flatMap(name -> calculatorRunRepository.findRunsByName(name, frequency, days, rn).stream())
            .sorted(Comparator.comparing(RunWithSlaStatus::reportingDate)
                    .thenComparing(r -> r.startTime() != null ? r.startTime() : Instant.EPOCH))
            .toList();

    RunPerformanceData response = buildExecutionsResponse(calculatorName, rawRuns, days, frequency);
    cacheService.putInCache(CACHE_EXECUTIONS, calculatorName, frequency.name(), days, rn, response);
    return response;
}
```

**Note on calculatorName in response:** `buildRunPerformanceDataEnvelope` currently uses `latestRaw.calculatorName()` for the `calculatorName` field in `RunPerformanceData`. For multi-alias, that's the real name of the last run. Since the existing `calculatorId` field already carries the alias (passed as `calculatorKey = calculatorName`), this is acceptable — no change needed to the envelope builder.

**Step 3: Run the new tests**

```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=AnalyticsServiceTest 2>&1 | tail -15
```
Expected: PASS.

**Step 4: Commit**

```bash
git add src/main/java/com/company/observability/service/AnalyticsService.java \
        src/test/java/com/company/observability/service/AnalyticsServiceTest.java
git commit -m "feat: expand calculator aliases in /executions and merge multi-alias runs"
```

---

## Task 6: Cache invalidation for alias keys (AnalyticsCacheService)

**Files:**
- Modify: `src/main/java/com/company/observability/cache/AnalyticsCacheService.java`

**Context:** When `capitalcalc` fires a completion event, the cached response for alias `capital` (if any) must also be evicted. `CalculatorNameResolver.findAliasFor(realName)` provides the reverse lookup.

**Step 1: Inject CalculatorNameResolver into AnalyticsCacheService**

Add to the constructor (Lombok `@RequiredArgsConstructor` handles injection):

```java
private final CalculatorNameResolver nameResolver;
```

**Step 2: Update evictForCalculator to also evict alias index**

```java
private void evictForCalculator(CalculatorRun run) {
    evictIndex(buildIndexKey(run.getCalculatorId()), run.getCalculatorId());
    if (run.getCalculatorName() != null && !run.getCalculatorName().equals(run.getCalculatorId())) {
        evictIndex(buildIndexKey(run.getCalculatorName()), run.getCalculatorName());
    }
    // Also evict the alias index if the real calculator name maps to a UI alias
    nameResolver.findAliasFor(run.getCalculatorName())
            .ifPresent(alias -> evictIndex(buildIndexKey(alias), alias));
}
```

The same pattern applies to `evictForCalculatorByPrefix`:

```java
private void evictForCalculatorByPrefix(CalculatorRun run, String fullKeyPrefix) {
    evictIndexByPrefix(buildIndexKey(run.getCalculatorId()), fullKeyPrefix, run.getCalculatorId());
    if (run.getCalculatorName() != null && !run.getCalculatorName().equals(run.getCalculatorId())) {
        evictIndexByPrefix(buildIndexKey(run.getCalculatorName()), fullKeyPrefix, run.getCalculatorName());
    }
    nameResolver.findAliasFor(run.getCalculatorName())
            .ifPresent(alias ->
                evictIndexByPrefix(buildIndexKey(alias), fullKeyPrefix, alias));
}
```

**Step 3: Run all tests**

```bash
SPRING_PROFILES_ACTIVE=local mvn clean test 2>&1 | tail -20
```
Expected: All tests pass.

**Step 4: Commit**

```bash
git add src/main/java/com/company/observability/cache/AnalyticsCacheService.java
git commit -m "feat: evict alias cache keys on real calculator events"
```

---

## Task 7: Build verification

**Step 1: Full build + test**

```bash
SPRING_PROFILES_ACTIVE=local mvn clean package 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`, no compilation errors, all tests pass.

**Step 2: Verify app starts**

```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run &
sleep 15
curl -s http://localhost:8080/actuator/health | jq .status
```
Expected: `"UP"`

**Step 3: Smoke test alias expansion**

```bash
# Test /batch/runs with alias (will return empty runs if no data, but no 500)
curl -s -u admin:admin -H "X-Tenant-Id: test" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=$(date +%Y-%m-%d)&keys=capital|portfolio" \
  | jq '.calculators | keys'
# Expected: ["capital", "portfolio"]

# Test /executions with alias
curl -s -u admin:admin -H "X-Tenant-Id: test" \
  "http://localhost:8080/api/v1/analytics/calculators/capital/executions" \
  | jq '.calculatorId'
# Expected: "capital"
```

**Step 4: Stop the app**

```bash
kill %1
docker compose down
```

---

## Verification Summary

End-to-end checks:
1. `/batch/runs?keys=capital` → response has `calculators.capital` key, not `capitalcalc` (or whatever real name)
2. `/batch/runs?keys=capital|portfolio` → response has both `capital` and `portfolio` keys
3. `/executions` for `capital` → `calculatorId = "capital"`, runs from all configured real names present
4. Completion event for `capitalcalcmedium` evicts both `capitalcalcmedium` and `capital` alias cache
5. Unknown name passthrough: `/batch/runs?keys=capitalcalc` → still works (no alias config = passthrough)
6. All existing tests pass

---

## Notes

- **No DB migration needed** — this is purely a service-layer mapping.
- **Local profile**: No aliases configured in `application-local.yml` by design — all names pass through unchanged. Add them if testing with local data seeded to the real name pattern.
- **New calculator onboarding**: Add the alias entry to `application-dev.yml` + `application-prod.yml` and redeploy. No code change needed.
- **UI backward compatibility**: Real calculator names still work as keys (passthrough behavior). The UI can switch alias names incrementally.
