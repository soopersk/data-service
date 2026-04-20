# Calculator Dashboard — Unified API Design Plan

**Date:** 2026-04-18
**Status:** Implemented — 2026-04-18
**Endpoint:** `GET /api/v1/analytics/projections/calculator-dashboard`

---

## Context

The existing backend serves a **Regional Batch Status** endpoint that powers the "Regional" section of the Capital Calculation Insight dashboard. The UI now needs to display **5 collapsible accordion sections** — Regional, Portfolio, Group Portfolio, Risk Governed, Consolidation — all filtered by reporting date, frequency (Daily/Monthly), and run number (Run 1/Run 2). Today the backend only supports the Regional section. This plan adds a single unified dashboard endpoint that returns the full state for all sections in one response.

### Clarified Requirements (from user)

| Question | Answer |
|----------|--------|
| Portfolio structure | **1 calculator run**, displayed as 5 CAP label rows (CAP 8, 10, 12, 13, 14) in the UI — all show the same status/timing |
| Model Exposure display | **1 row with 3 sub-status buttons** (OTC, ETD, SFT) — like Regional's 10 region buttons |
| Run number in data | **Already in JSONB** (`run_parameters->>'run_number'`), backend just needs to filter on it |
| Section structure | Group Portfolio = 1 calc, Consolidation = 1 calc, Risk Governed = 2 (Gemini Hedge + Modelled Exposure) |

### Dashboard Structure

```
[Daily | Monthly]  [Run 1 | Run 2]  [17-Apr-2026 📅]

○ Regional          ∨    ← 10 region buttons (existing)
○ Portfolio          ∨    ← 1 run, 5 CAP display labels, each row shows same status
○ Group portfolio    ∨    ← 1 run, 1 row
○ Risk governed      ∨    ← 2 sub-calcs:
                              Gemini Hedge: 1 row
                              Modelled Exposure: 1 row, 3 sub-buttons (OTC, ETD, SFT)
○ Consolidation      ∨    ← 1 run, 1 row

Dependency chain: Regional → Portfolio → Group Portfolio → Consolidation
                  Risk Governed runs in parallel (no strict dependency)
```

---

## 1. New Endpoint

```
GET /api/v1/analytics/projections/calculator-dashboard
    ?reportingDate=2026-04-17
    &frequency=DAILY
    &runNumber=1
    X-Tenant-Id: <header>
```

| Param | Type | Required | Notes |
|-------|------|----------|-------|
| `reportingDate` | LocalDate (ISO) | Yes | Business date |
| `frequency` | String | Yes | DAILY / MONTHLY / D / M |
| `runNumber` | int | Yes | 1 or 2 |
| `X-Tenant-Id` | Header | Yes | Tenant |

The existing `GET /regional-batch-status` endpoint remains unchanged (backward compatible).

---

## 2. Response DTO — `CalculatorDashboardResponse.java`

**File:** `src/main/java/com/company/observability/dto/response/CalculatorDashboardResponse.java`

