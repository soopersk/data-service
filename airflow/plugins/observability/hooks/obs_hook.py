"""
ObsHook — thin wrapper around Airflow's HttpHook for the Observability ingestion API.

Centralises:
  - Basic-auth header construction from the Airflow Connection
  - X-Tenant-Id header injection
  - Endpoint paths for /start and /complete

Airflow Connection (conn_id: obs_ingestion_default, type: HTTP):
  host     → observability-service:8080
  schema   → http  (or https)
  login    → username
  password → password
  extra    → {"tenant_id": "acme-corp"}   # default tenant, overridable per-call
"""

from __future__ import annotations

import base64
import json
import logging

from airflow.exceptions import AirflowException
from airflow.providers.http.hooks.http import HttpHook

log = logging.getLogger(__name__)


class ObsHook(HttpHook):
    """HTTP hook for the Observability run-ingestion endpoints."""

    conn_name_attr = "obs_conn_id"
    default_conn_name = "obs_ingestion_default"
    conn_type = "http"
    hook_name = "Observability Ingestion"

    def __init__(self, conn_id: str = default_conn_name) -> None:
        super().__init__(method="POST", http_conn_id=conn_id)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _get_headers(self, tenant_id: str) -> dict[str, str]:
        """Build auth + tenant headers from the Airflow Connection."""
        conn = self.get_connection(self.http_conn_id)
        credentials = base64.b64encode(
            f"{conn.login}:{conn.get_password()}".encode()
        ).decode()
        return {
            "Authorization": f"Basic {credentials}",
            "X-Tenant-Id": tenant_id,
            "Content-Type": "application/json",
        }

    def _call(self, endpoint: str, payload: dict, tenant_id: str) -> dict:
        """POST to endpoint, raise on non-2xx, return parsed JSON body."""
        headers = self._get_headers(tenant_id)
        log.info("ObsHook POST %s tenant=%s", endpoint, tenant_id)
        response = self.run(
            endpoint=endpoint,
            data=json.dumps(payload),
            headers=headers,
        )
        # HttpHook.run() raises AirflowException on non-2xx by default
        return response.json()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def start_run(self, payload: dict, tenant_id: str) -> dict:
        """
        POST /api/v1/runs/start

        Returns the RunResponse dict on success.
        Raises AirflowException on HTTP error.
        """
        return self._call("/api/v1/runs/start", payload, tenant_id)

    def complete_run(self, run_id: str, payload: dict, tenant_id: str) -> dict:
        """
        POST /api/v1/runs/{run_id}/complete

        Returns the RunResponse dict on success.
        Raises AirflowException on HTTP error.
        """
        return self._call(f"/api/v1/runs/{run_id}/complete", payload, tenant_id)
