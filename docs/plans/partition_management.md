# Plan: Externalize Partition Management to Airflow via REST

## Context

Today, partition lifecycle for `calculator_runs` runs as Spring `@Scheduled` jobs in
[PartitionManagementJob.java](src/main/java/com/company/observability/scheduled/PartitionManagementJob.java).
The PL/pgSQL functions (`create_calculator_run_partitions`, `drop_old_calculator_run_partitions`,
`get_partition_statistics`) already exist in
[V2__calculator_runs.sql](src/main/resources/db/migration/V2__calculator_runs.sql).

**Problem being solved:** `@Scheduled` fires on **every replica**. There is no ShedLock or
leader election in the codebase. With multiple pods, partition create/drop runs N times
concurrently. Functions are idempotent (`IF NOT EXISTS` / `IF EXISTS`), so this is not
catastrophic, but it produces noisy logs, double-counted metrics, and wasted DB cycles.

**Chosen approach (per user decision):** Move the trigger out of Spring `@Scheduled` and
into Airflow. Airflow calls REST endpoints on the observability service, which call the
existing PL/pgSQL functions. Single Airflow scheduler → single HTTP call → single execution.

> **Architectural caveat (recorded for posterity):** ShedLock would solve the multi-replica
> problem with ~30 lines of config and zero new failure modes. The chosen design adds
> Airflow + REST + auth in exchange for unified ops visibility in Airflow alongside calc-run
> DAGs. Justified only if Airflow-as-control-plane is a strategic direction.

---

## Design

### 1. New REST endpoints

New controller: `MaintenanceController` at `/api/v1/admin/maintenance/partitions/*`.

| Method | Path | Action |
|---|---|---|
| `POST` | `/api/v1/admin/maintenance/partitions/create` | `SELECT create_calculator_run_partitions()` |
| `POST` | `/api/v1/admin/maintenance/partitions/drop` | `SELECT drop_old_calculator_run_partitions()` |
| `GET`  | `/api/v1/admin/maintenance/partitions/stats` | `SELECT * FROM get_partition_statistics() ORDER BY partition_date DESC LIMIT 30` |

**Conventions:**
- **Synchronous execution** — Airflow's HTTP response = task success/failure. Do NOT use
  the existing `taskExecutor.execute(...)` async pattern; it would return 200 before the
  SQL completes, hiding failures from Airflow.
- **No `X-Tenant-Id` required** — partition mgmt is global. Per-controller header binding
  (verified in `RunIngestionController` etc.), so no filter changes needed.
- **Response body** returns structured outcome:
  ```json
  {
    "operation": "create",
    "durationMs": 1234,
    "partitionsAfter": 456,
    "stats": [ /* recent 7 partitions */ ]
  }
  ```
  This gives Airflow logs the same information `logPartitionStatistics()` writes today.
- **Timeout discipline:** Set Spring MVC async timeout / connector timeout high enough
  (>60s); drop operation can be slow. JDBC statement timeout: 5 min ceiling.