```java
public record CalculatorDashboardResponse(
    LocalDate reportingDate,
    String reportingDateFormatted,       // "Fri 17 Apr 2026"
    String frequency,                    // "DAILY"
    int runNumber,                       // 1 or 2
    List<DashboardSection> sections      // ordered by displayOrder
) {

    public record DashboardSection(
        String sectionKey,               // "REGIONAL", "PORTFOLIO", etc.
        String displayName,              // "Regional", "Portfolio", etc.
        int displayOrder,
        SectionSla sla,
        DependencyStatus dependency,     // null for REGIONAL / RISK_GOVERNED
        SectionSummary summary,
        List<CalculatorEntry> calculators // 1 for most; 10 RegionEntry for Regional; 2 for Risk Governed
    ) {}

    public record SectionSla(
        String deadlineTimeCet,          // "17:45"
        BigDecimal deadlineHourCet,      // 17.75
        boolean breached
    ) {}

    public record DependencyStatus(
        String dependsOnSection,         // "REGIONAL"
        boolean dependencyMet,           // true if upstream all terminal
        String statusLabel               // "Waiting for Regional" / "Ready" / "Blocked"
    ) {}

    public record SectionSummary(
        int totalCalculators,
        int completedCount,
        int runningCount,
        int failedCount,
        int notStartedCount,
        TimeReference estimatedStart,
        TimeReference estimatedEnd
    ) {}

    public record CalculatorEntry(
        String calculatorId,
        String calculatorName,           // "Gemini Hedge", "Portfolio", etc.
        String runId,                    // null if NOT_STARTED
        String status,                   // ON_TIME, DELAYED, FAILED, RUNNING, NOT_STARTED
        String startTimeCet,             // "10:30 CET"
        String endTimeCet,
        BigDecimal startHourCet,
        BigDecimal endHourCet,
        Long durationMs,
        String durationFormatted,
        boolean slaBreached,
        List<SubRunStatus> subRuns,      // null for most; 3 items for Modelled Exposure (OTC/ETD/SFT)
        List<LastRunIndicator> lastRuns  // last 5 historical run statuses
    ) {}

    // For Modelled Exposure's OTC/ETD/SFT sub-buttons
    public record SubRunStatus(
        String subRunKey,                // "OTC", "ETD", "SFT"
        String runId,
        String status,                   // ON_TIME, DELAYED, FAILED, RUNNING, NOT_STARTED
        String startTimeCet,
        String endTimeCet,
        Long durationMs,
        String durationFormatted,
        boolean slaBreached
    ) {}

    // Colored dot in "Last runs: ○○○○○"
    public record LastRunIndicator(
        LocalDate reportingDate,
        String status                    // ON_TIME, DELAYED, FAILED
    ) {}

    // Reuse pattern from RegionalBatchStatusResponse
    public record TimeReference(
        String timeCet,
        BigDecimal hourCet,
        String basedOn,
        boolean actual
    ) {}
}
```

### Regional section special handling

For the Regional section, `calculators` contains **10 entries** (one per region). Each entry uses:
- `calculatorId` = the region's `calculator_id` from DB
- `calculatorName` = region code ("WMAP", "WMDE", etc.)
- `subRuns` = null (not applicable)

This reuses the existing `RegionalBatchService` logic.

### Portfolio section special handling

The API returns **1 `CalculatorEntry`** for Portfolio. The config includes 5 display labels (CAP 8, 10, 12, 13, 14) that the frontend uses to render 5 identical rows. The backend doesn't duplicate data — it returns 1 entry with a `displayLabels` list in the section config (see Section 3).

### Model Exposure special handling

Modelled Exposure is **1 `CalculatorEntry`** with `subRuns` containing 3 items: `{OTC, ETD, SFT}`. The overall entry status = worst status across the 3 sub-runs.

---

## 3. Configuration — `DashboardProperties.java`

**File:** `src/main/java/com/company/observability/config/DashboardProperties.java`

```java
@Component
@ConfigurationProperties(prefix = "observability.dashboard")
@Getter @Setter
public class DashboardProperties {
    private List<SectionConfig> sections;

    @Getter @Setter
    public static class SectionConfig {
        private String sectionKey;        // REGIONAL, PORTFOLIO, GROUP_PORTFOLIO, etc.
        private String displayName;
        private int displayOrder;
        private LocalTime slaTimeCet;
        private String dependsOn;         // null or parent section key
        private List<CalculatorConfig> calculators;
        private List<String> displayLabels; // for Portfolio: ["CAP 8 - Securitization", ...]
    }

    @Getter @Setter
    public static class CalculatorConfig {
        private String calculatorId;
        private String displayName;
        private boolean hasSubRuns;       // true for Modelled Exposure
        private List<SubRunConfig> subRuns;
    }

    @Getter @Setter
    public static class SubRunConfig {
        private String subRunKey;         // "OTC", "ETD", "SFT"
        private String calculatorId;      // each sub-run's calculator_id in DB
    }
}
```

**YAML** (in `application.yml` under `observability:`):

