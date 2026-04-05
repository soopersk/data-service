"""
ObsStartEventTrigger — async BaseTrigger that polls the EDF event microservice
for a calculator START event identified by correlation_id.

Polling is done via aiohttp (available in all Airflow 2.10 environments through
the apache-airflow-providers-http dependency chain).

Yielded TriggerEvent payload on success:
  {
    "databricks_run_id": str,
    "start_time":        str,   # ISO-8601 UTC
    ...                         # any extra fields from the START event
  }

Yielded TriggerEvent payload on timeout:
  {"error": "timeout", "correlation_id": str}
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import AsyncIterator

try:
    import aiohttp
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "aiohttp is required by ObsStartEventTrigger. "
        "Install it with: pip install aiohttp"
    ) from exc

from airflow.triggers.base import BaseTrigger, TriggerEvent

log = logging.getLogger(__name__)


class ObsStartEventTrigger(BaseTrigger):
    """
    Polls the EDF event microservice for a calculator START event.

    Args:
        event_service_url:  Full URL of the microservice endpoint, e.g.
                            ``http://edf-event-service/events/calculator/start``.
                            The trigger appends ``?correlationId={correlation_id}``.
        correlation_id:     EDF correlation ID pushed to XCom by EdfTriggerOperator.
        auth_headers:       Dict with ``Authorization`` and any other static headers
                            needed to reach the event microservice.
        poll_interval:      Seconds between polls (default 10).
        timeout:            Seconds before giving up and yielding an error event
                            (default 1800 = 30 min).
    """

    def __init__(
        self,
        event_service_url: str,
        correlation_id: str,
        auth_headers: dict[str, str],
        poll_interval: float = 10.0,
        timeout: float = 1800.0,
    ) -> None:
        super().__init__()
        self.event_service_url = event_service_url
        self.correlation_id = correlation_id
        self.auth_headers = auth_headers
        self.poll_interval = poll_interval
        self.timeout = timeout

    def serialize(self) -> tuple[str, dict]:
        return (
            "observability.triggers.obs_start_event_trigger.ObsStartEventTrigger",
            {
                "event_service_url": self.event_service_url,
                "correlation_id": self.correlation_id,
                "auth_headers": self.auth_headers,
                "poll_interval": self.poll_interval,
                "timeout": self.timeout,
            },
        )

    async def run(self) -> AsyncIterator[TriggerEvent]:
        """
        Polls event_service_url until a START event is found or timeout expires.

        The microservice is expected to return:
          - HTTP 200 + JSON body when the event exists
          - HTTP 404 (or empty body) when not yet available

        The JSON body must contain at minimum:
          ``databricks_run_id`` and ``start_time``.
        """
        deadline = asyncio.get_event_loop().time() + self.timeout
        url = f"{self.event_service_url}?correlationId={self.correlation_id}"

        log.info(
            "ObsStartEventTrigger polling %s (correlation_id=%s, timeout=%ss)",
            url,
            self.correlation_id,
            self.timeout,
        )

        async with aiohttp.ClientSession(headers=self.auth_headers) as session:
            while asyncio.get_event_loop().time() < deadline:
                try:
                    async with session.get(url) as resp:
                        if resp.status == 200:
                            body = await resp.json(content_type=None)
                            if body and body.get("databricks_run_id"):
                                log.info(
                                    "START event received for correlation_id=%s: %s",
                                    self.correlation_id,
                                    body,
                                )
                                yield TriggerEvent(body)
                                return
                            # 200 but no useful payload yet — keep polling
                        elif resp.status not in (404, 204):
                            log.warning(
                                "Unexpected status %s from event service — will retry",
                                resp.status,
                            )
                except aiohttp.ClientError as exc:
                    log.warning("Network error polling event service: %s — will retry", exc)

                await asyncio.sleep(self.poll_interval)

        log.error(
            "Timeout waiting for START event (correlation_id=%s, timeout=%ss)",
            self.correlation_id,
            self.timeout,
        )
        yield TriggerEvent({"error": "timeout", "correlation_id": self.correlation_id})
