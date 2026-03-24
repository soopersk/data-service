# Domain & DTO Standards Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix type safety, validation, and coding standards across domain entities and DTOs (F1-F7).

**Architecture:** Replace raw String fields with enums in domain/projection classes. Convert immutable DTOs/projections to Java 17 records (drop Lombok). Add strict input validation at API boundary. Create API-specific enums where semantics diverge from domain.

**Tech Stack:** Java 17 records, Spring Boot 3.5.9, Jackson 2.15+, Lombok (retained only on mutable entities: `CalculatorRun`, `SlaBreachEvent`, request DTOs)

---

## Conventions for this plan

- **Record migration:** Replace `@Data @Builder @NoArgsConstructor @AllArgsConstructor` with Java `record`. Drop `Serializable`/`serialVersionUID` (JSON serializers used, not JDK). All `.builder().x(v).build()` call sites become `new Record(v, ...)` using canonical constructor. Parameter order matches current field declaration order.
- **TDD for new behavior:** Write failing test first (F1, F6). **TDD for refactors:** Ensure existing tests compile and pass after each change.
- **Commit after each task** with a descriptive message.
- **Run:** `SPRING_PROFILES_ACTIVE=local ./mvnw clean test` to verify after each task (requires Docker containers).

---

## Task 1: Create `CompletionStatus` enum (F6 prerequisite)

**Files:**
- Create: `src/main/java/com/company/observability/domain/enums/CompletionStatus.java`
- Create: `src/test/java/com/company/observability/domain/enums/CompletionStatusTest.java`

**Step 1: Write failing test**

```java
package com.company.observability.domain.enums;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionStatusTest {

    @Test
    void validValues() {
        assertEquals(CompletionStatus.SUCCESS, CompletionStatus.valueOf("SUCCESS"));
        assertEquals(CompletionStatus.FAILED, CompletionStatus.valueOf("FAILED"));
        assertEquals(CompletionStatus.TIMEOUT, CompletionStatus.valueOf("TIMEOUT"));
        assertEquals(CompletionStatus.CANCELLED, CompletionStatus.valueOf("CANCELLED"));
    }

    @Test
    void exactlyFourValues() {
        assertEquals(4, CompletionStatus.values().length, "RUNNING must not be a CompletionStatus");
    }

    @Test
    void toRunStatus() {
        assertEquals(RunStatus.SUCCESS, CompletionStatus.SUCCESS.toRunStatus());
        assertEquals(RunStatus.FAILED, CompletionStatus.FAILED.toRunStatus());
        assertEquals(RunStatus.TIMEOUT, CompletionStatus.TIMEOUT.toRunStatus());
        assertEquals(RunStatus.CANCELLED, CompletionStatus.CANCELLED.toRunStatus());
    }
}
```

**Step 2: Run test — expect compile failure**

**Step 3: Implement**

```java
package com.company.observability.domain.enums;

public enum CompletionStatus {
    SUCCESS, FAILED, TIMEOUT, CANCELLED;

    public RunStatus toRunStatus() {
        return RunStatus.valueOf(this.name());
    }
}
```

**Step 4: Run test — expect PASS**

**Step 5: Commit** — `feat: add CompletionStatus enum (F6 prerequisite)`

---

## Task 2: Create `SlaStatus` enum (F7 prerequisite)

**Files:**
- Create: `src/main/java/com/company/observability/dto/enums/SlaStatus.java`

**Step 1: Create enum**

```java
package com.company.observability.dto.enums;

/**
 * API-facing traffic-light status for SLA compliance.
 * Distinct from domain Severity — maps multiple severity levels to display categories.
 */
public enum SlaStatus {
    GREEN, AMBER, RED,      // day-level classification (TrendDataPoint, SlaSummary)
    SLA_MET, LATE, VERY_LATE // run-level classification (PerformanceCard RunBar)
}
```

Note: This enum intentionally has two vocabularies — day-level (GREEN/AMBER/RED) and run-level (SLA_MET/LATE/VERY_LATE). They serve different API consumers (trend charts vs performance cards). A single enum avoids creating two near-identical enums while the `@Schema` on each DTO field documents which subset is valid.