```yaml
observability:
  dashboard:
    sections:
      - section-key: REGIONAL
        display-name: Regional
        display-order: 1
        sla-time-cet: "17:45"
        calculators: []  # Regional uses existing region-order config

      - section-key: PORTFOLIO
        display-name: Portfolio
        display-order: 2
        sla-time-cet: "18:30"
        depends-on: REGIONAL
        display-labels:
          - "CAP 8 - Securitization"
          - "CAP 10 - Counterparty credit risk on derivatives and SFTs"
          - "CAP 12 - Leverage ratio denominator"
          - "CAP 13 - Credit valuation adjustments"
          - "CAP 14 - Business indicator component"
        calculators:
          - calculator-id: portfolio-calc
            display-name: Portfolio

      - section-key: GROUP_PORTFOLIO
        display-name: Group portfolio
        display-order: 3
        sla-time-cet: "19:00"
        depends-on: PORTFOLIO
        calculators:
          - calculator-id: group-portfolio-calc
            display-name: Group portfolio

      - section-key: RISK_GOVERNED
        display-name: Risk governed
        display-order: 4
        sla-time-cet: "20:30"
        calculators:
          - calculator-id: gemini-hedge-calc
            display-name: Gemini Hedge
          - calculator-id: modelled-exposure-calc
            display-name: Modelled Exposure
            has-sub-runs: true
            sub-runs:
              - sub-run-key: OTC
                calculator-id: model-exposure-otc
              - sub-run-key: ETD
                calculator-id: model-exposure-etd
              - sub-run-key: SFT
                calculator-id: model-exposure-sft

      - section-key: CONSOLIDATION
        display-name: Consolidation
        display-order: 5
        sla-time-cet: "21:00"
        depends-on: GROUP_PORTFOLIO
        calculators:
          - calculator-id: consolidation-calc
            display-name: Consolidation
```

> **Note:** Calculator IDs are placeholders. Real IDs will come from the Airflow team.

---

## 4. Data Identification Strategy

| Section | How identified in DB | Query approach |
|---------|---------------------|----------------|
| **Regional** | `run_parameters->>'run_type' = 'BATCH'` + `region IS NOT NULL` (existing) | Reuse `findRegionalBatchRuns`, add `run_number` filter |
| **Portfolio** | `calculator_id = 'portfolio-calc'` from config | New `findDashboardCalculatorRuns` by calculator_id list |
| **Group Portfolio** | `calculator_id = 'group-portfolio-calc'` | Same batch query |
| **Risk Governed** | `calculator_id IN ('gemini-hedge-calc', 'model-exposure-otc', 'model-exposure-etd', 'model-exposure-sft')` | Same batch query |
| **Consolidation** | `calculator_id = 'consolidation-calc'` | Same batch query |

**Run1 vs Run2**: Filter by `run_parameters->>'run_number' = :runNumber` on all queries. The field already exists in JSONB data from Airflow.

---

## 5. Repository Layer — New Queries

**File:** `src/main/java/com/company/observability/repository/CalculatorRunRepository.java`

### 5a. `findDashboardCalculatorRuns` (new)

Fetches latest run per calculator_id for all non-regional sections in **one query**:

```sql
SELECT * FROM (
    SELECT *, ROW_NUMBER() OVER (
        PARTITION BY calculator_id ORDER BY start_time DESC NULLS LAST
    ) as rn
    FROM calculator_runs
    WHERE tenant_id = :tenantId
      AND reporting_date = :reportingDate
      AND frequency = :frequency
      AND calculator_id IN (:calculatorIds)
      AND run_parameters->>'run_number' = :runNumber
) ranked WHERE rn = 1
```

Returns `Map<String, CalculatorRun>` keyed by `calculator_id`. Single-partition scan (filtered by exact `reporting_date`).

### 5b. `findDashboardCalculatorHistory` (new)

Fetches last 5 days' run statuses for "Last runs" dots:

```sql
SELECT calculator_id, reporting_date, status FROM (
    SELECT calculator_id, reporting_date, status,
           ROW_NUMBER() OVER (
               PARTITION BY calculator_id, reporting_date
               ORDER BY start_time DESC NULLS LAST
           ) as rn
    FROM calculator_runs
    WHERE tenant_id = :tenantId
      AND reporting_date < :reportingDate
      AND reporting_date >= :fromDate
      AND frequency = :frequency
      AND calculator_id IN (:calculatorIds)
      AND run_parameters->>'run_number' = :runNumber
) ranked WHERE rn = 1
ORDER BY calculator_id, reporting_date DESC
```

Returns lightweight `List<HistoricalRunStatus>` records (calculator_id, reporting_date, status). Scans ~5 partitions.

### 5c. Modify existing `findRegionalBatchRuns` (existing)

Add optional `runNumber` filter:

```sql
-- Add to WHERE clause:
AND run_parameters->>'run_number' = :runNumber
```

Overload the method: `findRegionalBatchRuns(tenantId, reportingDate, runNumber)`. The existing no-arg version calls the new one with `null` for backward compat.

### 5d. Flyway migration `V13__dashboard_indexes.sql` (new)

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS calculator_runs_run_number_idx
    ON calculator_runs ((run_parameters->>'run_number'), reporting_date, tenant_id);
