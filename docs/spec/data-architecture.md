# Data Architecture

---

## PostgreSQL Overview

Three tables form the core data model:

| Table | Purpose | Partitioned |
|-------|---------|-------------|
| `calculator_runs` | Raw run lifecycle records | Yes — RANGE on `reporting_date` |
| `calculator_sli_daily` | Pre-aggregated daily statistics | No |
| `sla_breach_events` | SLA breach event log with alert lifecycle | No |

Schema migrations are managed by **Flyway** (`src/main/resources/db/migration/`). Never modify existing migration files.

---

## Table: `calculator_runs`

The primary data table. Every start and complete event from Airflow is persisted here.

```sql
CREATE TABLE calculator_runs (
    run_id                VARCHAR(100)   NOT NULL,
    calculator_id         VARCHAR(100)   NOT NULL,
    calculator_name       VARCHAR(255)   NOT NULL,
    tenant_id             VARCHAR(50)    NOT NULL,
    frequency             VARCHAR(20)    NOT NULL,       -- 'DAILY' or 'MONTHLY'
    reporting_date        DATE           NOT NULL,       -- PARTITION KEY
    start_time            TIMESTAMPTZ    NOT NULL,
    end_time              TIMESTAMPTZ,
    duration_ms           BIGINT,
    start_hour_cet        DECIMAL(4,2),                 -- e.g. 06.25 = 06:15 CET
    end_hour_cet          DECIMAL(4,2),
    status                VARCHAR(20)    NOT NULL,       -- RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED
    sla_time              TIMESTAMPTZ,
    expected_duration_ms  BIGINT,
    estimated_start_time  TIMESTAMPTZ,
    estimated_end_time    TIMESTAMPTZ,
    sla_breached          BOOLEAN        DEFAULT false,
    sla_breach_reason     TEXT,
    run_parameters        JSONB,
    additional_attributes JSONB,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (run_id, reporting_date)
) PARTITION BY RANGE (reporting_date);
```

### Key Design Notes

- All timestamps stored as `TIMESTAMPTZ` (UTC). CET display values (`start_hour_cet`, `end_hour_cet`) are **pre-computed** via `TimeUtils` at write time and stored as `DECIMAL(4,2)` (e.g., `06.25` = 06:15 CET) to avoid timezone conversion at query time.
- `run_parameters` and `additional_attributes` are untyped `JSONB` — no PostgreSQL CHECK constraint on their structure.
- No foreign key constraints (partitioned tables cannot have inbound FK references in PostgreSQL).
- No CHECK constraint on `status` or `frequency` columns — only the Java layer enforces enum membership.

### Immutable Columns

The following columns are **immutable after first INSERT**. The `ON CONFLICT UPDATE` clause deliberately omits them:

- `calculator_name`
- `start_time` / `start_hour_cet`
- `sla_time`
- `expected_duration_ms`
- `estimated_start_time` / `estimated_end_time`

---

## Table: `calculator_sli_daily`

Pre-aggregated statistics stored as **SUMs** (averages computed at read time). Rebuilt by a **nightly batch** (`DailyAggregationJob`), not per `completeRun()`. Frequency-aware so DAILY and MONTHLY runs of the same calculator do not blend on shared (month-end) reporting dates.

```sql
CREATE TABLE calculator_sli_daily (
    calculator_id     VARCHAR(100)  NOT NULL,
    tenant_id         VARCHAR(50)   NOT NULL,
    frequency         VARCHAR(10)   NOT NULL DEFAULT 'DAILY',  -- V8
    reporting_date    DATE          NOT NULL,
    total_runs        INT           DEFAULT 0,
    success_runs      INT           DEFAULT 0,
    sla_breaches      INT           DEFAULT 0,
    sum_duration_ms   BIGINT        DEFAULT 0,
    sum_start_min_utc BIGINT        DEFAULT 0,   -- minutes since midnight UTC, summed
    sum_end_min_utc   BIGINT        DEFAULT 0,
    computed_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (calculator_id, tenant_id, frequency, reporting_date)  -- 4-col (V8)
);
```

- Not partitioned. One row per `(calculatorId, tenantId, frequency, reportingDate)`.
- Sums (not averages) are stored; `DailyAggregate` / `CalculatorProfile` compute averages at read time. This sidesteps the old running-average concurrency hazard.
- `reporting_date` is the business date (UTC-aligned), matching `calculator_runs`. Start/end minutes are UTC.
- Aggregate-backed analytics (`/runtime`, `/sla-summary`, `/trends`) read from this table — frequency-agnostic reads collapse across frequency (one row per date); the SLA baseline / estimate **profile** reads it frequency-scoped.
- `run-performance` and `executions` are the exception — they read raw `calculator_runs` directly (live).
- The `frequency` column joins the PRIMARY KEY (a derived aggregate, so widening the PK is the clean way to give `ON CONFLICT`/grouping a unique key); `DEFAULT 'DAILY'` keeps future no-frequency calculators working.

---

## Table: `sla_breach_events`

Records every SLA breach event, including alert delivery lifecycle.