**Step 2: Commit** — `feat: add SlaStatus API enum (F7 prerequisite)`

---

## Task 3: Strict frequency validation (F1)

**Files:**
- Modify: `src/main/java/com/company/observability/domain/enums/CalculatorFrequency.java`
- Modify: `src/main/java/com/company/observability/controller/RunQueryController.java:53,92`
- Modify: `src/main/java/com/company/observability/controller/AnalyticsController.java:44,142`
- Create: `src/test/java/com/company/observability/domain/enums/CalculatorFrequencyTest.java`

**Step 1: Write failing test for `fromStrict()`**

```java
package com.company.observability.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorFrequencyTest {

    @ParameterizedTest
    @ValueSource(strings = {"D", "DAILY", "daily", "Daily"})
    void fromStrict_daily(String input) {
        assertEquals(CalculatorFrequency.DAILY, CalculatorFrequency.fromStrict(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"M", "MONTHLY", "monthly", "Monthly"})
    void fromStrict_monthly(String input) {
        assertEquals(CalculatorFrequency.MONTHLY, CalculatorFrequency.fromStrict(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"WEEKLY", "X", "123", ""})
    void fromStrict_invalid_throws(String input) {
        assertThrows(IllegalArgumentException.class, () -> CalculatorFrequency.fromStrict(input));
    }

    @Test
    void fromStrict_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> CalculatorFrequency.fromStrict(null));
    }

    @Test
    void from_null_defaults_daily() {
        // Existing lenient behavior preserved for Jackson @JsonCreator
        assertEquals(CalculatorFrequency.DAILY, CalculatorFrequency.from(null));
    }
}
```