```

---

## 6. Service Layer

### 6a. `DashboardService.java` (new)

**File:** `src/main/java/com/company/observability/service/DashboardService.java`

Core orchestrator. Responsibilities:
- Collect all non-regional calculator IDs from config
- Call `findDashboardCalculatorRuns` (1 query for all non-regional)
- Call `RegionalBatchService.getRegionalBatchStatus` (reuse existing, add runNumber)
- Call `findDashboardCalculatorHistory` (1 query for last-runs dots)
- Build each section: compute status, SLA breach, dependency resolution
- Return domain-level `DashboardResult` (no formatting)

```
buildDashboard(tenantId, reportingDate, frequency, runNumber)
  ├── collectAllCalculatorIds(config)           → List<String>
  ├── repo.findDashboardCalculatorRuns(...)      → Map<String, CalculatorRun>   [1 DB query]
  ├── repo.findDashboardCalculatorHistory(...)   → Map<String, List<HistoricalRunStatus>>  [1 DB query]
  ├── regionalBatchService.getRegionalBatchStatus(tenantId, reportingDate, runNumber)  [reuse, 1-2 DB queries]
  └── for each section in config order:
        buildSection(config, runs, history, upstreamSectionResult)
```

**Dependency resolution**: Sections are built in `displayOrder`. Each section checks its `dependsOn` upstream:
- If upstream has `runningCount > 0 || notStartedCount > 0` → `dependencyMet = false`, label = "Waiting for {upstream}"
- If upstream has `failedCount > 0` → `dependencyMet = false`, label = "Blocked by {upstream} failure"
- Otherwise → `dependencyMet = true`, label = "Ready"

**Status computation** (reuse same logic as `RegionalBatchService`):
- `RUNNING` if `RunStatus.RUNNING`
- `FAILED` if `RunStatus.FAILED`
- `DELAYED` if completed after SLA deadline
- `ON_TIME` if completed before SLA deadline
- `NOT_STARTED` if no run found

**Model Exposure aggregation**: Query returns 3 runs (OTC/ETD/SFT by `calculator_id`). Build 3 `SubRunStatus` entries. The parent `CalculatorEntry.status` = worst of the 3 (FAILED > RUNNING > DELAYED > NOT_STARTED > ON_TIME).

**Inner records** (domain-level, not formatted):

```java
public record DashboardResult(
    LocalDate reportingDate, CalculatorFrequency frequency, int runNumber,
    List<SectionResult> sections
) {}

public record SectionResult(
    String sectionKey, String displayName, int displayOrder,
    Instant slaDeadline, boolean slaBreached,
    DependencyResult dependency,
    int totalCalcs, int completedCount, int runningCount, int failedCount, int notStartedCount,
    EstimatedTime estimatedStart, EstimatedTime estimatedEnd,
    List<CalculatorEntryResult> entries
) {}

public record DependencyResult(String dependsOn, boolean met, String label) {}

public record CalculatorEntryResult(
    String calculatorId, String calculatorName, CalculatorRun run,
    String status, boolean slaBreached,
    List<SubRunResult> subRuns,
    List<HistoricalRunStatus> lastRuns
) {}

