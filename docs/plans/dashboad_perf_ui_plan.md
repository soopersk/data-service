# Batch Runs + Executions Endpoints — Implementation Plan

> ### Status: Implemented (with name-keyed delta, 2026-05-19)
>
> Tasks 1–7 of this plan were implemented as written. A follow-up adapter plan then switched both new endpoints from upstream-UUID `calculator_id` to readable `calculator_name`. See `analyze-the-plan-docs-plans-dashboad-per-agile-harbor.md` in `~/.claude/plans/` for the delta rationale, and the current shipped contract in:
> - `docs/spec/api-reference.md` — `/api/v1/calculators/batch/runs`, `/api/v1/analytics/calculators/{calculatorName}/executions`
> - `docs/user/query-api.md` — `/batch/runs` usage
> - `docs/user/analytics-api.md` — `/executions` usage
>
> Key differences from this document below:
> - `/batch/runs` `keys` param: pipe-separated **`calculator_name`** values (not UUIDs).
> - `/batch/runs` response map: keyed by **`calculator_name`**; `CalculatorEntry` has **`calculatorName` only** (no `calculatorId` field).
> - `/executions` path variable: **`{calculatorName}`**. Calls `findRunsWithSlaStatusByName(...)`. Existing `/run-performance` retained its UUID `{calculatorId}` path and the existing repo method.
> - Index migration `V7__calculator_name_indexes.sql` added composite indexes on `(tenant_id, calculator_name, ...)`.
>
> The original UUID-keyed prose below is preserved for archaeological context; do not implement against it.

---

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add two new domain-aligned endpoints — `GET /api/v1/calculators/batch/runs` (powers the dashboard) and `GET /api/v1/analytics/calculators/{id}/executions` (raw run history for the performance card). All existing endpoints including projection endpoints are left completely untouched.

**Architecture:** Additive only. One logical `calculatorId` per calculator. Dimensional runs (regional capital, risk governed types) are represented as multiple `RunEntry` items within one calculator entry. `region` field = geographic dimension (WMAP/WMDE/etc); `runType` field = computation type (ETD/OTC/SFT). Existing `findDashboardCalculatorRuns` is untouched.

**Tech Stack:** Java 17, Spring Boot 3.5.9, NamedParameterJdbcTemplate, existing `RunStatusClassifier`

---

## Domain Model

```
Calculator       Dimension field   Runs per date+runNumber   UI renders as
─────────────────────────────────────────────────────────────────────────────
capital          region            10 (WMAP, WMDE, ASIA,     region matrix
                                   WMUS, AUNZ, WMCH, ZURI,   columns = region codes
                                   LDNL, AMER, EURO)
modelled-exposure runType           3 (ETD, OTC, SFT)         type matrix
gemini-hedge      runType           3 (ETD, OTC, SFT)         type matrix
portfolio         —                 1                          single row
group-portfolio   —                 1                          single row
consolidation     —                 1                          single row

"Eligible total loss absorbing capacity", "Credit Risk Migration", etc.
→ pure frontend display labels, NOT calculators
→ all repeat the same capital runs in different visual sections of the dashboard
```

**Re-run signalling:** `isRerun: true` on a run entry means a re-trigger was fired for that specific region or type. UI renders the dimension column label with `*` suffix (LDNL*) and failed icon.

**`runNumber` query param (`"1"` or `"2"`):** DB column is `VARCHAR(10)` — typed as **`String` end-to-end** (query param, service, DTO, response). The width allows non-numeric buckets in the future (e.g. `"EARLY"`, `"ADHOC"`) without an API break. Selects early-cut vs late-cut bucket. Within a bucket, a dimension (e.g. AMER capital) can fail and be re-triggered — both original and retry share `run_number="1"`. Re-run detection is based on multiple DB rows for the same (calculatorId, region, runType) dimension, not on `run_number` value.

---

## Naming Rationale

The 10 regional codes (WMAP, WMDE, etc.) belong on `region`, not `runType`. `runType` is reserved for computation type (ETD/OTC/SFT). Both fields already exist on `CalculatorRun`. Using them correctly:

```
capital.runs[*].region   = "WMAP" | "WMDE" | ... | "EURO"
capital.runs[*].runType  = null

modelled-exposure.runs[*].region   = null
modelled-exposure.runs[*].runType  = "ETD" | "OTC" | "SFT"
```

---

## Architecture

```
CURRENT (unchanged BFF projection endpoints — completely untouched)
──────────────────────────────────────────────────────────────────────
  GET /projections/calculator-dashboard   →   DashboardProjection → DashboardService
  GET /projections/performance-card       →   PerformanceCardProjection → AnalyticsService

TARGET (new domain endpoints, additive)
──────────────────────────────────────────────────────────────────────
  Astro SSR
  ┌──────────────────────────────────────────────────────────────────┐
  │ section config (which calcIds per section)                       │
  │ display labels ("Eligible total loss…" → capital runs)           │
  │ CET formatting · chart coords · group by calculatorName          │
  └───────────────┬──────────────────────────────────────────────────┘
                  │
  GET /api/v1/calculators/batch/runs
  ?reporting_date=2026-03-06&frequency=DAILY&run_number=1
  &keys=capital|modelled-exposure|gemini-hedge|portfolio|...
                  │
  CalculatorStateService (new)
  CalculatorRunRepository.findAllRunsByDateAndDimension()  ← new method
  RunStatusClassifier (existing)

  GET /api/v1/analytics/calculators/{id}/executions
  ?days=30&frequency=DAILY
                  │
  AnalyticsService.getRunExecutions()  ← new method (bypasses LogicalRunGrouper)
  CalculatorRunRepository.findRunsWithSlaStatus()  ← existing, SQL extended
```

