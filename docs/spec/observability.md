# Observability of the System

---

## Actuator Endpoints

| Endpoint | Path | Auth Required | Notes |
|----------|------|--------------|-------|
| Health | `/actuator/health` | No | Shows component details (`show-details: always`); liveness + readiness probes enabled |
| Info | `/actuator/info` | Yes | Application info |
| Metrics | `/actuator/metrics` | Yes | All Micrometer metric names and values |
| Prometheus | `/actuator/prometheus` | Yes | Prometheus text exposition format for scraping |

All other actuator endpoints (`env`, `configprops`, `beans`, `loggers`, etc.) are not exposed.

---

## Global Metric Tags

Applied automatically to all Micrometer metrics:

| Tag | Value |
|-----|-------|
| `application` | `observability-service-main` |
| `environment` | `${SPRING_PROFILES_ACTIVE:local}` |

---

## Custom Counters

### API Request Counters

| Metric | Tags | Source |
|--------|------|--------|
| `api.runs.start.requests` | — | `RunIngestionController` |
| `api.runs.complete.requests` | — | `RunIngestionController` |
| `api.calculators.status.requests` | `frequency`, `bypass_cache` | `RunQueryController` |
| `api.calculators.batch.requests` | `frequency`, `allow_stale` | `RunQueryController` |
| api.analytics.runtime.requests | - | AnalyticsController |
| api.analytics.sla-summary.requests | - | AnalyticsController |
| api.analytics.trends.requests | - | AnalyticsController |
| api.analytics.sla-breaches.requests | - | AnalyticsController |
| api.analytics.run-performance.requests | - | AnalyticsController |
| api.analytics.projection.performance-card.requests | - | ProjectionController |

### Business Event Counters

| Metric | Tags | Description |
|--------|------|-------------|
| `calculator.runs.started` | `frequency` | Runs started (service level) |
| `calculator.runs.completed` | `frequency`, `status`, `sla_breached` | Runs completed |
| `calculator.runs.start.duplicate` | — | Idempotency duplicates (start) |
| `calculator.runs.complete.duplicate` | — | Idempotency duplicates (complete) |

### SLA & Alert Counters

| Metric | Tags | Description |
|--------|------|-------------|
| `sla.breaches.created` | `severity`, `frequency` | New SLA breach records persisted |
| `sla.breaches.duplicate` | — | Duplicate breach events caught (`DuplicateKeyException`) |
| `sla.breaches.live_detected` | `severity` | Breaches detected by the live detection job |
| `sla.alerts.sent` | — | Alert notifications sent |
| `sla.alerts.failed` | — | Alert notifications failed |
| `sla.breach.live_detection.failures` | — | Per-run failures in the live detection loop |

### Cache Counters

| Metric | Tags | Description |
|--------|------|-------------|
| `cache.evictions` | `event`, `frequency` | Cache eviction events by trigger type |
| `query.calculator_status.cache_hit` | — | Status cache hits |
| `query.calculator_status.cache_miss` | `frequency` | Status cache misses (broken down by frequency) |
| `query.batch_status.requests` | `frequency` | Batch status request counter |

### Partition Management Counters

| Metric | Description |
|--------|-------------|
| `partitions.create.success` | Successful partition creation runs |
| `partitions.create.failures` | Failed partition creation runs |
| `partitions.drop.success` | Successful partition drop runs |
| `partitions.drop.failures` | Failed partition drop runs |

---

## Custom Gauges

| Metric | Description | Updated |
|--------|-------------|---------|
| `calculator.runs.active` | Count of currently RUNNING runs (queries DB via `countRunning()`) | Every Prometheus scrape |
| `sla.approaching.count` | Runs within 10 min of SLA deadline | Every 3 minutes (early warning check) |
| `partitions.total_rows` | Total rows across all `calculator_runs` partitions | Daily at 06:00 |
| `partitions.daily_rows` | Rows in DAILY partitions | Daily at 06:00 |
| `partitions.monthly_rows` | Rows in MONTHLY partitions | Daily at 06:00 |
| `partitions.count` | Number of `calculator_runs` partitions | Daily at 06:00 |

!!! warning "Active runs gauge — DB query on every scrape"
    `calculator.runs.active` calls `countRunning()` on every Prometheus scrape. This is a DB query (with a 7-day window index scan). At default Prometheus 15-second scrape intervals, this is ~4 queries/minute. Monitor this if scrape frequency increases.

---

## Custom Timers

| Metric | Tags | Description |
|--------|------|-------------|
| `query.batch_status.duration` | `frequency` | End-to-end duration of batch status requests |
| `api.ingestion.duration` | `endpoint` | Duration of ingestion endpoint calls (start/complete) |

---

## Request Correlation

`RequestLoggingFilter` intercepts every inbound request:

1. Reads `X-Request-ID` header (or generates a UUID v4 if absent)
2. Stores as `requestId` in the SLF4J MDC
3. Echoes back in the response `X-Request-ID` header

All log statements automatically include `[reqId=<uuid>]` via the logging pattern. MDC is propagated to the async thread pool via `MdcTaskDecorator`.

### Additional MDC Keys

| Key | Source | Content |
|-----|--------|---------|
| `requestId` | `RequestLoggingFilter` | UUID from header or generated |
| `tenant` | Application code | `X-Tenant-Id` header value |
| `calculatorId` | Application code | Calculator ID from request/run |
| `runId` | Application code | Run ID from request/run |

---

## Known Observability Gaps

| Gap | Impact | Recommendation |
|-----|--------|----------------|
| No per-endpoint latency histograms | Cannot monitor p95/p99 per endpoint; only batch status has a timer | Add `Timer` to all controller methods |
| No Redis latency metrics | Cannot detect Redis slowdowns until they affect API latency | Add Lettuce instrumentation |
| No partition age metric | Cannot alert on old or missing partitions | Add `partitions.oldest_partition_days` gauge |
| No `sla_breach_events.unalerted_count` metric | Cannot alert on growing alert backlog | Add gauge reading `COUNT(*) WHERE alerted=false` |

---

## Prometheus Scraping

The service exposes metrics at `/actuator/prometheus` in the standard Prometheus text exposition format.

Recommended scrape configuration:

```yaml
scrape_configs:
  - job_name: observability-service
    metrics_path: /actuator/prometheus
    basic_auth:
      username: admin
      password: admin
    static_configs:
      - targets: ['observability-service:8080']
```

All metrics carry the global `application` and `environment` tags for easy filtering in Grafana.