public record SubRunResult(String subRunKey, CalculatorRun run, String status, boolean slaBreached) {}
public record HistoricalRunStatus(String calculatorId, LocalDate reportingDate, String status) {}
```

### 6b. Modify `RegionalBatchService.java` (existing)

- Add overloaded method: `getRegionalBatchStatus(String tenantId, LocalDate reportingDate, String runNumber)`
- Pass `runNumber` through to `findRegionalBatchRuns`
- Original method delegates with `runNumber = null` (backward compat)

### 6c. Extend `ProjectionService.java` (existing)

Add `getCalculatorDashboard()` method:
- Check dashboard cache → return on hit
- Call `DashboardService.buildDashboard()` → get domain result
- Call `toDashboardResponse()` → format CET times, durations, etc.
- Write to dashboard cache with smart TTL
- Return formatted response

The `toDashboardResponse()` method follows the same pattern as existing `toRegionalBatchResponse()` — format each field using `TimeUtils`.

---

## 7. Cache Layer — `DashboardCacheService.java` (new)

**File:** `src/main/java/com/company/observability/cache/DashboardCacheService.java`

Mirrors `RegionalBatchCacheService` pattern. Two tiers:

| Tier | Redis Key | TTL | Content |
|------|-----------|-----|---------|
| History | `obs:analytics:dashboard:history:{tenantId}:{reportingDate}:{frequency}:{runNumber}` | 24h | Historical run data for last-runs dots |
| Status | `obs:analytics:dashboard:status:{tenantId}:{reportingDate}:{frequency}:{runNumber}` | Smart | Full `CalculatorDashboardResponse` |

**Smart TTL** (scans all sections' summaries):

| State | TTL | Condition |
|-------|-----|-----------|
| ALL_TERMINAL_CLEAN | 4 hours | Every section: 0 running, 0 not-started, 0 failed |
| ALL_TERMINAL_WITH_FAILURES | 5 minutes | Every section terminal but ≥1 failed anywhere |
| ANY_RUNNING | 30 seconds | Any section has ≥1 running |
| ALL_NOT_STARTED | 60 seconds | No runs found yet across all sections |

All Redis exceptions swallowed with `log.warn` (best-effort, same as existing).

---

## 8. Controller — `ProjectionController.java` (modify)

Add new endpoint method alongside existing ones:

```java
@GetMapping("/calculator-dashboard")
@Operation(summary = "Unified calculator dashboard", ...)
public ResponseEntity<CalculatorDashboardResponse> getCalculatorDashboard(
    @RequestHeader("X-Tenant-Id") String tenantId,
    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportingDate,
    @RequestParam String frequency,
    @RequestParam @Min(1) @Max(2) int runNumber
) { ... }
```

With Micrometer timer, 30s `Cache-Control`, same pattern as existing endpoints.

---

## 9. DB Queries Summary (per request)

| Query | Partitions scanned | When |
|-------|--------------------|------|
| `findRegionalBatchRuns` | 1 | Always |
| `findRegionalBatchHistory` | 7 | Only on cache miss + no actual override |
| `findDashboardCalculatorRuns` | 1 | Always (cache miss) |
| `findDashboardCalculatorHistory` | 5 | Only on cache miss |

**Worst case (cold, no cache):** 4 DB queries, 14 partitions scanned.
**Typical (after first request):** 0 queries (status cache hit) or 2 queries (status miss, history cached).

---

## 10. Files to Create / Modify

### New files
| # | File | Purpose |
|---|------|---------|
| 1 | `src/main/java/.../config/DashboardProperties.java` | Section/calculator YAML config |
| 2 | `src/main/java/.../dto/response/CalculatorDashboardResponse.java` | Response DTO record |
| 3 | `src/main/java/.../service/DashboardService.java` | Core orchestrator + domain records |
| 4 | `src/main/java/.../cache/DashboardCacheService.java` | Two-tier Redis cache |
| 5 | `src/main/resources/db/migration/V13__dashboard_indexes.sql` | Expression index on `run_number` |

### Modified files
| # | File | Change |
|---|------|--------|
| 6 | `src/main/java/.../controller/ProjectionController.java` | Add `getCalculatorDashboard()` endpoint |
| 7 | `src/main/java/.../service/ProjectionService.java` | Add `getCalculatorDashboard()` + `toDashboardResponse()` |
| 8 | `src/main/java/.../service/RegionalBatchService.java` | Overload with `runNumber` param |
| 9 | `src/main/java/.../repository/CalculatorRunRepository.java` | Add 2 new methods, modify `findRegionalBatchRuns` |
| 10 | `src/main/resources/application.yml` | Add `observability.dashboard.sections` config |

### Test files (new)
| # | File |
|---|------|
| 11 | `src/test/java/.../service/DashboardServiceTest.java` |
| 12 | `src/test/java/.../cache/DashboardCacheServiceTest.java` |
| 13 | `src/test/java/.../controller/ProjectionControllerDashboardTest.java` |

---

## 11. Implementation Phases

### Phase 1: Foundation
1. Create `V13__dashboard_indexes.sql` — expression index
2. Create `DashboardProperties.java` — config model
3. Add YAML config to `application.yml`
4. Create `CalculatorDashboardResponse.java` — response DTO

### Phase 2: Repository
5. Add `findDashboardCalculatorRuns()` to `CalculatorRunRepository`
6. Add `findDashboardCalculatorHistory()` to `CalculatorRunRepository`
7. Modify `findRegionalBatchRuns()` — add `runNumber` param
8. Modify `findRegionalBatchHistory()` — add `runNumber` param

### Phase 3: Service
9. Modify `RegionalBatchService` — overload with `runNumber`
10. Create `DashboardService` — orchestrator with section building, dependency resolution, status computation

### Phase 4: Cache
11. Create `DashboardCacheService` — two-tier cache with smart TTL

### Phase 5: Projection + Controller
12. Extend `ProjectionService` — `getCalculatorDashboard()` + formatting
13. Extend `ProjectionController` — new endpoint

### Phase 6: Tests
14. `DashboardServiceTest` — dependency chain, status computation, Model Exposure aggregation
15. `DashboardCacheServiceTest` — TTL logic, get/put
16. Controller test — endpoint integration

---

## 12. Verification Plan

1. `mvn clean test` — all existing tests pass (no regressions)
2. `docker compose up -d` + `SPRING_PROFILES_ACTIVE=local mvn spring-boot:run`
3. Hit `GET /api/v1/analytics/projections/calculator-dashboard?reportingDate=2026-04-17&frequency=DAILY&runNumber=1` — verify response shape
4. Verify existing `GET /regional-batch-status` still works unchanged
5. Verify Redis cache keys are created with correct TTLs via `redis-cli KEYS "obs:analytics:dashboard:*"` + `TTL`
6. Swagger UI at `/swagger-ui.html` — verify new endpoint documented

---

## 13. UI Recommendations

The current accordion layout works well and should be kept. Specific suggestions for the frontend team:

1. **Section header status icon**: Circle reflects worst status across all calculators in the section (green=all on-time, amber=delayed, red=failed, gray=idle, pulsing=running)
2. **Dependency indicator**: When `dependency.dependencyMet = false`, show the `statusLabel` ("Waiting for Regional") as muted text in the section header, and gray out the section's expand area
3. **Portfolio display**: Frontend receives 1 `CalculatorEntry` + `displayLabels` list from config → render 5 identical rows, each labeled with a CAP name
4. **Model Exposure sub-buttons**: Render 3 colored status circles (OTC/ETD/SFT) inline within the single "Modelled Exposure" row, with tooltip showing each sub-run's timing
5. **Last runs dots**: Use `lastRuns[]` array to color 5 dots — green/amber/red/gray based on status

---

## 14. UI Visual Mockups

### Full Dashboard — Mixed States (Run 1, Daily, active day)

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                      │
│   ┌──────────┬──────────┐    ┌────────┬────────┐    ┌──────────────────────┐         │
│   │▓ Daily   │  Monthly │    │▓ Run 1 │  Run 2 │    │  17-Apr-2026    📅  │         │
│   └──────────┴──────────┘    └────────┴────────┘    └──────────────────────┘         │
│                                                                                      │
│   Capital calculation insight                                          Expand all    │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│   🟢 Regional                  SLA: 17:45 CET                                  ∧   │
│   ┌──────────────────────────────────────────────────────────────────────────────┐   │
│   │                                                                              │   │
│   │  Est. start: 17 Apr at 04:15 CET (actual)    Est. end: ~17:30 CET (est.)   │   │
│   │                                                                              │   │
│   │  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐  │   │
│   │  │ WMAP │ │ WMDE │ │ ASIA │ │ WMUS │ │ AUNZ │ │ WMCH │ │ ZURI │ │ LDNL │  │   │
│   │  │  🟢  │ │  🟡  │ │  🟢  │ │  🟢  │ │  🔵  │ │  🔴  │ │  🟢  │ │  🟢  │  │   │
│   │  └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘ └──────┘  │   │
│   │  ┌──────┐ ┌──────┐                                                          │   │
│   │  │ AMER │ │ EURO │    8/10 complete · 1 running · 1 failed                  │   │
│   │  │  🟢  │ │  ⚪  │    SLA breached: WMDE                                    │   │
│   │  └──────┘ └──────┘                                                          │   │
│   │                                                                              │   │
│   │  ┌─── Tooltip (hover on WMDE) ──────────────────────────┐                   │   │
│   │  │  Region:    WMDE                                      │                   │   │
│   │  │  Status:    DELAYED                                   │                   │   │
│   │  │  Start:     06:30 CET                                 │                   │   │
│   │  │  End:       18:10 CET                                 │                   │   │
│   │  │  Duration:  11hrs 40mins                              │                   │   │
│   │  │  Run day:   Thu 17 Apr 2026                           │                   │   │
│   │  │  SLA:       ⚠ Breached (ended after 17:45 CET)       │                   │   │
│   │  └───────────────────────────────────────────────────────┘                   │   │
│   └──────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                      │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│   ⚪ Portfolio               SLA: 18:30 CET · ⏳ Waiting for Regional          ∧   │
│   ┌──────────────────────────────────────────────────────────────────────────────┐   │
│   │                                                       ░░░ grayed out ░░░     │   │
│   │  CAP 8 - Securitization                                                      │   │
│   │  (○ Idle) · Est. start: 18 Apr at 10:30:00 · Est. end: 18 Apr at 11:30:00   │   │
│   │                                                  Last runs: ⚪ ⚪ ⚪ ⚪ ⚪    │   │
│   │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─    │   │
│   │  CAP 10 - Counterparty credit risk on derivatives and SFTs                   │   │
│   │  (○ Idle) · Est. start: 18 Apr at 10:30:00 · Est. end: 18 Apr at 11:30:00   │   │
│   │                                                  Last runs: ⚪ ⚪ ⚪ ⚪ ⚪    │   │
│   │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─    │   │
│   │  CAP 12 - Leverage ratio denominator                                         │   │
│   │  (○ Idle) · Est. start: 18 Apr at 10:30:00 · Est. end: 18 Apr at 11:30:00   │   │
│   │                                                  Last runs: ⚪ ⚪ ⚪ ⚪ ⚪    │   │
│   │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─    │   │
│   │  CAP 13 - Credit valuation adjustments                                       │   │
│   │  (○ Idle) · Est. start: 18 Apr at 10:30:00 · Est. end: 18 Apr at 11:30:00   │   │
│   │                                                  Last runs: ⚪ ⚪ ⚪ ⚪ ⚪    │   │
│   │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─    │   │
│   │  CAP 14 - Business indicator component                                       │   │
│   │  (○ Idle) · Est. start: 18 Apr at 10:30:00 · Est. end: 18 Apr at 11:30:00   │   │
│   │                                                  Last runs: ⚪ ⚪ ⚪ ⚪ ⚪    │   │
│   └──────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                      │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   ⚪ Group portfolio         SLA: 19:00 CET · ⏳ Waiting for Portfolio          ∨   │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│   ⚪ Risk governed           SLA: 20:30 CET                                     ∧   │
│   ┌──────────────────────────────────────────────────────────────────────────────┐   │
│   │                                                                              │   │
│   │  Gemini hedge                                                                │   │
│   │  (○ Idle) · Est. start: 18 Apr at 19:00:00 · Est. end: 18 Apr at 20:00:00   │   │
│   │                                                  Last runs: 🟢 🟢 🟡 🟢 🟢  │   │
│   │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─    │   │
│   │  Modelled exposure                                                           │   │
│   │  (○ Idle) · Est. start: 18 Apr at 18:00:00 · Est. end: 18 Apr at 19:00:00   │   │
│   │                                                  Last runs: 🟢 🟢 🟢 🔴 🟢  │   │
│   │                                                                              │   │
│   │           ┌──────┐  ┌──────┐  ┌──────┐                                      │   │
│   │           │ OTC  │  │ ETD  │  │ SFT  │    ← sub-run status buttons          │   │
│   │           │  ⚪  │  │  ⚪  │  │  ⚪  │                                      │   │
│   │           └──────┘  └──────┘  └──────┘                                      │   │
│   │                                                                              │   │
│   └──────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                      │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   ⚪ Consolidation           SLA: 21:00 CET · ⏳ Waiting for Group portfolio    ∨   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### Status Color Legend

```
  🟢  ON_TIME    — Completed before SLA deadline    (green filled)
  🟡  DELAYED    — Completed after SLA deadline     (amber filled)
  🔴  FAILED     — Run ended with error             (red filled)
  🔵  RUNNING    — Currently in progress            (blue pulsing)
  ⚪  NOT_STARTED / Idle — No run found yet         (gray hollow)
