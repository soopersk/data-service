"""Unit tests for ObsRunCompleteOperator."""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException

from observability.operators.obs_run_complete_operator import (
    ObsRunCompleteOperator,
    _parse_event,
)


def _make_operator(**overrides) -> ObsRunCompleteOperator:
    defaults = dict(
        task_id="obs_run_complete",
        tenant_id="acme-corp",
        run_id="db-999",
        source_task_id="check_calculator_status",
    )
    defaults.update(overrides)
    return ObsRunCompleteOperator(**defaults)


def _make_context(xcom_value=None):
    mock_ti = MagicMock()
    mock_ti.xcom_pull.return_value = xcom_value
    return {"ti": mock_ti}


class TestParseEvent:
    def test_dict_passthrough(self):
        assert _parse_event({"endTime": "T"}) == {"endTime": "T"}

    def test_json_string(self):
        raw = json.dumps({"endTime": "T", "status": "SUCCESS"})
        assert _parse_event(raw) == {"endTime": "T", "status": "SUCCESS"}

    def test_list_with_value_key(self):
        inner = {"endTime": "T", "status": "SUCCESS"}
        raw = [{"value": json.dumps(inner)}]
        assert _parse_event(raw) == inner

    def test_list_with_dict_item(self):
        raw = [{"endTime": "T", "status": "FAILED"}]
        assert _parse_event(raw) == {"endTime": "T", "status": "FAILED"}

    def test_empty_list_raises(self):
        with pytest.raises(AirflowException):
            _parse_event([])

    def test_unsupported_type_raises(self):
        with pytest.raises(AirflowException, match="unexpected type"):
            _parse_event(42)


class TestObsRunCompleteOperatorExecute:
    def _run(self, op, xcom_value, mock_response=None):
        if mock_response is None:
            mock_response = {"runId": "db-999", "status": "SUCCESS", "durationMs": 3600}
        context = _make_context(xcom_value)

        with patch(
            "observability.operators.obs_run_complete_operator.ObsHook"
        ) as MockHook:
            MockHook.return_value.complete_run.return_value = mock_response
            result = op.execute(context)

        return result, context["ti"], MockHook

    # ── Happy path ──────────────────────────────────────────────────────────

    def test_calls_complete_run_with_correct_run_id(self):
        op = _make_operator(run_id="db-999")
        event = {"endTime": "2026-04-05T06:00:00Z", "status": "SUCCESS"}
        _, _, MockHook = self._run(op, event)

        MockHook.return_value.complete_run.assert_called_once()
        args = MockHook.return_value.complete_run.call_args.args
        assert args[0] == "db-999"

    def test_extracts_end_time_and_status_from_event(self):
        op = _make_operator()
        event = {"endTime": "2026-04-05T06:00:00Z", "status": "FAILED"}
        _, _, MockHook = self._run(op, event)

        payload = MockHook.return_value.complete_run.call_args.args[1]
        assert payload["endTime"] == "2026-04-05T06:00:00Z"
        assert payload["status"] == "FAILED"

    def test_defaults_status_to_success_when_missing(self):
        op = _make_operator()
        event = {"endTime": "2026-04-05T06:00:00Z"}  # no status field
        _, _, MockHook = self._run(op, event)

        payload = MockHook.return_value.complete_run.call_args.args[1]
        assert payload["status"] == "SUCCESS"

    def test_custom_field_names(self):
        op = _make_operator(end_time_field="end", status_field="result")
        event = {"end": "2026-04-05T06:00:00Z", "result": "TIMEOUT"}
        _, _, MockHook = self._run(op, event)

        payload = MockHook.return_value.complete_run.call_args.args[1]
        assert payload["endTime"] == "2026-04-05T06:00:00Z"
        assert payload["status"] == "TIMEOUT"

    def test_pushes_run_response_to_xcom(self):
        op = _make_operator()
        mock_resp = {"runId": "db-999", "status": "SUCCESS", "durationMs": 100}
        event = {"endTime": "2026-04-05T06:00:00Z", "status": "SUCCESS"}
        _, mock_ti, _ = self._run(op, event, mock_response=mock_resp)

        mock_ti.xcom_push.assert_called_once_with(key="run_response", value=mock_resp)

    # ── Upstream failure path ────────────────────────────────────────────────

    def test_sends_failed_when_xcom_is_none_and_override_set(self):
        op = _make_operator(status_on_upstream_failure="FAILED")
        _, _, MockHook = self._run(op, xcom_value=None)

        payload = MockHook.return_value.complete_run.call_args.args[1]
        assert payload["status"] == "FAILED"
        assert "endTime" in payload  # should be set to now()

    def test_raises_when_xcom_is_none_and_override_is_none(self):
        op = _make_operator(status_on_upstream_failure=None)
        context = _make_context(xcom_value=None)

        with (
            patch("observability.operators.obs_run_complete_operator.ObsHook"),
            pytest.raises(AirflowException),
        ):
            op.execute(context)

    def test_accepts_json_string_xcom(self):
        op = _make_operator()
        event = json.dumps({"endTime": "2026-04-05T06:00:00Z", "status": "SUCCESS"})
        _, _, MockHook = self._run(op, event)

        payload = MockHook.return_value.complete_run.call_args.args[1]
        assert payload["endTime"] == "2026-04-05T06:00:00Z"
