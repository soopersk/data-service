# Extension Guide

This guide covers how to extend the service safely. Follow these patterns to maintain consistency with the existing architecture.

---

## Adding a New Analytics Endpoint

1. **Service method** — Add a method to `AnalyticsService`:
   - Check `AnalyticsCacheService.get(...)` first
   - Query the repository
   - Build the response DTO
   - Call `AnalyticsCacheService.put(...)` before returning

2. **Response DTO** — Define a new class in `dto/response/`. Must implement `Serializable` for Redis serialization.

3. **Cache key prefix** — Add a constant in `AnalyticsCacheService`:
   ```java
   private static final String MY_NEW_PREFIX = "obs:analytics:my-new:";
   ```

4. **Controller endpoint** — Add to `AnalyticsController`:
   - Extract `tenantId` from `X-Tenant-Id` header
   - Validate parameters with `@Valid`, `@Min`, `@Max`
   - Call service method
   - Set `Cache-Control` response header
   - Add `@Operation` and `@Tag` annotations for OpenAPI

5. **Metrics counter** — Increment a Micrometer counter in the controller.

6. **Partition safety** — If your endpoint queries `calculator_runs`, verify it includes `reporting_date` in the WHERE clause (see [Partition Safety Rules](#partition-safety-rules)).

---

## Adding a New Calculator Frequency

1. Add value to `CalculatorFrequency` enum with appropriate `lookbackDays`.

2. Update `buildPartitionPrunedQuery()` in `CalculatorRunRepository` to handle the new frequency's date window.

3. Update `SlaMonitoringCache.registerForSlaMonitoring()` if the new frequency requires live SLA monitoring.

4. Update TTL logic in `RedisCalculatorCache.cacheRunOnWrite()`.

5. Update `LiveSlaBreachDetectionJob` if the frequency should be monitored.

6. Add test coverage in `RunIngestionServiceTest` and `RunQueryServiceTest`.

!!! warning "Lookback days inconsistency (TD-6)"
    `CalculatorFrequency.lookbackDays` is declared but never used in actual queries — all query windows are hardcoded. If you add a new frequency, either use the hardcoded approach consistently or resolve TD-6 first by wiring `getLookbackDays()` into the repository.

---

## Adding a New SLA Policy

1. Add a new check in `SlaEvaluationService.evaluateSla()` — append a reason string to the result.

2. Add a corresponding value to the `BreachType` enum.

3. Update `AlertHandlerService.determineBreachType()` to recognise the new reason string.

4. If the new policy needs a custom severity, update `SlaEvaluationService.determineSeverity()`.

5. All downstream paths (alert persistence, breach detail API) handle arbitrary breach type strings dynamically — no changes needed there.

---

## Modifying the Schema

1. Create a new Flyway migration: `V{n}__description.sql` in `src/main/resources/db/migration/`.

2. **Never modify existing migration files.** Flyway validates checksums on startup.

3. For index creation on `calculator_runs` or any large table:
   ```sql
   -- flyway:transactional=false
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_name ON table (columns);
   ```

4. After adding columns to `calculator_runs`, update:
   - `CalculatorRun` domain class
   - `CalculatorRunRowMapper` (full mapper for all queries)
   - `StatusRunRowMapper` (read-only mapper — only if column is needed for status queries)
   - `upsert()` INSERT and ON CONFLICT UPDATE SQL
   - `findRunsWithSlaStatus()` SELECT list if the column is needed for performance card

---

## Adding a Redis Key

When adding a new Redis key:

1. Define the key pattern as a constant in the relevant cache class.

2. Document (in code comments and here): key pattern, data structure, TTL, write path, read path, eviction trigger.

3. **Always set a TTL.** No unbounded keys.

4. If the key must be invalidated on run state change, add eviction to `CacheWarmingService`.

5. Update [`redis-architecture.md`](redis-architecture.md) with the new key in the reference table.

---

## Adding an Index

1. Create a new Flyway migration with `-- flyway:transactional=false`.

2. Use `CREATE INDEX CONCURRENTLY IF NOT EXISTS` for production migrations on large tables — concurrent creation does not lock the table.

3. For `calculator_runs` indexes: create on the **parent partitioned table** — PostgreSQL automatically propagates to all child partitions.

4. Verify that the index column order matches the WHERE clause and ORDER BY in the target query (leftmost prefix rule — the most selective and leftmost-in-WHERE columns should be leftmost in the index).

5. Use partial indexes (`WHERE status='RUNNING'`) for condition-specific queries to reduce index size.

---

## Partition Safety Rules

Every new query against `calculator_runs` **MUST** satisfy at least one of:

| Rule | Example |
|------|---------|
| Exact partition | `WHERE reporting_date = ?` (single partition scan) |
| Bounded range | `WHERE reporting_date >= ? AND reporting_date <= ?` (N partitions) |
| Rolling window | `WHERE reporting_date >= CURRENT_DATE - INTERVAL '<n> days'` (bounded N) |

**Queries without a `reporting_date` predicate MUST NOT be added to production code** without explicit documentation of the full-scan risk and an upper bound on expected execution time.

### Evaluating a New Query

Run `EXPLAIN (ANALYZE, BUFFERS)` on the query with a representative dataset:
- Look for `Append` nodes — these indicate partition scans
- Count the `Seq Scan on calculator_runs_*` sub-nodes to see how many partitions are scanned
- A well-pruned DAILY query should show exactly 3–4 child partition scans

---

## Adding a Scheduled Job

1. Create a new class in the `scheduled/` package, annotated with `@Component`.

2. Use `@Scheduled(fixedDelay = ...)` or `@Scheduled(cron = "...")`.

3. Implement exception handling — a thrown exception from a `@Scheduled` method suppresses all future invocations of that method. Always wrap the body in a try-catch.

4. Use the scheduling thread pool for lightweight coordination; use the async executor pool for heavier work triggered from the job.

5. Add configuration properties to control enable/disable and interval via `application.yml`.

6. Document the job in [`architecture.md` — Scheduled Jobs](architecture.md#scheduled-jobs).

---

## Writing Tests

### Integration Tests (Repository Layer)

Extend `PostgresJdbcIntegrationTestBase` for repository tests. This base class:
- Provides a real PostgreSQL container via Testcontainers
- Runs Flyway migrations before the test class
- Provides `NamedParameterJdbcTemplate` for direct SQL assertions

### Service Tests

Use `@ExtendWith(MockitoExtension.class)` and mock repositories and cache classes. Test business logic, SLA evaluation, and idempotency without DB or Redis dependencies.

### Controller Tests

Use `@WebMvcTest` with `MockBean` for services. Test request validation, response structure, and HTTP header handling.
