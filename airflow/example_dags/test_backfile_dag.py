from __future__ import annotations

import logging
from collections import defaultdict
from datetime import date, timedelta
from typing import Any

import pendulum
from airflow.decorators import dag, task
from airflow.models import Param
from airflow.utils.trigger_rule import TriggerRule

logger = logging.getLogger(__name__)

# External functions injected by the runtime environment — not declared here so
# the module stays importable without them:
#   get_config() → dict
#   get_events(config, query_params) → list[dict]  (raises RuntimeError on empty)
#   obs_start_run(start_event, tenant_id, run_number=None) → str | None
#   obs_complete_run(run_id, event, tenant_id, reporting_date) → None

_FAILED_ID_CAP = 50


# ── Pure helpers ──────────────────────────────────────────────────────────────

def _safe_get_events(config: dict[str, Any], query_params: dict[str, Any]) -> list[dict[str, Any]]:
    """Wrap get_events; treat empty-result RuntimeError as []."""
    try:
        events = get_events(config=config, query_params=query_params)
        return events if isinstance(events, list) else []
    except RuntimeError as exc:
        if "Failed to fetch valid data" in str(exc):
            logger.info("No events for query %s", query_params)
            return []
        raise


def extract_start_run_id(event: dict[str, Any]) -> str | None:
    return event.get("additionalData", {}).get("runId")


def extract_complete_run_id(event: dict[str, Any]) -> str | None:
    return event.get("additionalData", {}).get("runId")


def index_latest_by_run_id(
    events: list[dict[str, Any]],
    extractor,
) -> dict[str, dict[str, Any]]:
    """Return {runId: event}, keeping the latest event per runId by eventTimestamp."""
    result: dict[str, dict[str, Any]] = {}
    for event in events:
        run_id = extractor(event)
        if not run_id:
            continue
        existing = result.get(run_id)
        if existing is None or event.get("eventTimestamp", "") >= existing.get("eventTimestamp", ""):
            result[run_id] = event
    return result


def assign_run_numbers(
    starts_by_run: dict[str, dict[str, Any]],
    success_run_ids: set[str],
) -> dict[str, int]:
    """Tag each started run as run_number 1 or 2 (two buckets only — never 3+).

    Within each (calculator_name, reporting-date, frequency) group, runs are ordered by the START
    event's publicationTimestamp (tiebreak by runId for determinism). The first successful pair
    (a started run whose complete is a FINISHED event, i.e. its runId is in ``success_run_ids``) and
    every run before it are run_number=1; every run after it is run_number=2. A group with no
    successful pair is entirely run_number=1.
    """
    groups: dict[tuple[Any, Any, Any], list[tuple[str, str]]] = defaultdict(list)
    for run_id, event in starts_by_run.items():
        data = event.get("context", {}).get("data", {})
        key = (data.get("class"), data.get("reporting-date"), data.get("frequency"))
        groups[key].append((event.get("publicationTimestamp", ""), run_id))

    run_numbers: dict[str, int] = {}
    for items in groups.values():
        items.sort(key=lambda t: (t[0], t[1]))  # START publicationTimestamp, then runId
        first_success = next(
            (i for i, (_, rid) in enumerate(items) if rid in success_run_ids), None
        )
        for i, (_, rid) in enumerate(items):
            run_numbers[rid] = 1 if (first_success is None or i <= first_success) else 2
    return run_numbers


def fetch_events(
    config: dict[str, Any],
    scan_date: str,
    event_type: str,
    *,
    calculator: str | None = None,
    frequency: str | None = None,
    tenant_id: str | None = None,
) -> list[dict[str, Any]]:
    """Fetch events of one type for a single reporting date."""
    query_params: dict[str, Any] = {"reporting_date": scan_date, "event_type": event_type}
    if calculator:
        query_params["calculator"] = calculator
    if frequency:
        query_params["frequency"] = frequency
    if tenant_id:
        query_params["tenant_id"] = tenant_id
    return _safe_get_events(config=config, query_params=query_params)