---

## Endpoint 1: `GET /api/v1/calculators/batch/runs`

### Request
```
GET /api/v1/calculators/batch/runs
  ?reporting_date=2026-05-19          (required, ISO date)
  &frequency=DAILY                    (DAILY|MONTHLY, default DAILY)
  &run_number=1                       (1|2, optional)
  &keys=capitalcalc|portfoliocalc|grportfoliocalc
Header: X-Tenant-Id: tenant1
```

### Response
```json
{
  "reportingDate": "2026-03-06",
  "frequency": "DAILY",
  "runNumber": "1",
  "generatedAt": "2026-03-06T17:00:00Z",
  "calculators": {
    "capital": {
      "calculatorId": "capital",
      "calculatorName": "Capital",
      "runs": [
        {
          "region": "WMAP",
          "runId": "run-wmap-001",
          "status": "SUCCESS",
          "slaStatus": "ON_TIME",
          "startTime": "2026-03-06T13:02:00Z",
          "endTime": "2026-03-06T14:45:00Z",
          "estimatedStartTime": "2026-03-06T13:00:00Z",
          "estimatedEndTime": "2026-03-06T14:50:00Z",
          "sla": "2026-03-06T15:00:00Z",
          "durationMs": 6180000,
          "slaBreached": false,
          "isRerun": false
        },
        {
          "region": "LDNL",
          "runId": "run-ldnl-002",
          "status": "FAILED",
          "slaStatus": "FAILED",
          "startTime": "2026-03-06T13:02:00Z",
          "endTime": "2026-03-06T14:58:00Z",
          "estimatedStartTime": "2026-03-06T13:00:00Z",
          "estimatedEndTime": "2026-03-06T14:50:00Z",
          "sla": "2026-03-06T15:00:00Z",
          "durationMs": 6960000,
          "slaBreached": true,
          "slaBreachReason": "Run status: FAILED",
          "isRerun": true
        }
      ]
    },
    "modelled-exposure": {
      "calculatorId": "modelled-exposure",
      "calculatorName": "Modelled exposure",
      "runs": [
        {
          "runType": "ETD",
          "status": "NOT_STARTED",
          "slaStatus": "IN_PROGRESS",
          "estimatedStartTime": "2026-03-06T17:02:00Z",
          "estimatedEndTime": "2026-03-06T17:58:00Z",
          "sla": "2026-03-06T18:30:00Z",
          "slaBreached": false,
          "isRerun": false
        },
        { "runType": "OTC", "status": "NOT_STARTED", "slaBreached": false, "isRerun": false },
        { "runType": "SFT", "status": "NOT_STARTED", "slaBreached": false, "isRerun": false }
      ]
    },
    "portfolio": {
      "calculatorId": "portfolio",
      "calculatorName": "Portfolio",
      "runs": [
        {
          "status": "NOT_STARTED",
          "slaStatus": "IN_PROGRESS",
          "estimatedStartTime": "2026-03-06T16:02:00Z",
          "estimatedEndTime": "2026-03-06T17:05:00Z",
          "sla": "2026-03-06T17:00:00Z",
          "slaBreached": false,
          "isRerun": false
        }
      ]
    }
  }
}
```

**Notes:**
- `runs` is **empty list** when no run found for a calculatorId — do not omit the key
- `runId` is null for `NOT_STARTED` runs (no DB row yet; derived from config/history estimates)
- `region` and `runType` are mutually exclusive per calculator — a calculator uses one or neither, never both
- `isRerun` = true when that specific dimensional run was re-triggered (UI renders `LDNL*`)
- `@JsonInclude(NON_NULL)` on `RunEntry` — null fields are omitted from JSON

---

## Endpoint 2: `GET /api/v1/analytics/calculators/{calculatorName}/executions`

**Not an alias for `/run-performance`.** Returns each physical run independently — split runs sharing a `correlationId` appear as separate rows, not collapsed. `subRunIds` is always `null`. Backed by a new `getRunExecutions()` service method that bypasses `LogicalRunGrouper`.

**Params:** `calculatorId` (path), `X-Tenant-Id` (header), `days` (default 30, 1–365), `frequency` (default DAILY)

**Contrast with `/run-performance`:** same underlying data but splits there are collapsed into one logical entry with `subRunIds` populated.

GET /api/v1/analytics/calculators/portfoliocalc/executions
  ?days=30           (1-365, default 30)
  &frequency=DAILY   (default DAILY)
  &run_number=1      (1|2, optional)
Header: X-Tenant-Id: tenant1


### Sample Response
```json
{
  "calculatorId": "portfolio-calc",
  "calculatorName": "portfolio",
  "frequency": "DAILY",
  "periodDays": 30,
  "meanDurationMs": 285000,
  "totalRuns": 30,
  "runningRuns": 1,
  "slaMetCount": 24,
  "lateCount": 4,
  "veryLateCount": 2,
  "estimatedStartTime": "2026-05-14T04:00:00Z",
  "slaTime": "2026-05-14T06:30:00Z",
  "runs": [
    {
      "runId": "run-2026-05-13-001",
      "reportingDate": "2026-05-13",
      "startTime": "2026-05-13T04:02:15Z",
      "endTime": "2026-05-13T04:07:30Z",
      "durationMs": 315000,
      "expectedDurationMs": 300000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "SLA_MET",
      "runNumber": "1",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-13T04:00:00Z",
      "slaTime": "2026-05-13T06:30:00Z"
    },
    {
      "runId": "run-2026-05-11-split-1",
      "reportingDate": "2026-05-11",
      "startTime": "2026-05-11T03:59:50Z",
      "endTime": "2026-05-11T04:08:10Z",
      "durationMs": 500000,
      "expectedDurationMs": 300000,
      "status": "SUCCESS",
      "slaBreached": false,
      "slaStatus": "SLA_MET",
      "runNumber": "1",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-11T04:00:00Z",
      "slaTime": "2026-05-11T06:30:00Z"
    },
    {
      "runId": "run-2026-05-11-split-2",
      "reportingDate": "2026-05-11",
      "startTime": "2026-05-11T04:00:05Z",
      "endTime": "2026-05-11T04:15:45Z",
      "durationMs": 940000,
      "expectedDurationMs": 300000,
      "status": "SUCCESS",
      "slaBreached": true,
      "slaStatus": "VERY_LATE",
      "runNumber": "1",
      "subRunIds": null,
      "estimatedStartTime": "2026-05-11T04:00:00Z",
      "slaTime": "2026-05-11T06:30:00Z"
    }
  ]
}
```

