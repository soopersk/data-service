# Technical Debt

This page documents known limitations and technical debt items. Each item includes a risk rating, description, and recommended fix.

---

## Summary Table

| ID | Summary | Risk | Status |
|----|---------|------|--------|
| [TD-1](#td-1-findbyidstring-full-partition-scan) | `findById(String)` — no `reporting_date`, full scan ~455 partitions | 🔴 High perf | Open |
| [TD-2](#td-2-orphaned-postgresql-function) | `cleanup_expired_idempotency_keys()` references dropped table | 🟡 Runtime error if called | Open |
| [TD-3](#td-3-daily-aggregate-running-average-concurrency-unsafe) | `upsertDaily()` running average not concurrency-safe | 🟡 Inaccurate analytics under parallelism | Open |
| [TD-4](#td-4-retrying-alert-status-not-retried) | `RETRYING` alert status excluded from retry query | 🟡 Silent alert loss | Open |
| [TD-5](#td-5-slabreachevent-fields-are-untyped-strings) | `SlaBreachEvent` breach_type/severity/alertStatus stored as raw String | 🟡 No Java type safety | Open |
| [TD-6](#td-6-calculatorfrequencylookbackdays-is-dead-code) | `CalculatorFrequency.lookbackDays` is dead code (never used in queries) | 🟡 Latent bug if ever used | Open |
| [TD-7](#td-7-basic-auth-password-is-plaintext) | Basic Auth password is plaintext (`{noop}` encoding) | 🟠 Security | Open |
| [TD-8](#td-8-monthly-partition-scan) | MONTHLY queries scan ~395 partitions | 🟡 Medium perf | Open |
| [TD-9](#td-9-no-per-endpoint-latency-tracking) | No per-endpoint latency tracking (only batch has a Timer) | 🟡 Observability gap | Open |
| [TD-10](#td-10-stale-jpa-config-in-dev-and-prod-profiles) | `application-dev.yml` and `application-prod.yml` have stale JPA/Hibernate config | 🟢 Misleading only | Open |
| [TD-11](#td-11-alert-mechanism-is-log-only) | Alert delivery is log-only — no external notification channel | 🟠 Feature gap | Open |

---

## TD-1: `findById(String)` Full Partition Scan

**Risk:** 🔴 High performance

**Affected files:** `CalculatorRunRepository.findById(String)`

**Description:**

`CalculatorRunRepository.findById(String runId)` contains no `reporting_date` predicate:

```sql
SELECT ... FROM calculator_runs
WHERE run_id = ?
ORDER BY reporting_date DESC LIMIT 1
```

PostgreSQL must query every child partition. With ~455 total partitions (60 future + ~395 historical), this executes ~455 index range scans via an `Append` node.

**Call site:** Used as a last-resort fallback in `RunIngestionService.findRecentRun()` only when the 7-day recent-run search returns nothing. This occurs only for very old runs or data inconsistencies.

**Impact:** 200–500ms in a cold scenario; potentially seconds under high partition fragmentation. Grows linearly as partition count increases.

**Recommended fix:** Require callers to always pass `reportingDate`. Eliminate the no-date overload entirely, or require callers to compute the date from the run metadata before calling the fallback.

---

## TD-2: Orphaned PostgreSQL Function

**Risk:** 🟡 Runtime error if called

**Affected files:** `V9__maintenance_functions.sql`

**Description:**

`cleanup_expired_idempotency_keys()` was created in migration V9 and references the `idempotency_keys` table. That table was dropped in V11. Calling this function at runtime throws:

```
ERROR: relation "idempotency_keys" does not exist
```

The function is not called by any Java code, but its presence is misleading and a trap for future developers.

**Recommended fix:** Add `DROP FUNCTION IF EXISTS cleanup_expired_idempotency_keys()` to the next migration file.

---

## TD-3: Daily Aggregate Running Average — Concurrency Unsafe

**Risk:** 🟡 Inaccurate analytics under parallelism

**Affected files:** `DailyAggregateRepository.upsertDaily()`

**Description:**

The running-average upsert uses:

```sql
avg_duration_ms = (avg_duration_ms * total_runs + EXCLUDED.avg_duration_ms) / (total_runs + 1)
```

Under concurrent upserts for the same `(calculatorId, tenantId, day_cet)`, `total_runs` in the denominator is the pre-increment value read at the start of the SQL execution. Two concurrent completions for the same calculator on the same day will both use the same `total_runs` value, producing a slightly wrong average.

**Impact:** Non-critical (analytics data, not financial). Inaccuracy is bounded and proportional to degree of parallelism.

**Recommended fix (option A):** Use PostgreSQL advisory locks to serialize upserts for the same key.

**Recommended fix (option B):** Store `sum_duration_ms` and `total_runs` separately. Compute the average at read time: `avg = sum / total`. This is naturally concurrent-safe.

---

## TD-4: RETRYING Alert Status Not Retried

**Risk:** 🟡 Silent alert loss

**Affected files:** `SlaBreachEventRepository.findUnalertedBreaches()`

**Description:**

`findUnalertedBreaches()` filters:

```sql
WHERE alerted = false AND alert_status IN ('PENDING', 'FAILED')
```

A breach record with `alert_status = 'RETRYING'` (set by application code when queueing for retry) is never fetched for retry. Once a record enters `RETRYING` status, the only way to recover it is a manual database update.

**Recommended fix:** Add `'RETRYING'` to the `IN` clause:

```sql
WHERE alerted = false AND alert_status IN ('PENDING', 'FAILED', 'RETRYING')
```

Or implement a dedicated retry sweep job that specifically targets `RETRYING` records.

---

## TD-5: `SlaBreachEvent` Fields Are Untyped Strings

**Risk:** 🟡 No Java type safety

**Affected files:** `SlaBreachEvent` domain class

**Description:**

`SlaBreachEvent.breachType`, `.severity`, and `.alertStatus` are `String` fields. The corresponding enums (`BreachType`, `Severity`, `AlertStatus`) exist in the codebase but are not used on the domain object.

Consequence: invalid string values can be set in Java code and written to the database without compile-time detection. Only PostgreSQL CHECK constraints catch invalid `severity` and `alert_status` values at runtime.

**Recommended fix:** Change domain class fields to use enum types. Serialize to/from `String` in the repository layer (mapper) using `.name()` / `Enum.valueOf()`.

---

## TD-6: `CalculatorFrequency.lookbackDays` Is Dead Code

**Risk:** 🟡 Latent bug if ever used

**Affected files:** `CalculatorFrequency` enum

**Description:**

The enum declares:
```java
DAILY(2), MONTHLY(10)
```

with a `getLookbackDays()` method. No repository SQL uses these values — query windows are hardcoded (3 days for DAILY, 13 months for MONTHLY).

The discrepancy between the enum value (2 days for DAILY) and the actual query window (3 days for DAILY) is a latent bug. If `getLookbackDays()` is ever wired into a query, results will be silently wrong.

**Recommended fix (option A):** Remove the `lookbackDays` field and `getLookbackDays()` method entirely.

**Recommended fix (option B):** Replace all hardcoded intervals in the repository with `getLookbackDays()`, and update the enum values to match actual behaviour (3 for DAILY, ~395 days for MONTHLY).

---

## TD-7: Basic Auth Password Is Plaintext

**Risk:** 🟠 Security

**Affected files:** `BasicSecurityConfig`

**Description:**

`BasicSecurityConfig` uses `{noop}` password encoding, meaning passwords are stored and compared as plaintext strings. Additionally, all environments share the same default credentials (`admin`/`admin`) unless explicitly overridden via environment variables.

**Recommended fix:**

1. Switch to `{bcrypt}` encoding: use `BCryptPasswordEncoder` in the security config.
2. Enforce `OBS_BASIC_USER` and `OBS_BASIC_PASSWORD` as required environment variables in all non-local deployments.
3. Rotate default credentials in all deployed environments.

---

## TD-8: MONTHLY Partition Scan (~395 Partitions)

**Risk:** 🟡 Medium performance

**Affected files:** `CalculatorRunRepository` — MONTHLY query path

**Description:**

The end-of-month row filter:
```sql
reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE
```
is a self-referential expression. PostgreSQL cannot evaluate it at plan time, so all ~395 partitions within the 13-month lower bound are scanned.

**Impact:** +50–200ms vs an equivalent DAILY query.

**Recommended fix (long-term):** Create a separate `monthly_calculator_runs` table with a simple PK, or sub-partition `calculator_runs` by frequency, giving MONTHLY runs their own monthly-granularity partition scheme.

**Workaround:** The 60-second Redis cache TTL absorbs repeated MONTHLY queries effectively. Avoid `bypassCache=true` for MONTHLY calculators in high-traffic scenarios.

---

## TD-9: No Per-Endpoint Latency Tracking

**Risk:** 🟡 Observability gap

**Affected files:** Controllers

**Description:**

All API endpoints track request counts via counters, but only `query.batch_status.duration` has a `Timer` metric. There are no latency histograms or percentile metrics (p50, p95, p99) for ingestion, single-calculator status, or analytics endpoints.

**Impact:** Cannot detect latency degradation per endpoint without application-level monitoring. Relies on infrastructure-level metrics (ALB/APIM latency) or manual log analysis.

**Recommended fix:** Add `Timer.record()` in all controller methods or use `@Timed` from Micrometer.

---

## TD-10: Stale JPA Config in Dev and Prod Profiles

**Risk:** 🟢 Misleading only

**Affected files:** `application-dev.yml`, `application-prod.yml`

**Description:**

`application-dev.yml` contains `org.hibernate.SQL: DEBUG`. `application-prod.yml` contains `spring.jpa.show-sql: false`. Hibernate / JPA is not used in this service (no `@Entity` classes, no `spring-data-jpa` dependency in active use). The config is harmless but misleads developers into thinking JPA is active.

**Recommended fix:** Remove these stale properties from both files.

---

## TD-11: Alert Mechanism Is Log-Only

**Risk:** 🟠 Feature gap

**Affected files:** `AlertHandlerService.sendSimpleAlert()`

**Description:**

`AlertHandlerService.sendSimpleAlert()` logs a `WARN`-level message. The full alert lifecycle infrastructure (PENDING → SENT/FAILED, `retry_count`, `last_error`, `alerted_at`) is fully implemented in the database and service layer, but nothing actually delivers an external notification.

**Impact:** SLA breaches are recorded and visible via the analytics API, but on-call teams receive no automatic notification.

**Open question:** What is the target alert channel? Email, Slack webhook, PagerDuty, Azure Monitor alerts?

**Recommended fix:** Implement `sendSimpleAlert()` with the chosen delivery mechanism. The method signature and surrounding infrastructure (DB update, error handling) are already in place.
