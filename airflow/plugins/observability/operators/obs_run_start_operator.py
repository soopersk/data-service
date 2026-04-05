"""
ObsRunStartOperator — deferrable Airflow operator that:

  1. Defers execution to ObsStartEventTrigger, which polls the EDF event
     microservice for the Databricks START event identified by correlation_id.
  2. On resume (execute_complete), extracts databricks_run_id + start_time
     from the event payload and calls POST /api/v1/runs/start.
  3. Pushes databricks_run_id and the full RunResponse to XCom.

Why deferrable:
  The START event may arrive seconds to minutes after the EDF trigger fires.
  Deferring releases the Airflow worker slot during that wait instead of
  blocking with a sleep loop.

DAG wiring example:
    obs_start = ObsRunStartOperator(
        task_id="obs_run_start",
        conn_id="obs_ingestion_default",
        tenant_id="acme-corp",
        correlation_id="{{ ti.xcom_pull('trigger_calculator', key='correlation_id') }}",
        event_service_url="http://edf-event-service/events/calculator/start",
        calculator_id="calc-001",
        calculator_name="My Calculator",
        frequency="DAILY",
        reporting_date="{{ ds }}",
        sla_time_cet="06:30:00",
    )
"""

from __future__ import annotations

import base64
import logging
from typing import Any

from airflow.exceptions import AirflowException
from airflow.models import BaseOperator

from observability.hooks.obs_hook import ObsHook
from observability.triggers.obs_start_event_trigger import ObsStartEventTrigger

log = logging.getLogger(__name__)