**Key notes on `expectedDurationMs`:** Sourced from the immutable `expected_duration_ms` column on `calculator_runs` (set at first INSERT, never overwritten). Use this for actual-vs-expected comparison per run. `meanDurationMs` at the envelope level is the 30-day rolling average — use for trend baseline.

---

## Files

```
CREATE:
  src/main/java/.../dto/response/CalculatorBatchRunsResponse.java
  src/main/java/.../service/CalculatorStateService.java

MODIFY (additive only):
  src/main/java/.../domain/CalculatorRun.java
    → add transient boolean isRerun field
  src/main/java/.../domain/RunWithSlaStatus.java
    → add String runNumber, Long expectedDurationMs fields
  src/main/java/.../repository/CalculatorRunRepository.java
    → add findAllRunsByDateAndDimension()
    → add run_number + expected_duration_ms to findRunsWithSlaStatus() SELECT
  src/main/java/.../service/projection/LogicalRunGrouper.java
    → add runNumber + expectedDurationMs to LogicalRun
  src/main/java/.../service/AnalyticsService.java
    → add runNumber + expectedDurationMs to buildRunPerformanceData() constructor call
    → add new getRunExecutions() method
  src/main/java/.../dto/response/RunPerformanceData.java
    → add runNumber + expectedDurationMs to RunDataPoint
  src/main/java/.../controller/RunQueryController.java
    → add GET /batch/runs handler + inject CalculatorStateService
  src/main/java/.../controller/AnalyticsController.java
    → add GET /executions as new separate endpoint

UNTOUCHED:
  All projection controllers, projection services, all existing DTOs,
  existing findDashboardCalculatorRuns() and all other existing repository methods,
  existing /run-performance endpoint and getRunPerformanceData() service method
```

---

## Implementation Plan

### Task 1: Add `isRerun` field to `CalculatorRun`

**File:** `src/main/java/com/company/observability/domain/CalculatorRun.java`

Add one field after `runNumber`. This is a transient field — no DB column, no migration. The base `calculatorRunRowMapper` does not set it (defaults `false`). It is set by `CalculatorStateService` in the service layer.

```java
private boolean isRerun;   // transient — set by CalculatorStateService, not persisted
```

No tests required for this step. Commit:
```bash
git add src/main/java/com/company/observability/domain/CalculatorRun.java
git commit -m "feat: add transient isRerun field to CalculatorRun"
```

---

### Task 2: Thread `runNumber` + `expectedDurationMs` into `RunPerformanceData` pipeline

Both fields touch the same 7 locations. Do them together to avoid opening each file twice.

**Step 1: `CalculatorRunRepository.findRunsWithSlaStatus()` — extend SELECT**

Add `cr.run_number` and `cr.expected_duration_ms` to the SELECT list:
```sql
SELECT cr.run_id, cr.calculator_id, cr.calculator_name, cr.reporting_date,
       cr.start_time, cr.end_time, cr.duration_ms,
       cr.sla_time, cr.estimated_start_time, cr.frequency, cr.status,
       cr.sla_breached, cr.sla_breach_reason, cr.correlation_id,
       cr.run_number, cr.expected_duration_ms,
       sbe.severity
```

**Step 2: `domain/RunWithSlaStatus.java` — add fields**

Add after `correlationId`:
```java
String runNumber,
Long expectedDurationMs
```

**Step 3: Row mapper in `findRunsWithSlaStatus()` — add mappings**

Add at the matching positions in the constructor call:
```java
rs.getString("run_number"),
rs.getObject("expected_duration_ms", Long.class)
```

**Step 4: `LogicalRunGrouper.LogicalRun` — add fields**

Add after `correlationId`:
```java
String runNumber,
Long expectedDurationMs
```

**Step 5: `LogicalRunGrouper.groupWithSla()` — pass through**

In the `LogicalRun` constructor call, add:
```java
runs.get(0).runNumber(),           // all splits share the same run_number
runs.get(0).expectedDurationMs()   // immutable — same across splits
```

**Step 6: `RunPerformanceData.RunDataPoint` — add fields**

Add after `slaTime`:
```java
String runNumber,
Long expectedDurationMs
```

**Step 7: `AnalyticsService.buildRunPerformanceData()` — add to constructor**

In the `RunDataPoint` constructor call, add:
```java
lr.runNumber(),
lr.expectedDurationMs()
```

**Run tests:**
```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=AnalyticsServiceTest,RunPerformanceDataTest
```

**Commit:**
```bash
git add src/main/java/com/company/observability/repository/CalculatorRunRepository.java \
        src/main/java/com/company/observability/domain/RunWithSlaStatus.java \
        src/main/java/com/company/observability/service/projection/LogicalRunGrouper.java \
        src/main/java/com/company/observability/dto/response/RunPerformanceData.java \
        src/main/java/com/company/observability/service/AnalyticsService.java
git commit -m "feat: thread runNumber and expectedDurationMs into RunPerformanceData pipeline"
```