```

### State Transitions — Section Header Examples

```
Before any runs start:
  ⚪ Regional               SLA: 17:45 CET                                       ∨

First region starts running:
  🔵 Regional               SLA: 17:45 CET · 1/10 running                        ∨

All regions complete on time:
  🟢 Regional               SLA: 17:45 CET · ✓ All complete                      ∨

Some regions delayed:
  🟡 Regional               SLA: 17:45 CET · ⚠ WMDE breached SLA                ∨

A region failed:
  🔴 Regional               SLA: 17:45 CET · ✗ WMCH failed                       ∨
```

### Dependency Chain Visualization

```
When Regional is still running — downstream sections show dependency state:

  🔵 Regional               SLA: 17:45 CET · 8/10 complete                       ∧
  │   [expanded content...]
  │
  ├── ⚪ Portfolio            SLA: 18:30 CET · ⏳ Waiting for Regional             ∨
  │        ░░░░░░░░░░░░ section body grayed out ░░░░░░░░░░
  │
  ├── ⚪ Group portfolio      SLA: 19:00 CET · ⏳ Waiting for Portfolio             ∨
  │        ░░░░░░░░░░░░ section body grayed out ░░░░░░░░░░
  │
  │   ⚪ Risk governed        SLA: 20:30 CET                                       ∨
  │        (no dependency — can expand and show normal content)
  │
  └── ⚪ Consolidation        SLA: 21:00 CET · ⏳ Waiting for Group portfolio       ∨
           ░░░░░░░░░░░░ section body grayed out ░░░░░░░░░░


