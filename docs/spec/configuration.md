# Configuration & Profiles

---

## Spring Profiles

| Profile | Use Case |
|---------|---------|
| `local` | Local development with Docker Compose (localhost Postgres + Redis) |
| `dev` | Azure infrastructure (dev environment) |
| `prod` | Azure infrastructure (production) |

Default: `dev` (set via `SPRING_PROFILES_ACTIVE` env var or `spring.profiles.active` property).

### Profile Differences

| Setting | `local` | `dev` | `prod` |
|---------|---------|-------|--------|
| Datasource host | `localhost:5432` | Via `POSTGRES_HOST` env var | Via `POSTGRES_HOST` env var |
| Redis host | `localhost:6379` | Via `REDIS_HOST` env var | Via `REDIS_HOST` env var |
| SLA detection interval | **30s** | 120s | 120s |
| Log level (root) | DEBUG | DEBUG | INFO |
| Log level (application) | DEBUG | DEBUG | INFO |
| JDBC SQL logging | DEBUG | DEBUG | INFO |
| Spring Security auto-config | **Disabled** | Enabled | Enabled |
| Flyway `clean-disabled` | `false` | — | — |

!!! danger "Local profile — no security"
    `SecurityAutoConfiguration` and `ManagementWebSecurityAutoConfiguration` are excluded in the `local` profile. All actuator endpoints are unauthenticated. Never run the local profile outside a development machine.

---

## Custom Application Properties

### Security

| Property | Default | Description |
|----------|---------|-------------|
| `observability.security.basic.username` | `admin` | HTTP Basic auth username |
| `observability.security.basic.password` | `admin` | HTTP Basic auth password (`{noop}` — plaintext) |
| `observability.security.basic.role` | `USER` | Spring Security role |

### SLA Monitoring

| Property | Default | Description |
|----------|---------|-------------|
| `observability.sla.live-tracking.enabled` | `true` | Register DAILY runs in SLA monitoring Redis ZSET |
| `observability.sla.live-detection.enabled` | `true` | Enable `LiveSlaBreachDetectionJob` |
| `observability.sla.live-detection.interval-ms` | `120000` | Detection job interval in milliseconds (2 min) |
| `observability.sla.live-detection.initial-delay-ms` | `30000` | Startup delay in milliseconds (30s) |
| `observability.sla.early-warning.enabled` | `true` | Enable early warning check |
| `observability.sla.early-warning.interval-ms` | `180000` | Early warning interval in milliseconds (3 min) |
| `observability.sla.early-warning.threshold-minutes` | `10` | Warn if SLA deadline within this many minutes |

### Cache

| Property | Default | Description |
|----------|---------|-------------|
| `observability.cache.eviction.enabled` | `true` | Cache eviction toggle |
| `observability.cache.legacy-eviction-listener.enabled` | `false` | Enable `CacheEvictionService` (disabled; `CacheWarmingService` is active) |
| `observability.cache.warm-on-completion` | `true` | Enable `CacheWarmingService` |

### Partition Management

| Property | Default | Description |
|----------|---------|-------------|
| `observability.partitions.management.enabled` | `true` | Enable `PartitionManagementJob` |
| `observability.partitions.management.create-cron` | `0 0 1 * * *` | Daily at 01:00 — create 60-day forward window |
| `observability.partitions.management.drop-cron` | `0 0 2 * * SUN` | Weekly Sunday at 02:00 — drop partitions > 395 days |
| `observability.partitions.monitoring.cron` | `0 0 6 * * *` | Daily at 06:00 — record partition health gauges |

---

## Environment Variables

All infrastructure connection details are controlled via environment variables:

| Variable | Maps To | Default | Required In Prod |
|----------|---------|---------|-----------------|
| `SPRING_PROFILES_ACTIVE` | `spring.profiles.active` | `dev` | Yes |
| `POSTGRES_HOST` | `spring.datasource.url` | `localhost` | Yes |
| `POSTGRES_DB` | `spring.datasource.url` | `observability` | Yes |
| `POSTGRES_USER` | `spring.datasource.username` | `postgres` | Yes |
| `POSTGRES_PASSWORD` | `spring.datasource.password` | `postgres` | Yes |
| `REDIS_HOST` | `spring.data.redis.host` | `localhost` | Yes |
| `REDIS_PORT` | `spring.data.redis.port` | `6379` | Yes |
| `REDIS_PASSWORD` | `spring.data.redis.password` | _(empty)_ | Yes (if Redis auth) |
| `OBS_BASIC_USER` | `observability.security.basic.username` | `admin` | Yes |
| `OBS_BASIC_PASSWORD` | `observability.security.basic.password` | `admin` | Yes |

---

## Spring Data Source (HikariCP)

| Property | Value |
|----------|-------|
| Driver | `org.postgresql.Driver` |
| Maximum pool size | `20` |
| Minimum idle | `5` |
| Connection timeout | `30000ms` (30s) |
| Idle timeout | `600000ms` (10min) |
| Max lifetime | `1800000ms` (30min) |
| Leak detection threshold | `60000ms` (60s) |
| Pool name | `ObservabilityHikariCP` |

---

## Redis (Lettuce)

| Property | Value |
|----------|-------|
| Default timeout | `3000ms` |
| Lettuce pool max-active | `10` |
| Lettuce pool max-idle | `5` |
| Lettuce pool min-idle | `2` |
| Spring Cache default TTL | `900000ms` (15 min) |
| Spring Cache null values | Disabled |

---

## Thread Pools

### Async Executor

| Property | Value |
|----------|-------|
| Core pool size | `5` |
| Max pool size | `10` |
| Queue capacity | `100` |
| Thread name prefix | `async-` |

### Scheduling Pool

| Property | Value |
|----------|-------|
| Pool size | `5` |
| Thread name prefix | `scheduled-` |

---

## Actuator Exposure

| Endpoint | Exposed | Auth Required |
|----------|---------|--------------|
| `health` | Yes | No |
| `info` | Yes | Yes |
| `metrics` | Yes | Yes |
| `prometheus` | Yes | Yes |
| All others | **No** | — |

Liveness and readiness probes are enabled (`management.endpoint.health.probes.enabled: true`).

---

## Logging

### Pattern

```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [reqId=%X{requestId:-} tenant=%X{tenant:-} calc=%X{calculatorId:-} run=%X{runId:-}] - %msg%n
```

Every log line includes MDC context: request ID, tenant, calculator ID, and run ID when available.

### Structured Log Format

All log statements at INFO and above use `event=<noun>.<verb> outcome=success|failure|rejected` fields. Additional context is added as extra key-value pairs (`reason=`, `count=`, etc.). See [observability.md](observability.md#structured-logging) for the full convention, level policy, and MDC key inventory.

### Log Levels

| Logger | `local` | `dev` | `prod` |
|--------|---------|-------|--------|
| root | DEBUG | DEBUG | INFO |
| `com.company.observability` | DEBUG | DEBUG | INFO |
| `org.flywaydb` | DEBUG | DEBUG | INFO |
| `org.springframework.jdbc` | DEBUG | DEBUG | INFO |

---

## OpenAPI / Swagger

| Property | Value |
|----------|-------|
| API docs path | `/api-docs` |
| Swagger UI path | `/swagger-ui.html` |
| Operations sorter | By HTTP method |
| Tags sorter | Alphabetical |
