# Duration-Based SLA Cleanup Plan

## Summary

Current SLA handling is mostly sound operationally: storing `calculator_runs.sla_time` as a `TIMESTAMPTZ` derived deadline is the right design because completion grading, live Redis monitoring, and query projections all need one absolute comparison point.

The main fixes are API and semantics:

- `StartRunRequest.slaTime` should change from `Instant` to `String`, because an `Instant` cannot represent `PT2H30M`.
- `CalculatorRun.slaTime` and DB `sla_time` should stay `Instant`/`TIMESTAMPTZ`; it should store the frozen derived deadline.
- Incoming `slaTime` duration wins over `expectedDurationMs`.
- If both external fields are missing, use the existing cached profile aggregation/lookback logic.
- Treat incoming durations as the same kind of baseline as `expectedDurationMs`, then apply the existing threshold/band formula.

## Key Changes

- Update the start-run wire contract:
  - `slaTime` accepts ISO-8601 duration strings such as `"PT2H30M"`.
  - Do not accept legacy ISO instants such as `"2026-05-19T15:00:00Z"`.
  - Do not support clock-only values like `"22:00"`.

- Refactor `SlaBaselineResolver` to return a small resolution object:
  - `baselineDurationMs`
  - derived `deadline`
  - Precedence: `slaTime` duration -> `expectedDurationMs` -> cached profile average -> ungraded.

- Update `RunIngestionService`:
  - Persist `run.slaTime = resolved.deadline`.
  - Persist `run.expectedDurationMs = resolved.baselineDurationMs`, so query consumers always see the effective SLA baseline when one exists.
  - Use the resolved baseline for estimated-end fallback when `estimatedEndTime` is absent.

- Keep existing SLA grading and live monitoring behavior:
  - `deadline = startTime + baselineDurationMs * (1 + thresholdPercent) + lateBand`.
  - Completion grading and Redis live detection continue comparing against the frozen `slaTime`.
  - No DB migration is needed.

- Update docs:
  - `docs/user/consumer-api.md`, ingestion/SLA docs, and `CLAUDE.md`.
  - Clarify that request `slaTime` means duration, while persisted/response `slaTime` means derived deadline.

## Test Plan

- Unit tests for SLA resolution:
  - `slaTime: "PT2H30M"` derives deadline from 9,000,000 ms.
  - `slaTime` wins when `expectedDurationMs` disagrees.
  - `expectedDurationMs` is used when `slaTime` is absent.
  - profile average is used when both external fields are absent.
  - invalid, zero, or negative `slaTime` fails with `400`.

- Service tests:
  - start-run stores derived `slaTime`.
  - start-run stores effective `expectedDurationMs`.
  - live monitoring registration still happens only when a deadline exists.
  - estimated end uses the resolved baseline when only `slaTime` is provided.

- Controller/API tests:
  - JSON payload with `"slaTime": "PT2H30M"` is accepted.
  - legacy ISO instant is rejected.
  - malformed `slaTime` returns a stable bad-request response.

- Regression tests:
  - existing `SlaEvaluationService` band behavior remains unchanged.
  - live breach detection severity boundaries remain unchanged.
  - query responses still expose `sla`/`slaTime` as instants.

## Assumptions

- `slaTime` wins over `expectedDurationMs` when both are present.
- ISO-8601 duration is the supported new format.
- The existing threshold and late-band formula still applies.
- Tenant optionality, calculator-name query changes, and cache key strategy are outside this SLA-specific change unless a failing test exposes a direct dependency.
