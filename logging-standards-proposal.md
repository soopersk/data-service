
# **Standardized Logging Specification (Spring / Java)**

## **1. Core Principles**

1. **Logs are structured data, not strings**

   * Prefer key-value (JSON) logging over free-form text.
   * Every log must be machine-parseable.

2. **Lifecycle events are authoritative facts**

   * Represent domain state transitions.
   * Used for SLAs, monitoring, and audit.

3. **Identifiers live in MDC (Mapped Diagnostic Context)**

   * Never embed IDs in log messages.
   * Examples: `runId`, `requestId`, `correlationId`.

4. **Low cardinality by default**

   * Avoid high-cardinality fields unless necessary (e.g., error diagnostics).
   * Enables efficient indexing and querying.

5. **Structure over convention**

   * Use consistent fields instead of relying on message patterns.

---

## **2. Log Categories (Non-Overlapping)**

Every log MUST belong to exactly one category:

| Category         | Purpose                    | Cardinality      | Level(s)        | API                     |
| ---------------- | -------------------------- | ---------------- | --------------- | ----------------------- |
| Lifecycle Events | Domain state transitions   | Very Low         | INFO/WARN/ERROR | Type-safe lifecycle API |
| Operational Logs | System behavior            | Low              | INFO/WARN       | Standard logger         |
| Debug Logs       | Diagnostics / control flow | High (disabled)  | DEBUG           | Standard logger         |
| Error Logs       | Stack traces & diagnostics | Potentially High | ERROR           | Standard logger         |

---

## **3. Lifecycle Event Logging**

### **3.1 Definition**

A lifecycle event MUST satisfy ALL:

* Represents a **domain state transition**
* Is **business meaningful**
* Occurs **at most once per execution** (terminal events exactly once)
* Used for **SLA, monitoring, or audit**
* Can **stand alone** without INFO/DEBUG logs

---

### **3.2 Examples**

**Valid lifecycle events:**

* `run.accepted`
* `run.started`
* `run.completed`
* `run.rejected`
* `run.errored`

**Invalid (non-lifecycle):**

* Cache operations
* Retries
* Validation steps
* Internal branching

---

### **3.3 Canonical Run Lifecycle**

| Event         | Phase     | Outcome  | Level |
| ------------- | --------- | -------- | ----- |
| run.accepted  | accepted  | success  | INFO  |
| run.started   | started   | success  | INFO  |
| run.completed | completed | success  | INFO  |
| run.rejected  | rejected  | rejected | WARN  |
| run.errored   | errored   | failure  | ERROR |

---

### **3.4 Type-Safe Lifecycle API**

```java
public enum RunLifecycleEvent {

    ACCEPTED("run.accepted", "accepted", "success", Level.INFO),
    STARTED("run.started", "started", "success", Level.INFO),
    COMPLETED("run.completed", "completed", "success", Level.INFO),
    REJECTED("run.rejected", "rejected", "rejected", Level.WARN),
    ERRORED("run.errored", "errored", "failure", Level.ERROR);

    public final String event;
    public final String phase;
    public final String outcome;
    public final Level level;

    RunLifecycleEvent(String event, String phase, String outcome, Level level) {
        this.event = event;
        this.phase = phase;
        this.outcome = outcome;
        this.level = level;
    }
}
```

---

### **3.5 Central Lifecycle Logger**

```java
public final class RunLifecycleLogger {

    private static final Logger log =
        LoggerFactory.getLogger("run.lifecycle");

    private RunLifecycleLogger() {}

    public static void emit(RunLifecycleEvent event) {
        emit(event, Map.of());
    }

    public static void emit(RunLifecycleEvent event,
                            Map<String, String> extra) {

        Map<String, Object> fields = new HashMap<>();
        fields.put("event", event.event);
        fields.put("entity", "run");
        fields.put("phase", event.phase);
        fields.put("outcome", event.outcome);

        // Only allow low-cardinality fields
        fields.putAll(extra);

        log.atLevel(event.level)
           .log("{}", fields);
    }
}
```

---

### **3.6 Usage Example**

```java
try {
    executeRun();

    RunLifecycleLogger.emit(RunLifecycleEvent.COMPLETED);

} catch (DuplicateRunException e) {

    RunLifecycleLogger.emit(
        RunLifecycleEvent.REJECTED,
        Map.of("reason", "duplicate")
    );

} catch (Exception e) {

    RunLifecycleLogger.emit(
        RunLifecycleEvent.ERRORED,
        Map.of("error_type", "execution_failure")
    );

    throw e;
}
```

---

## **4. General Logging**

### **4.1 Purpose**

General logs describe **implementation behavior**, not domain state.

They answer:

* What is the system doing?
* Which path was taken?
* Why did something fail?

---

### **4.2 INFO Logging**

**Use for:**

* Major operational steps
* External service calls
* Configuration decisions

**Rules:**

* MUST NOT duplicate lifecycle events
* MUST NOT include identifiers (use MDC)
* SHOULD be concise and readable

**Example:**

```java
log.info("Submitting job to execution backend");
```

**Incorrect:**

```java
log.info("Run started"); // ❌ duplicates lifecycle event
```

---

### **4.3 WARN Logging**

**Use for:**

* Expected, recoverable failures
* Degraded behavior (fallbacks, retries)

**Example:**

```java
log.warn("{}", Map.of(
    "event", "cache.write",
    "component", "redis",
    "operation", "run_cache_write",
    "outcome", "failure"
), cacheException);
```

---

### **4.4 DEBUG Logging**

**Use for:**

* Control flow tracing
* Branch decisions
* Developer diagnostics

**Example:**

```java
log.debug("Resolved backend={} capacityClass={}",
          backend, capacityClass);
```

---

### **4.5 ERROR Logging**

**Use for:**

* Exceptions and stack traces
* Deep diagnostics

**Important:**

* ERROR logs may coexist with `run.errored` lifecycle events
* Lifecycle = **business outcome**
* ERROR log = **technical cause**

---

## **5. MDC (Mapped Diagnostic Context)**

### **Required MDC Fields**

| Field         | Description           |
| ------------- | --------------------- |
| requestId     | Request correlation   |
| runId         | Domain identifier     |
| correlationId | Cross-service tracing |

### **Rules**

* MUST be set at request entry (e.g., filter/interceptor)
* MUST be cleared after request completion
* MUST NOT be duplicated in log payloads

---

## **6. Field Naming Conventions**

| Field      | Description                  |
| ---------- | ---------------------------- |
| event      | Stable event name            |
| entity     | Domain entity (e.g., `run`)  |
| phase      | Lifecycle phase              |
| outcome    | success / failure / rejected |
| reason     | Low-cardinality explanation  |
| error_type | Categorized failure type     |

---

## **7. Decision Guide (Quick Reference)**

| Question                     | Use                 |
| ---------------------------- | ------------------- |
| Did a domain state change?   | Lifecycle event     |
| Is it a business milestone?  | Lifecycle event     |
| Is it implementation detail? | INFO / WARN / DEBUG |
| Needed for SLA / audit?      | Lifecycle event     |
| Is it noisy or diagnostic?   | DEBUG               |

---

## **8. Recommended Enhancements (Practical, Not Overengineered)**

* Use **JSON logging** (e.g., Logback + Jackson encoder)
* Integrate with:

  * Micrometer (metrics from lifecycle events)
  * OpenTelemetry (trace correlation via MDC)
* Enforce via:

  * Code reviews
  * Static checks (optional)

---
