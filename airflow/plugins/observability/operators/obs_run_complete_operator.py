"""
ObsRunCompleteOperator — standard (non-deferrable) Airflow operator that:

  1. Pulls the completion event from XCom (pushed by the upstream
     CheckStatusOperator / deferrable status sensor).
  2. Extracts end_time and status from the event payload.
  3. Calls POST /api/v1/runs/{run_id}/complete on the Observability service.
  4. Pushes the RunResponse to XCom.

Must be wired with trigger_rule=TriggerRule.ALL_DONE so it fires even when
the upstream CheckStatusOperator times out — preventing orphaned RUNNING
records and ensuring the SLA state is always closed correctly.

DAG wiring example:
    obs_complete = ObsRunCompleteOperator(
        task_id="obs_run_complete",
        conn_id="obs_ingestion_default",
        tenant_id="acme-corp",
        run_id="{{ ti.xcom_pull('obs_run_start', key='databricks_run_id') }}",
        source_task_id="check_calculator_status",
        end_time_field="endTime",
        status_field="status",
        trigger_rule=TriggerRule.ALL_DONE,
        retries=3,
    )
"""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from typing import Any

from airflow.exceptions import AirflowException
from airflow.models import BaseOperator

from observability.hooks.obs_hook import ObsHook

log = logging.getLogger(__name__)


class ObsRunCompleteOperator(BaseOperator):
    """
    Calls POST /api/v1/runs/{run_id}/complete using data from the upstream
    completion event stored in XCom.

    Args:
        conn_id:                    Airflow Connection ID for the Observability
                                    service (default ``obs_ingestion_default``).
        tenant_id:                  Value for the ``X-Tenant-Id`` request header.
        run_id:                     Observability runId (= databricks_run_id).
                                    Templatable — typically pulled from
                                    ObsRunStartOperator XCom.
        source_task_id:             task_id whose XCom holds the completion event
                                    (e.g. ``"check_calculator_status"``).
        end_time_field:             Key in the completion event payload for end time
                                    (default ``"endTime"``).
        status_field:               Key in the completion event payload for run status
                                    (default ``"status"``).
        xcom_key:                   XCom key to pull from source_task_id
                                    (default ``"return_value"``).
        status_on_upstream_failure: Status sent to /complete when the upstream task
                                    failed or timed out and left no XCom data
                                    (default ``"FAILED"``). Set to ``None`` to raise
                                    instead of closing the run.
    """

    template_fields = ("run_id",)

    def __init__(
        self,
        *,
        conn_id: str = ObsHook.default_conn_name,
        tenant_id: str,
        run_id: str,
        source_task_id: str,
        end_time_field: str = "endTime",
        status_field: str = "status",
        xcom_key: str = "return_value",
        status_on_upstream_failure: str | None = "FAILED",
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.conn_id = conn_id
        self.tenant_id = tenant_id
        self.run_id = run_id
        self.source_task_id = source_task_id
        self.end_time_field = end_time_field
        self.status_field = status_field
        self.xcom_key = xcom_key
        self.status_on_upstream_failure = status_on_upstream_failure

    def execute(self, context: dict) -> dict:
        """
        Pull XCom completion event, extract fields, call /complete.

        If the upstream task failed/timed out (XCom is None):
          - If status_on_upstream_failure is set: calls /complete with that
            status and end_time=now(), closing the RUNNING record.
          - If status_on_upstream_failure is None: raises AirflowException.

        Returns the RunResponse dict (also pushed to XCom key "run_response").
        """
        raw = context["ti"].xcom_pull(
            task_ids=self.source_task_id, key=self.xcom_key
        )

        if raw is None:
            if self.status_on_upstream_failure is None:
                raise AirflowException(
                    f"No completion event found in XCom from task "
                    f"'{self.source_task_id}' and status_on_upstream_failure is None."
                )
            log.warning(
                "No completion event in XCom from '%s' — upstream task likely "
                "failed or timed out. Closing run as %s.",
                self.source_task_id,
                self.status_on_upstream_failure,
            )
            end_time = datetime.now(timezone.utc).isoformat()
            status = self.status_on_upstream_failure
        else:
            event = _parse_event(raw)
            end_time = event[self.end_time_field]
            status = event.get(self.status_field, "SUCCESS")

        payload = {"endTime": end_time, "status": status}

        log.info(
            "ObsRunCompleteOperator calling /complete — run_id=%s status=%s endTime=%s",
            self.run_id,
            status,
            end_time,
        )

        hook = ObsHook(self.conn_id)
        run_response = hook.complete_run(self.run_id, payload, self.tenant_id)

        log.info(
            "Observability /complete succeeded — runId=%s status=%s "
            "slaBreached=%s durationMs=%s",
            run_response.get("runId"),
            run_response.get("status"),
            run_response.get("slaBreached"),
            run_response.get("durationMs"),
        )

        context["ti"].xcom_push(key="run_response", value=run_response)
        return run_response


# ---------------------------------------------------------------------------
# Module-level helper (not a method — keeps operator class lean)
# ---------------------------------------------------------------------------

def _parse_event(raw: Any) -> dict:
    """
    Normalise the XCom value from the upstream task into a plain dict.

    Handles three shapes that different upstream operators produce:
      - dict              → returned as-is
      - str               → parsed as JSON
      - list[dict]        → treats first element as the message container;
                            if it has a "value" key (Kafka message format),
                            the value is parsed as JSON
    """
    if isinstance(raw, dict):
        return raw

    if isinstance(raw, str):
        return json.loads(raw)

    if isinstance(raw, list):
        if not raw:
            raise AirflowException("Completion event XCom list is empty.")
        item = raw[0]
        if isinstance(item, dict) and "value" in item:
            value = item["value"]
            return json.loads(value) if isinstance(value, str) else value
        return item if isinstance(item, dict) else json.loads(item)

    raise AirflowException(
        f"Cannot parse completion event from XCom — unexpected type {type(raw).__name__}."
    )
