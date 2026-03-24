# Local Setup

This page walks through running the Observability Service locally using Docker Compose.

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 17+ | Build and run the service |
| Maven | 3.8+ | Build tool (or use the included `./mvnw` wrapper) |
| Docker | 24+ | Run PostgreSQL and Redis containers |
| Docker Compose | v2+ | Orchestrate local infrastructure |

!!! tip "Rancher Desktop"
    The project is developed and tested with **Rancher Desktop** as the Docker runtime. Docker Desktop works equally well.

---

## Step 1: Start Infrastructure

```bash
docker compose up -d
```

This starts two containers:

| Container | Port | Credentials |
|-----------|------|-------------|
| `observability-postgres` | `5432` | user: `postgres` / pass: `postgres` |
| `observability-redis` | `6379` | no password |

Wait a few seconds for PostgreSQL to initialize. Flyway migrations run automatically on first application start.

---

## Step 2: Run the Application

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

The `local` profile connects to `localhost:5432` and `localhost:6379`.

On startup, Flyway applies all pending migrations to the `observability` database. You will see log output like:

```
Flyway Community Edition ... will be used.
Database: jdbc:postgresql://localhost:5432/observability (PostgreSQL 17.x)
Successfully validated 12 migrations ...
Migrating schema "public" to version 1 - init schema
...
Successfully applied 12 migrations to schema "public"
```

---

## Step 3: Verify

| Endpoint | Expected Response |
|----------|------------------|
| `GET http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| `GET http://localhost:8080/swagger-ui.html` | Swagger UI |
| `GET http://localhost:8080/api-docs` | OpenAPI JSON |

!!! note "Local security"
    The `local` profile disables Spring Security auto-configuration. Actuator endpoints are unauthenticated. All API endpoints still require `X-Tenant-Id` but **do not** require `Authorization` headers locally.

---

## Step 4: Connect to Databases (Optional)

```bash
# PostgreSQL
docker exec -it observability-postgres psql -U postgres -d observability

# Redis
docker exec -it observability-redis redis-cli
```

Useful commands once connected:

```sql
-- Check migrations applied
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;

-- Count runs
SELECT COUNT(*) FROM calculator_runs;

-- Check partitions
SELECT tablename FROM pg_tables WHERE tablename LIKE 'calculator_runs_%' ORDER BY tablename;
```

```bash
# Redis: list all keys
KEYS obs:*

# Redis: check a specific ZSET
ZRANGE obs:sla:deadlines 0 -1 WITHSCORES
```

---

## Build

```bash
./mvnw clean package
```

Produces `target/observability-service-*.jar`.

---

## Run Tests

Tests require the Docker containers to be running (PostgreSQL + Redis):

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw clean test
```

Run a specific test class:

```bash
./mvnw test -Dtest=RunIngestionServiceTest
```

---

## Stopping Infrastructure

```bash
docker compose down
```

Add `-v` to also remove data volumes (fresh start):

```bash
docker compose down -v
```

---

## Environment Variables Reference

The service supports full configuration via environment variables. Override defaults as needed:

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `dev` | Active profile (`local`, `dev`, `prod`) |
| `POSTGRES_HOST` | `localhost` | PostgreSQL host |
| `POSTGRES_DB` | `observability` | Database name |
| `POSTGRES_USER` | `postgres` | DB username |
| `POSTGRES_PASSWORD` | `postgres` | DB password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password |
| `OBS_BASIC_USER` | `admin` | API username |
| `OBS_BASIC_PASSWORD` | `admin` | API password |
