# PostgreSQL Flexible Server-Compatible Flyway Index Rewrite

## Summary

Rewrite the existing Flyway index migrations for the current development schema, targeting Azure Database for PostgreSQL Flexible Server. Optimize only the active consumer query paths that filter by `calculator_name`; exclude `tenant_id` from all indexes because it is optional and not used in query predicates.

## Key Changes

- Replace old calculator_id/dashboard/regional indexes with a compact consumer-focused B-tree index set.
- Remove `CREATE INDEX CONCURRENTLY` from Flyway scripts; use normal parent-table partitioned indexes on `calculator_runs`.
- Remove `-- flyway:transactional=false` from index migrations unless another non-transactional statement remains.
- Remove deprecated dashboard/regional index comments and index definitions.
- Keep `tenant_id` as a nullable stored column only; do not add tenant-aware index variants.
- Keep `V6__add_correlation_id.sql` for the column, but remove its old calculator_id-led correlation index unless still needed by consumer queries.
- Consolidate or empty `V7__calculator_name_indexes.sql` after moving the final name-keyed indexes into the cleaned index migration.

## New Index Set

```sql
CREATE INDEX IF NOT EXISTS calculator_runs_consumer_batch_idx
    ON calculator_runs (reporting_date, frequency, calculator_name, run_number, created_at);

CREATE INDEX IF NOT EXISTS calculator_runs_consumer_executions_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, run_number, created_at DESC);

CREATE INDEX IF NOT EXISTS calculator_runs_latest_estimate_by_name_idx
    ON calculator_runs (calculator_name, frequency, reporting_date DESC, created_at DESC)
    WHERE expected_duration_ms IS NOT NULL;

CREATE INDEX IF NOT EXISTS calculator_sli_daily_profile_idx
    ON calculator_sli_daily (calculator_name, frequency, reporting_date DESC);
```

## Compatibility Notes

- Azure Flexible Server supports PostgreSQL partitioning and standard B-tree/partial indexes.
- Parent-table `CREATE INDEX` on `calculator_runs` is compatible and automatically creates/attaches matching partition indexes.
- `CREATE INDEX CONCURRENTLY` is intentionally avoided because PostgreSQL does not support it directly on partitioned parent tables.
- No tablespace clauses or Azure-incompatible options will be introduced.

## Test Plan

- Run `mvn test`.
- Run a fresh Flyway migration against local/PostgreSQL-compatible DB.
- Verify final indexes with `\d calculator_runs` and `\d calculator_sli_daily`.
- Run `EXPLAIN` for:
  - `/batch/runs` query with exact `reporting_date`, `frequency`, multiple `calculator_name` values, with and without `run_number`.
  - `/executions` query with one `calculator_name`, `frequency`, lookback window, with and without `run_number`.
  - Latest estimate fallback query with `expected_duration_ms IS NOT NULL`.
  - Rolling profile lookup from `calculator_sli_daily`.

## Assumptions

- Existing Flyway migrations may be edited directly because the project is still in development.
- Only consumer query APIs are in scope.
- Deprecated dashboard/regional indexes should be removed.
- Tenant ID remains optional and intentionally excluded from index design.