---

### Task 3: Create `CalculatorBatchRunsResponse` DTO

**File:** `src/main/java/com/company/observability/dto/response/CalculatorBatchRunsResponse.java`

**Step 1: Write the serialisation tests**

```java
// src/test/java/com/company/observability/dto/response/CalculatorBatchRunsResponseTest.java
class CalculatorBatchRunsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void emptyRunsSerializesWithEmptyArray() throws Exception {
        var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", "Capital", List.of());
        var response = new CalculatorBatchRunsResponse(
                LocalDate.of(2026, 3, 6), "DAILY", "1", Instant.now(), Map.of("capital", entry));
        String json = mapper.writeValueAsString(response);
        assertThat(json).contains("\"runs\":[]");
        assertThat(json).contains("\"calculatorId\":\"capital\"");
    }

    @Test
    void nullFieldsOmittedFromRunEntry() throws Exception {
        var run = CalculatorBatchRunsResponse.RunEntry.builder()
                .region("WMAP")
                .status("SUCCESS")
                .slaStatus("ON_TIME")
                .sla(Instant.parse("2026-03-06T15:00:00Z"))
                .slaBreached(false)
                .isRerun(false)
                .build();
        String json = mapper.writeValueAsString(run);
        assertThat(json).doesNotContain("runId");
        assertThat(json).doesNotContain("runType");
        assertThat(json).doesNotContain("startTime");
        assertThat(json).doesNotContain("latenessMs");
        assertThat(json).contains("\"region\":\"WMAP\"");
        assertThat(json).contains("\"slaStatus\":\"ON_TIME\"");
        assertThat(json).contains("\"isRerun\":false");
    }

    @Test
    void rerunFlagAndRegionSerializedCorrectly() throws Exception {
        var run = CalculatorBatchRunsResponse.RunEntry.builder()
                .region("LDNL")
                .runId("run-ldnl-002")
                .status("FAILED")
                .slaStatus("FAILED")
                .startTime(Instant.parse("2026-03-06T13:02:00Z"))
                .endTime(Instant.parse("2026-03-06T14:58:00Z"))
                .sla(Instant.parse("2026-03-06T15:00:00Z"))
                .durationMs(6960000L)
                .slaBreached(true)
                .slaBreachReason("Run status: FAILED")
                .isRerun(true)
                .build();
        String json = mapper.writeValueAsString(run);
        assertThat(json).contains("\"isRerun\":true");
        assertThat(json).contains("\"region\":\"LDNL\"");
        assertThat(json).contains("\"status\":\"FAILED\"");
    }
}
```

**Step 2: Create the DTO**

```java
package com.company.observability.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CalculatorBatchRunsResponse(
        LocalDate reportingDate,
        String frequency,
        String runNumber,
        Instant generatedAt,
        Map<String, CalculatorEntry> calculators
) {
    public record CalculatorEntry(
            String calculatorId,
            String calculatorName,
            List<RunEntry> runs
    ) {}

    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunEntry(
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
            Boolean slaBreached,
            String slaBreachReason,
            boolean isRerun
    ) {}
}
```

**Step 3: Run tests**
```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorBatchRunsResponseTest
```

**Step 4: Commit**
```bash
git add src/main/java/com/company/observability/dto/response/CalculatorBatchRunsResponse.java \
        src/test/java/com/company/observability/dto/response/CalculatorBatchRunsResponseTest.java
git commit -m "feat: add CalculatorBatchRunsResponse DTO with region/runType/isRerun dimensional fields"
```

---

### Task 4: Add `findAllRunsByDateAndDimension` to `CalculatorRunRepository`

**File:** `src/main/java/com/company/observability/repository/CalculatorRunRepository.java`

Returns ALL rows for the given filters — no deduplication in SQL. Deduplication and split-grouping are done in `CalculatorStateService`. Attempting SQL-only deduplication with `ROW_NUMBER()` would silently discard parallel splits that share the same (calcId, region, runType) under a `correlationId`, making grouping impossible. Parameter `runNumber` is `String` — consistent with all other methods in this class.

**Step 1: Write the repository tests**

```java
@SpringBootTest
@ActiveProfiles("local")
class CalculatorRunRepositoryDimensionalTest {

    @Autowired CalculatorRunRepository repository;

    @Test
    void findAllRunsByDateAndDimension_returnsAllRowsIncludingSplits() {
        // Insert: capital WMAP (1 run), WMDE (1 run), LDNL (2 attempts — rerun), 3 splits (correlationId)
        List<CalculatorRun> runs = repository.findAllRunsByDateAndDimension(
                "test-tenant", LocalDate.of(2026, 3, 6),
                CalculatorFrequency.DAILY, "1",
                List.of("capital"));

        assertThat(runs).hasSizeGreaterThanOrEqualTo(5);
        assertThat(runs).extracting(CalculatorRun::getCorrelationId)
                .filteredOn(Objects::nonNull).hasSize(3);
    }

    @Test
    void findAllRunsByDateAndDimension_returnsEmptyForUnknownCalculator() {
        List<CalculatorRun> runs = repository.findAllRunsByDateAndDimension(
                "test-tenant", LocalDate.of(2026, 3, 6),
                CalculatorFrequency.DAILY, "1",
                List.of("unknown-calc"));

        assertThat(runs).isEmpty();
    }
}
```

**Step 2: Add the method**

Add immediately after `findDashboardCalculatorRuns`:

```java
public List<CalculatorRun> findAllRunsByDateAndDimension(
        String tenantId,
        LocalDate reportingDate,
        CalculatorFrequency frequency,
        String runNumber,
        List<String> calculatorIds) {

    if (calculatorIds.isEmpty()) {
        return List.of();
    }

    String sql = """
            SELECT *
            FROM calculator_runs
            WHERE tenant_id      = :tenantId
              AND reporting_date = :reportingDate
              AND frequency      = :frequency
              AND run_number     = :runNumber
              AND calculator_id  IN (:calculatorIds)
            ORDER BY calculator_id,
                     COALESCE(correlation_id, ''),
                     COALESCE(region, ''),
                     COALESCE(run_type, ''),
                     created_at ASC
            """;

    MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("reportingDate", reportingDate)
            .addValue("frequency", frequency.name())
            .addValue("runNumber", runNumber)
            .addValue("calculatorIds", calculatorIds);

    return jdbcTemplate.query(sql, params, calculatorRunRowMapper);
}
```

**Step 3: Run tests**
```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorRunRepositoryDimensionalTest
```

**Step 4: Commit**
```bash
git add src/main/java/com/company/observability/repository/CalculatorRunRepository.java \
        src/test/java/com/company/observability/repository/CalculatorRunRepositoryDimensionalTest.java
git commit -m "feat: add findAllRunsByDateAndDimension — returns all rows, service handles deduplication"
```

---

### Task 5: Create `CalculatorStateService`

**File:** `src/main/java/com/company/observability/service/CalculatorStateService.java`

Two-phase grouping:
- **Phase 1** — collapses parallel splits (non-null `correlationId`) into one `RunEntry` using worst-status wins
- **Phase 2** — deduplicates sequential reruns (null `correlationId`) by (region, runType) → picks latest `createdAt`, sets `isRerun = true` when group size > 1

`String runNumber` is threaded straight through — DB column is `VARCHAR(10)`, so no type conversion at any boundary.

**Step 1: Write the service tests**

```java
@ExtendWith(MockitoExtension.class)
class CalculatorStateServiceTest {

    @Mock CalculatorRunRepository runRepository;
    @InjectMocks CalculatorStateService service;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 6);
    private static final CalculatorFrequency FREQ = CalculatorFrequency.DAILY;
    private static final Instant SLA_TIME = Instant.parse("2026-03-06T15:00:00Z");
    private static final Instant T_MINUS_3 = Instant.parse("2026-03-06T12:00:00Z");
    private static final Instant T_MINUS_2 = Instant.parse("2026-03-06T13:00:00Z");
    private static final Instant T_MINUS_1 = Instant.parse("2026-03-06T14:00:00Z");
    private static final Instant NOW       = Instant.parse("2026-03-06T14:45:00Z");

    @Test
    void returnsEmptyRunsForMissingCalculator() {
        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of());

        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> result =
                service.getState("t1", DATE, FREQ, "1", List.of("missing-calc"));

        assertThat(result).containsKey("missing-calc");
        assertThat(result.get("missing-calc").runs()).isEmpty();
    }

    @Test
    void splitGroupsCollapsedIntoOneEntry() {
        CalculatorRun s1 = buildRun("cap", "r-s1", RunStatus.SUCCESS, "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_1, SLA_TIME);
        CalculatorRun s2 = buildRun("cap", "r-s2", RunStatus.FAILED,  "WMAP", null, "1", "corr-1", T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun s3 = buildRun("cap", "r-s3", RunStatus.RUNNING, "WMAP", null, "1", "corr-1", T_MINUS_3, null,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(s1, s2, s3));

        var result = service.getState("t1", DATE, FREQ, "1", List.of("cap"));
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("RUNNING");   // worst-wins
        assertThat(entries.get(0).isRerun()).isFalse();              // splits ≠ rerun
    }

    @Test
    void standaloneRerunDetectedByMultipleAttemptsInSameDimension() {
        CalculatorRun attempt1 = buildRun("cap", "r-1", RunStatus.FAILED,   "LDNL", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME);
        CalculatorRun attempt2 = buildRun("cap", "r-2", RunStatus.SUCCESS,  "LDNL", null, "1", null, T_MINUS_1, NOW,       SLA_TIME);

        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(List.of(attempt1, attempt2));

        var result = service.getState("t1", DATE, FREQ, "1", List.of("cap"));
        var entries = result.get("cap").runs();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("SUCCESS");
        assertThat(entries.get(0).isRerun()).isTrue();
    }

    @Test
    void regionalRunsGroupedUnderOneCalculatorId() {
        List<CalculatorRun> dbRuns = List.of(
                buildRun("capital", "r-wmap", RunStatus.SUCCESS, "WMAP", null, "1", null, T_MINUS_3, T_MINUS_1, SLA_TIME),
                buildRun("capital", "r-wmde", RunStatus.SUCCESS, "WMDE", null, "1", null, T_MINUS_3, T_MINUS_2, SLA_TIME)
        );
        when(runRepository.findAllRunsByDateAndDimension(any(), eq(DATE), eq(FREQ), eq("1"), any()))
                .thenReturn(dbRuns);

        var result = service.getState("t1", DATE, FREQ, "1", List.of("capital"));
        var entry = result.get("capital");

        assertThat(entry.runs()).hasSize(2);
        assertThat(entry.runs()).extracting(r -> r.region())
                .containsExactlyInAnyOrder("WMAP", "WMDE");
    }

    // helper — runNumber is String; correlationId may be null
    private CalculatorRun buildRun(String calcId, String runId, RunStatus status,
                                    String region, String runType, String runNumber,
                                    String correlationId, Instant createdAt,
                                    Instant endTime, Instant slaTime) {
        CalculatorRun run = new CalculatorRun();
        run.setCalculatorId(calcId);
        run.setCalculatorName(calcId);
        run.setRunId(runId);
        run.setStatus(status);
        run.setRegion(region);
        run.setRunType(runType);
        run.setRunNumber(runNumber);
        run.setCorrelationId(correlationId);
        run.setCreatedAt(createdAt);
        run.setEndTime(endTime);
        run.setSlaTime(slaTime);
        run.setReportingDate(DATE);
        return run;
    }
}
```

