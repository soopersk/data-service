# GitLab Delivery Structure — Observability Backend Service

**Service:** `ai-data-service` (observability-service)
**Stack:** Java 17 · Spring Boot 3.5.9 · PostgreSQL 17 (partitioned) · Redis · Flyway
**Approach:** Ground-up implementation plan. Epics are ordered by dependency; issues within each Epic can largely be parallelised by different developers.

---

## Epic Index

| Epic | Title | Issues |
|------|-------|--------|
| [EPIC-1](#epic-1-project-foundation--infrastructure) | Project Foundation & Infrastructure | 5 |
| [EPIC-2](#epic-2-core-data-model--repository-layer) | Core Data Model & Repository Layer | 6 |
| [EPIC-3](#epic-3-run-ingestion-api) | Run Ingestion API | 5 |
| [EPIC-4](#epic-4-redis-caching-layer) | Redis Caching Layer | 5 |
| [EPIC-5](#epic-5-calculator-status-query-api) | Calculator Status Query API | 4 |
| [EPIC-6](#epic-6-sla-detection--alerting) | SLA Detection & Alerting | 6 |
| [EPIC-7](#epic-7-analytics-api) | Analytics API | 6 |
| [EPIC-8](#epic-8-partition-management--db-maintenance) | Partition Management & DB Maintenance | 4 |
| [EPIC-9](#epic-9-observability--metrics) | Observability & Metrics | 5 |
| [EPIC-10](#epic-10-security-hardening) | Security Hardening | 4 |
| [EPIC-11](#epic-11-testing--quality) | Testing & Quality | 6 |
| [EPIC-12](#epic-12-production-readiness) | Production Readiness | 5 |

---

---

## EPIC-1: Project Foundation & Infrastructure

**Goal:** Establish a working local development environment, CI pipeline, and base Spring Boot project that all subsequent epics build on.

**Labels:** `epic`, `foundation`, `infrastructure`

---

### Issue 1.1 — Spring Boot Project Scaffold & Maven Configuration

**Labels:** `foundation`, `backend`, `priority::high`

**Description:**
Bootstrap the Spring Boot 3.5.9 / Java 17 Maven project with all required dependencies, multi-profile configuration, and a passing build pipeline.

**Technical Scope:**
- Maven `pom.xml` with all required dependencies:
  - `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-data-redis`, `spring-boot-starter-actuator`, `spring-boot-starter-security`
  - `spring-boot-starter-validation`, `springdoc-openapi-starter-webmvc-ui`
  - `flyway-core`, `postgresql`, `micrometer-registry-prometheus`
  - `jackson-datatype-jsr310`, Lombok, `spring-boot-starter-test`
- `application.yml` (base), `application-local.yml`, `application-dev.yml`, `application-prod.yml`
- `ObservabilityServiceApplication.java` with `@SpringBootApplication`, `@EnableCaching`, `@EnableScheduling`, `@OpenAPIDefinition`
- `AsyncConfig.java`: 5 core / 10 max / 100 queue executor, prefix `async-`
- Scheduling pool: 5 threads, prefix `scheduled-`
- `RequestLoggingFilter.java`: reads/generates `X-Request-ID`, stores in MDC, propagates on response

**Implementation Notes:**
- All timestamps must use `TIMESTAMPTZ` / Java `Instant`. Enable `JavaTimeModule` in ObjectMapper.
- Base `application.yml` must define all `observability.*` and infrastructure properties with safe defaults. Profile files override only what differs.
- Local profile must exclude `SecurityAutoConfiguration` for actuator access without auth.
- Log pattern must include `[%X{requestId}]` from MDC.

**Dependencies:** None

**Acceptance Criteria:**
- [ ] `./mvnw clean package` succeeds with zero test failures
- [ ] `SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run` starts without errors
- [ ] `GET /actuator/health` returns `{"status":"UP"}` unauthenticated
- [ ] `X-Request-ID` header is echoed on every response; UUID generated if absent in request
- [ ] All `observability.*` properties are documented with defaults in `application.yml`

---

### Issue 1.2 — Docker Compose: Local PostgreSQL & Redis

**Labels:** `foundation`, `infrastructure`, `priority::high`

**Description:**
Provide a `docker-compose.yml` that starts a local PostgreSQL 17 and Redis instance matching the production topology, usable for all local development and test runs.

**Technical Scope:**
- `docker-compose.yml` with services:
  - `observability-postgres`: `postgres:17`, port `5432`, db `observability`, user/pass `postgres`
  - `observability-redis`: `redis:7`, port `6379`, no password (local)
- Volume mounts for data persistence between restarts
- Health checks on both services
- `application-local.yml` must connect to these containers

**Implementation Notes:**
- Use named volumes, not bind mounts, to avoid permission issues on Windows (Rancher Desktop).
- Add a `depends_on` with `condition: service_healthy` if the application container is ever added to compose.

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] `docker compose up -d` starts both services with status `healthy`
- [ ] `docker exec -it observability-postgres psql -U postgres -d observability` connects successfully
- [ ] `docker exec -it observability-redis redis-cli ping` returns `PONG`
- [ ] Application connects to both services when started with `local` profile
- [ ] `docker compose down -v` cleans up without errors

---

### Issue 1.3 — Flyway: Base Schema (V1–V3)

**Labels:** `foundation`, `database`, `priority::high`

**Description:**
Create the initial Flyway migrations that establish PostgreSQL extensions, the `calculator_runs` base table, and its indexes. This is the foundational schema all other features depend on.

**Technical Scope:**
- `V1__extensions.sql`: `CREATE EXTENSION IF NOT EXISTS "uuid-ossp";`
- `V2__calculator_runs_base_table.sql`: Full DDL for `calculator_runs` with `PARTITION BY RANGE (reporting_date)`. Columns: `run_id`, `calculator_id`, `calculator_name`, `tenant_id`, `frequency`, `reporting_date` (PK component + partition key), `start_time`, `end_time`, `duration_ms`, `start_hour_cet DECIMAL(4,2)`, `end_hour_cet DECIMAL(4,2)`, `status`, `sla_time`, `expected_duration_ms`, `estimated_start_time`, `estimated_end_time`, `sla_breached DEFAULT false`, `sla_breach_reason`, `run_parameters JSONB`, `additional_attributes JSONB`, `created_at`, `updated_at`. `PRIMARY KEY (run_id, reporting_date)`.
- `V3__calculator_runs_indexes.sql` (with `-- flyway:transactional=false`):
  - `calculator_runs_lookup_idx`: `(calculator_id, tenant_id, reporting_date DESC, created_at DESC)`
  - `calculator_runs_tenant_idx`: `(tenant_id, reporting_date DESC)`
  - `calculator_runs_status_idx`: partial `(status, reporting_date DESC) WHERE status='RUNNING'`
  - `calculator_runs_sla_idx`: partial `(sla_time, status) WHERE status='RUNNING' AND sla_time IS NOT NULL`
  - `calculator_runs_frequency_idx`: `(frequency, reporting_date DESC)`

**Implementation Notes:**
- No CHECK constraints on `status` or `frequency` — enforcement is at the Java layer.
- No FK constraints — partitioned tables cannot have inbound FK references.
- `run_parameters` and `additional_attributes` are untyped JSONB; no structural validation at the DB layer.
- `start_time`, `sla_time`, `expected_duration_ms`, `estimated_start_time`, `estimated_end_time`, `calculator_name` are set on INSERT only — the ON CONFLICT UPDATE clause must NOT include these columns.

**Dependencies:** Issue 1.2

**Acceptance Criteria:**
- [ ] Flyway applies V1–V3 cleanly on a fresh database; `flyway_schema_history` shows all three as success
- [ ] `calculator_runs` has `PARTITION BY RANGE (reporting_date)` confirmed via `\d+ calculator_runs`
- [ ] `PRIMARY KEY (run_id, reporting_date)` confirmed
- [ ] All 5 indexes exist on the parent table and are confirmed propagated to child partitions (once V4 adds them)
- [ ] Re-running migrations on an already-migrated DB is a no-op (idempotent)

---

### Issue 1.4 — CI/CD Pipeline (GitLab CI)

**Labels:** `foundation`, `ci-cd`, `priority::medium`

**Description:**
Define the `.gitlab-ci.yml` pipeline that builds, tests, and packages the application on every push. Establishes the quality gate for all future merge requests.

**Technical Scope:**
- Stages: `build`, `test`, `package`
- `build` job: `./mvnw clean compile -DskipTests`
- `test` job: spin up PostgreSQL + Redis as GitLab services; run `SPRING_PROFILES_ACTIVE=local ./mvnw test`
- `package` job (main branch only): `./mvnw clean package -DskipTests`; archive the JAR as artifact
- Fail pipeline on any test failure
- Cache `.m2/repository` between jobs

**Implementation Notes:**
- Use `postgres:17` and `redis:7` as GitLab CI services for integration tests.
- Set `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` environment variables in the test job.
- Set `SPRING_PROFILES_ACTIVE=local` for the test job.

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] Pipeline triggers on every push to any branch
- [ ] Test stage runs all tests against live PostgreSQL + Redis services
- [ ] A failing test causes pipeline to fail and blocks MR merge
- [ ] JAR artifact is archived on successful main-branch build
- [ ] Pipeline completes in under 5 minutes on a standard GitLab runner

---

### Issue 1.5 — Global Exception Handling & API Error Contract

**Labels:** `foundation`, `backend`, `priority::high`

**Description:**
Implement the `GlobalExceptionHandler` and domain exception hierarchy that all controllers rely on. Establishes the uniform error response contract for all APIs.

**Technical Scope:**
- Exception classes: `DomainNotFoundException` (→ 404), `DomainAccessDeniedException` (→ 403), `DomainValidationException` (→ 400)
- `GlobalExceptionHandler` (`@RestControllerAdvice`):
  - `DomainNotFoundException` + `NoSuchElementException` → 404
  - `DomainAccessDeniedException` + `SecurityException` → 403
  - `DomainValidationException` + `IllegalArgumentException` → 400
  - `MethodArgumentNotValidException` → 400 with field-level `errors` map
  - `ConstraintViolationException` → 400
  - `Exception` (catch-all) → 500, generic message, logs error
- Standard error body: `{timestamp, status, error, message}`
- Validation error body: `{timestamp, status, error, errors: {field: message}}`

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] `DomainNotFoundException` results in HTTP 404 with `{timestamp, status:404, error:"Not Found", message}`
- [ ] `@Valid` violation on a request body returns HTTP 400 with field-level `errors` map
- [ ] Unhandled `RuntimeException` returns HTTP 500 with generic message; original exception is logged at ERROR level
- [ ] Timestamp in error response is a valid ISO-8601 UTC instant
- [ ] Unit tests cover all 7 mapped exception types

---

---

## EPIC-2: Core Data Model & Repository Layer

**Goal:** Implement all domain classes, enums, repositories, and the remaining Flyway migrations (V4–V6, V12) that constitute the full data layer.

**Labels:** `epic`, `data-layer`, `backend`

---

### Issue 2.1 — Domain Classes & Enums

**Labels:** `data-layer`, `backend`, `priority::high`

**Description:**
Implement all domain model classes and enumerations used throughout the service.

**Technical Scope:**
- `CalculatorRun` (Lombok `@Data @Builder`, `Serializable`): all 22 fields as defined in `V2`. Helper methods: `isDaily()`, `isMonthly()`, `isEndOfMonth()`.
- `DailyAggregate` (Lombok `@Data @Builder`, `Serializable`): `calculatorId`, `tenantId`, `dayCet`, `totalRuns`, `successRuns`, `slaBreaches`, `avgDurationMs`, `avgStartMinCet`, `avgEndMinCet`, `computedAt`.
- `SlaBreachEvent` (Lombok `@Data @Builder`): `breachId`, `runId`, `calculatorId`, `calculatorName`, `tenantId`, `breachType`, `expectedValue`, `actualValue`, `severity`, `alerted`, `alertedAt`, `alertStatus`, `retryCount`, `lastError`, `createdAt`.
- `RunWithSlaStatus`: lightweight projection for the performance-card query (run fields + `severity` from breach event JOIN).
- Enums: `CalculatorFrequency` (`DAILY`/`MONTHLY`, `from()` with `@JsonCreator` — returns DAILY on null/unknown, never throws), `RunStatus` (`fromString()`, `fromCompletionStatus()`, `isTerminal()`, `isSuccessful()`), `Severity` (`LOW/MEDIUM/HIGH/CRITICAL`, level-based comparison), `BreachType`, `AlertStatus` (`isFinal()`).
- `JsonbConverter`: serialize `Map<String,Object>` → `PGobject(jsonb)` and deserialize via Jackson.

**Implementation Notes:**
- `CalculatorFrequency.from()` must accept `D`/`M` short codes and be case-insensitive.
- `RunStatus.fromCompletionStatus()` must default to `SUCCESS` on null/blank; throw `IllegalArgumentException` on `RUNNING` or unknown values.
- `SlaBreachEvent.breachType`, `.severity`, `.alertStatus` are stored as `String` (not enum) to match the DB schema — type conversion is the caller's responsibility.

**Dependencies:** Issue 1.3

**Acceptance Criteria:**
- [ ] `CalculatorFrequency.from("d")`, `from("DAILY")`, `from(null)` all return `DAILY`
- [ ] `RunStatus.fromCompletionStatus("FAILED")` returns `FAILED`; `fromCompletionStatus("RUNNING")` throws `IllegalArgumentException`
- [ ] `JsonbConverter.toJsonb(null)` returns `null`; `toJsonb(map)` returns a `PGobject` with type `jsonb`
- [ ] `Severity.HIGH.isHigherThan(Severity.LOW)` returns `true`
- [ ] All domain classes are `Serializable` where required by Redis

---

### Issue 2.2 — `CalculatorRunRepository`: Partition-Safe Queries

**Labels:** `data-layer`, `backend`, `priority::critical`

**Description:**
Implement the full `CalculatorRunRepository` using `NamedParameterJdbcTemplate`. Every query against `calculator_runs` must include a `reporting_date` predicate unless explicitly documented as a fallback.

**Technical Scope:**
- Two `RowMapper`s:
  - `CalculatorRunRowMapper` (full, for upsert RETURNING and findById): maps all 22 columns including JSONB via `JsonbConverter`
  - `StatusRunRowMapper` (partial, omits `run_parameters` and `additional_attributes`): for all read-path queries to avoid JSONB overhead
- Methods:
  - `upsert(CalculatorRun)`: full INSERT with 22 params, `ON CONFLICT (run_id, reporting_date) DO UPDATE` (immutable columns excluded from UPDATE SET), `RETURNING *`
  - `findById(String runId, LocalDate reportingDate)`: `WHERE run_id=? AND reporting_date=?` — partition-safe
  - `findById(String runId)` **(fallback only)**: `WHERE run_id=? ORDER BY reporting_date DESC LIMIT 1` — documented full-scan, used only when `reportingDate` is unavailable
  - `findRecentRuns(calculatorId, tenantId, frequency, limit)`: frequency-dispatched; DAILY uses 3-day window, MONTHLY uses 13-month window with end-of-month filter
  - `findBatchRecentRunsDbOnly(calculatorIds, tenantId, frequency, limit)`: window-function query with `ROW_NUMBER() OVER (PARTITION BY calculator_id ORDER BY reporting_date DESC, created_at DESC)`
  - `markSlaBreached(runId, reason, reportingDate)`: UPDATE with `AND status='RUNNING' AND sla_breached=false`
  - `countRunning()`: 7-day window, global count
  - `findRunsWithSlaStatus(calculatorId, tenantId, frequency, days)`: `calculator_runs cr LEFT JOIN sla_breach_events sbe ON sbe.run_id = cr.run_id`

**Implementation Notes:**
- `findById(String)` (no date) MUST have a Javadoc warning: `// WARNING: no reporting_date — scans all partitions. Use only as last-resort fallback.`
- MONTHLY query: the `reporting_date = (DATE_TRUNC(...) + INTERVAL '1 month - 1 day')::DATE` expression is a row-level filter; partition pruning is partial (only the lower-bound `>= NOW-13mo` applies). This is a known architectural limitation.
- All SQL must use named parameters (`:paramName`), not positional `?`.

**Dependencies:** Issue 2.1, Issue 1.3

**Acceptance Criteria:**
- [ ] `upsert()` on a new run inserts; on duplicate `(run_id, reporting_date)` updates only mutable columns (end_time, status, sla_breached, sla_breach_reason, updated_at); immutable columns unchanged
- [ ] `findById(String, LocalDate)` uses `EXPLAIN` output showing single-partition scan
- [ ] `findRecentRuns(DAILY)` SQL includes `reporting_date >= CURRENT_DATE - INTERVAL '3 days'`
- [ ] `markSlaBreached()` only updates rows with `status='RUNNING' AND sla_breached=false`; returns 0 for already-breached runs
- [ ] `findBatchRecentRunsDbOnly()` returns correct per-calculator top-N rows ordered by `reporting_date DESC`

---

### Issue 2.3 — Flyway: Partition Setup (V4) & Hot-Path Indexes (V12)

**Labels:** `data-layer`, `database`, `priority::critical`

**Description:**
Implement the partition creation infrastructure and the hot-path composite indexes that make batch queries and keyset pagination performant.

**Technical Scope:**
- `V4__calculator_runs_partitions.sql`:
  - PL/pgSQL function `create_calculator_run_partitions()`: creates daily partitions from yesterday to +60 days using `FOR VALUES FROM ('date') TO ('date+1')`
  - PL/pgSQL function `drop_old_calculator_run_partitions()`: drops partitions older than 395 days
  - PL/pgSQL function `get_partition_statistics()`: returns per-partition row counts, size, frequency breakdown
  - Execute immediately: `SELECT create_calculator_run_partitions()`
- `V12__hot_path_indexes.sql` (with `-- flyway:transactional=false`):
  - `calculator_runs_tenant_calculator_frequency_idx`: `(tenant_id, calculator_id, frequency, reporting_date DESC, created_at DESC)` — covers batch queries
  - `sla_breach_events_tenant_calculator_created_idx`: `(tenant_id, calculator_id, created_at DESC, breach_id DESC)` — keyset pagination without severity
  - `sla_breach_events_tenant_calculator_severity_created_idx`: `(tenant_id, calculator_id, severity, created_at DESC, breach_id DESC)` — keyset pagination with severity

**Implementation Notes:**
- `create_calculator_run_partitions()` must be idempotent: use `CREATE TABLE IF NOT EXISTS ... PARTITION OF ...`.
- Partition naming convention: `calculator_runs_YYYY_MM_DD`.
- V4 migration must call `create_calculator_run_partitions()` at the end to pre-populate ~62 initial partitions.
- **Do NOT use `CREATE INDEX CONCURRENTLY`** inside Flyway transactions; use `-- flyway:transactional=false`.

**Dependencies:** Issue 1.3

**Acceptance Criteria:**
- [ ] After V4 migration, `\dt calculator_runs_*` shows ~62 partitions
- [ ] `SELECT create_calculator_run_partitions()` is idempotent (second call creates no new partitions for already-existing dates)
- [ ] `SELECT drop_old_calculator_run_partitions()` removes partitions confirmed older than 395 days
- [ ] V12 indexes exist and are confirmed on both parent and child partition tables
- [ ] `EXPLAIN (ANALYZE) SELECT ... FROM calculator_runs WHERE calculator_id=? AND tenant_id=? AND frequency=? AND reporting_date >= CURRENT_DATE - INTERVAL '3 days'` shows `Index Scan` not `Seq Scan`

---

### Issue 2.4 — Flyway: Supporting Tables (V5, V6)

**Labels:** `data-layer`, `database`, `priority::high`

**Description:**
Create the `calculator_sli_daily` aggregate table and `sla_breach_events` event log table.

**Technical Scope:**
- `V5__daily_aggregations.sql`: `calculator_sli_daily` table with PK `(calculator_id, tenant_id, day_cet)`. Columns: `total_runs INT DEFAULT 0`, `success_runs INT DEFAULT 0`, `sla_breaches INT DEFAULT 0`, `avg_duration_ms BIGINT DEFAULT 0`, `avg_start_min_cet INT DEFAULT 0`, `avg_end_min_cet INT DEFAULT 0`, `computed_at TIMESTAMPTZ`. Index: `idx_calculator_sli_daily_recent` on `(calculator_id, tenant_id, day_cet DESC)`.
- `V6__sla_breach_events.sql`: `sla_breach_events` table with `BIGSERIAL` PK. `UNIQUE (run_id)` for idempotency. `severity` CHECK `IN ('LOW','MEDIUM','HIGH','CRITICAL')`. `alert_status` DEFAULT `'PENDING'` CHECK `IN ('PENDING','SENT','FAILED','RETRYING')`. Indexes: `idx_sla_breach_events_unalerted` partial `(created_at) WHERE alerted=false`; `idx_sla_breach_events_calculator` on `(calculator_id, created_at DESC)`.

**Implementation Notes:**
- `calculator_sli_daily` is NOT partitioned — it's a rolling aggregate with low row count (one per calculator per day).
- The `UNIQUE (run_id)` on `sla_breach_events` is the idempotency guard — the Java layer must handle `DuplicateKeyException`.

**Dependencies:** Issue 1.3

**Acceptance Criteria:**
- [ ] `calculator_sli_daily` PK enforces uniqueness on `(calculator_id, tenant_id, day_cet)`
- [ ] `sla_breach_events` rejects a second INSERT with same `run_id` with a unique violation
- [ ] `severity` CHECK constraint rejects `'CRITICAL_PLUS'`
- [ ] Both tables and all indexes appear in migration history as success

---

### Issue 2.5 — `DailyAggregateRepository`

**Labels:** `data-layer`, `backend`, `priority::high`

**Description:**
Implement the `DailyAggregateRepository` with its incremental upsert and analytics query methods.

**Technical Scope:**
- `upsertDaily(calculatorId, tenantId, reportingDate, successIncrement, breachIncrement, durationMs, startMinCet, endMinCet)`:
  - `INSERT INTO calculator_sli_daily (...) VALUES (..., 1, ...) ON CONFLICT (...) DO UPDATE SET total_runs = total_runs + 1, success_runs = success_runs + EXCLUDED.success_runs, avg_duration_ms = (avg_duration_ms * total_runs + EXCLUDED.avg_duration_ms) / (total_runs + 1), ...`
  - Incremental running average for `avg_duration_ms`, `avg_start_min_cet`, `avg_end_min_cet`
- `findRecentAggregates(calculatorId, tenantId, days)`: `WHERE day_cet >= CURRENT_DATE - CAST(:days AS INTEGER) * INTERVAL '1 day' ORDER BY day_cet DESC`
- `findByReportingDates(calculatorId, tenantId, dates)`: IN clause query
- `DailyAggregateRowMapper`: maps all columns

**Implementation Notes:**
- The running average formula is not concurrency-safe under parallel inserts for the same row. This is a known limitation — acceptable for the current load profile. Document with a comment.
- `upsertDaily` errors must be caught and logged as WARN (non-blocking) — a failure here must not roll back the parent run completion transaction.

**Dependencies:** Issue 2.4

**Acceptance Criteria:**
- [ ] Two sequential calls to `upsertDaily` for the same `(calcId, tenantId, day)` result in `total_runs=2`
- [ ] Running average for `avg_duration_ms` is correct after 3 sequential inserts with known values
- [ ] `findRecentAggregates(calcId, tenantId, 7)` returns only rows within the last 7 days
- [ ] A failure in `upsertDaily` is logged as WARN but does not propagate an exception to the caller

---

### Issue 2.6 — `SlaBreachEventRepository`

**Labels:** `data-layer`, `backend`, `priority::high`

**Description:**
Implement the `SlaBreachEventRepository` covering the full breach event lifecycle: save, update (alert status), and all query methods including keyset-paginated breach detail.

**Technical Scope:**
- `save(SlaBreachEvent)`: INSERT 14 columns; uses `GeneratedKeyHolder` to return `breach_id`; caller must handle `DuplicateKeyException`
- `update(SlaBreachEvent)`: UPDATE `alerted`, `alerted_at`, `alert_status`, `retry_count`, `last_error` WHERE `breach_id=?`
- `findUnalertedBreaches(limit)`: `WHERE alerted=false AND alert_status IN ('PENDING','FAILED','RETRYING') ORDER BY created_at ASC LIMIT ?`
- `findByCalculatorIdAndPeriod(calculatorId, tenantId, days)`: full list for analytics
- `findByCalculatorIdPaginated(calculatorId, tenantId, days, severity, offset, limit)`: offset pagination (two variants: with/without severity filter)
- `findByCalculatorIdKeyset(calculatorId, tenantId, days, severity, cursorCreatedAt, cursorBreachId, limit)`: `AND (created_at, breach_id) < (:cursorCreatedAt, :cursorBreachId)` keyset clause (four variants: with/without severity, with/without cursor)
- `countByCalculatorIdAndPeriod(calculatorId, tenantId, days, severity)`: two variants

**Implementation Notes:**
- `findUnalertedBreaches` must include `RETRYING` in the `alert_status IN (...)` clause — this is a deliberate correction from a known design gap.
- The keyset cursor `(created_at, breach_id)` must match `ORDER BY created_at DESC, breach_id DESC` exactly.

**Dependencies:** Issue 2.4

**Acceptance Criteria:**
- [ ] `save()` returns the generated `breach_id`
- [ ] `save()` on a duplicate `run_id` throws `DuplicateKeyException` (not a generic exception)
- [ ] `findUnalertedBreaches()` returns breaches with `alert_status IN ('PENDING','FAILED','RETRYING')`
- [ ] Keyset query with a cursor returns the page immediately following the cursor position (verified with 50-row dataset)
- [ ] `countByCalculatorIdAndPeriod` returns consistent count matching the full result set

---

---

## EPIC-3: Run Ingestion API

**Goal:** Implement the Airflow-facing run lifecycle ingestion endpoints — start and complete — with SLA evaluation, idempotency, and full event publication.

**Labels:** `epic`, `ingestion`, `backend`

---

### Issue 3.1 — Request & Response DTOs (Ingestion)

**Labels:** `ingestion`, `backend`, `priority::high`

**Description:**
Define all request and response DTOs used by the ingestion endpoints.

**Technical Scope:**
- `StartRunRequest` (Lombok `@Data @Builder`): `runId @NotBlank`, `calculatorId @NotBlank`, `calculatorName @NotBlank`, `frequency @NotNull`, `reportingDate @NotNull`, `startTime @NotNull`, `slaTimeCet @NotNull`, `expectedDurationMs` (optional), `estimatedStartTimeCet` (optional), `runParameters Map<String,Object>` (optional), `additionalAttributes Map<String,Object>` (optional).
- `CompleteRunRequest` (Lombok `@Data @Builder`): `endTime @NotNull`, `status` optional with `@Pattern(regexp="(?i)SUCCESS|FAILED|TIMEOUT|CANCELLED")` (defaults to SUCCESS if null).
- `RunResponse` (Lombok `@Data @Builder`): `runId`, `calculatorId`, `calculatorName`, `status`, `startTime`, `endTime`, `durationMs`, `slaBreached`, `slaBreachReason`.

**Dependencies:** Issue 2.1

**Acceptance Criteria:**
- [ ] `StartRunRequest` with missing `runId` fails `@Valid` with message "Run ID is required"
- [ ] `CompleteRunRequest` with `status="INVALID"` fails with `@Pattern` violation
- [ ] `CompleteRunRequest` with `status=null` is valid (defaults to SUCCESS downstream)
- [ ] All fields serialize/deserialize correctly with `Instant` values as ISO-8601 strings

---

### Issue 3.2 — `TimeUtils`: CET Conversion Utilities

**Labels:** `ingestion`, `backend`, `priority::high`

**Description:**
Implement the static `TimeUtils` utility class for all CET timezone conversions used during run ingestion and display.

**Technical Scope:**
- `calculateSlaDeadline(LocalDate reportingDate, LocalTime slaTimeCet) → Instant`: combine date + time in CET zone, convert to UTC Instant
- `calculateEstimatedEndTime(Instant startTime, Long expectedDurationMs) → Instant`
- `calculateNextEstimatedStart(LocalDate reportingDate, LocalTime estimatedStartCet, CalculatorFrequency frequency) → Instant`: next day (DAILY) or next month (MONTHLY)
- `calculateCetHour(Instant) → BigDecimal`: e.g., 06:15 → `6.25`
- `calculateCetMinute(Instant) → int`: total minutes since midnight CET (0–1439)
- `getCetDate(Instant) → LocalDate`: extract CET calendar date
- `formatDuration(long ms) → String`: e.g., `"2hrs 15mins"`, `"45mins 30s"`, `"15s"`
- `formatCetHour(BigDecimal) → String`: e.g., `6.25 → "06:15"`
- `toTimestamp(Instant) → Timestamp`: for JDBC

**Implementation Notes:**
- Always use `ZoneId.of("Europe/Amsterdam")` for CET/CEST (handles DST automatically). Never hardcode `+01:00`.

**Dependencies:** Issue 2.1

**Acceptance Criteria:**
- [ ] `calculateSlaDeadline(2026-02-06, 06:15)` returns the UTC instant equivalent to `2026-02-06T05:15:00Z` (CET is UTC+1 in February)
- [ ] `calculateCetHour(instant equivalent to 06:15 CET)` returns `BigDecimal("6.25")`
- [ ] `formatDuration(8100000)` returns `"2hrs 15mins"`
- [ ] `getCetDate` returns the correct CET date for an instant spanning midnight CET
- [ ] Unit tests cover DST boundary dates (last Sunday of March and October)

---

### Issue 3.3 — `SlaEvaluationService`

**Labels:** `ingestion`, `backend`, `sla`, `priority::high`

**Description:**
Implement the synchronous SLA evaluation logic called during run completion (and partially during start).

**Technical Scope:**
- `evaluateSla(CalculatorRun run) → SlaEvaluationResult`:
  1. If `endTime > slaTime`: compute delay minutes, add reason `"END_TIME_EXCEEDED"`
  2. If `status=RUNNING AND now > slaTime`: compute delay, add `"STILL_RUNNING_PAST_SLA"`
  3. If `durationMs > expectedDurationMs * 1.5`: add `"DURATION_EXCEEDED"`
  4. If `status = FAILED or TIMEOUT`: add `"RUN_FAILED"`
  - All matching reasons accumulated; joined with `;`. `breached=true` if any reason present.
- `determineSeverity(run, reasons) → String`:
  - FAILED/TIMEOUT → always `CRITICAL`
  - Time delay >60min → `CRITICAL`; >30min → `HIGH`; >15min → `MEDIUM`; ≤15min → `LOW`
  - Duration-only breach → `MEDIUM`
  - No breach → `null`
- `SlaEvaluationResult`: `boolean breached`, `String reason`, `String severity`

**Implementation Notes:**
- Multiple breach conditions produce multiple reasons but a single severity (the highest across all applicable checks).
- The `STILL_RUNNING_PAST_SLA` check is present but only relevant for the live detection job — on-write completion checks typically use `END_TIME_EXCEEDED` instead.

**Dependencies:** Issue 2.1, Issue 3.2

**Acceptance Criteria:**
- [ ] Run with `endTime = slaTime + 20min` returns breach with reason `END_TIME_EXCEEDED`, severity `MEDIUM`
- [ ] Run with `endTime = slaTime + 70min` returns severity `CRITICAL`
- [ ] Run with `status=FAILED` returns `RUN_FAILED` and severity `CRITICAL` regardless of time
- [ ] Run with `durationMs = expectedDurationMs * 2` returns `DURATION_EXCEEDED`, severity `MEDIUM`
- [ ] Run that exceeds both time and duration accumulates both reasons in the result string
- [ ] Run with no breach returns `SlaEvaluationResult(breached=false, reason=null, severity=null)`

---

### Issue 3.4 — `RunIngestionService`

**Labels:** `ingestion`, `backend`, `priority::critical`

**Description:**
Implement the core ingestion service orchestrating `startRun()` and `completeRun()` — the primary write path of the system.

**Technical Scope:**
- `startRun(StartRunRequest, tenantId) → CalculatorRun`:
  1. Idempotency check: `findById(runId, reportingDate)` → return existing if found
  2. MONTHLY validation: warn if `reportingDate` is not end-of-month
  3. SLA deadline: `TimeUtils.calculateSlaDeadline(reportingDate, slaTimeCet)` (DAILY only)
  4. Start-time breach check: if `startTime > slaDeadline` → set `slaBreached=true`
  5. Build `CalculatorRun` (status=RUNNING), populate CET hour fields via `TimeUtils`
  6. DB write: `runRepository.upsert(run)`
  7. SLA monitoring registration (DAILY + not breached + slaTime != null)
  8. Publish `SlaBreachedEvent` if breached at start
  9. Publish `RunStartedEvent` unconditionally
- `completeRun(runId, CompleteRunRequest, tenantId) → CalculatorRun`:
  1. `findRecentRun(runId)`: tries last 7 days first (partition-safe), then falls back to `findById(String)` (documented scan)
  2. Tenant validation: throw `DomainAccessDeniedException` on mismatch
  3. Idempotency: return if `status != RUNNING`
  4. Validation: `endTime` must be after `startTime`
  5. Compute `durationMs`, resolve `RunStatus`
  6. `SlaEvaluationService.evaluateSla(run)` → update run fields
  7. `runRepository.upsert(run)`
  8. Deregister from SLA monitoring
  9. `updateDailyAggregate()` — calls `DailyAggregateRepository.upsertDaily()`, errors caught and logged
  10. Publish `SlaBreachedEvent` or `RunCompletedEvent`

**Implementation Notes:**
- Events are published AFTER the DB write. The `@TransactionalEventListener(AFTER_COMMIT)` on listeners guarantees they run post-commit.
- `updateDailyAggregate()` must not throw — catch and log at WARN.

**Dependencies:** Issue 2.2, Issue 2.5, Issue 3.3

**Acceptance Criteria:**
- [ ] Duplicate `startRun` with same `(runId, reportingDate)` returns identical response without DB re-write
- [ ] `completeRun` with wrong `tenantId` throws `DomainAccessDeniedException`
- [ ] `completeRun` where run is already COMPLETED returns existing run (idempotent)
- [ ] `endTime < startTime` throws `DomainValidationException`
- [ ] `SlaBreachedEvent` is published when run completes with SLA breach; `RunCompletedEvent` when not breached
- [ ] Daily aggregate is updated after every completion (verified by querying `calculator_sli_daily`)

---

### Issue 3.5 — `RunIngestionController`

**Labels:** `ingestion`, `backend`, `priority::high`

**Description:**
Implement the two ingestion HTTP endpoints with validation, metrics, and request logging.

**Technical Scope:**
- `POST /api/v1/runs/start` → 201 Created with `Location: /api/v1/runs/{runId}` header
- `POST /api/v1/runs/{runId}/complete` → 200 OK
- Both require `X-Tenant-Id` header (`@RequestHeader`) and HTTP Basic auth (`Principal`)
- Micrometer counters: `api.runs.start.requests`, `api.runs.complete.requests`
- Log INFO: `"Start run request from user {userId} for {calculatorId} in tenant {tenantId}"`
- `@Tag(name = "Run Ingestion")` for Swagger grouping

**Dependencies:** Issue 1.5, Issue 3.1, Issue 3.4

**Acceptance Criteria:**
- [ ] `POST /api/v1/runs/start` without auth returns 401
- [ ] `POST /api/v1/runs/start` without `X-Tenant-Id` returns 400
- [ ] Valid `startRun` returns 201 with `Location` header and `RunResponse` body
- [ ] `api.runs.start.requests` counter increments on every call (verified via `/actuator/prometheus`)
- [ ] Request body with missing `@NotBlank` field returns 400 with field-level error detail

---

---

## EPIC-4: Redis Caching Layer

**Goal:** Implement the full Redis write-through/read-through cache layer: run ZSET, status hash, SLA monitoring ZSET/hash, analytics cache, and cache warming service.

**Labels:** `epic`, `caching`, `redis`

---

### Issue 4.1 — `RedisCacheConfig`

**Labels:** `caching`, `redis`, `priority::high`

**Description:**
Configure the `RedisTemplate` and multi-tier `CacheManager` with correct serialization, connection settings, and cache name TTL mappings.

**Technical Scope:**
- `LettuceConnectionFactory`: `RedisStandaloneConfiguration`, socket options (`connectTimeout=5s`, `keepAlive=true`), `autoReconnect=true`, command timeout 2s
- `RedisTemplate<String, Object>`: key=`StringRedisSerializer`, value=`Jackson2JsonRedisSerializer<Object>`, hash-key=`StringRedisSerializer`, hash-value=`Jackson2JsonRedisSerializer<Object>`, `enableTransactionSupport=false`
- Jackson `ObjectMapper` for Redis: `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS=false`, `FAIL_ON_UNKNOWN_PROPERTIES=false`, `DefaultTyping.NON_FINAL` with `BasicPolymorphicTypeValidator`
- `RedisCacheManager` with named caches and TTLs: `calculatorStatus` 5m, `batchCalculatorStatus` 3m, `runningCount` 1m, `recentRuns:DAILY` 15m, `recentRuns:MONTHLY` 1h, `calculatorStats` 30m, `dailyAggregates` 2h, `calculatorMetadata` 6h, `activeCalculators` 1h, `historicalStats` 12h, `slaConfigs` 24h. All prefixed with `obs:`.

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] `RedisTemplate.opsForValue().set("test", "value")` and `.get("test")` round-trips correctly
- [ ] Instant values serialize as ISO-8601 strings (not epoch ms arrays)
- [ ] `RedisCacheManager` has all 11 named caches with correct TTLs (verified via `/actuator/caches`)
- [ ] Application reconnects to Redis after restart (Lettuce auto-reconnect)

---

### Issue 4.2 — `RedisCalculatorCache`: Run ZSET & Status Hash

**Labels:** `caching`, `redis`, `priority::critical`

**Description:**
Implement the primary Redis cache operations: run sorted set (recent runs) and status response hash.

**Technical Scope:**
- Run ZSET key: `obs:runs:zset:{calcId}:{tenantId}:{freq}`
  - `cacheRunOnWrite(run)`: ZADD scored by `createdAt.toEpochMilli()`, trim to 100 members, set TTL (5m RUNNING / 15m <30min-completed / 1h DAILY / 4h MONTHLY)
  - `updateRunInCache(run)`: ZREM old + ZADD updated (same score)
  - `getRecentRuns(calcId, tenantId, freq, limit)`: ZREVRANGE 0 limit-1 → `Optional<List<CalculatorRun>>`
  - `evictRecentRuns(calcId, tenantId, freq)`: DEL
- Status hash key: `obs:status:hash:{calcId}:{tenantId}:{freq}`
  - `cacheStatusResponse(calcId, tenantId, freq, historyLimit, response)`: HSET field=historyLimit, EXPIRE 30s (RUNNING) or 60s (completed)
  - `getStatusResponse(calcId, tenantId, freq, historyLimit)`: HGET → `Optional<CalculatorStatusResponse>`
  - `evictStatusResponse(calcId, tenantId, freq)`: DEL entire hash
  - `evictAllFrequencies(calcId, tenantId)`: DEL DAILY and MONTHLY hash keys
- Bloom filter (Set): `obs:active:bloom` — `addToBloomFilter(calcId)`: SADD + EXPIRE 24h; `mightExist(calcId)`: SISMEMBER
- Running Set: `obs:running` — `isRunning(calcId, tenantId, freq)`: SISMEMBER with member `{calcId}:{tenantId}:{freq}`
- Batch operations: `getBatchStatusResponses` and `cacheBatchStatusResponses` using Redis pipelining

**Implementation Notes:**
- `updateRunInCache()` is not atomic (ZREM + ZADD). A concurrent read between ops will see a cache miss — this is acceptable; the miss triggers a DB fallback.
- `obs:running` write path must be added when a run starts and removed when it completes.

**Dependencies:** Issue 4.1, Issue 2.1

**Acceptance Criteria:**
- [ ] `cacheRunOnWrite()` for a RUNNING run sets TTL of ~300s (5 minutes) ± 5s
- [ ] `cacheRunOnWrite()` trims ZSET to max 100 members when 101st is added
- [ ] `getRecentRuns()` returns empty Optional on cache miss, not null
- [ ] `evictStatusResponse()` removes all historyLimit variants (entire hash deleted)
- [ ] `getBatchStatusResponses()` for 10 calculators uses a single Redis pipeline (1 round-trip)

---

### Issue 4.3 — `SlaMonitoringCache`

**Labels:** `caching`, `redis`, `sla`, `priority::high`

**Description:**
Implement the SLA deadline monitoring cache used by the live breach detection job.

**Technical Scope:**
- Run key format: `{tenantId}:{runId}:{reportingDate}` (fallback `"unknown-{uuid}"` if nulls)
- `registerForSlaMonitoring(run)`: ZADD `obs:sla:deadlines` scored by `slaTime.toEpochMilli()` + HSET `obs:sla:run_info` with JSON `{runId, calcId, tenantId, reportingDate, startTime, slaTime}`. Both keys expire 24h. Gated by `observability.sla.live-tracking.enabled`.
- `deregisterFromSlaMonitoring(runId, tenantId, reportingDate)`: ZREM + HDEL
- `getBreachedRuns()`: `ZRANGEBYSCORE obs:sla:deadlines 0 {nowMs}` → for each key, HGET `obs:sla:run_info` → deserialize. Returns `List<Map<String,Object>>`. Exceptions swallowed, empty list returned.
- `getApproachingSlaRuns(minutesAhead)`: `ZRANGEBYSCORE obs:sla:deadlines {nowMs} {nowMs + minutesAhead*60000}`
- `getMonitoredRunCount()`: ZCARD
- `getNextSlaDeadline()`: ZRANGE 0 0 with WITHSCORES → first element score as Instant

**Dependencies:** Issue 4.1, Issue 2.1

**Acceptance Criteria:**
- [ ] `registerForSlaMonitoring(run)` creates entries in both `obs:sla:deadlines` and `obs:sla:run_info`
- [ ] `getBreachedRuns()` returns runs with score ≤ current epoch ms
- [ ] `deregisterFromSlaMonitoring()` removes from both ZSET and Hash
- [ ] Exception during `getBreachedRuns()` returns empty list (does not propagate)
- [ ] Registering with `observability.sla.live-tracking.enabled=false` is a no-op

---

### Issue 4.4 — `AnalyticsCacheService`

**Labels:** `caching`, `redis`, `analytics`, `priority::medium`

**Description:**
Implement the analytics result cache with index-based bulk eviction.

**Technical Scope:**
- Key patterns: `obs:analytics:{prefix}:{calcId}:{tenantId}:{days}` and `obs:analytics:{prefix}:{calcId}:{tenantId}:{freq}:{days}`. TTL: 5 minutes.
- Index key: `obs:analytics:index:{calcId}:{tenantId}`. TTL: 1 hour. Tracks all analytics keys for bulk eviction.
- `getFromCache<T>(prefix, calcId, tenantId, days, freq, type) → Optional<T>`: GET + Jackson deserialization
- `putInCache(prefix, calcId, tenantId, days, freq, value)`: SET with TTL + SADD to index key
- `evictForCalculator(calcId, tenantId)`: read index set → DEL all tracked keys + DEL index key itself
- `@TransactionalEventListener(AFTER_COMMIT) @Async` on `RunCompletedEvent` and `SlaBreachedEvent` → call `evictForCalculator()`

**Dependencies:** Issue 4.1

**Acceptance Criteria:**
- [ ] `getFromCache()` returns empty Optional on miss, correctly typed result on hit
- [ ] `evictForCalculator()` deletes all keys previously added via `putInCache()` for that calculator
- [ ] Cache is automatically evicted on `RunCompletedEvent` (verified by asserting cache miss after event)
- [ ] 5-minute TTL confirmed on cached keys via `TTL obs:analytics:*` in Redis CLI

---

### Issue 4.5 — `CacheWarmingService`

**Labels:** `caching`, `redis`, `priority::high`

**Description:**
Implement the active cache lifecycle manager that invalidates and re-warms the status cache on all run state transitions.

**Technical Scope:**
- Conditional on `observability.cache.warm-on-completion=true` (default)
- `onRunStarted(RunStartedEvent)`: `@TransactionalEventListener(AFTER_COMMIT) @Async` → `evictCacheForRun()` (invalidate status hash + runs ZSET)
- `onRunCompleted(RunCompletedEvent)`: same → `evictCacheForRun()` + `warmCacheForRun()` (re-query DB with `findRecentRuns(limit=20)` to repopulate ZSET + status hash)
- `onSlaBreached(SlaBreachedEvent)`: → `redisCache.updateRunInCache(run)` + `evictStatusResponse()`
- `evictCacheForRun(run)`: `evictStatusResponse() + evictRecentRuns()`
- `warmCacheForRun(run)`: `runRepository.findRecentRuns(calcId, tenantId, freq, 20)` — the write-through path in the repository re-populates cache. Errors logged, not propagated.

**Dependencies:** Issue 4.2, Issue 3.4

**Acceptance Criteria:**
- [ ] After `RunStartedEvent`, status hash for the calculator is deleted from Redis
- [ ] After `RunCompletedEvent`, status hash is absent then re-populated by next `findRecentRuns()` call
- [ ] `warmCacheForRun()` failure (e.g., DB timeout) is logged as ERROR but does not throw
- [ ] With `warm-on-completion=false`, no eviction or warming occurs on events

---

---

## EPIC-5: Calculator Status Query API

**Goal:** Implement the read-path APIs for single and batch calculator status, with Redis-first and DB-fallback semantics.

**Labels:** `epic`, `query`, `backend`

---

### Issue 5.1 — Status Response DTOs

**Labels:** `query`, `backend`, `priority::high`

**Description:**
Define the response DTOs for all status query endpoints.

**Technical Scope:**
- `CalculatorStatusResponse` (Lombok, `Serializable`): `calculatorName`, `lastRefreshed Instant`, `current RunStatusInfo`, `history List<RunStatusInfo>`
- `RunStatusInfo` (Lombok, `Serializable`): `runId`, `status`, `start Instant`, `end Instant`, `estimatedStart Instant`, `estimatedEnd Instant`, `sla Instant`, `durationMs Long`, `durationFormatted String`, `slaBreached Boolean`, `slaBreachReason String`

**Dependencies:** Issue 2.1

**Acceptance Criteria:**
- [ ] `CalculatorStatusResponse` serializes and deserializes via Jackson with all Instant fields as ISO-8601
- [ ] `CalculatorStatusResponse` implements `Serializable` (required for Redis serialization)

---

### Issue 5.2 — `RunQueryService`

**Labels:** `query`, `backend`, `priority::critical`

**Description:**
Implement the query service with Redis-first, DB-fallback, and cache-write-through semantics.

**Technical Scope:**
- `getCalculatorStatus(calcId, tenantId, frequency, historyLimit, bypassCache)`:
  1. If `!bypassCache`: check `redisCache.getStatusResponse()` → return if hit (counter: `query.calculator_status.cache_hit`)
  2. `runRepository.findRecentRuns(calcId, tenantId, frequency, historyLimit+1)`: first element = `current`, rest = `history`
  3. Build `CalculatorStatusResponse` via `mapToRunStatusInfo()`
  4. If `!bypassCache`: `redisCache.cacheStatusResponse()`
  5. Counter: `query.calculator_status.cache_miss` (tagged by frequency)
  6. Throw `DomainNotFoundException` if no runs found
- `getBatchCalculatorStatus(calcIds, tenantId, frequency, historyLimit, allowStale)`:
  1. If `allowStale`: `redisCache.getBatchStatusResponses()` (pipelined multi-hGet)
  2. Identify misses; query `runRepository.findBatchRecentRunsDbOnly()` for misses
  3. Build responses for misses; if `allowStale`: `redisCache.cacheBatchStatusResponses()`
  4. Merge cached + fresh; filter nulls
  5. Timers: `query.batch_status.duration`; counters: `query.batch_status.requests`

**Dependencies:** Issue 4.2, Issue 2.2, Issue 5.1

**Acceptance Criteria:**
- [ ] Cache hit returns response without DB query (verified by asserting repository not called)
- [ ] Cache miss queries DB, returns response, and writes to cache
- [ ] `bypassCache=true` always queries DB regardless of cache state
- [ ] `getBatchCalculatorStatus` for 20 calculators with all cached uses one pipelined Redis call
- [ ] Missing calculator throws `DomainNotFoundException` (404)

---

### Issue 5.3 — `RunQueryController`

**Labels:** `query`, `backend`, `priority::high`

**Description:**
Implement the two HTTP status query endpoints with cache-control headers and metrics.

**Technical Scope:**
- `GET /api/v1/calculators/{calculatorId}/status`: params `frequency` (required), `historyLimit @Min(1) @Max(100) default 5`, `bypassCache default false`. `Cache-Control: max-age=30, private` (or `no-cache` if `bypassCache=true`). Metric: `api.calculators.status.requests` tagged by `frequency` and `bypass_cache`.
- `POST /api/v1/calculators/batch/status`: body `List<String> @NotEmpty @Size(max=100)`, params `frequency` (required), `historyLimit @Min(1) @Max(50) default 5`, `allowStale default true`. `Cache-Control: max-age=60, private` (or `no-cache` if `allowStale=false`). Metric: `api.calculators.batch.requests`.
- `@Tag(name = "Calculator Status")`

**Dependencies:** Issue 1.5, Issue 5.2

**Acceptance Criteria:**
- [ ] Missing `frequency` parameter returns 400 with descriptive error
- [ ] `historyLimit=0` returns 400; `historyLimit=101` returns 400; `historyLimit=50` returns 200
- [ ] `bypassCache=true` sets `Cache-Control: no-cache` on response
- [ ] Batch with 101 calculator IDs returns 400
- [ ] Metrics tagged with correct frequency and cache flag values

---

### Issue 5.4 — `MetricsConfiguration`: Active Run Gauge

**Labels:** `query`, `backend`, `observability`, `priority::medium`

**Description:**
Register the `calculator.runs.active` Gauge metric that exposes the count of currently-running calculator runs.

**Technical Scope:**
- `MetricsConfiguration` (`@Configuration`): register a Gauge `calculator.runs.active` that calls `calculatorRunRepository.countRunning()` on every scrape.
- Graceful degradation: if `countRunning()` throws, log WARN and return 0 (gauge never fails).
- Tags: `application` and `environment` (using active Spring profile).

**Dependencies:** Issue 2.2, Issue 4.1

**Acceptance Criteria:**
- [ ] `/actuator/prometheus` contains `calculator_runs_active` metric
- [ ] Gauge returns correct count matching DB `WHERE status='RUNNING' AND reporting_date >= CURRENT_DATE - INTERVAL '7 days'`
- [ ] Redis exception during `countRunning()` fallback is handled; gauge returns 0 and does not break metrics scraping

---

---

## EPIC-6: SLA Detection & Alerting

**Goal:** Implement the dual-mechanism SLA detection system (on-write + live) and the full alert delivery pipeline including persistence, retry, and an extensible notification interface.

**Labels:** `epic`, `sla`, `alerting`

---

### Issue 6.1 — Spring Events: Event Classes & Publication

**Labels:** `sla`, `backend`, `priority::high`

**Description:**
Define the three application events and confirm publication points in the ingestion service.

**Technical Scope:**
- `RunStartedEvent(CalculatorRun run)`: extends `ApplicationEvent`
- `RunCompletedEvent(CalculatorRun run)`: extends `ApplicationEvent`
- `SlaBreachedEvent(CalculatorRun run, SlaEvaluationResult result)`: extends `ApplicationEvent`
- `SlaEvaluationResult`: Lombok `@AllArgsConstructor`, fields `boolean breached`, `String reason`, `String severity`
- Publication in `RunIngestionService`: `startRun` publishes `RunStartedEvent` (+ optionally `SlaBreachedEvent`); `completeRun` publishes `SlaBreachedEvent` or `RunCompletedEvent`

**Dependencies:** Issue 3.4

**Acceptance Criteria:**
- [ ] `RunStartedEvent` is published on every `startRun()` (including idempotent duplicates returning early? No — only on actual writes)
- [ ] `SlaBreachedEvent` is published when start-time breach detected
- [ ] `RunCompletedEvent` is published on non-breach completion; `SlaBreachedEvent` on breach completion
- [ ] Events contain the post-upsert `CalculatorRun` object (not pre-write)

---

### Issue 6.2 — `AlertSender` Interface & `LoggingAlertSender`

**Labels:** `sla`, `alerting`, `priority::high`

**Description:**
Define the alert delivery abstraction and implement the default logging-based sender. This interface is the extension point for future real notification channels.

**Technical Scope:**
- `AlertSender` interface: `void send(SlaBreachEvent breach) throws AlertDeliveryException`
- `AlertDeliveryException`: runtime exception wrapping delivery failures
- `LoggingAlertSender` (`@Component`, `@Primary` unless overridden): logs a structured WARN message containing `runId`, `calculatorName`, `severity`, `breachType`, `breachReason`. Always succeeds (no exception).
- `AlertSenderConfig` (`@Configuration`): conditionally activates senders based on `observability.alerts.channel` property (default: `logging`)

**Implementation Notes:**
- The `AlertSender` abstraction is the correct extension point for Slack, email, PagerDuty, etc. in future epics. Do not couple `AlertHandlerService` directly to a concrete delivery mechanism.

**Dependencies:** Issue 2.6

**Acceptance Criteria:**
- [ ] `LoggingAlertSender.send()` produces a WARN log with all required fields
- [ ] `LoggingAlertSender.send()` never throws `AlertDeliveryException`
- [ ] `AlertSender` can be swapped by providing an alternative `@Primary @Component` implementation
- [ ] Unit test verifies log output contains `severity` and `calculatorName`

---

### Issue 6.3 — `AlertHandlerService`

**Labels:** `sla`, `alerting`, `priority::critical`

**Description:**
Implement the `SlaBreachedEvent` listener that persists the breach record and invokes the alert sender.

**Technical Scope:**
- `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` + `@Transactional(propagation = REQUIRES_NEW)`
- On `SlaBreachedEvent`:
  1. Build `SlaBreachEvent` domain object: populate from run + `SlaEvaluationResult`; `alertStatus = "PENDING"`
  2. `breachRepository.save(breach)` → catch `DuplicateKeyException` → counter `sla.breaches.duplicate`; return
  3. Counter: `sla.breaches.created` tagged by `severity` and `frequency`
  4. Call `alertSender.send(breach)`
  5. On success: update breach record `alerted=true, alerted_at=now, alertStatus="SENT"`; counter `sla.alerts.sent`
  6. On `AlertDeliveryException`: update `alertStatus="FAILED"`, increment `retryCount`, store `lastError`; counter `sla.alerts.failed`; rethrow (async task failure)
- `determineBreachType(SlaEvaluationResult)`: maps reason string to `BreachType` enum value
- `calculateExpectedValue(run)`: returns SLA deadline epoch seconds if available, else `expectedDurationMs`
- `calculateActualValue(run)`: returns actual end time epoch seconds or `durationMs`

**Dependencies:** Issue 2.6, Issue 6.1, Issue 6.2

**Acceptance Criteria:**
- [ ] A `SlaBreachedEvent` results in one `sla_breach_events` row in the DB
- [ ] Second `SlaBreachedEvent` for the same `run_id` is caught as duplicate and skipped (counter incremented)
- [ ] `alertStatus` transitions from `PENDING` → `SENT` after successful `LoggingAlertSender.send()`
- [ ] `AlertDeliveryException` from the sender sets `alertStatus="FAILED"` and increments `retryCount`
- [ ] Handler executes in a new transaction (verified: original run commit is not rolled back on alert failure)

---

### Issue 6.4 — `LiveSlaBreachDetectionJob`

**Labels:** `sla`, `scheduled`, `priority::critical`

**Description:**
Implement the scheduled job that continuously monitors the Redis SLA ZSET and fires breach events for DAILY runs that pass their SLA deadline without completing.

**Technical Scope:**
- `@Scheduled(fixedDelayString = "${observability.sla.live-detection.interval-ms:120000}", initialDelayString = "30000")` — default 2 minutes
- `detectLiveSlaBreaches()`:
  1. `slaMonitoringCache.getBreachedRuns()` → list of run metadata maps
  2. Per run: parse `runId`, `tenantId`, `reportingDate`
  3. `runRepository.findById(runId, reportingDate)` — partition-safe lookup
  4. Dedup gates: not found → deregister + skip; status != RUNNING → deregister + skip; `slaBreached==true` → deregister + skip
  5. `runRepository.markSlaBreached(runId, reason, reportingDate)` — reason: `"Still running X minutes past SLA deadline (live detection)"`
  6. `eventPublisher.publishEvent(new SlaBreachedEvent(run, result))`
  7. `slaMonitoringCache.deregisterFromSlaMonitoring(runId, tenantId, reportingDate)`
  8. Counter: `sla.breaches.live_detected` tagged by severity
  9. Outer try-catch: log + counter `sla.breach.live_detection.failures` on total failure
- Early warning: `@Scheduled(fixedDelayString = "${observability.sla.early-warning.interval-ms:180000}", initialDelayString = "30000")` — `getApproachingSlaRuns(10)` → log WARN per approaching run; gauge `sla.approaching.count`

**Dependencies:** Issue 4.3, Issue 2.2, Issue 6.1

**Acceptance Criteria:**
- [ ] A DAILY run registered in SLA cache that passes its deadline triggers a `SlaBreachedEvent` within one detection interval
- [ ] A run that completes normally (deregistered before deadline) is NOT re-breached by the job
- [ ] A run already marked `sla_breached=true` is deregistered and not processed again
- [ ] Job failure for one run logs ERROR and continues processing remaining runs
- [ ] `sla.breaches.live_detected` counter increments on each live breach; `sla.breach.live_detection.failures` on job error

---

### Issue 6.5 — Alert Retry Sweep Job

**Labels:** `sla`, `alerting`, `scheduled`, `priority::medium`

**Description:**
Implement a scheduled job that retries failed alert deliveries for breach records in `PENDING`, `FAILED`, or `RETRYING` status.

**Technical Scope:**
- `@Scheduled(fixedDelayString = "${observability.alerts.retry-interval-ms:300000}")` — default 5 minutes
- `retryUnalertedBreaches()`:
  1. `breachRepository.findUnalertedBreaches(limit=50)` — fetches `PENDING`, `FAILED`, `RETRYING`
  2. Per breach: set `alertStatus="RETRYING"`, increment `retryCount`, update record
  3. Call `alertSender.send(breach)` → on success: `alerted=true, alertStatus="SENT"`; on failure: `alertStatus="FAILED"`, store `lastError`
  4. Skip breaches with `retryCount > maxRetries` (default 5): set `alertStatus="FAILED"` permanently, log ERROR
- Controlled by `observability.alerts.retry.enabled=true` (default)

**Dependencies:** Issue 2.6, Issue 6.2, Issue 6.3

**Acceptance Criteria:**
- [ ] A breach with `alertStatus='FAILED'` is retried within one retry interval
- [ ] After `maxRetries` exhausted, breach is permanently set to `FAILED` and not retried again
- [ ] Successful retry sets `alerted=true`, `alertStatus='SENT'`, `alerted_at=now`
- [ ] `findUnalertedBreaches()` includes `RETRYING` status in its query

---

### Issue 6.6 — Slack Alert Sender (Webhook)

**Labels:** `sla`, `alerting`, `priority::medium`

**Description:**
Implement a `SlackAlertSender` that delivers breach notifications to a configured Slack webhook URL, activatable via `observability.alerts.channel=slack`.

**Technical Scope:**
- `SlackAlertSender implements AlertSender`: conditional on `observability.alerts.channel=slack`
- Webhook URL from `observability.alerts.slack.webhook-url` (required when active)
- Payload: structured Slack Block Kit message with: calculator name, severity (colour-coded: 🔴 CRITICAL/HIGH, 🟡 MEDIUM/LOW), breach type, expected vs actual values, run ID
- HTTP POST via `RestTemplate` or `WebClient`; timeout: 5s connect, 10s read
- On HTTP 4xx/5xx or timeout: throw `AlertDeliveryException` (handled by `AlertHandlerService` retry logic)

**Dependencies:** Issue 6.2

**Acceptance Criteria:**
- [ ] `SlackAlertSender` only activates when `observability.alerts.channel=slack`
- [ ] Payload serializes correctly to Slack Block Kit JSON
- [ ] HTTP 4xx from Slack throws `AlertDeliveryException`
- [ ] Timeout throws `AlertDeliveryException` (not a raw `SocketTimeoutException`)
- [ ] Webhook URL missing when channel=slack causes application startup failure with clear error

---

---

## EPIC-7: Analytics API

**Goal:** Implement all five analytics endpoints with pre-aggregated data sources, analytics cache integration, and keyset-paginated breach detail.

**Labels:** `epic`, `analytics`, `backend`

---

### Issue 7.1 — Analytics Response DTOs

**Labels:** `analytics`, `backend`, `priority::high`

**Description:**
Define all analytics response DTOs.

**Technical Scope:**
- `RuntimeAnalyticsResponse` (`Serializable`): `calculatorId`, `periodDays`, `frequency`, `avgDurationMs`, `avgDurationFormatted`, `minDurationMs`, `maxDurationMs`, `totalRuns`, `successRate double`, `dataPoints List<DailyDataPoint>`. Nested `DailyDataPoint`: `date LocalDate`, `avgDurationMs`, `totalRuns`, `successRuns`.
- `SlaSummaryResponse` (`Serializable`): `calculatorId`, `periodDays`, `totalBreaches`, `greenDays`, `amberDays`, `redDays`, `breachesBySeverity Map<String,Integer>`, `breachesByType Map<String,Integer>`.
- `TrendAnalyticsResponse` (`Serializable`): `calculatorId`, `periodDays`, `trends List<TrendDataPoint>`. Nested `TrendDataPoint`: `date`, `avgDurationMs`, `totalRuns`, `successRuns`, `slaBreaches`, `avgStartMinCet`, `avgEndMinCet`, `slaStatus String` (GREEN/AMBER/RED).
- `SlaBreachDetailResponse` (`Serializable`): `breachId`, `runId`, `calculatorId`, `calculatorName`, `breachType`, `severity`, `slaStatus` (AMBER/RED derived from severity), `expectedValue`, `actualValue`, `createdAt`.
- `PagedResponse<T extends Serializable>` (`Serializable`): `content List<T>`, `page`, `size`, `totalElements`, `totalPages`, `nextCursor String`.
- `PerformanceCardResponse` (`Serializable`): see tech-spec §5.3 for full nested structure (`ScheduleInfo`, `SlaSummaryPct`, `RunBar`, `ReferenceLines`).

**Dependencies:** Issue 2.1

**Acceptance Criteria:**
- [ ] All response DTOs implement `Serializable`
- [ ] `PagedResponse` is generic (`<T extends Serializable>`)
- [ ] `SlaSummaryPct.slaMetPct + latePct + veryLatePct` sums to 100.0 (enforced in builder or validated in test)
- [ ] `PerformanceCardResponse.RunBar.slaStatus` accepts only `SLA_MET`, `LATE`, `VERY_LATE`

---

### Issue 7.2 — `AnalyticsService`: Runtime & Trends

**Labels:** `analytics`, `backend`, `priority::high`

**Description:**
Implement `getRuntimeAnalytics()` and `getTrends()` service methods backed by `calculator_sli_daily`.

**Technical Scope:**
- `getRuntimeAnalytics(calcId, tenantId, days, frequency)`:
  - Cache check via `AnalyticsCacheService`
  - `dailyAggregateRepository.findRecentAggregates(calcId, tenantId, days)`
  - Aggregation: weighted avg duration `Σ(avg*runs)/Σ(runs)`, min/max, success rate `Σ(success)/Σ(total)`
  - Each daily aggregate → one `DailyDataPoint`
  - Cache result; return `RuntimeAnalyticsResponse`
- `getTrends(calcId, tenantId, days)`:
  - Cache check
  - `findRecentAggregates()` → one `TrendDataPoint` per aggregate
  - Per-day SLA status classification: GREEN=0 breaches, RED=has HIGH/CRITICAL breach, AMBER=other
  - Cache; return `TrendAnalyticsResponse`

**Dependencies:** Issue 2.5, Issue 4.4, Issue 7.1

**Acceptance Criteria:**
- [ ] Weighted average calculation is verified with a known dataset (3 days: 100ms×2runs, 200ms×1run → avg=133ms)
- [ ] Day with no breaches → GREEN; day with CRITICAL breach → RED; day with MEDIUM breach → AMBER
- [ ] Cache hit on second call (no DB query on second invocation)
- [ ] `days=365` returns data points from 365 days back (not limited to 30)

---

### Issue 7.3 — `AnalyticsService`: SLA Summary & Breach Detail

**Labels:** `analytics`, `backend`, `sla`, `priority::high`

**Description:**
Implement `getSlaSummary()` and `getSlaBreachDetails()` — including the keyset cursor pagination logic.

**Technical Scope:**
- `getSlaSummary(calcId, tenantId, days)`:
  - Cache check
  - `dailyAggregateRepository.findRecentAggregates()` → count GREEN/AMBER/RED days
  - `slaBreachEventRepository.findByCalculatorIdAndPeriod()` → group by severity and breach type
  - Cache; return `SlaSummaryResponse`
- `getSlaBreachDetails(calcId, tenantId, days, severity, page, size, cursor)`:
  - **No cache** (fresh data always)
  - Mode selection: `cursor != null` → keyset; `cursor == null AND page > 0` → offset; else first-page keyset
  - `countByCalculatorIdAndPeriod()` for `totalElements`
  - Fetch `size+1` rows; if `results.size() > size` → more pages, encode `nextCursor` = Base64URL `{createdAt}:{breachId}` of last result
  - `decodeCursor()`: parse Base64URL → `(Instant createdAt, Long breachId)`; return null on decode error (treat as first page)
  - Return `PagedResponse<SlaBreachDetailResponse>`
- `toBreachDetail(SlaBreachEvent)`: map severity HIGH/CRITICAL → `slaStatus="RED"`, else `"AMBER"`

**Dependencies:** Issue 2.5, Issue 2.6, Issue 4.4, Issue 7.1

**Acceptance Criteria:**
- [ ] 50-breach dataset with `size=10, cursor=null` returns 10 items + `nextCursor`
- [ ] Using `nextCursor` from page 1 returns the correct next 10 items (no duplicates, no gaps)
- [ ] `severity=HIGH` filter returns only HIGH-severity breaches
- [ ] `totalElements` matches `countByCalculatorIdAndPeriod()` result
- [ ] Corrupt cursor value is treated as first-page (no exception thrown)

---

### Issue 7.4 — `AnalyticsService`: Performance Card

**Labels:** `analytics`, `backend`, `priority::high`

**Description:**
Implement `getPerformanceCard()` — the composite endpoint reading raw run data with JOIN.

**Technical Scope:**
- `getPerformanceCard(calcId, tenantId, days, frequency)`:
  - Cache check
  - `calculatorRunRepository.findRunsWithSlaStatus(calcId, tenantId, frequency, days)` → `List<RunWithSlaStatus>`
  - Per-run classification: `SLA_MET` (not breached), `LATE` (breached, severity LOW/MEDIUM), `VERY_LATE` (severity HIGH/CRITICAL)
  - Mean duration: `Σ(durationMs) / count` for completed runs (durationMs > 0)
  - SLA percentages: `slaMetPct`, `latePct`, `veryLatePct` rounded to 1 decimal, sum = 100%
  - Reference lines: from the latest run's `estimatedStartTime` and `slaTime` fields
  - Runs sorted chronologically ascending for chart rendering
  - Build and cache `PerformanceCardResponse`

**Dependencies:** Issue 2.2, Issue 4.4, Issue 7.1

**Acceptance Criteria:**
- [ ] `slaMetPct + latePct + veryLatePct = 100.0` (verified with rounding edge cases)
- [ ] Runs returned in `reportingDate ASC, createdAt ASC` order
- [ ] `days=30` with no runs returns an empty `runs` list (not 404)
- [ ] `startHourCet` and `endHourCet` in `RunBar` match the corresponding `TimeUtils.calculateCetHour()` output

---

### Issue 7.5 — `AnalyticsController`

**Labels:** `analytics`, `backend`, `priority::high`

**Description:**
Implement the five analytics HTTP endpoints.

**Technical Scope:**
- All under `/api/v1/analytics/calculators/{calculatorId}`, `@Tag(name = "Analytics")`
- `/runtime`: `days @Min(1) @Max(365)`, `frequency default DAILY`. `Cache-Control: max-age=60, private`. Counter: `api.analytics.runtime.requests`.
- `/sla-summary`: `days @Min(1) @Max(365)`. `Cache-Control: max-age=60, private`. Counter: `api.analytics.sla-summary.requests`.
- `/trends`: `days @Min(1) @Max(365)`. `Cache-Control: max-age=60, private`. Counter: `api.analytics.trends.requests`.
- `/sla-breaches`: `days @Min(1) @Max(365)`, `severity optional`, `page @Min(0) default 0`, `size @Min(1) @Max(100) default 20`, `cursor optional`. `Cache-Control: no-cache`. Counter: `api.analytics.sla-breaches.requests`.
- `/performance-card`: `days @Min(1) @Max(365) default 30`, `frequency default DAILY`. `Cache-Control: max-age=60, private`. Counter: `api.analytics.performance-card.requests`.

**Dependencies:** Issue 1.5, Issue 7.2, Issue 7.3, Issue 7.4

**Acceptance Criteria:**
- [ ] `days=0` returns 400; `days=366` returns 400; `days=365` returns 200
- [ ] `/sla-breaches` always returns `Cache-Control: no-cache`
- [ ] All 5 analytics counters increment on each call (verified via `/actuator/prometheus`)
- [ ] Missing `X-Tenant-Id` on any analytics endpoint returns 400

---

### Issue 7.6 — Helper Views (V10)

**Labels:** `analytics`, `database`, `priority::low`

**Description:**
Create the three SQL helper views that provide convenient partition-safe windows on `calculator_runs`.

**Technical Scope:**
- `V10__helper_views.sql`:
  - `recent_daily_runs`: `WHERE frequency='DAILY' AND reporting_date >= CURRENT_DATE - INTERVAL '3 days' AND reporting_date <= CURRENT_DATE`
  - `recent_monthly_runs`: `WHERE frequency='MONTHLY' AND reporting_date = (DATE_TRUNC('month', reporting_date) + INTERVAL '1 month - 1 day')::DATE AND reporting_date >= CURRENT_DATE - INTERVAL '13 months'`
  - `active_calculator_runs`: `WHERE status='RUNNING' AND reporting_date >= CURRENT_DATE - INTERVAL '7 days'`

**Dependencies:** Issue 2.3

**Acceptance Criteria:**
- [ ] `SELECT COUNT(*) FROM recent_daily_runs` returns only rows within last 3 days
- [ ] `SELECT COUNT(*) FROM recent_monthly_runs` returns only end-of-month rows within last 13 months
- [ ] `EXPLAIN` on `recent_daily_runs` shows partition constraint exclusion (not full scan)
- [ ] All three views survive `flyway repair` and re-apply cleanly

---

---

## EPIC-8: Partition Management & DB Maintenance

**Goal:** Implement scheduled partition lifecycle management, maintenance functions, and partition health monitoring.

**Labels:** `epic`, `database`, `maintenance`

---

### Issue 8.1 — `PartitionManagementJob`

**Labels:** `maintenance`, `scheduled`, `database`, `priority::high`

**Description:**
Implement the scheduled job that creates future partitions, drops old ones, and monitors partition health.

**Technical Scope:**
- `createPartitions()`: `@Scheduled(cron="${observability.partitions.management.create-cron:0 0 1 * * *}")` — calls `SELECT create_calculator_run_partitions()` asynchronously; metrics `partitions.create.success` / `partitions.create.failures`
- `dropOldPartitions()`: `@Scheduled(cron="${observability.partitions.management.drop-cron:0 0 2 * * SUN}")` — calls `SELECT drop_old_calculator_run_partitions()`; metrics `partitions.drop.success` / `partitions.drop.failures`
- `monitorPartitionHealth()`: `@Scheduled(cron="${observability.partitions.monitoring.cron:0 0 6 * * *}")` — `SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 30`; record gauges: `partitions.total_rows`, `partitions.daily_rows`, `partitions.monthly_rows`, `partitions.count`
- Controlled by `observability.partitions.management.enabled=true` (default)

**Dependencies:** Issue 2.3

**Acceptance Criteria:**
- [ ] After `createPartitions()` executes, 60 future partitions exist (verified via `\dt calculator_runs_*`)
- [ ] `dropOldPartitions()` removes partitions confirmed older than 395 days
- [ ] Both create and drop jobs are idempotent (second execution is a no-op)
- [ ] `partitions.create.failures` counter increments on DB error; exception does not crash the JVM
- [ ] `partitions.count` gauge reflects correct number of partitions after creation

---

### Issue 8.2 — DB Maintenance Functions (V9)

**Labels:** `maintenance`, `database`, `priority::medium`

**Description:**
Implement DB-level maintenance functions referenced by the application.

**Technical Scope:**
- `V9__maintenance_functions.sql`: Create only functions that are actually called by the application. **Do NOT create** `cleanup_expired_idempotency_keys()` — this references the `idempotency_keys` table which does not exist and will never be called.
- Optional: `analyze_calculator_runs()` — runs `ANALYZE calculator_runs` to update statistics for the query planner. Can be called periodically by `PartitionManagementJob`.

**Implementation Notes:**
- No `idempotency_keys` table, no `cleanup_expired_idempotency_keys()` function — these are intentionally excluded.

**Dependencies:** Issue 1.3

**Acceptance Criteria:**
- [ ] V9 migration applies cleanly with no errors
- [ ] No orphaned functions referencing non-existent tables
- [ ] All functions created in V9 have a corresponding caller in the Java codebase

---

### Issue 8.3 — Partition Health Alerting

**Labels:** `maintenance`, `observability`, `priority::medium`

**Description:**
Add an alerting mechanism for partition creation failure — a missed partition prevents all inserts for that date.

**Technical Scope:**
- After `createPartitions()` failure: in addition to incrementing the counter, send an alert via `AlertSender` with severity `CRITICAL` and a synthetic `SlaBreachEvent`-like payload, OR log at ERROR level with a specific marker that can be captured by Azure Monitor log alerts.
- Add a Prometheus alerting rule definition (YAML, for documentation/reference) for `partitions_create_failures_total > 0`.
- Add `/actuator/health` custom `HealthIndicator` (`PartitionHealthIndicator`) that returns `DOWN` if the most recent partition creation failed.

**Dependencies:** Issue 8.1, Issue 6.2

**Acceptance Criteria:**
- [ ] Simulated `createPartitions()` failure causes `partitions.create.failures` to increment AND an ERROR log is emitted with a searchable marker (e.g., `[PARTITION_CREATION_FAILED]`)
- [ ] `GET /actuator/health` returns `status: DOWN` when `PartitionHealthIndicator` detects a recent failure
- [ ] Prometheus alerting rule YAML is present in `src/main/resources/alerts/` or `docs/`

---

### Issue 8.4 — DB Schema Cleanup Migration

**Labels:** `maintenance`, `database`, `priority::low`

**Description:**
Clean up the schema of anything that should not exist: no orphaned functions, no vestigial tables, consistent naming.

**Technical Scope:**
- No `calculator_statistics` table (not created — excluded from initial schema)
- No `idempotency_keys` table (not created — excluded from initial schema)
- No `cleanup_expired_idempotency_keys()` function (not created — excluded from V9)
- Verify final schema matches exactly what the Java codebase reads/writes: `calculator_runs` (partitioned), `calculator_sli_daily`, `sla_breach_events`

**Dependencies:** Issue 1.3, Issue 2.4, Issue 8.2

**Acceptance Criteria:**
- [ ] `\df` in psql shows no functions referencing non-existent tables
- [ ] `\dt` shows exactly the three expected tables (plus Flyway history and partitions)
- [ ] Every table and column in the DB has a corresponding mapping in at least one Java `RowMapper`

---

---

## EPIC-9: Observability & Metrics

**Goal:** Ensure the system is fully observable in production: per-endpoint latency timers, Redis operation metrics, structured request logging, and a complete Prometheus metrics catalogue.

**Labels:** `epic`, `observability`, `metrics`

---

### Issue 9.1 — Per-Endpoint Latency Timers

**Labels:** `observability`, `metrics`, `priority::high`

**Description:**
Add `Timer` metrics to all 10 API endpoints so latency percentiles are available in Prometheus/Grafana.

**Technical Scope:**
- Add `Timer.Sample` start + `Timer.record()` in every controller method (or use `@Timed` annotation with AOP if available)
- Timer names:
  - `api.runs.start.duration`
  - `api.runs.complete.duration`
  - `api.calculators.status.duration` (tagged: `frequency`, `bypass_cache`, `cache_hit`)
  - `api.calculators.batch.duration` (tagged: `frequency`, `allow_stale`, `batch_size` histogram)
  - `api.analytics.runtime.duration`
  - `api.analytics.sla-summary.duration`
  - `api.analytics.trends.duration`
  - `api.analytics.sla-breaches.duration`
  - `api.analytics.performance-card.duration`
- Record as Micrometer `Timer` (not `DistributionSummary`) with percentile publishing enabled: p50, p95, p99

**Dependencies:** Issue 3.5, Issue 5.3, Issue 7.5

**Acceptance Criteria:**
- [ ] `/actuator/prometheus` contains `api_runs_start_duration_seconds_bucket` histograms
- [ ] `api.calculators.status.duration` tagged with `cache_hit=true` vs `false` (enables cache-hit latency comparison)
- [ ] p99 latency is measurable for each endpoint after 100 requests in a load test
- [ ] Timer recording does not interfere with exception paths (Timer always records even on error responses)

---

### Issue 9.2 — Redis Operation Metrics

**Labels:** `observability`, `metrics`, `redis`, `priority::medium`

**Description:**
Add timing and error metrics for critical Redis operations to surface latency and failure rates.

**Technical Scope:**
- Wrap `RedisCalculatorCache` hot-path methods with `Timer` recording:
  - `redis.cache.get.duration` (tagged: `operation=getStatusResponse/getRecentRuns/getBatchStatus`)
  - `redis.cache.set.duration` (tagged: `operation=cacheStatusResponse/cacheRunOnWrite`)
  - `redis.cache.error` counter (tagged: `operation`, `error_type`)
- `SlaMonitoringCache`: `redis.sla.getBreachedRuns.duration`, `redis.sla.register.duration`

**Dependencies:** Issue 4.2, Issue 4.3

**Acceptance Criteria:**
- [ ] Prometheus contains `redis_cache_get_duration_seconds` for each tagged operation
- [ ] `redis.cache.error` counter increments when Redis throws `RedisConnectionFailureException`
- [ ] No additional Redis round-trips introduced by the instrumentation itself

---

### Issue 9.3 — SLA Alert Backlog Metric

**Labels:** `observability`, `metrics`, `sla`, `priority::medium`

**Description:**
Expose a gauge for unalerted breach records so teams can detect alert delivery failures before they accumulate.

**Technical Scope:**
- Gauge `sla.alerts.backlog`: queries `SELECT COUNT(*) FROM sla_breach_events WHERE alerted=false AND alert_status IN ('PENDING','FAILED','RETRYING')` — evaluated on Prometheus scrape
- Threshold guidance: `> 0` for 10+ minutes = alert delivery degraded; `> 10` = critical alert delivery failure
- Graceful: gauge returns 0 on DB error

**Dependencies:** Issue 2.6

**Acceptance Criteria:**
- [ ] `/actuator/prometheus` contains `sla_alerts_backlog` gauge
- [ ] Gauge value matches `SELECT COUNT(*)...` at the time of scrape
- [ ] DB error during gauge evaluation returns 0 and logs WARN (never breaks metrics scraping)

---

### Issue 9.4 — Structured Request/Response Logging Audit

**Labels:** `observability`, `logging`, `priority::medium`

**Description:**
Ensure all controllers log sufficient context at the appropriate level, and that MDC is consistently populated.

**Technical Scope:**
- All ingestion controller endpoints: INFO log with `userId`, `calculatorId`/`runId`, `tenantId`
- Query and analytics controllers: DEBUG log with key params (calculatorId, frequency, days)
- All logs must include `requestId` via MDC (`X-Request-ID`)
- No PII or secrets logged at any level
- Dev profile enables `org.springframework.jdbc: DEBUG` for SQL tracing; prod disables it
- Remove `org.hibernate.SQL: DEBUG` from `application-dev.yml` (JPA not used)

**Dependencies:** Issue 1.1, Issue 3.5

**Acceptance Criteria:**
- [ ] Every ingestion request produces one INFO log line with `requestId`, `userId`, `calculatorId`, `tenantId`
- [ ] `X-Request-ID` appears in all log lines for a given request (MDC propagated through async context)
- [ ] `org.hibernate.SQL` logger is removed from all profile configurations
- [ ] No `password`, `token`, or `Authorization` values appear in any log output

---

### Issue 9.5 — Health Indicator Enrichment

**Labels:** `observability`, `health`, `priority::medium`

**Description:**
Enrich the `/actuator/health` endpoint with component-level health indicators for PostgreSQL and Redis connectivity.

**Technical Scope:**
- Spring Boot's built-in `DataSourceHealthIndicator` and `RedisHealthIndicator` are auto-configured — verify they appear under `/actuator/health/db` and `/actuator/health/redis`
- Add `PartitionHealthIndicator` (from Issue 8.3)
- `management.endpoint.health.show-details: always` (already configured)
- `management.health.livenessstate.enabled: true`, `management.health.readinessstate.enabled: true`
- Kubernetes probes: `/actuator/health/liveness`, `/actuator/health/readiness`

**Dependencies:** Issue 1.1, Issue 8.3

**Acceptance Criteria:**
- [ ] `GET /actuator/health` returns component status for `db`, `redis`, `partitionHealth`
- [ ] DB health returns `DOWN` when PostgreSQL is unreachable
- [ ] Redis health returns `DOWN` when Redis is unreachable; application remains `UP` (Redis failure is non-fatal)
- [ ] Liveness probe at `/actuator/health/liveness` returns 200 when app is running, regardless of Redis state

---

---

## EPIC-10: Security Hardening

**Goal:** Harden the service's security posture: encrypted credentials, tenant-principal binding, mandatory env-var enforcement in production.

**Labels:** `epic`, `security`

---

### Issue 10.1 — Replace Plaintext Password Encoding with BCrypt

**Labels:** `security`, `priority::high`

**Description:**
Replace the `{noop}` plaintext password encoder in `BasicSecurityConfig` with BCrypt. Default credentials must not be `admin/admin` in non-local profiles.

**Technical Scope:**
- `BasicSecurityConfig`: change to `{bcrypt}` password encoding
- `application.yml`: encode the default password with BCrypt in configuration, or require it to be provided pre-hashed via environment variable
- Local profile may retain `{noop}` for convenience, but all other profiles must use BCrypt
- Startup validation: if `SPRING_PROFILES_ACTIVE` is `dev` or `prod` and `OBS_BASIC_PASSWORD` is the default `admin`, fail startup with a clear error message

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] `{noop}` encoding is not present in `dev` or `prod` profile configurations
- [ ] Application with `SPRING_PROFILES_ACTIVE=prod` and default `admin/admin` fails to start with an explicit error
- [ ] `{bcrypt}` encoded password authenticates correctly via HTTP Basic
- [ ] Local profile still accepts plaintext for developer convenience

---

### Issue 10.2 — Tenant-Principal Binding Validation

**Labels:** `security`, `priority::medium`

**Description:**
Prevent cross-tenant data access by binding the authenticated user's permitted tenants to the `X-Tenant-Id` header value.

**Technical Scope:**
- Define `observability.security.allowed-tenants` property: a comma-separated list of tenant IDs the configured user is permitted to access (wildcard `*` = all tenants, default for single-tenant deployments)
- `TenantValidationFilter` or `@Aspect`: intercept all authenticated requests; if `allowed-tenants != *`, validate that the `X-Tenant-Id` value is in the permitted list; throw `DomainAccessDeniedException` (403) if not
- Multi-tenant deployments can configure separate Basic Auth users per tenant by running multiple instances or using a future per-user config

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] With `allowed-tenants=tenant-A`, a request with `X-Tenant-Id: tenant-B` returns 403
- [ ] With `allowed-tenants=*`, any tenant ID is accepted
- [ ] Without `X-Tenant-Id` header, request returns 400 (existing validation unchanged)
- [ ] Validation does not add >1ms to request latency (in-memory list check)

---

### Issue 10.3 — Secrets Management: Mandatory Env Var Enforcement

**Labels:** `security`, `priority::high`

**Description:**
Ensure production deployments cannot use default credentials or localhost infrastructure addresses.

**Technical Scope:**
- `ProductionConfigValidator` (`@Component`, active on `prod` profile only): validates on startup:
  - `OBS_BASIC_USER` is not `admin`
  - `OBS_BASIC_PASSWORD` is not `admin` (and length > 16 chars)
  - `POSTGRES_HOST` is not `localhost` or `127.0.0.1`
  - `REDIS_HOST` is not `localhost` or `127.0.0.1`
  - `REDIS_PASSWORD` is not empty
  - If any check fails: throw `ApplicationContextException` with descriptive message listing all violations

**Dependencies:** Issue 1.1, Issue 10.1

**Acceptance Criteria:**
- [ ] Starting with `prod` profile and default `POSTGRES_HOST=localhost` fails with a list of all configuration violations
- [ ] Starting with all production-appropriate values succeeds
- [ ] Validator is not active on `local` or `dev` profiles
- [ ] Validator provides all violations at once (not fail-fast on first)

---

### Issue 10.4 — Security Audit & Headers

**Labels:** `security`, `priority::low`

**Description:**
Add security response headers and conduct a basic OWASP Top 10 audit of the implemented endpoints.

**Technical Scope:**
- Add security headers via Spring Security `headers()` configuration:
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Strict-Transport-Security: max-age=31536000` (prod only)
  - Remove `X-Powered-By` (Spring Boot default)
- OWASP audit checklist:
  - A01 Broken Access Control: tenant isolation verified
  - A03 Injection: all SQL uses parameterized queries (NamedParameterJdbcTemplate) — no string concatenation
  - A04 Insecure Design: request body size limits configured
  - A09 Logging Failures: no sensitive data in logs (Issue 9.4)

**Dependencies:** Issue 1.1

**Acceptance Criteria:**
- [ ] `curl -I /api/v1/health` response includes `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`
- [ ] No raw SQL string concatenation exists anywhere in the repository (`Grep` for `+ " AND "` or similar patterns returns zero results in repository classes)
- [ ] OWASP audit checklist is documented and all items marked pass/fail/N-A in `docs/security-audit.md`

---

---

## EPIC-11: Testing & Quality

**Goal:** Ensure every significant behaviour is covered by automated tests before production deployment.

**Labels:** `epic`, `testing`, `quality`

---

### Issue 11.1 — Unit Tests: Service Layer

**Labels:** `testing`, `priority::high`

**Description:**
Comprehensive unit tests for all service classes, with mocked dependencies.

**Technical Scope:**
- `RunIngestionServiceTest`: start idempotency, complete idempotency, tenant mismatch (403), end-before-start (400), SLA breach on start, SLA breach on completion, non-breach completion, daily aggregate update
- `RunQueryServiceTest`: cache hit path, cache miss path, bypassCache, batch with partial cache hit, batch all miss, DomainNotFoundException on empty result
- `SlaEvaluationServiceTest`: all 4 breach conditions independently, multiple conditions combined, severity thresholds (edge cases: exactly 15min, 30min, 60min)
- `AnalyticsServiceTest`: weighted average calculation, GREEN/AMBER/RED classification, cursor encode/decode, cursor pagination correctness
- `AlertHandlerServiceTest`: successful alert, duplicate breach (DuplicateKeyException), alert failure → FAILED status

**Dependencies:** EPIC-3, EPIC-5, EPIC-6, EPIC-7

**Acceptance Criteria:**
- [ ] All service unit tests use `@ExtendWith(MockitoExtension.class)` — no Spring context loaded
- [ ] Branch coverage ≥ 80% for all service classes (reported by JaCoCo)
- [ ] SLA severity thresholds are tested at exact boundary values (15min, 30min, 60min)
- [ ] Cursor decode with invalid Base64 does not throw; returns null (treated as first page)

---

### Issue 11.2 — Unit Tests: `TimeUtils` & Domain Enums

**Labels:** `testing`, `priority::high`

**Description:**
Exhaustive unit tests for CET conversion utilities and all enum factory methods.

**Technical Scope:**
- `TimeUtilsTest`: SLA deadline calculation (winter CET = UTC+1, summer CEST = UTC+2), DST transitions (last Sunday March/October), `calculateCetHour` precision, `formatDuration` all branches (hours+mins, mins+secs, secs only)
- `CalculatorFrequencyTest`: `from("D")`, `from("daily")`, `from(null)`, `from("")`, `from("UNKNOWN")`
- `RunStatusTest`: `fromCompletionStatus("SUCCESS")`, `fromCompletionStatus(null)` → SUCCESS, `fromCompletionStatus("RUNNING")` → throws, `isTerminal()`, `isSuccessful()`

**Dependencies:** Issue 3.2, Issue 2.1

**Acceptance Criteria:**
- [ ] DST test: `calculateSlaDeadline(2026-03-29, 06:15)` returns `05:15Z` (CET); `calculateSlaDeadline(2026-03-30, 06:15)` returns `04:15Z` (CEST — clocks spring forward)
- [ ] All `CalculatorFrequency.from()` variants return correct enum or DAILY for unknowns
- [ ] `RunStatus.fromCompletionStatus("RUNNING")` throws `IllegalArgumentException`

---

### Issue 11.3 — Integration Tests: Ingestion & Query Controllers

**Labels:** `testing`, `integration`, `priority::high`

**Description:**
Integration tests using `@SpringBootTest` + `@AutoConfigureMockMvc` against a real PostgreSQL and Redis instance.

**Technical Scope:**
- `RunIngestionControllerTest`:
  - Full start → complete happy path
  - Duplicate start (idempotency)
  - Missing `X-Tenant-Id` → 400
  - Invalid request body → 400 with field errors
  - Unauthenticated request → 401
  - Cross-tenant complete → 403
- `RunQueryControllerTest`:
  - Status query cache miss → DB query → cache population verified
  - Status query cache hit (second call) → no DB query
  - `bypassCache=true` → always DB
  - Batch with mixed cache hit/miss
  - Unknown calculatorId → 404

**Dependencies:** Issue 3.5, Issue 5.3

**Acceptance Criteria:**
- [ ] Tests run against the Docker PostgreSQL and Redis started via `@DynamicPropertySource` or Testcontainers
- [ ] All tests pass with `SPRING_PROFILES_ACTIVE=local ./mvnw test`
- [ ] Cache hit/miss verified by asserting repository mock call count (or by intercepting Redis commands)
- [ ] 401 on unauthenticated request, 403 on wrong tenant — correct HTTP status in each case

---

### Issue 11.4 — Integration Tests: Analytics Endpoints

**Labels:** `testing`, `integration`, `analytics`, `priority::high`

**Description:**
Integration tests for all five analytics endpoints with seeded test data.

**Technical Scope:**
- Test data setup: insert known `calculator_sli_daily` rows and `sla_breach_events` rows
- `AnalyticsControllerTest`:
  - `/runtime`: verify `avgDurationMs` matches the known weighted average of seeded data
  - `/sla-summary`: verify GREEN/AMBER/RED day counts match seeded breach data
  - `/trends`: verify `slaStatus` per day matches classification logic
  - `/sla-breaches`: first page returns 20 items; cursor navigation returns correct next page; severity filter reduces results
  - `/performance-card`: `slaSummaryPct` sums to 100.0; `runs` ordered chronologically
- `AnalyticsServiceTest` (unit, mocked): re-verify aggregation logic with controlled input

**Dependencies:** Issue 7.5

**Acceptance Criteria:**
- [ ] `/runtime` with 3 seeded days returns correct `avgDurationMs` (calculated manually from seed data)
- [ ] Cursor pagination returns no duplicates and no gaps across all pages
- [ ] `/sla-breaches` with `severity=HIGH` returns only HIGH-severity records
- [ ] Empty date range (no matching data) returns empty `content` array with `totalElements=0` (not 404)

---

### Issue 11.5 — Partition Pruning Verification Tests

**Labels:** `testing`, `database`, `partition`, `priority::medium`

**Description:**
Tests that confirm partition pruning is working correctly for each query window, providing a regression guard against accidentally removing `reporting_date` predicates from queries.

**Technical Scope:**
- Use `EXPLAIN (ANALYZE, FORMAT JSON)` to verify query plans
- Test cases:
  - `findRecentRuns(DAILY)` plan shows `Partitions selected: 4 (of N)` not `Partitions selected: N`
  - `findById(String, LocalDate)` plan shows exactly 1 partition
  - `findRunsWithSlaStatus(days=30)` plan shows 30 partitions
  - `findBatchRecentRunsDbOnly(DAILY)` plan shows ≤4 partitions
- Expose a test-only `PartitionPruningVerifier` component that parses `EXPLAIN JSON` and asserts partition count bounds

**Dependencies:** Issue 2.2, Issue 2.3

**Acceptance Criteria:**
- [ ] DAILY `findRecentRuns` selects ≤4 partitions in EXPLAIN output
- [ ] `findById(String, LocalDate)` selects exactly 1 partition
- [ ] If `reporting_date` predicate is accidentally removed from a query, the corresponding partition test fails
- [ ] Tests run as part of the normal `mvnw test` suite (not a separate manual step)

---

### Issue 11.6 — Contract Tests: OpenAPI Validation

**Labels:** `testing`, `api-contract`, `priority::medium`

**Description:**
Validate that all API endpoints conform to their OpenAPI specification and that the spec is complete and accurate.

**Technical Scope:**
- Generate OpenAPI 3.0 spec at `GET /api-docs` and save to `src/main/resources/static/api-spec.json` as part of build
- Use `openapi-generator` or `swagger-request-validator` to validate all integration test requests/responses against the spec
- Validate: all required fields present, response schemas match, error schemas match
- Expose spec for external teams at `/api-docs` (unauthenticated)

**Dependencies:** Issue 1.1, Issue 11.3, Issue 11.4

**Acceptance Criteria:**
- [ ] `GET /api-docs` returns a valid OpenAPI 3.0 JSON document
- [ ] All 10 endpoints appear in the spec with correct paths, methods, parameters, and response schemas
- [ ] Every integration test request/response is validated against the spec (no schema violations)
- [ ] OpenAPI spec is committed to the repository and updated automatically on build

---

---

## EPIC-12: Production Readiness

**Goal:** Ensure the service is ready for production: deployment documentation, runbooks for operations teams, load-tested performance baseline, and a complete deployment checklist.

**Labels:** `epic`, `production`, `operations`

---

### Issue 12.1 — Operational Runbook: Partition Management

**Labels:** `operations`, `documentation`, `priority::high`

**Description:**
Write the operational runbook for the partition management lifecycle.

**Technical Scope (doc content):**
- How to check current partition state: `SELECT * FROM get_partition_statistics()`
- How to manually create partitions: `SELECT create_calculator_run_partitions()`
- How to verify a specific date has a partition: `\d+ calculator_runs_YYYY_MM_DD`
- What happens if a partition is missing: inserts fail with `ERROR: no partition of relation found for row`; queries succeed (no data for that date)
- How to recover from a missed partition creation: manual `CREATE TABLE calculator_runs_YYYY_MM_DD PARTITION OF calculator_runs FOR VALUES FROM ('YYYY-MM-DD') TO ('YYYY-MM-DD+1')`
- Drop schedule: partitions older than 395 days are automatically dropped weekly. If historical data is needed, export before the drop window.
- Monitoring: `partitions.create.failures` Prometheus counter; alert when > 0

**Dependencies:** Issue 8.1

**Acceptance Criteria:**
- [ ] Runbook is in `docs/runbooks/partition-management.md`
- [ ] Reviewed and approved by at least one operations engineer
- [ ] Includes example commands for all manual interventions

---

### Issue 12.2 — Operational Runbook: Redis Failure & Recovery

**Labels:** `operations`, `documentation`, `priority::high`

**Description:**
Document Redis failure scenarios and their impact on service behaviour.

**Technical Scope (doc content):**
- Redis outage impact: all cache reads miss, all requests fall through to DB; SLA live detection stops; analytics serve stale data up to TTL then miss → DB
- Monitoring signals: `redis.cache.error` counter; Redis health indicator DOWN
- Recovery: once Redis reconnects, Lettuce auto-reconnects; cache re-warms on next requests (no manual intervention needed)
- Complete Redis data loss: no data loss — Redis is a cache only; all data in PostgreSQL
- Key TTL reference table (from redis-keys.md)
- Warning: `obs:sla:deadlines` and `obs:sla:run_info` are lost on Redis restart; DAILY runs in-flight during Redis outage will NOT be live-monitored until next `startRun()` call re-registers them

**Dependencies:** Issue 4.3

**Acceptance Criteria:**
- [ ] Runbook in `docs/runbooks/redis-failure.md`
- [ ] Includes clear statement: "Redis outage causes no data loss"
- [ ] Documents the SLA monitoring gap during Redis restart for in-flight runs

---

### Issue 12.3 — Operational Runbook: SLA Breach Investigation

**Labels:** `operations`, `documentation`, `sla`, `priority::medium`

**Description:**
Guide for operations engineers to investigate and resolve SLA breach events.

**Technical Scope (doc content):**
- How to query all recent breaches: `SELECT * FROM sla_breach_events ORDER BY created_at DESC LIMIT 20`
- How to find unalerted breaches: `WHERE alerted=false AND alert_status IN ('PENDING','FAILED','RETRYING')`
- How to manually retry a FAILED alert: `UPDATE sla_breach_events SET alert_status='PENDING', retry_count=0 WHERE breach_id=?`
- Severity classification reference (LOW/MEDIUM/HIGH/CRITICAL thresholds)
- How to distinguish on-write vs live-detected breaches (breach reason string pattern)
- How to check if the live detection job is running: `sla.approaching.count` gauge; check scheduling thread health

**Dependencies:** Issue 6.3, Issue 6.4

**Acceptance Criteria:**
- [ ] Runbook in `docs/runbooks/sla-breach-investigation.md`
- [ ] Includes SQL queries for all common investigation scenarios
- [ ] Includes escalation guide based on severity level

---

### Issue 12.4 — Load Testing & Performance Baseline

**Labels:** `production`, `performance`, `priority::medium`

**Description:**
Establish a performance baseline for each endpoint under expected load to validate the latency budget assumptions in the tech spec.

**Technical Scope:**
- Tool: k6 or Apache JMeter scripts in `load-tests/`
- Scenarios:
  - `startRun` / `completeRun`: 50 RPS for 5 minutes; assert p99 < 100ms
  - `GET /calculators/{id}/status` (cache-warmed): 200 RPS; assert p99 < 20ms
  - `POST /calculators/batch/status` (20 calculators, warmed): 50 RPS; assert p99 < 50ms
  - `GET /analytics/runtime` (cached): 100 RPS; assert p99 < 10ms
  - `GET /analytics/performance-card` (uncached, days=30): 20 RPS; assert p99 < 200ms
- Target environment: Docker Compose local stack with realistic data volume (100k rows across 30 partitions)
- Document results in `docs/performance-baseline.md`

**Dependencies:** All API epics (EPIC-3 through EPIC-7)

**Acceptance Criteria:**
- [ ] Load test scripts are committed to `load-tests/` directory
- [ ] All P99 targets met at stated RPS levels
- [ ] Baseline results documented with infrastructure specs (CPU, memory, Redis/PG instance size)
- [ ] Any endpoint that misses its target has a documented investigation and remediation plan

---

### Issue 12.5 — Production Deployment Checklist

**Labels:** `production`, `documentation`, `priority::high`

**Description:**
Create a pre-deployment and post-deployment verification checklist for production releases.

**Technical Scope (checklist content):**
- **Pre-deploy:**
  - All Flyway migrations applied to dev successfully
  - No migration version gaps
  - Environment variables set: `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `OBS_BASIC_USER`, `OBS_BASIC_PASSWORD`
  - `OBS_BASIC_PASSWORD` is not `admin` and length > 16 chars
  - `SPRING_PROFILES_ACTIVE=prod`
  - Partition forward window verified: at least 30 future partitions exist
  - Load test passed on staging with production data volume
- **Post-deploy:**
  - `GET /actuator/health` returns `{"status":"UP"}` for all components
  - `GET /actuator/prometheus` returns 200
  - Verify first ingestion request succeeds end-to-end
  - Verify Prometheus scrape target is healthy
  - Verify `sla.alerts.backlog` gauge = 0
  - Verify `partitions.create.failures_total` = 0
  - Check logs for any ERROR within first 5 minutes

**Dependencies:** All epics

**Acceptance Criteria:**
- [ ] Checklist is in `docs/deployment-checklist.md`
- [ ] Checklist is part of the MR/deploy template (GitLab MR description template or deploy job artifact)
- [ ] Every item has a pass/fail criterion with the exact command or URL to verify

---

---

## Suggested Epic Delivery Order

```
Sprint 1:  EPIC-1 (Foundation) + EPIC-2 (Data Model)
Sprint 2:  EPIC-3 (Ingestion) + EPIC-4 (Redis)
Sprint 3:  EPIC-5 (Query API) + EPIC-6 (SLA Detection)
Sprint 4:  EPIC-7 (Analytics) + EPIC-8 (Partition Management)
Sprint 5:  EPIC-9 (Observability) + EPIC-10 (Security)
Sprint 6:  EPIC-11 (Testing) + EPIC-12 (Production Readiness)
```

Dependencies must be respected within each sprint. EPIC-4 (Redis) can be parallelised with EPIC-3 (Ingestion) since the cache layer is plugged in via events, not inline dependencies.

---

## Labels Reference

| Label | Usage |
|-------|-------|
| `priority::critical` | Blocks other issues; must be done first |
| `priority::high` | Core functionality; needed for MVP |
| `priority::medium` | Important but not blocking |
| `priority::low` | Nice-to-have / cleanup |
| `epic` | Epic-level issue |
| `foundation` | Project setup and infrastructure |
| `backend` | Java application code |
| `database` | Flyway migrations or schema changes |
| `data-layer` | Repository and domain model |
| `ingestion` | Run lifecycle write path |
| `query` | Status read path |
| `analytics` | Analytics API |
| `caching` / `redis` | Redis layer |
| `sla` | SLA detection and evaluation |
| `alerting` | Alert delivery |
| `scheduled` | Scheduled jobs |
| `observability` / `metrics` | Micrometer, Prometheus, logging |
| `security` | Security hardening |
| `testing` / `integration` | Test coverage |
| `operations` / `documentation` | Runbooks and checklists |
| `performance` | Load testing and tuning |
