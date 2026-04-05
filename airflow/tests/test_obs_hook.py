"""Unit tests for ObsHook."""

from __future__ import annotations

import base64
import json
from unittest.mock import MagicMock, patch

import pytest

from observability.hooks.obs_hook import ObsHook


@pytest.fixture()
def mock_connection():
    conn = MagicMock()
    conn.login = "admin"
    conn.get_password.return_value = "secret"
    return conn


@pytest.fixture()
def hook(mock_connection):
    h = ObsHook(conn_id="obs_ingestion_default")
    with patch.object(h, "get_connection", return_value=mock_connection):
        yield h


class TestObsHookHeaders:
    def test_authorization_header_is_basic_base64(self, hook, mock_connection):
        headers = hook._get_headers("tenant-a")
        expected = base64.b64encode(b"admin:secret").decode()
        assert headers["Authorization"] == f"Basic {expected}"

    def test_tenant_id_header(self, hook):
        headers = hook._get_headers("tenant-xyz")
        assert headers["X-Tenant-Id"] == "tenant-xyz"

    def test_content_type_header(self, hook):
        headers = hook._get_headers("t")
        assert headers["Content-Type"] == "application/json"


class TestObsHookStartRun:
    def test_calls_correct_endpoint(self, hook):
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"runId": "db-123", "status": "RUNNING"}
        with patch.object(hook, "run", return_value=mock_resp) as mock_run:
            result = hook.start_run({"runId": "db-123"}, "tenant-a")

        mock_run.assert_called_once()
        call_kwargs = mock_run.call_args.kwargs
        assert call_kwargs["endpoint"] == "/api/v1/runs/start"
        assert json.loads(call_kwargs["data"]) == {"runId": "db-123"}

    def test_returns_parsed_json(self, hook):
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"runId": "db-123", "status": "RUNNING"}
        with patch.object(hook, "run", return_value=mock_resp):
            result = hook.start_run({}, "t")
        assert result["runId"] == "db-123"


class TestObsHookCompleteRun:
    def test_calls_correct_endpoint(self, hook):
        mock_resp = MagicMock()
        mock_resp.json.return_value = {"runId": "db-123", "status": "SUCCESS"}
        with patch.object(hook, "run", return_value=mock_resp) as mock_run:
            hook.complete_run("db-123", {"endTime": "2026-04-05T06:00:00Z"}, "t")

        call_kwargs = mock_run.call_args.kwargs
        assert call_kwargs["endpoint"] == "/api/v1/runs/db-123/complete"

    def test_run_id_in_path(self, hook):
        mock_resp = MagicMock()
        mock_resp.json.return_value = {}
        with patch.object(hook, "run", return_value=mock_resp) as mock_run:
            hook.complete_run("my-run-id", {}, "t")

        assert "/my-run-id/complete" in mock_run.call_args.kwargs["endpoint"]