**Step 2: Implement `CalculatorStateService.java`**

```java
package com.company.observability.service;

import com.company.observability.domain.CalculatorRun;
import com.company.observability.domain.enums.CalculatorFrequency;
import com.company.observability.domain.enums.RunStatus;
import com.company.observability.dto.response.CalculatorBatchRunsResponse;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.CalculatorEntry;
import com.company.observability.dto.response.CalculatorBatchRunsResponse.RunEntry;
import com.company.observability.repository.CalculatorRunRepository;
import com.company.observability.util.RunStatusClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CalculatorStateService {

    private final CalculatorRunRepository runRepository;

    @Value("${observability.dashboard.late-threshold-ms:3600000}")
    private long lateThresholdMs;

    // Lower index = worse status — same ordering as LogicalRunGrouper
    private static final List<RunStatus> STATUS_PRECEDENCE = List.of(
            RunStatus.RUNNING, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED, RunStatus.SUCCESS);

    public Map<String, CalculatorEntry> getState(
            String tenantId,
            LocalDate reportingDate,
            CalculatorFrequency frequency,
            String runNumber,
            List<String> calculatorIds) {

        Map<String, List<CalculatorRun>> runsByCalcId = runRepository
                .findAllRunsByDateAndDimension(tenantId, reportingDate, frequency, runNumber, calculatorIds)
                .stream()
                .collect(Collectors.groupingBy(CalculatorRun::getCalculatorId));

        return calculatorIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> buildEntry(id, runsByCalcId.getOrDefault(id, List.of()))
        ));
    }

    private CalculatorEntry buildEntry(String calculatorId, List<CalculatorRun> runs) {
        String name = runs.stream().map(CalculatorRun::getCalculatorName)
                .filter(Objects::nonNull).findFirst().orElse(calculatorId);

        // Phase 1: collapse correlated splits → one RunEntry each
        Map<String, List<CalculatorRun>> splitGroups = runs.stream()
                .filter(r -> r.getCorrelationId() != null)
                .collect(Collectors.groupingBy(CalculatorRun::getCorrelationId));

        List<RunEntry> splitEntries = splitGroups.values().stream()
                .map(this::collapseSplitGroup)
                .toList();

        // Phase 2: deduplicate standalone reruns by (region, runType) → latest wins
        List<RunEntry> standaloneEntries = runs.stream()
                .filter(r -> r.getCorrelationId() == null)
                .collect(Collectors.groupingBy(r ->
                        Objects.toString(r.getRegion(), "") + ":" + Objects.toString(r.getRunType(), "")))
                .values().stream()
                .map(group -> {
                    CalculatorRun latest = group.stream()
                            .max(Comparator.comparing(CalculatorRun::getCreatedAt))
                            .orElseThrow();
                    latest.setRerun(group.size() > 1);
                    return toRunEntry(latest);
                })
                .toList();

        List<RunEntry> allEntries = Stream.concat(splitEntries.stream(), standaloneEntries.stream()).toList();
        return new CalculatorEntry(calculatorId, name, allEntries);
    }

    private RunEntry collapseSplitGroup(List<CalculatorRun> splits) {
        CalculatorRun first = splits.stream()
                .min(Comparator.comparing(CalculatorRun::getCreatedAt))
                .orElseThrow();

        RunStatus worstStatus = splits.stream()
                .map(CalculatorRun::getStatus)
                .min(Comparator.comparingInt(STATUS_PRECEDENCE::indexOf))
                .orElse(RunStatus.SUCCESS);

        Instant startTime = splits.stream().map(CalculatorRun::getStartTime)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        Instant endTime = worstStatus == RunStatus.RUNNING ? null :
                splits.stream().map(CalculatorRun::getEndTime)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        Long durationMs = startTime != null && endTime != null
                ? endTime.toEpochMilli() - startTime.toEpochMilli() : null;

        boolean slaBreached = splits.stream().anyMatch(r -> Boolean.TRUE.equals(r.getSlaBreached()));
        String breachReason = splits.stream().map(CalculatorRun::getSlaBreachReason)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("; "));

        CalculatorRun rep = new CalculatorRun();
        rep.setRunId(first.getRunId());
        rep.setCalculatorId(first.getCalculatorId());
        rep.setCalculatorName(first.getCalculatorName());
        rep.setRegion(first.getRegion());
        rep.setRunType(first.getRunType());
        rep.setRunNumber(first.getRunNumber());
        rep.setStatus(worstStatus);
        rep.setStartTime(startTime);
        rep.setEndTime(endTime);
        rep.setDurationMs(durationMs);
        rep.setSlaBreached(slaBreached);
        rep.setSlaBreachReason(breachReason.isBlank() ? null : breachReason);
        rep.setSlaTime(first.getSlaTime());
        rep.setEstimatedStartTime(first.getEstimatedStartTime());
        rep.setEstimatedEndTime(first.getEstimatedEndTime());
        rep.setRerun(false);   // parallel splits ≠ sequential rerun

        return toRunEntry(rep);
    }

    private RunEntry toRunEntry(CalculatorRun run) {
        String slaStatus = RunStatusClassifier.classify(run, run.getSlaTime(), lateThresholdMs);

        return RunEntry.builder()
                .runId(run.getRunId())
                .region(run.getRegion())
                .runType(run.getRunType())
                .status(run.getStatus().name())
                .slaStatus(slaStatus)
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .estimatedStartTime(run.getEstimatedStartTime())
                .estimatedEndTime(run.getEstimatedEndTime())
                .sla(run.getSlaTime())
                .durationMs(run.getDurationMs())
                .slaBreached(run.isSlaBreached())
                .slaBreachReason(run.getSlaBreachReason())
                .isRerun(run.isRerun())
                .build();
    }
}
```