**Step 2: Run test — expect compile failure (fromStrict doesn't exist)**

**Step 3: Add `fromStrict()` to `CalculatorFrequency`**

```java
/**
 * Strict parsing for query/analytics endpoints. Rejects invalid values with IllegalArgumentException.
 */
public static CalculatorFrequency fromStrict(String frequency) {
    if (frequency == null || frequency.isBlank()) {
        throw new IllegalArgumentException("Frequency is required. Valid values: DAILY, D, MONTHLY, M");
    }
    return switch (frequency.trim().toUpperCase()) {
        case "D", "DAILY" -> DAILY;
        case "M", "MONTHLY" -> MONTHLY;
        default -> throw new IllegalArgumentException(
                "Unknown frequency: '" + frequency + "'. Valid values: DAILY, D, MONTHLY, M");
    };
}
```

Keep existing `from()` with `@JsonCreator` unchanged — it's used for JSON deserialization of `StartRunRequest.frequency` where DAILY default is correct.

**Step 4: Run test — expect PASS**

**Step 5: Update controllers**

In `RunQueryController.java` replace both occurrences (lines 53, 92):
```java
// Before:
CalculatorFrequency freq = CalculatorFrequency.from(frequency);
// After:
CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);
```

In `AnalyticsController.java` replace both occurrences (lines 44, 142):
```java
// Before:
CalculatorFrequency freq = CalculatorFrequency.from(frequency);
// After:
CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);
```

Note: `CalculatorRunRepository.java:473` uses `CalculatorFrequency.from(rs.getString("frequency"))` to parse DB values — keep as `from()` (DB values are trusted, never invalid).

**Step 6: Verify `GlobalExceptionHandler` catches `IllegalArgumentException` → 400**

Check `src/main/java/com/company/observability/exception/GlobalExceptionHandler.java` handles `IllegalArgumentException` and returns HTTP 400. If not, add a handler.

**Step 7: Run all tests**

**Step 8: Commit** — `feat: reject invalid frequency with 400 (F1)`

---

## Task 4: `SlaBreachEvent` enum typing (F2) + equality fix (F5)

**Files:**
- Modify: `src/main/java/com/company/observability/domain/SlaBreachEvent.java`
- Modify: `src/main/java/com/company/observability/repository/SlaBreachEventRepository.java` (RowMapper ~line 282)
- Modify: `src/main/java/com/company/observability/service/AlertHandlerService.java:48,103,119`
- Modify: `src/test/java/com/company/observability/service/AlertHandlerServiceTest.java:47`
- Modify: `src/test/java/com/company/observability/service/AnalyticsServiceTest.java:156`
- Modify: `src/test/java/com/company/observability/repository/SlaBreachEventRepositoryJdbcTest.java:72`

**Step 1: Update `SlaBreachEvent` domain class**

Replace `@Data` with explicit Lombok annotations and type the three String fields:

```java
package com.company.observability.domain;

import com.company.observability.domain.enums.AlertStatus;
import com.company.observability.domain.enums.BreachType;
import com.company.observability.domain.enums.Severity;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "breachId")
@ToString
public class SlaBreachEvent {
    private Long breachId;
    private String runId;
    private String calculatorId;
    private String calculatorName;
    private String tenantId;
    private BreachType breachType;        // was String
    private Long expectedValue;
    private Long actualValue;
    private Severity severity;            // was String
    private Boolean alerted;
    private Instant alertedAt;
    private AlertStatus alertStatus;      // was String
    private Integer retryCount;
    private String lastError;
    private Instant createdAt;
}
```

**Step 2: Update `SlaBreachEventRepository` RowMapper**

In the RowMapper (around line 282), change:
```java
// Before:
.breachType(rs.getString("breach_type"))
.severity(rs.getString("severity"))
.alertStatus(rs.getString("alert_status"))

// After:
.breachType(BreachType.fromString(rs.getString("breach_type")))
.severity(Severity.fromString(rs.getString("severity")))
.alertStatus(AlertStatus.fromString(rs.getString("alert_status")))
```

**Step 3: Update `SlaBreachEventRepository.save()` and `update()` SQL params**

Anywhere the repository binds these fields to SQL parameters, use `.name()`:
```java
// Before:
params.addValue("breach_type", breach.getBreachType());
// After:
params.addValue("breach_type", breach.getBreachType().name());
```

Same for `severity` and `alert_status` parameters.

**Step 4: Update `AlertHandlerService`**

Line 48 — builder call: use enum values instead of strings:
```java
// Before:
.breachType(breachType)    // String from event
.severity(severity)        // String from event
.alertStatus("PENDING")

// After:
.breachType(BreachType.fromString(breachType))   // event still passes String
.severity(Severity.fromString(severity))
.alertStatus(AlertStatus.PENDING)
```

Lines 103, 119 — setter calls:
```java
// Before:
breach.setAlertStatus("SENT");
breach.setAlertStatus("FAILED");

// After:
breach.setAlertStatus(AlertStatus.SENT);
breach.setAlertStatus(AlertStatus.FAILED);
```

Note: Check what `SlaBreachedEvent` passes as `breachType`/`severity` — if it passes Strings, parse in `AlertHandlerService`. If the event class should also use enums, update it too (but keep blast radius contained to this task).

**Step 5: Update `AnalyticsService.toBreachDetail()`**

After F2, `breach.getBreachType()` returns `BreachType` enum. Update the mapping:
```java
// Before:
.breachType(breach.getBreachType())
.severity(breach.getSeverity())

// After:
.breachType(breach.getBreachType().name())
.severity(breach.getSeverity().name())
```

Or if F7 response DTOs use enum types by then, pass the enum directly.

**Step 6: Fix all test compilation — update builder calls in test files**

Update `AlertHandlerServiceTest.java:47`, `AnalyticsServiceTest.java:156`, `SlaBreachEventRepositoryJdbcTest.java:72` to use enum values instead of strings in builder calls.

**Step 7: Run all tests**

**Step 8: Commit** — `refactor: type SlaBreachEvent fields with enums (F2, TD-5)`

---

## Task 5: `CalculatorRun` equality fix (F5 — mutable entity)

**Files:**
- Modify: `src/main/java/com/company/observability/domain/CalculatorRun.java`

**Step 1: Replace `@Data` with explicit annotations**

```java
// Before:
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatorRun implements Serializable {

// After:
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = {"runId", "reportingDate"})
@ToString
public class CalculatorRun implements Serializable {
```

Keep `Serializable` — `CalculatorRun` is stored in Redis sorted sets.

**Step 2: Run all tests**

**Step 3: Commit** — `refactor: CalculatorRun identity-based equality on composite PK (F5)`

---

## Task 6: `RunWithSlaStatus` → record with enum types (F3 + F5)

**Files:**
- Modify: `src/main/java/com/company/observability/domain/RunWithSlaStatus.java`
- Modify: `src/main/java/com/company/observability/repository/CalculatorRunRepository.java:424-440`
- Modify: `src/main/java/com/company/observability/service/AnalyticsService.java` (all sites that read RunWithSlaStatus fields)

**Step 1: Convert to record with enum-typed fields**

```java
package com.company.observability.domain;

import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.domain.enums.Severity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lightweight projection for performance card: calculator_runs LEFT JOIN sla_breach_events.
 * Severity is nullable (LEFT JOIN — null when no breach exists).
 */
public record RunWithSlaStatus(
        String runId,
        String calculatorId,
        String calculatorName,
        LocalDate reportingDate,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        BigDecimal startHourCet,
        BigDecimal endHourCet,
        Instant slaTime,
        Instant estimatedStartTime,
        CalculatorFrequency frequency,
        RunStatus status,
        Boolean slaBreached,
        String slaBreachReason,
        Severity severity
) {}
```

**Step 2: Update `CalculatorRunRepository` RowMapper (~line 424)**

```java
// Before:
return RunWithSlaStatus.builder()
        ...
        .frequency(rs.getString("frequency"))
        .status(rs.getString("status"))
        ...
        .severity(rs.getString("severity"))
        .build();

// After:
String severityStr = rs.getString("severity"); // nullable from LEFT JOIN
return new RunWithSlaStatus(
        rs.getString("run_id"),
        rs.getString("calculator_id"),
        rs.getString("calculator_name"),
        rs.getObject("reporting_date", LocalDate.class),
        getInstant(rs, "start_time"),
        getInstant(rs, "end_time"),
        rs.getObject("duration_ms", Long.class),
        rs.getBigDecimal("start_hour_cet"),
        rs.getBigDecimal("end_hour_cet"),
        getInstant(rs, "sla_time"),
        getInstant(rs, "estimated_start_time"),
        CalculatorFrequency.from(rs.getString("frequency")),
        RunStatus.fromString(rs.getString("status")),
        rs.getObject("sla_breached", Boolean.class),
        rs.getString("sla_breach_reason"),
        severityStr != null ? Severity.fromString(severityStr) : null
);
```

**Step 3: Update `AnalyticsService` — fix getter calls**

Record accessors use `run.fieldName()` not `run.getFieldName()`:
```java
// Before: run.getRunId(), run.getCalculatorName(), run.getFrequency(), etc.
// After:  run.runId(),    run.calculatorName(),    run.frequency(),    etc.
```

Where `run.getFrequency()` previously returned `String`, it now returns `CalculatorFrequency`. Update comparisons and `.name()` calls accordingly:
```java
// Before: .frequency(run.getFrequency())       — was String
// After:  .frequency(run.frequency().name())    — enum.name() for String response field
```

**Step 4: Run all tests**

**Step 5: Commit** — `refactor: RunWithSlaStatus to record with enum types (F3, F5)`

---

## Task 7: `DailyAggregate` → record (F5)

**Files:**
- Modify: `src/main/java/com/company/observability/domain/DailyAggregate.java`
- Modify: `src/main/java/com/company/observability/repository/DailyAggregateRepository.java:135`
- Modify: `src/test/java/com/company/observability/service/AnalyticsServiceTest.java:98,128`

**Step 1: Convert to record**

```java
package com.company.observability.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily aggregated metrics per calculator. Pre-computed for fast dashboard queries.
 */
public record DailyAggregate(
        String calculatorId,
        String tenantId,
        LocalDate dayCet,
        Integer totalRuns,
        Integer successRuns,
        Integer slaBreaches,
        Long avgDurationMs,
        Integer avgStartMinCet,
        Integer avgEndMinCet,
        Instant computedAt
) {}
```

**Step 2: Update `DailyAggregateRepository` RowMapper (line 135)**

Replace `.builder()...build()` with `new DailyAggregate(...)` using the same field order.

**Step 3: Update `AnalyticsServiceTest` builder calls (lines 98, 128)**

Replace `DailyAggregate.builder()...build()` with `new DailyAggregate(...)`.

**Step 4: Update `AnalyticsService`** — fix getter calls

All `aggregate.getFieldName()` → `aggregate.fieldName()`.

**Step 5: Run all tests**

**Step 6: Commit** — `refactor: DailyAggregate to record (F5)`

---

## Task 8: Simple response DTOs → records (F5)

Convert these 4 simple DTOs (few fields, few call sites):

**Files:**
- Modify: `src/main/java/com/company/observability/dto/response/RunResponse.java`
- Modify: `src/main/java/com/company/observability/dto/response/SlaSummaryResponse.java`
- Modify: `src/main/java/com/company/observability/dto/response/SlaBreachDetailResponse.java`
- Modify: `src/main/java/com/company/observability/dto/response/PagedResponse.java`
- Modify: all call sites in controllers, services, and tests

**Step 1: Convert `RunResponse`**

```java
package com.company.observability.dto.response;

import java.time.Instant;

public record RunResponse(
        String runId,
        String calculatorId,
        String calculatorName,
        String status,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        Boolean slaBreached,
        String slaBreachReason
) {}
```

Update `RunIngestionController.toRunResponse()`:
```java
return new RunResponse(
        run.getRunId(), run.getCalculatorId(), run.getCalculatorName(),
        run.getStatus().name(), run.getStartTime(), run.getEndTime(),
        run.getDurationMs(), run.getSlaBreached(), run.getSlaBreachReason());
```

**Step 2: Convert `SlaSummaryResponse`**

```java
package com.company.observability.dto.response;

import java.util.Map;

public record SlaSummaryResponse(
        String calculatorId,
        int periodDays,
        int totalBreaches,
        int greenDays,
        int amberDays,
        int redDays,
        Map<String, Integer> breachesBySeverity,
        Map<String, Integer> breachesByType
) {}
```

Update `AnalyticsService.getSlaSummary()` builder → constructor.

**Step 3: Convert `SlaBreachDetailResponse`**

```java
package com.company.observability.dto.response;

import java.time.Instant;

public record SlaBreachDetailResponse(
        long breachId,
        String runId,
        String calculatorId,
        String calculatorName,
        String breachType,
        String severity,
        String slaStatus,
        Long expectedValue,
        Long actualValue,
        Instant createdAt
) {}
```

Update `AnalyticsService.toBreachDetail()` builder → constructor.

**Step 4: Convert `PagedResponse<T>`**

```java
package com.company.observability.dto.response;

import java.util.List;

public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String nextCursor
) {}
```

Update `AnalyticsService.getSlaBreachDetails()` builder → constructor.

**Step 5: Run all tests**

**Step 6: Commit** — `refactor: RunResponse, SlaSummaryResponse, SlaBreachDetailResponse, PagedResponse to records (F5)`

---

## Task 9: `RunStatusInfo` → record + vocabulary fix (F4 + F5)

**Files:**
- Modify: `src/main/java/com/company/observability/dto/response/RunStatusInfo.java`
- Modify: `src/main/java/com/company/observability/service/RunQueryService.java:191`
- Modify: `src/test/java/com/company/observability/service/RunQueryServiceTest.java:50`
- Modify: `src/test/java/com/company/observability/controller/RunQueryControllerTest.java:150`

**Step 1: Convert to record, fix the misleading comment**

```java
package com.company.observability.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record RunStatusInfo(
        String runId,
        @Schema(description = "Run status", allowableValues = {"RUNNING", "SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"})
        String status,
        Instant start,
        Instant end,
        Instant estimatedStart,
        Instant estimatedEnd,
        Instant sla,
        Long durationMs,
        String durationFormatted,
        Boolean slaBreached,
        String slaBreachReason
) {}
```

The comment `// RUNNING, COMPLETED, FAILED, TIMEOUT, NOT_STARTED` is replaced by a `@Schema` annotation documenting the actual valid values from `RunStatus.name()`.

**Step 2: Update `RunQueryService.mapToRunStatusInfo()` (line 191)**

```java
return new RunStatusInfo(
        run.getRunId(), run.getStatus().name(),
        run.getStartTime(), run.getEndTime(),
        run.getEstimatedStartTime(), run.getEstimatedEndTime(),
        run.getSlaTime(), run.getDurationMs(),
        TimeUtils.formatDuration(run.getDurationMs()),
        run.getSlaBreached(), run.getSlaBreachReason());
```

**Step 3: Update tests**

`RunQueryServiceTest.java:50` and `RunQueryControllerTest.java:150` — replace `.builder()...build()` with constructor calls.

**Step 4: Run all tests**

**Step 5: Commit** — `refactor: RunStatusInfo to record, fix status vocabulary (F4, F5)`

---

## Task 10: `CalculatorStatusResponse` → record (F5)

**Files:**
- Modify: `src/main/java/com/company/observability/dto/response/CalculatorStatusResponse.java`
- Modify: `src/main/java/com/company/observability/service/RunQueryService.java:70,134`
- Modify: `src/main/java/com/company/observability/cache/RedisCalculatorCache.java:171,313`
- Modify: `src/test/java/com/company/observability/service/RunQueryServiceTest.java:47`
- Modify: `src/test/java/com/company/observability/controller/RunQueryControllerTest.java:156`

**Step 1: Convert to record**

```java
package com.company.observability.dto.response;

import java.time.Instant;
import java.util.List;

public record CalculatorStatusResponse(
        String calculatorName,
        Instant lastRefreshed,
        RunStatusInfo current,
        List<RunStatusInfo> history
) {}
```

**Step 2: Update `RunQueryService` (lines 70, 134)**

Replace builder with constructor. Example:
```java
return new CalculatorStatusResponse(calculatorName, Instant.now(), current, history);
```

**Step 3: Update `RedisCalculatorCache` (lines 171, 313)**

These lines read `response.getCurrent().getStatus()`. After record conversion:
- `response.getCurrent()` → `response.current()`
- `.getStatus()` → `.status()` (since RunStatusInfo is also a record)

So: `response.current().status()`

**Step 4: Update tests**

Replace builder calls in test files with constructor calls.

**Step 5: Run all tests**

**Step 6: Commit** — `refactor: CalculatorStatusResponse to record (F5)`

---

## Task 11: `RuntimeAnalyticsResponse` + `TrendAnalyticsResponse` → records (F5)

**Files:**
- Modify: `src/main/java/com/company/observability/dto/response/RuntimeAnalyticsResponse.java`
- Modify: `src/main/java/com/company/observability/dto/response/TrendAnalyticsResponse.java`
- Modify: `src/main/java/com/company/observability/service/AnalyticsService.java` (multiple builder sites)

**Step 1: Convert `RuntimeAnalyticsResponse` + inner `DailyDataPoint`**

```java
package com.company.observability.dto.response;

import java.time.LocalDate;
import java.util.List;

public record RuntimeAnalyticsResponse(
        String calculatorId,
        int periodDays,
        String frequency,
        long avgDurationMs,
        String avgDurationFormatted,
        long minDurationMs,
        long maxDurationMs,
        int totalRuns,
        double successRate,
        List<DailyDataPoint> dataPoints
) {
    public record DailyDataPoint(
            LocalDate date,
            long avgDurationMs,
            int totalRuns,
            int successRuns
    ) {}
}
```

**Step 2: Convert `TrendAnalyticsResponse` + inner `TrendDataPoint`**

```java
package com.company.observability.dto.response;

import java.time.LocalDate;
import java.util.List;

public record TrendAnalyticsResponse(
        String calculatorId,
        int periodDays,
        List<TrendDataPoint> trends
) {
    public record TrendDataPoint(
            LocalDate date,
            long avgDurationMs,
            int totalRuns,
            int successRuns,
            int slaBreaches,
            int avgStartMinCet,
            int avgEndMinCet,
            String slaStatus
    ) {}
}
```

**Step 3: Update `AnalyticsService`**

Replace all `.builder()...build()` calls with constructors. Inner records: `RuntimeAnalyticsResponse.DailyDataPoint` and `TrendAnalyticsResponse.TrendDataPoint`. Also fix all getter calls to record accessor style.

**Step 4: Run all tests**

**Step 5: Commit** — `refactor: RuntimeAnalyticsResponse, TrendAnalyticsResponse to records (F5)`

---

## Task 12: `PerformanceCardResponse` → record (F5)

**Files:**
- Modify: `src/main/java/com/company/observability/dto/response/PerformanceCardResponse.java`
- Modify: `src/main/java/com/company/observability/service/AnalyticsService.java` (lines 320-420)

**Step 1: Convert to record with inner records**

```java
package com.company.observability.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PerformanceCardResponse(
        String calculatorId,
        String calculatorName,
        ScheduleInfo schedule,
        int periodDays,
        long meanDurationMs,
        String meanDurationFormatted,
        SlaSummaryPct slaSummary,
        List<RunBar> runs,
        ReferenceLines referenceLines
) {
    public record ScheduleInfo(
            String estimatedStartTimeCet,
            String frequency
    ) {}

    public record SlaSummaryPct(
            int totalRuns,
            int slaMetCount,
            double slaMetPct,
            int lateCount,
            double latePct,
            int veryLateCount,
            double veryLatePct
    ) {}

    public record RunBar(
            String runId,
            LocalDate reportingDate,
            String dateFormatted,
            BigDecimal startHourCet,
            BigDecimal endHourCet,
            String startTimeCet,
            String endTimeCet,
            long durationMs,
            String durationFormatted,
            String slaStatus
    ) {}

    public record ReferenceLines(
            BigDecimal slaStartHourCet,
            BigDecimal slaEndHourCet
    ) {}
}
```

**Step 2: Update `AnalyticsService.getPerformanceCard()`**

Replace nested `.builder()...build()` chains with nested constructor calls. Example:
```java
new PerformanceCardResponse(
        calculatorId, latestRun.calculatorName(),
        new PerformanceCardResponse.ScheduleInfo(startTimeCet, frequency.name()),
        days, meanDuration, TimeUtils.formatDuration(meanDuration),
        slaSummary, runBars,
        new PerformanceCardResponse.ReferenceLines(slaStartHour, slaEndHour));
```

Note: `latestRun` is a `RunWithSlaStatus` record (from Task 6), so use `latestRun.calculatorName()` not `latestRun.getCalculatorName()`.

**Step 3: Run all tests**

**Step 4: Commit** — `refactor: PerformanceCardResponse to record (F5)`

---

## Task 13: `CompleteRunRequest` — use `CompletionStatus` enum (F6)

**Files:**
- Modify: `src/main/java/com/company/observability/dto/request/CompleteRunRequest.java`
- Modify: `src/main/java/com/company/observability/service/RunIngestionService.java:177`
- Modify: `src/test/java/com/company/observability/service/RunIngestionServiceTest.java`
- Modify: `src/test/java/com/company/observability/controller/RunIngestionControllerTest.java`

**Step 1: Update `CompleteRunRequest`**

```java
package com.company.observability.dto.request;

import com.company.observability.domain.enums.CompletionStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteRunRequest {
    @NotNull(message = "End time is required")
    private Instant endTime;

    private CompletionStatus status; // Optional. Defaults to SUCCESS when omitted.
}
```

Jackson deserializes `"SUCCESS"` → `CompletionStatus.SUCCESS` automatically. Invalid values → 400 (Jackson `InvalidFormatException` mapped by Spring).

**Step 2: Update `RunIngestionService.completeRun()` (line 177)**

```java
// Before:
run.setStatus(RunStatus.fromCompletionStatus(request.getStatus()));

// After:
CompletionStatus completionStatus = request.getStatus() != null
        ? request.getStatus()
        : CompletionStatus.SUCCESS;
run.setStatus(completionStatus.toRunStatus());
```

**Step 3: Remove `RunStatus.fromCompletionStatus()`**

This method is now dead code — `CompletionStatus.toRunStatus()` replaces it. Remove the method from `RunStatus.java` and delete the corresponding tests in `RunStatusTest.java` that test `fromCompletionStatus()`.

Note: Verify no other callers of `fromCompletionStatus()` exist before deleting. The grep showed it's only called from `RunIngestionService.java:177` and test files.

**Step 4: Update tests**

In test files, replace string status values in `CompleteRunRequest.builder()` with `CompletionStatus` enum values:
```java
// Before:
.status("SUCCESS")
// After:
.status(CompletionStatus.SUCCESS)
```

**Step 5: Run all tests**

**Step 6: Commit** — `refactor: CompleteRunRequest uses CompletionStatus enum (F6)`

---

## Task 14: Apply `SlaStatus` enum in response DTOs (F7)

**Files:**
- Modify: `src/main/java/com/company/observability/service/AnalyticsService.java`

**Decision:** Keep response DTO fields as `String` type (they're already records by now — changing types is trivial but affects JSON output). Instead, ensure all call sites use `SlaStatus` enum constants via `.name()` for compile-time safety in the service layer:

```java
// Before (scattered string literals):
slaStatus = "GREEN";
slaStatus = "SLA_MET";

// After:
slaStatus = SlaStatus.GREEN.name();
slaStatus = SlaStatus.SLA_MET.name();
```

This gives compile-time safety in the service without changing the JSON wire format. If the team later wants enum-typed response fields, the `SlaStatus` enum is already in place.

**Step 1: Find all SLA status string literals in `AnalyticsService`**

Search for `"GREEN"`, `"AMBER"`, `"RED"`, `"SLA_MET"`, `"LATE"`, `"VERY_LATE"` in `AnalyticsService.java`.

**Step 2: Replace with `SlaStatus.X.name()`**

Add import: `import com.company.observability.dto.enums.SlaStatus;`

Replace each literal. Example:
```java
// Before:
return "GREEN";
// After:
return SlaStatus.GREEN.name();
```

**Step 3: Run all tests**

**Step 4: Commit** — `refactor: use SlaStatus enum constants in AnalyticsService (F7)`

---

## Task 15: Final verification

**Step 1: Full clean build**

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw clean package
```

**Step 2: Run all tests**

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw clean test
```

**Step 3: Verify no Lombok on record classes**

Grep for `@Data` or `@Builder` in response DTOs — should only appear on `CalculatorRun`, `SlaBreachEvent`, and request DTOs.

**Step 4: Verify no stale imports**

Check for unused Lombok imports in converted record files.

**Step 5: Commit** — `chore: final cleanup after domain/DTO standards fixes`

---

## Summary: Files Changed per Task

| Task | Fix | Files created | Files modified |
|------|-----|---------------|----------------|
| 1 | F6 prereq | `CompletionStatus.java`, `CompletionStatusTest.java` | — |
| 2 | F7 prereq | `SlaStatus.java` | — |
| 3 | F1 | `CalculatorFrequencyTest.java` | `CalculatorFrequency.java`, 2 controllers |
| 4 | F2+F5 | — | `SlaBreachEvent.java`, repo, service, 3 tests |
| 5 | F5 | — | `CalculatorRun.java` |
| 6 | F3+F5 | — | `RunWithSlaStatus.java`, repo, `AnalyticsService` |
| 7 | F5 | — | `DailyAggregate.java`, repo, test |
| 8 | F5 | — | 4 response DTOs, controller, service |
| 9 | F4+F5 | — | `RunStatusInfo.java`, service, 2 tests |
| 10 | F5 | — | `CalculatorStatusResponse.java`, service, cache, 2 tests |
| 11 | F5 | — | 2 response DTOs, service |
| 12 | F5 | — | `PerformanceCardResponse.java`, service |
| 13 | F6 | — | `CompleteRunRequest.java`, service, `RunStatus.java`, tests |
| 14 | F7 | — | `AnalyticsService.java` |
| 15 | verify | — | cleanup only |