- **HTTP status:** `200` on success, `500` with structured error body on failure (so
  Airflow's `SimpleHttpOperator` marks the task failed).

### 2. Security model

Reuse existing Basic Auth ([BasicSecurityConfig.java](src/main/java/com/company/observability/config/BasicSecurityConfig.java))
but introduce a **separate ops user** with role `ADMIN`:

```yaml
observability:
  security:
    basic:
      username: ${OBS_BASIC_USER:admin}
      password: ${OBS_BASIC_PASSWORD:admin}
      role: USER
    admin:
      username: ${OBS_ADMIN_USER:ops}
      password: ${OBS_ADMIN_PASSWORD:ops}
```

Update `BasicSecurityConfig`:
- `InMemoryUserDetailsManager` holds both users
- Add rule: `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`
- App user retains access to `/api/v1/runs/**`, `/api/v1/calculators/**`, `/api/v1/analytics/**`

This keeps the calculator app credentials separate from ops credentials. Rotate
independently. Acknowledged TD-7 (plaintext `{noop}`) is unchanged — out of scope here.

### 3. Disable Spring `@Scheduled` lifecycle jobs

In [PartitionManagementJob.java](src/main/java/com/company/observability/scheduled/PartitionManagementJob.java):

- **Delete** `createPartitions()` and `dropOldPartitions()` methods (and their
  `@Scheduled` annotations).
- **Keep** `monitorPartitionHealth()` — it publishes Micrometer gauges
  (`obs.partition.rows.*`, `obs.partition.count`) consumed by the in-app metrics
  pipeline. Pure read, no lifecycle side effects, multi-replica double-execution is
  harmless (last-write-wins on AtomicLong gauges).
- **Keep** `logPartitionStatistics()` (call it from the new endpoint).
- **Keep** `@ConditionalOnProperty(observability.partitions.management.enabled)` — now
  guards only `monitorPartitionHealth`. Rename to
  `observability.partitions.monitoring.enabled` for honesty (or accept the slight
  misnomer to avoid config-flag churn).
- **Remove** the `create-cron` and `drop-cron` properties from `application.yml`. They
  are now dead config and would mislead future readers.

### 4. Move execution logic into a service

Extract a `PartitionMaintenanceService` that both the controller and (kept) monitoring
job depend on. This avoids the controller calling `jdbcTemplate.execute()` directly
and keeps the existing Micrometer counters
(`PARTITION_CREATE_SUCCESS/FAILURE`, `PARTITION_DROP_SUCCESS/FAILURE`) and structured
logs intact — operators don't lose existing dashboards.

```
MaintenanceController ─┐
                       ├─→ PartitionMaintenanceService → JDBC → SQL function
PartitionManagementJob ┘   (monitoring path only)
```

### 5. Airflow DAG (contract — implementation in Airflow repo)

| DAG | Schedule | Endpoint | Retries | Notes |
|---|---|---|---|---|
| `partition_create_daily` | `0 1 * * *` | `POST /create` | 3, exp backoff, 5 min | Idempotent — safe to retry |
| `partition_drop_weekly` | `0 2 * * 0` | `POST /drop` | 2, exp backoff, 30 min | Idempotent |

- Use `SimpleHttpOperator` with Basic Auth via Airflow Connection (`obs_admin_http`).
- Task timeout: 10 min create, 30 min drop.
- Failure callback: page on-call via existing Airflow alerting (out of scope here).

### 6. Migration / cutover sequence

This sequence is critical to avoid both double-execution and gaps:

1. **Release N:** Deploy MaintenanceController + service + admin auth user.
   `@Scheduled` jobs **still run**. Test endpoints manually.
2. **Deploy Airflow DAGs paused.** Verify HTTP connection from Airflow → service.
3. **Set `observability.partitions.management.enabled=false`** in env (disables the
   whole `PartitionManagementJob` bean, including monitoring — temporary blind spot
   acceptable for cutover window). Rolling restart.
4. **Unpause Airflow DAGs.** Trigger one run manually to confirm.
5. **Release N+1:** Delete `createPartitions()` / `dropOldPartitions()` methods,
   rename the conditional flag, re-enable the bean (now monitor-only). Remove dead
   cron config.

### 7. Verification

End-to-end, against local docker compose stack (`docker compose up -d`):

1. **Endpoint smoke test:**
   ```bash
   curl -u ops:ops -X POST http://localhost:8080/api/v1/admin/maintenance/partitions/create
   curl -u ops:ops -X POST http://localhost:8080/api/v1/admin/maintenance/partitions/drop
   curl -u ops:ops      http://localhost:8080/api/v1/admin/maintenance/partitions/stats
   ```
   Expect `200` with structured JSON; verify response includes `partitionsAfter`.

2. **DB verification:**
   ```sql
   SELECT relname FROM pg_class WHERE relname LIKE 'calculator_runs_%' ORDER BY relname;
   ```
   Confirm partitions exist for `CURRENT_DATE - 1` through `CURRENT_DATE + 60`.

3. **Auth boundary:**
   - `curl -u admin:admin -X POST .../create` → expect `403` (USER role)
   - `curl -X POST .../create` (no auth) → expect `401`
   - `curl -u ops:ops /api/v1/runs/start` → expect `403` (ADMIN can't access app endpoints — or accept that ADMIN inherits; decide explicitly during impl)

4. **Idempotency:** Hit `/create` twice in a row. Second call should succeed with same
   partition count. No duplicate CREATE TABLE errors.

5. **Tests:** Add `MaintenanceControllerTest` (MockMvc) covering: 200 happy path,
   401 no-auth, 403 wrong-role, 500 on JDBC failure (mock the service).
   Run: `SPRING_PROFILES_ACTIVE=local mvn test -Dtest=MaintenanceControllerTest`

6. **Metric continuity:** Hit `/actuator/prometheus` after a `/create` call; verify
   `obs_partition_create_success_total` increments — confirms the existing dashboard
   queries still work.

---

## Files to modify / create

| File | Change |
|---|---|
| `src/main/java/com/company/observability/controller/MaintenanceController.java` | **NEW** |
| `src/main/java/com/company/observability/service/PartitionMaintenanceService.java` | **NEW** — extracted from `PartitionManagementJob` |
| `src/main/java/com/company/observability/dto/PartitionOperationResponse.java` | **NEW** |
| `src/main/java/com/company/observability/scheduled/PartitionManagementJob.java` | Remove `createPartitions`, `dropOldPartitions`; keep monitoring |
| `src/main/java/com/company/observability/config/BasicSecurityConfig.java` | Add `ADMIN` user; add `/api/v1/admin/**` rule |
| `src/main/resources/application.yml` | Add `observability.security.admin.*`; remove dead `create-cron` |
| `src/test/java/.../MaintenanceControllerTest.java` | **NEW** |
| `tasks/todo.md` | Track implementation per CLAUDE.md §5 |

## Out of scope

- ShedLock as alternative (decision made)
- pg_cron as alternative (decision made)
- TD-7 (plaintext password) — pre-existing
- Airflow DAG code itself (separate repo)
- Notification channel for partition failures (TD-11 territory)