**Step 3: Run tests**
```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=CalculatorStateServiceTest
```

**Step 4: Commit**
```bash
git add src/main/java/com/company/observability/service/CalculatorStateService.java \
        src/test/java/com/company/observability/service/CalculatorStateServiceTest.java
git commit -m "feat: add CalculatorStateService — two-phase split collapse + rerun deduplication"
```

---

### Task 6: Add `GET /batch/runs` to `RunQueryController`

**File:** `src/main/java/com/company/observability/controller/RunQueryController.java`

**Step 1: Write the controller tests**

```java
@Test
void batchRuns_returns200WithMapKeyedByCalculatorId() throws Exception {
    var entry = new CalculatorBatchRunsResponse.CalculatorEntry("capital", "Capital", List.of());
    when(calculatorStateService.getState(eq("t1"), eq(LocalDate.of(2026, 3, 6)),
            eq(CalculatorFrequency.DAILY), eq("1"), eq(List.of("capital"))))
            .thenReturn(Map.of("capital", entry));

    mockMvc.perform(get("/api/v1/calculators/batch/runs")
                    .param("reporting_date", "2026-03-06")
                    .param("frequency", "DAILY")
                    .param("run_number", "1")
                    .param("keys", "capital")
                    .header("X-Tenant-Id", "t1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reportingDate").value("2026-03-06"))
            .andExpect(jsonPath("$.runNumber").value("1"))
            .andExpect(jsonPath("$.calculators.capital.calculatorId").value("capital"))
            .andExpect(jsonPath("$.calculators.capital.runs").isArray());
}

@Test
void batchRuns_returns400WhenReportingDateMissing() throws Exception {
    mockMvc.perform(get("/api/v1/calculators/batch/runs")
                    .param("keys", "capital")
                    .header("X-Tenant-Id", "t1"))
            .andExpect(status().isBadRequest());
}

@Test
void batchRuns_pipeSeparatedKeysParsedToList() throws Exception {
    when(calculatorStateService.getState(any(), any(), any(), anyString(),
            eq(List.of("capital", "modelled-exposure", "portfolio"))))
            .thenReturn(Map.of());

    mockMvc.perform(get("/api/v1/calculators/batch/runs")
                    .param("reporting_date", "2026-03-06")
                    .param("keys", "capital|modelled-exposure|portfolio")
                    .header("X-Tenant-Id", "t1"))
            .andExpect(status().isOk());

    verify(calculatorStateService).getState(any(), any(), any(), anyString(),
            eq(List.of("capital", "modelled-exposure", "portfolio")));
}
```

**Step 2: Add field injection and endpoint**

Add alongside existing field injections:
```java
private final CalculatorStateService calculatorStateService;
```

Add after `getBatchStatus`:

```java
@GetMapping("/batch/runs")
@Operation(
        summary = "Batch calculator runs by reporting date",
        description = "Returns all dimensional run instances per logical calculator for a specific reporting date. " +
                "Regional calculators return one RunEntry per region; typed calculators return one per runType. " +
                "Empty runs list = no run found. isRerun=true = a re-trigger was fired for that dimension."
)
public ResponseEntity<CalculatorBatchRunsResponse> getBatchRuns(
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestParam("reporting_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
        @RequestParam(defaultValue = "DAILY") String frequency,
        @RequestParam(value = "run_number", defaultValue = "1")
        @Pattern(regexp = "^[12]$", message = "run_number must be 1 or 2") String runNumber,
        @RequestParam @NotBlank String keys) {

    List<String> calculatorIds = Arrays.stream(keys.split("\\|"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    if (calculatorIds.isEmpty()) {
        throw new IllegalArgumentException("keys must contain at least one non-blank calculator ID");
    }

    CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        Map<String, CalculatorBatchRunsResponse.CalculatorEntry> calculators =
                calculatorStateService.getState(tenantId, reportingDate, freq, runNumber, calculatorIds);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePrivate())
                .body(new CalculatorBatchRunsResponse(
                        reportingDate, freq.name(), runNumber, Instant.now(), calculators));
    } finally {
        sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                "endpoint", "/calculators/batch/runs"));
    }
}
```

**Step 3: Run tests**
```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=RunQueryControllerTest
```

**Step 4: Smoke-test**
```bash
curl -s -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/calculators/batch/runs?reporting_date=2026-03-06&frequency=DAILY&run_number=1&keys=capital|modelled-exposure|portfolio" \
  | jq '{capitalRegions: .calculators.capital.runs | map(.region), exposureTypes: .calculators["modelled-exposure"].runs | map(.runType)}'
```

**Step 5: Commit**
```bash
git add src/main/java/com/company/observability/controller/RunQueryController.java \
        src/test/java/com/company/observability/controller/RunQueryControllerTest.java
git commit -m "feat: add GET /api/v1/calculators/batch/runs — point-in-time dimensional run state"
```

---

### Task 7: Add `GET /executions` to `AnalyticsController`

**`/executions` is a separate endpoint, not an alias for `/run-performance`.** It calls a new `getRunExecutions()` service method that bypasses `LogicalRunGrouper` — each physical run appears as an independent `RunDataPoint`. `subRunIds` is always `null`. Includes `runNumber` and `expectedDurationMs` (threaded in Task 2).