# ── DAG ───────────────────────────────────────────────────────────────────────

@dag(
    dag_id="obs_event_backfill_dag",
    description="One-time backfill of calculator run metrics from historical START/FINISH/FAILED events",
    default_args={},
    tags=["OBS"],
    schedule=None,
    start_date=pendulum.datetime(2021, 1, 1, tz="UTC"),
    catchup=False,
    params={
        "n_days": Param(30, type="integer", minimum=1, title="Number of reporting days to scan"),
        "end_date": Param(date.today().isoformat(), type="string", format="date"),
        "calculator": Param(None, type=["string", "null"], description="Optional filter — omit for all calculators"),
        "frequency": Param(None, type=["string", "null"], description="Optional filter: D or M"),
        "tenant_id": Param("default", type="string"),
        "dry_run": Param(False, type="boolean", description="Log intended calls without posting"),
    },
)
def obs_event_backfill_dag():

    @task(task_id="validate_params")
    def validate_params() -> dict[str, Any]:
        from airflow.operators.python import get_current_context
        conf = get_current_context()["dag_run"].conf or {}

        n_days = int(conf.get("n_days", 30))
        if n_days < 1:
            raise ValueError("n_days must be >= 1")

        raw_end = conf.get("end_date", date.today().isoformat())
        try:
            date.fromisoformat(raw_end)
        except ValueError:
            raise ValueError(f"end_date must be YYYY-MM-DD, got: {raw_end!r}")

        frequency = conf.get("frequency")
        if frequency and frequency.upper() not in {"D", "M", "DAILY", "MONTHLY"}:
            raise ValueError(f"frequency must be one of D, M, DAILY, MONTHLY — got {frequency!r}")

        return {
            "n_days": n_days,
            "end_date": raw_end,
            "calculator": conf.get("calculator"),
            "frequency": frequency,
            "tenant_id": conf.get("tenant_id", "default"),
            "dry_run": bool(conf.get("dry_run", False)),
        }

    @task(task_id="build_reporting_dates")
    def build_reporting_dates(params: dict[str, Any]) -> list[str]:
        end = date.fromisoformat(params["end_date"])
        n = params["n_days"]
        dates = [(end - timedelta(days=i)).isoformat() for i in range(n - 1, -1, -1)]
        logger.info("Reporting dates window: %s → %s (%d days)", dates[0], dates[-1], n)
        return dates

    @task(task_id="backfill_one_date")
    def backfill_one_date(reporting_date: str, params: dict[str, Any]) -> dict[str, Any]:
        config = get_config()
        calculator = params.get("calculator")
        frequency = params.get("frequency")
        tenant_id = params["tenant_id"]
        dry_run = params.get("dry_run", False)

        # 1. Fetch
        starts_raw = fetch_events(config, reporting_date, "STARTED",
                                  calculator=calculator, frequency=frequency, tenant_id=tenant_id)
        finished_raw = fetch_events(config, reporting_date, "FINISHED",
                                    calculator=calculator, frequency=frequency, tenant_id=tenant_id)
        failed_raw = fetch_events(config, reporting_date, "FAILED",
                                  calculator=calculator, frequency=frequency, tenant_id=tenant_id)

        logger.info(
            "date=%s fetched starts=%d finished=%d failed=%d",
            reporting_date, len(starts_raw), len(finished_raw), len(failed_raw),
        )

        # 2. Correlate
        starts_by_run = index_latest_by_run_id(starts_raw, extract_start_run_id)
        completes_by_run = index_latest_by_run_id(finished_raw + failed_raw, extract_complete_run_id)

        # A run is "successful" only if it has a matching FINISHED event (FAILED does not count).
        finished_by_run = index_latest_by_run_id(finished_raw, extract_complete_run_id)
        success_run_ids = {rid for rid in finished_by_run if rid in starts_by_run}
        run_numbers = assign_run_numbers(starts_by_run, success_run_ids)

        orphan_complete = sum(1 for rid in completes_by_run if rid not in starts_by_run)
        if orphan_complete:
            logger.warning("date=%s orphan_complete=%d (no matching start — skipped)", reporting_date, orphan_complete)

        matched_pairs = sum(1 for rid in starts_by_run if rid in completes_by_run)

        # 3. Replay
        posted_both = 0
        start_only = 0
        failed_count = 0
        failed_run_ids: list[str] = []
        run1 = sum(1 for n in run_numbers.values() if n == 1)
        run2 = sum(1 for n in run_numbers.values() if n == 2)

        for run_id, start_event in starts_by_run.items():
            complete_event = completes_by_run.get(run_id)
            run_number = run_numbers[run_id]
            rd = (
                start_event.get("context", {}).get("data", {}).get("reporting-date")
                or reporting_date
            )

            if dry_run:
                logger.info(
                    "[DRY-RUN] would POST /start run_id=%s reporting_date=%s run_number=%d has_complete=%s",
                    run_id, rd, run_number, complete_event is not None,
                )
                if complete_event:
                    posted_both += 1
                else:
                    start_only += 1
                continue

            try:
                posted_run_id = obs_start_run(start_event, tenant_id, run_number=run_number) or run_id
                if complete_event:
                    obs_complete_run(posted_run_id, complete_event, tenant_id, rd)
                    posted_both += 1
                else:
                    start_only += 1
            except Exception:
                logger.exception("Replay failed for run_id=%s date=%s", run_id, rd)
                failed_count += 1
                if len(failed_run_ids) < _FAILED_ID_CAP:
                    failed_run_ids.append(run_id)

        return {
            "reporting_date": reporting_date,
            "started_total": len(starts_by_run),
            "matched_pairs": matched_pairs,
            "posted_both": posted_both,
            "start_only": start_only,
            "orphan_complete": orphan_complete,
            "failed": failed_count,
            "run1": run1,
            "run2": run2,
            "failed_run_ids": failed_run_ids,
        }

    @task(task_id="summarise", trigger_rule=TriggerRule.ALL_DONE)
    def summarise(per_date: list[dict[str, Any]]) -> dict[str, Any]:
        results = [r for r in (per_date or []) if isinstance(r, dict)]
        total_started = sum(r.get("started_total", 0) for r in results)
        matched_pairs = sum(r.get("matched_pairs", 0) for r in results)
        posted_both = sum(r.get("posted_both", 0) for r in results)
        start_only = sum(r.get("start_only", 0) for r in results)
        orphan_complete = sum(r.get("orphan_complete", 0) for r in results)
        failed = sum(r.get("failed", 0) for r in results)
        run1 = sum(r.get("run1", 0) for r in results)
        run2 = sum(r.get("run2", 0) for r in results)

        all_failed_ids: list[str] = []
        for r in results:
            all_failed_ids.extend(r.get("failed_run_ids", []))

        summary = {
            "total_started": total_started,
            "matched_pairs": matched_pairs,
            "posted_both": posted_both,
            "start_only": start_only,
            "orphan_complete": orphan_complete,
            "failed": failed,
            "run1": run1,
            "run2": run2,
            "failed_run_ids": all_failed_ids[:_FAILED_ID_CAP],
            "per_date": results,
        }
        logger.info(
            "OBS backfill complete — started=%d matched_pairs=%d posted_both=%d "
            "start_only=%d orphan=%d failed=%d run1=%d run2=%d",
            total_started, matched_pairs, posted_both, start_only, orphan_complete, failed, run1, run2,
        )
        return summary

    # ── Wiring ────────────────────────────────────────────────────────────────
    params = validate_params()
    dates = build_reporting_dates(params)
    per_date_results = backfill_one_date.partial(params=params).expand(reporting_date=dates)
    summarise(per_date_results)


obs_event_backfill_dag()
