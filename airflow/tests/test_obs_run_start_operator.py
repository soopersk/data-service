"""Unit tests for ObsRunStartOperator."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest
from airflow.exceptions import AirflowException

from observability.operators.obs_run_start_operator import ObsRunStartOperator


def _make_operator(**overrides) -> ObsRunStartOperator:
    defaults = dict(
        task_id="obs_run_start",
        tenant_id="acme-corp",
        correlation_id="corr-abc",
        event_service_url="http://event-svc/start",
        calculator_id="calc-001",
        calculator_name="My Calc",
        frequency="DAILY",
        reporting_date="2026-04-05",
        sla_time_cet="06:30:00",
    )
    defaults.update(overrides)
    return ObsRunStartOperator(**defaults)


class TestObsRunStartOperatorExecute:
    def test_defers_on_execute(self):
        op = _make_operator()
        context = MagicMock()

        with (
            patch.object(op, "_build_event_service_headers", return_value={}),
            patch.object(op, "defer") as mock_defer,
        ):
            op.execute(context)

        mock_defer.assert_called_once()
        call_kwargs = mock_defer.call_args.kwargs
        assert call_kwargs["method_name"] == "execute_complete"

    def test_trigger_receives_correct_params(self):
        op = _make_operator(correlation_id="corr-xyz", poll_interval=5.0, deferral_timeout=600.0)
        context = MagicMock()

        with (
            patch.object(op, "_build_event_service_headers", return_value={"Authorization": "Basic x"}),
            patch.object(op, "defer") as mock_defer,
        ):
            op.execute(context)

        trigger = mock_defer.call_args.kwargs["trigger"]
        assert trigger.correlation_id == "corr-xyz"
        assert trigger.poll_interval == 5.0
        assert trigger.timeout == 600.0
        assert trigger.event_service_url == "http://event-svc/start"


class TestObsRunStartOperatorExecuteComplete:
    def _run_execute_complete(self, op, event):
        mock_ti = MagicMock()
        context = {"ti": mock_ti}
        mock_response = {"runId": "db-999", "status": "RUNNING", "slaBreached": False}

        with patch(
            "observability.operators.obs_run_start_operator.ObsHook"
        ) as MockHook:
            MockHook.return_value.start_run.return_value = mock_response
            result = op.execute_complete(context, event)

        return result, mock_ti, MockHook

    def test_raises_on_timeout_event(self):
        op = _make_operator()
        with pytest.raises(AirflowException, match="Timed out"):
            op.execute_complete({}, {"error": "timeout", "correlation_id": "corr-abc"})

    def test_calls_start_run_with_correct_payload(self):
        op = _make_operator(
            calculator_id="calc-001",
            calculator_name="My Calc",
            frequency="DAILY",
            reporting_date="2026-04-05",
            sla_time_cet="06:30:00",
            expected_duration_ms=300_000,
        )
        event = {"databricks_run_id": "db-999", "start_time": "2026-04-05T05:00:00Z"}
        _, _, MockHook = self._run_execute_complete(op, event)

        payload = MockHook.return_value.start_run.call_args.args[0]
        assert payload["runId"] == "db-999"
        assert payload["startTime"] == "2026-04-05T05:00:00Z"
        assert payload["calculatorId"] == "calc-001"
        assert payload["slaTimeCet"] == "06:30:00"
        assert payload["expectedDurationMs"] == 300_000

    def test_omits_optional_fields_when_none(self):
        op = _make_operator()  # no expected_duration_ms or run_parameters
        event = {"databricks_run_id": "db-1", "start_time": "2026-04-05T05:00:00Z"}
        _, _, MockHook = self._run_execute_complete(op, event)

        payload = MockHook.return_value.start_run.call_args.args[0]
        assert "expectedDurationMs" not in payload
        assert "runParameters" not in payload

    def test_pushes_databricks_run_id_to_xcom(self):
        op = _make_operator()
        event = {"databricks_run_id": "db-999", "start_time": "2026-04-05T05:00:00Z"}
        _, mock_ti, _ = self._run_execute_complete(op, event)

        mock_ti.xcom_push.assert_any_call(key="databricks_run_id", value="db-999")

    def test_pushes_run_response_to_xcom(self):
        op = _make_operator()
        event = {"databricks_run_id": "db-999", "start_time": "2026-04-05T05:00:00Z"}
        _, mock_ti, _ = self._run_execute_complete(op, event)

        mock_ti.xcom_push.assert_any_call(
            key="run_response",
            value={"runId": "db-999", "status": "RUNNING", "slaBreached": False},
        )

    def test_returns_databricks_run_id(self):
        op = _make_operator()
        event = {"databricks_run_id": "db-999", "start_time": "2026-04-05T05:00:00Z"}
        result, _, _ = self._run_execute_complete(op, event)
        assert result == "db-999"