When Regional completes but has a failure — Portfolio shows blocked:

  🔴 Regional               SLA: 17:45 CET · ✗ WMCH failed                       ∨
  │
  ├── ⚪ Portfolio            SLA: 18:30 CET · 🚫 Blocked by Regional failure      ∨
```

### Last Runs Dots — Detailed View

```
  Last runs:  🟢  🟢  🟡  🟢  🔴
              ↑    ↑    ↑    ↑    ↑
            Apr16 Apr15 Apr14 Apr13 Apr12
                        ↑
              Tooltip: "Mon 14 Apr 2026 — DELAYED (ended 18:10 CET)"
```

### Model Exposure — Sub-Run Buttons in Different States

```
Idle (all not started):
  Modelled exposure
  (○ Idle) · Est. start: 18 Apr at 18:00:00 · Est. end: 18 Apr at 19:00:00
           ┌──────┐  ┌──────┐  ┌──────┐           Last runs: ⚪ ⚪ ⚪ ⚪ ⚪
           │ OTC  │  │ ETD  │  │ SFT  │
           │  ⚪  │  │  ⚪  │  │  ⚪  │
           └──────┘  └──────┘  └──────┘

Mixed (OTC done, ETD running, SFT not started):
  Modelled exposure
  (◉ Running) · Start: 18 Apr at 18:05 CET · Est. end: 18 Apr at 19:00:00
           ┌──────┐  ┌──────┐  ┌──────┐           Last runs: 🟢 🟢 🟢 🟢 🟢
           │ OTC  │  │ ETD  │  │ SFT  │
           │  🟢  │  │  🔵  │  │  ⚪  │
           └──────┘  └──────┘  └──────┘