```sql
CREATE TABLE sla_breach_events (
    breach_id       BIGSERIAL PRIMARY KEY,
    run_id          VARCHAR(100)  NOT NULL UNIQUE,
    calculator_id   VARCHAR(100)  NOT NULL,
    calculator_name VARCHAR(255)  NOT NULL,
    tenant_id       VARCHAR(50)   NOT NULL,
    breach_type     VARCHAR(50)   NOT NULL,
    expected_value  BIGINT,
    actual_value    BIGINT,
    severity        VARCHAR(20)   NOT NULL
                    CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    alerted         BOOLEAN       DEFAULT false,
    alerted_at      TIMESTAMPTZ,
    alert_status    VARCHAR(20)   DEFAULT 'PENDING'
                    CHECK (alert_status IN ('PENDING','SENT','FAILED','RETRYING')),
    retry_count     INT           DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
```

- `UNIQUE (run_id)` acts as the idempotency key — `AlertHandlerService` catches `DuplicateKeyException` on duplicate breach events.
- `expected_value` and `actual_value` store epoch milliseconds for time-based breaches, or milliseconds for duration breaches.

---

## Partitioning Model

### Strategy

`RANGE` partition on `reporting_date` (DATE). One partition per calendar day.

Partition naming convention: `calculator_runs_YYYY_MM_DD`

Example: `calculator_runs_2026_03_24` covers `[2026-03-24, 2026-03-25)`.

### Partition Windows

| Phase | Action | Schedule |
|-------|--------|----------|
| Initial migration (V2) | Creates ~62 partitions (yesterday + today + 60 future days) | On first Flyway migration |
| Daily creation | `PartitionManagementJob.createPartitions()` | Daily at 01:00 — maintains 60-day forward window |
| Weekly cleanup | `PartitionManagementJob.dropOldPartitions()` | Sunday at 02:00 — drops partitions > 395 days old |

!!! warning "Partition creation failure"
    If the nightly creation job fails, no partitions are created for that window. The job is idempotent (`CREATE PARTITION IF NOT EXISTS`). However, if the **current day's partition** is missing, all `INSERT` statements for that date will fail with `ERROR: no partition of relation found for row`. Monitor `partitions.create.failures` counter.

### Composite Primary Key

`PRIMARY KEY (run_id, reporting_date)` — both components are mandatory. Consequences:

- `ON CONFLICT` targets `(run_id, reporting_date)` — both must be provided for idempotent upserts
- Any query that omits `reporting_date` from WHERE will scan every child partition

---

## Indexing Strategy

All indexes are created on the parent partitioned table. PostgreSQL propagates them automatically to all child partitions.

| Index | Columns | Type | Purpose |
|-------|---------|------|---------|
| `calculator_runs_lookup_idx` | `(calculator_id, tenant_id, reporting_date DESC, created_at DESC)` | BTREE | Single-calculator status queries |
| `calculator_runs_tenant_idx` | `(tenant_id, reporting_date DESC)` | BTREE | Tenant-level scans |
| `calculator_runs_status_idx` | `(status, reporting_date DESC) WHERE status='RUNNING'` | BTREE partial | Active run queries |
| `calculator_runs_sla_idx` | `(sla_time, status) WHERE status='RUNNING' AND sla_time IS NOT NULL` | BTREE partial | SLA deadline queries |
| `calculator_runs_frequency_idx` | `(frequency, reporting_date DESC)` | BTREE | Frequency-specific scans |
| `calculator_runs_tenant_calculator_frequency_idx` | `(tenant_id, calculator_id, frequency, reporting_date DESC, created_at DESC)` | BTREE | Batch status queries |
| `idx_calculator_sli_daily_recent` | `(calculator_id, tenant_id, reporting_date DESC)` | BTREE | Frequency-agnostic analytics reads (the 4-col PK index covers frequency-scoped profile reads) |
| `sla_breach_events_tenant_calculator_created_idx` | `(tenant_id, calculator_id, created_at DESC, breach_id DESC)` | BTREE | Keyset pagination (no severity filter) |
| `sla_breach_events_tenant_calculator_severity_created_idx` | `(tenant_id, calculator_id, severity, created_at DESC, breach_id DESC)` | BTREE | Keyset pagination (with severity filter) |
| `idx_sla_breach_events_unalerted` | `(created_at) WHERE alerted=false` | BTREE partial | Alert retry queries |
| `idx_sla_breach_events_calculator` | `(calculator_id, created_at DESC)` | BTREE | Breach history by calculator |

!!! note "Frequency migration (V8)"
    `V8__calculator_sli_daily_frequency.sql` adds the `frequency` column, widens the PRIMARY KEY to 4 columns, and recomputes `calculator_sli_daily` from `calculator_runs` (un-blending any historical DAILY/MONTHLY rows).

---

## Query Window Logic

| Frequency | `reporting_date` WHERE clause | Partitions Scanned |
|-----------|-----------------------------|-------------------|
| `DAILY` | `>= CURRENT_DATE - 3 days AND <= CURRENT_DATE` | ≤ 4 |
| `MONTHLY` | `>= CURRENT_DATE - 13 months` (+ end-of-month row filter) | ~395 |

