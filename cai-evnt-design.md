
## State Diagram

```
stateDiagram-v2
    direction TB

    [*] --> NO_VARIABLE : LBD begins

    NO_VARIABLE --> RUNNING : START event arrives
                              Generate UUID
                              run_counter = 1
                              INSERT process table

    RUNNING --> IDLE : FINISH event arrives
                       Correlate via current_run.run_id
                       UPDATE status = COMPLETED
                       Promote current_run → last_completed
                       Set current_run = null

    IDLE --> RUNNING : Next START event arrives
                       Generate new UUID
                       run_counter = run_counter + 1
                       INSERT process table

    IDLE --> [*] : Maintenance DAG at day close
                   Variable.delete()

    note right of NO_VARIABLE
        Variable does not exist yet.
        Key: 20260430_RUN_STATE
        current_run : null
        run_counter : 0
        last_completed : null
    end note

    note right of RUNNING
        current_run.status = RUNNING
        current_run.run_id = uuid-a1b2
        run_counter = 1
        last_completed = null
    end note

    note right of IDLE
        current_run = null
        run_counter = 1
        last_completed.run_id = uuid-a1b2
        last_completed.end_time = set
    end note
```

### Run 1 — RUNNING

START processed, UUID minted

Variable state:

```{
  "lbd": "20260430",
  "current_run": {
    "run_id": "uuid-a1b2",
    "start_time": "2026-04-30T08:00:00Z",
    "status": "RUNNING"
  },
  "run_counter": 1,
  "last_completed": null
}
```

### Run 1 — COMPLETED

FINISH received, run correlated: (Variable.get() → current_run.run_id = uuid-a1b2)

Variable state:

```{
  "lbd": "20260430",
  "current_run": null,
  "run_counter": 1,
  "last_completed": {
    "run_id": "uuid-a1b2",
    "start_time": "2026-04-30T08:00:00Z",
    "end_time": "2026-04-30T08:47:00Z"
  }
}
```

### Run 2 — RUNNING

Second START same LBD

Variable state:

```{
  "lbd": "20260430",
  "current_run": {
    "run_id": "uuid-c3d4",
    "start_time": "2026-04-30T11:30:00Z",
    "status": "RUNNING"
  },
  "run_counter": 2,
  "last_completed": {
    "run_id": "uuid-a1b2",
    "start_time": "2026-04-30T08:00:00Z",
    "end_time": "2026-04-30T08:47:00Z"
  }
}
```

New START arrives. current_run is null and last_completed.run_id exists → this is run 2. Mint new UUID. run_counter increments to 2. last_completed is preserved — both runs visible in one variable.

### Run 2 — COMPLETED

LBD closes cleanly:

Variable state:

{
```  "lbd": "20260430",
  "current_run": null,
  "run_counter": 2,
  "last_completed": {
    "run_id": "uuid-c3d4",
    "start_time": "2026-04-30T11:30:00Z",
    "end_time": "2026-04-30T12:15:00Z"
  }
}
```
FINISH arrives for run 2. Correlation via uuid-c3d4. Variable updated. Day ends cleanly with run_counter=2. 
Maintenance DAG will delete 20260430_RUN_STATE after N LBD closes.