class ObsRunStartOperator(BaseOperator):
    """
    Deferrable operator that waits for the Databricks START EDF event then
    calls POST /api/v1/runs/start on the Observability service.

    Args:
        conn_id:               Airflow Connection ID for the Observability service
                               (type HTTP, default ``obs_ingestion_default``).
        tenant_id:             Value for the ``X-Tenant-Id`` request header.
        correlation_id:        EDF correlation ID to locate the START event.
                               Templatable — typically pulled from EdfTriggerOperator XCom.
        event_service_url:     Base URL of the EDF event microservice endpoint that
                               exposes START events, e.g.
                               ``http://edf-event-service/events/calculator/start``.
        calculator_id:         Observability calculatorId. Templatable.
        calculator_name:       Human-readable calculator name.
        frequency:             ``DAILY`` | ``MONTHLY`` | ``D`` | ``M`` (default ``DAILY``).
        reporting_date:        ``YYYY-MM-DD`` reporting date. Templatable (use ``{{ ds }}``).
        sla_time_cet:          SLA deadline as CET time-of-day string ``HH:mm:ss``.
        expected_duration_ms:  Optional expected run duration in milliseconds.
        run_parameters:        Optional dict stored as JSONB in the Observability DB.
        additional_attributes: Optional dict stored as JSONB in the Observability DB.
        poll_interval:         Seconds between polls of the event microservice (default 10).
        deferral_timeout:      Seconds before the trigger gives up waiting for the START
                               event (default 1800 = 30 min). Task fails on timeout.
    """

    template_fields = (
        "correlation_id",
        "calculator_id",
        "calculator_name",
        "frequency",
        "reporting_date",
        "run_parameters",
        "additional_attributes",
    )

    def __init__(
        self,
        *,
        conn_id: str = ObsHook.default_conn_name,
        tenant_id: str,
        correlation_id: str,
        event_service_url: str,
        calculator_id: str,
        calculator_name: str,
        frequency: str = "DAILY",
        reporting_date: str,
        sla_time_cet: str,
        expected_duration_ms: int | None = None,
        run_parameters: dict | None = None,
        additional_attributes: dict | None = None,
        poll_interval: float = 10.0,
        deferral_timeout: float = 1800.0,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.conn_id = conn_id
        self.tenant_id = tenant_id
        self.correlation_id = correlation_id
        self.event_service_url = event_service_url
        self.calculator_id = calculator_id
        self.calculator_name = calculator_name
        self.frequency = frequency
        self.reporting_date = reporting_date
        self.sla_time_cet = sla_time_cet
        self.expected_duration_ms = expected_duration_ms
        self.run_parameters = run_parameters
        self.additional_attributes = additional_attributes
        self.poll_interval = poll_interval
        self.deferral_timeout = deferral_timeout

    # ------------------------------------------------------------------
    # Airflow lifecycle
    # ------------------------------------------------------------------

    def execute(self, context: dict) -> None:
        """
        Defer immediately — no work happens on the worker thread.
        The trigger handles polling; execute_complete handles the API call.
        """
        log.info(
            "ObsRunStartOperator deferring — waiting for START event "
            "(correlation_id=%s, timeout=%ss)",
            self.correlation_id,
            self.deferral_timeout,
        )

        # Auth headers for the event microservice are built here so the trigger
        # does not need access to the Airflow Connection store (triggers run in
        # the triggerer process which may not share the same secrets backend).
        auth_headers = self._build_event_service_headers()

        self.defer(
            trigger=ObsStartEventTrigger(
                event_service_url=self.event_service_url,
                correlation_id=self.correlation_id,
                auth_headers=auth_headers,
                poll_interval=self.poll_interval,
                timeout=self.deferral_timeout,
            ),
            method_name="execute_complete",
        )

    def execute_complete(self, context: dict, event: dict) -> str:
        """
        Called by Airflow when ObsStartEventTrigger yields a TriggerEvent.

        Extracts databricks_run_id and start_time from the START event,
        calls /api/v1/runs/start, and pushes results to XCom.

        Returns:
            databricks_run_id (str) — also available via XCom key "databricks_run_id".
        """
        if event.get("error") == "timeout":
            raise AirflowException(
                f"Timed out waiting for START EDF event "
                f"(correlation_id={event.get('correlation_id')}, "
                f"timeout={self.deferral_timeout}s). "
                f"No Observability record will be created."
            )

        databricks_run_id = str(event["databricks_run_id"])
        start_time: str = event["start_time"]

        log.info(
            "START event received — databricks_run_id=%s start_time=%s",
            databricks_run_id,
            start_time,
        )

        payload = self._build_start_payload(databricks_run_id, start_time)
        hook = ObsHook(self.conn_id)
        run_response = hook.start_run(payload, self.tenant_id)

        log.info(
            "Observability /start succeeded — runId=%s status=%s slaBreached=%s",
            run_response.get("runId"),
            run_response.get("status"),
            run_response.get("slaBreached"),
        )

        ti = context["ti"]
        ti.xcom_push(key="databricks_run_id", value=databricks_run_id)
        ti.xcom_push(key="run_response", value=run_response)

        return databricks_run_id

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _build_start_payload(self, databricks_run_id: str, start_time: str) -> dict:
        payload: dict = {
            "runId": databricks_run_id,
            "calculatorId": self.calculator_id,
            "calculatorName": self.calculator_name,
            "frequency": self.frequency,
            "reportingDate": self.reporting_date,
            "startTime": start_time,
            "slaTimeCet": self.sla_time_cet,
        }
        if self.expected_duration_ms is not None:
            payload["expectedDurationMs"] = self.expected_duration_ms
        if self.run_parameters:
            payload["runParameters"] = self.run_parameters
        if self.additional_attributes:
            payload["additionalAttributes"] = self.additional_attributes
        return payload

    def _build_event_service_headers(self) -> dict[str, str]:
        """
        Build auth headers for the EDF event microservice.

        Re-uses the same Airflow Connection as the Observability hook so that
        a single connection entry covers both the event service and the API
        (adjust if your event microservice uses separate credentials).
        """
        conn = ObsHook(self.conn_id).get_connection(self.conn_id)
        credentials = base64.b64encode(
            f"{conn.login}:{conn.get_password()}".encode()
        ).decode()
        return {"Authorization": f"Basic {credentials}"}