**Step 1: Add `getRunExecutions()` to `AnalyticsService`**

**File:** `src/main/java/com/company/observability/service/AnalyticsService.java`

```java
public RunPerformanceData getRunExecutions(
        String calculatorId, String tenantId, int days, CalculatorFrequency frequency) {

    List<RunWithSlaStatus> rawRuns = runRepository
            .findRunsWithSlaStatus(calculatorId, tenantId, days, frequency);

    List<RunPerformanceData.RunDataPoint> dataPoints = rawRuns.stream()
            .map(run -> {
                String slaStatus = classifySlaStatusForRun(run);
                Long wallClockMs = run.status() != RunStatus.RUNNING ? run.durationMs() : null;
                return new RunPerformanceData.RunDataPoint(
                        run.runId(),
                        run.reportingDate(),
                        run.startTime(),
                        run.status() != RunStatus.RUNNING ? run.endTime() : null,
                        wallClockMs,
                        run.status().name(),
                        run.slaBreached(),
                        slaStatus,
                        null,                        // subRunIds — no grouping
                        run.estimatedStartTime(),
                        run.slaTime(),
                        run.runNumber(),              // threaded in Task 2
                        run.expectedDurationMs()      // threaded in Task 2
                );
            })
            .toList();

    return buildRunPerformanceDataEnvelope(calculatorId, rawRuns, dataPoints, days, frequency);
}
```

**Step 2: Add the endpoint to `AnalyticsController`**

**File:** `src/main/java/com/company/observability/controller/AnalyticsController.java`

Add as a new method — do NOT modify the existing `getRunPerformanceData` method or its mapping:

```java
@GetMapping("/calculators/{calculatorId}/executions")
@Operation(
        summary = "Run execution history (raw)",
        description = "Returns all physical runs over the lookback window as independent entries. " +
                "Split runs sharing a correlationId appear as separate rows — no grouping. " +
                "Each entry includes durationMs (actual) and expectedDurationMs (configured) for comparison. " +
                "For grouped/logical view, use GET /run-performance."
)
public ResponseEntity<RunPerformanceData> getRunExecutions(
        @PathVariable String calculatorId,
        @RequestHeader("X-Tenant-Id") String tenantId,
        @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
        @RequestParam(defaultValue = "DAILY") String frequency) {

    CalculatorFrequency freq = CalculatorFrequency.fromStrict(frequency);

    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        RunPerformanceData response = analyticsService
                .getRunExecutions(calculatorId, tenantId, days, freq);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePrivate())
                .body(response);
    } finally {
        sample.stop(meterRegistry.timer(ObservabilityConstants.API_ANALYTICS_DURATION,
                "endpoint", "/executions"));
    }
}
```

**Step 3: Run tests**
```bash
SPRING_PROFILES_ACTIVE=local mvn test -Dtest=AnalyticsControllerTest,AnalyticsServiceTest
```

**Step 4: Smoke-test — both paths respond; splits appear as separate rows**
```bash
# /executions — splits appear independently
curl -s -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/calculators/portfolio-calc/executions?days=30" \
  | jq '.runs[] | {runId, durationMs, expectedDurationMs, runNumber, subRunIds}'

# /run-performance — same runs but splits collapsed, subRunIds populated
curl -s -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/calculators/portfolio-calc/run-performance?days=30" \
  | jq '.runs[] | {runId, durationMs, runNumber, subRunIds}'
```

**Step 5: Commit**
```bash
git add src/main/java/com/company/observability/service/AnalyticsService.java \
        src/main/java/com/company/observability/controller/AnalyticsController.java \
        src/test/java/com/company/observability/controller/AnalyticsControllerTest.java
git commit -m "feat: add GET /executions — raw run history with actual vs expected duration per run"
```

---

## Final Verification

```bash
# 1. Full suite passes
SPRING_PROFILES_ACTIVE=local mvn clean test

# 2. Original projection endpoints untouched
curl -s -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/projections/calculator-dashboard?reportingDate=2026-03-06&frequency=DAILY&runNumber=1" \
  | jq '.sections | length'

# 3. /run-performance unchanged (alias NOT added — separate endpoint only)
curl -s -u admin:admin -H "X-Tenant-Id: tenant1" \
  "http://localhost:8080/api/v1/analytics/calculators/portfolio-calc/run-performance?days=30" | jq '.calculatorId'

# 4. Swagger shows both new endpoints
# Open: http://localhost:8080/swagger-ui.html
```

---

## UI Consumption (Astro SSR reference)

```typescript
const SECTION_CONFIG = {
  REGIONAL: {
    keys: ['capital'],
    displaySections: ['Eligible total loss absorbing capacity', 'Credit risk migration', ...]
  },
  RISK_GOVERNED: { keys: ['modelled-exposure', 'gemini-hedge'] },
  PORTFOLIO:     { keys: ['portfolio', 'group-portfolio'] },
  CONSOLIDATION: { keys: ['consolidation'] }
}

const allKeys = Object.values(SECTION_CONFIG).flatMap(s => s.keys)
const { calculators } = await fetchBatchRuns({ reportingDate, frequency, runNumber: selectedRun, keys: allKeys })

// Capital section: runs[*].region → column headers; run.isRerun → "LDNL*"
const capitalEntry = calculators['capital']

// Performance card: fetch /executions for actual vs expected comparison
const executions = await fetchExecutions({ calculatorId, days: 30 })
// Per run: executions.runs[i].durationMs (actual) vs executions.runs[i].expectedDurationMs (configured)
// Envelope: executions.meanDurationMs (30-day rolling average for trend baseline)
```
