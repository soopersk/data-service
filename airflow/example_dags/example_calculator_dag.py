"""
Example DAG: Calculator run with Observability integration.

Shows how to wire ObsRunStartOperator and ObsRunCompleteOperator around an
existing EdfTriggerOperator + CheckStatusOperator pair.

Placeholder classes (EdfTriggerOperator, CheckStatusOperator) stand in for
the real implementations — replace with your actual imports.

Prerequisites
─────────────
Airflow Connection (Admin → Connections):
  Conn ID   : obs_ingestion_default
  Conn Type : HTTP
  Host      : observability-service:8080
  Schema    : http
  Login     : <username>
  Password  : <password>
  Extra     : {"tenant_id": "acme-corp"}

The EdfTriggerOperator must push ``correlation_id`` to XCom so that
ObsRunStartOperator can locate the START event in the event microservice.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

from airflow.models import BaseOperator
from airflow.models.dag import DAG
from airflow.utils.trigger_rule import TriggerRule

from observability.operators.obs_run_complete_operator import ObsRunCompleteOperator
from observability.operators.obs_run_start_operator import ObsRunStartOperator

# ---------------------------------------------------------------------------
# Placeholder operators — replace with your real implementations
# ---------------------------------------------------------------------------


class EdfTriggerOperator(BaseOperator):
    """
    Placeholder for the operator that sends the EDF trigger message.

    IMPORTANT: The real implementation must push ``correlation_id`` to XCom
    so ObsRunStartOperator can poll for the matching START event.

    Example:
        context["ti"].xcom_push(key="correlation_id", value="some-unique-id")
    """

    def execute(self, context: dict) -> Any:
        correlation_id = f"corr-{context['run_id']}"
        self.log.info("EDF trigger sent — correlation_id=%s", correlation_id)
        context["ti"].xcom_push(key="correlation_id", value=correlation_id)
        return correlation_id


class CheckStatusOperator(BaseOperator):
    """
    Placeholder for the existing deferrable operator that waits for the
    Databricks COMPLETE EDF event and pushes it to XCom(return_value).

    The real implementation is deferrable and unchanged by this integration.
    """

    def __init__(self, *, run_id: str, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.run_id = run_id

    def execute(self, context: dict) -> Any:
        # Real operator: defers until completion event arrives from EDF microservice
        completion_event = {
            "endTime": "2026-04-05T06:28:00Z",
            "status": "SUCCESS",
            "durationMs": 360000,
        }
        return completion_event


# ---------------------------------------------------------------------------
# DAG definition
# ---------------------------------------------------------------------------

with DAG(
    dag_id="example_calculator_dag",
    description="Calculator run with Observability tracking via EDF events",
    schedule="0 5 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    default_args={
        "retries": 3,
        "retry_delay": timedelta(minutes=2),
        "retry_exponential_backoff": False,
    },
    tags=["observability", "calculator"],
) as dag:

    # ── Step 1: Send EDF trigger message to launch the Databricks calculator ──
    edf_trigger = EdfTriggerOperator(
        task_id="trigger_calculator",
        # real params: calculator_id, reporting_date, etc.
    )

    # ── Step 2: Wait for Databricks START event → call /api/v1/runs/start ──
    obs_run_start = ObsRunStartOperator(
        task_id="obs_run_start",
        conn_id="obs_ingestion_default",
        tenant_id="acme-corp",
        # Pull the EDF correlation ID pushed by the trigger operator above
        correlation_id="{{ ti.xcom_pull('trigger_calculator', key='correlation_id') }}",
        # URL of the EDF event microservice START endpoint
        event_service_url="http://edf-event-service/events/calculator/start",
        # Calculator metadata — passed directly in StartRunRequest
        calculator_id="calc-001",
        calculator_name="My Calculator",
        frequency="DAILY",
        reporting_date="{{ ds }}",      # Airflow logical date as YYYY-MM-DD
        sla_time_cet="06:30:00",        # SLA deadline (CET time-of-day)
        expected_duration_ms=300_000,   # 5 min — used for >150% duration breach check
        # Deferral config
        poll_interval=10.0,             # poll event service every 10 seconds
        deferral_timeout=1800.0,        # give up after 30 minutes
    )

    # ── Step 3: Wait for Databricks COMPLETE event (existing — unchanged) ────
    check_calculator_status = CheckStatusOperator(
        task_id="check_calculator_status",
        run_id="{{ ti.xcom_pull('obs_run_start', key='databricks_run_id') }}",
    )

    # ── Step 4: Call /api/v1/runs/{run_id}/complete ──────────────────────────
    obs_run_complete = ObsRunCompleteOperator(
        task_id="obs_run_complete",
        conn_id="obs_ingestion_default",
        tenant_id="acme-corp",
        # databricks_run_id pushed by ObsRunStartOperator
        run_id="{{ ti.xcom_pull('obs_run_start', key='databricks_run_id') }}",
        # XCom source: completion event pushed by CheckStatusOperator
        source_task_id="check_calculator_status",
        end_time_field="endTime",
        status_field="status",
        # ALL_DONE ensures this fires even if check_calculator_status times out,
        # closing the RUNNING record in Observability rather than leaving it orphaned.
        trigger_rule=TriggerRule.ALL_DONE,
    )

    # ── Dependency chain ─────────────────────────────────────────────────────
    edf_trigger >> obs_run_start >> check_calculator_status >> obs_run_complete