All complete on time:
  Modelled exposure
  (● On time) · Start: 18:05 CET · End: 18:52 CET · Duration: 47mins
           ┌──────┐  ┌──────┐  ┌──────┐           Last runs: 🟢 🟢 🟢 🟢 🟢
           │ OTC  │  │ ETD  │  │ SFT  │
           │  🟢  │  │  🟢  │  │  🟢  │
           └──────┘  └──────┘  └──────┘

  ┌─── Tooltip (hover on ETD) ──────────────────────┐
  │  Sub-run:   ETD                                  │
  │  Status:    ON_TIME                              │
  │  Start:     18:20 CET                            │
  │  End:       18:45 CET                            │
  │  Duration:  25mins                               │
  └──────────────────────────────────────────────────┘
```

### End-of-Day — All Sections Complete (collapsed view)

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│   ┌──────────┬──────────┐    ┌────────┬────────┐    ┌──────────────────────┐         │
│   │▓ Daily   │  Monthly │    │▓ Run 1 │  Run 2 │    │  17-Apr-2026    📅  │         │
│   └──────────┴──────────┘    └────────┴────────┘    └──────────────────────┘         │
│                                                                                      │
│   Capital calculation insight                                          Expand all    │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   🟡 Regional               SLA: 17:45 CET · ⚠ WMDE breached SLA               ∨   │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   🟢 Portfolio               SLA: 18:30 CET · ✓ All complete                    ∨   │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   🟢 Group portfolio         SLA: 19:00 CET · ✓ Complete                        ∨   │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   🟢 Risk governed           SLA: 20:30 CET · ✓ All complete                    ∨   │
├──────────────────────────────────────────────────────────────────────────────────────┤
│   🟢 Consolidation           SLA: 21:00 CET · ✓ Complete                        ∨   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```