Analytics queries use a dynamic window: `>= CURRENT_DATE - CAST(? AS INTEGER) * INTERVAL '1 day'`, bounded by the caller-specified `days` parameter (1–365).

---

## Partition Safety Audit

> **Rule**: Every query against `calculator_runs` **MUST** include a `reporting_date` predicate that the PostgreSQL planner can evaluate at plan time to enable constraint exclusion.

### Audit Results — All Repository Methods

| Method | Partition Pruning | Risk |
|--------|-------------------|------|
| `findRecentRuns()` (DAILY) | **Yes** — bounded 3-day range | None |
| `findRecentRuns()` (MONTHLY) | **Partial** — lower bound only | Medium |
| `queryBatchFromDatabase()` (DAILY) | **Yes** — bounded range | None |
| `queryBatchFromDatabase()` (MONTHLY) | **Partial** | Medium |
| `upsert()` | **Yes** — exact value in INSERT | None |
| `findById(String, LocalDate)` | **Yes** — exact constant | None |
| `findById(String)` ⚠️ | **No** — full scan | **HIGH** |
| `markSlaBreached()` | **Yes** — exact constant | None |
| `countRunning()` | **Yes** — 7-day window | None |
| `findRunsWithSlaStatus()` | **Yes** — dynamic bounded range | None |

### Violation Detail: `findById(String)` — HIGH RISK

```sql
SELECT ... FROM calculator_runs
WHERE run_id = ?
ORDER BY reporting_date DESC LIMIT 1
```

No `reporting_date` predicate. PostgreSQL queries every child partition. With ~455 total partitions (60 future + ~395 historical), this executes ~455 index range scans.

- **Expected impact**: 20–100× slower than `findById(String, LocalDate)`
- **Call site**: Used as a last-resort fallback in `RunIngestionService.findRecentRun()` only when the 7-day recent-run search returns nothing
- **Fix**: See [TD-1](tech-debt.md#td-1-findbyidstring-full-partition-scan)

### MONTHLY Partition Scan — MEDIUM RISK

The end-of-month filter `reporting_date = DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day'` is a self-referential row-level filter. PostgreSQL cannot evaluate it at plan time, so ~395 partitions are scanned.

**Quantified impact:**
- DAILY query: 4 partitions → O(1) planner overhead
- MONTHLY query: ~395 partitions → 50–200ms overhead vs an equivalent DAILY query
- `findById(String)` no date: ~455 partitions → 200–500ms (cold); potentially seconds under high fragmentation

---

## Upsert Pattern

All writes use `INSERT ... ON CONFLICT (run_id, reporting_date) DO UPDATE`. This makes every write operation **idempotent**:

- Airflow retries the same `start` or `complete` call → no duplicate data
- The service returns the existing run state unchanged on duplicate detection

---

## Daily Aggregate Build Flow (end-of-day batch)

`calculator_sli_daily` is **not** written on `completeRun()`. A nightly `DailyAggregationJob`
(`@Scheduled`, default cron `0 30 0 * * *`) rebuilds a trailing window from `calculator_runs`,
then warms the profile cache. The recompute is **idempotent** (DELETE the range, re-INSERT):

```sql
DELETE FROM calculator_sli_daily WHERE reporting_date BETWEEN :from AND :to;

INSERT INTO calculator_sli_daily (
    calculator_id, tenant_id, frequency, reporting_date,
    total_runs, success_runs, sla_breaches,
    sum_duration_ms, sum_start_min_utc, sum_end_min_utc, computed_at)
SELECT calculator_id, tenant_id, frequency, reporting_date,
       COUNT(*),
       COUNT(*) FILTER (WHERE status = 'SUCCESS'),
       COUNT(*) FILTER (WHERE sla_breached),
       COALESCE(SUM(duration_ms), 0),
       COALESCE(SUM(EXTRACT(HOUR FROM start_time AT TIME ZONE 'UTC')*60
                  + EXTRACT(MINUTE FROM start_time AT TIME ZONE 'UTC')), 0),
       COALESCE(SUM(CASE WHEN end_time IS NOT NULL THEN
                  EXTRACT(HOUR FROM end_time AT TIME ZONE 'UTC')*60
                  + EXTRACT(MINUTE FROM end_time AT TIME ZONE 'UTC') ELSE 0 END), 0),
       NOW()
FROM calculator_runs
WHERE end_time IS NOT NULL AND reporting_date BETWEEN :from AND :to
GROUP BY calculator_id, tenant_id, frequency, reporting_date;
```

- `recompute-window-days` (default 3) covers the last few reporting dates so late-arriving completions are captured.
- Because sums are recomputed wholesale, there is **no concurrency hazard** (the former TD-3 running-average issue is gone — see [tech-debt.md](tech-debt.md)).
- Trade-off: aggregate analytics reflect data through the last completed day; the current day appears after the next nightly run. Live per-run views are unaffected.

