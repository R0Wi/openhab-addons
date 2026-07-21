"""High level read/write operations, ported from ``CommunicationServiceImpl``.

This layer turns :class:`~stiebel_heatpump.config_loader.Request` objects into
serial round trips and decodes the responses into ``{channel_id: value}`` maps.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Optional

from ..config_loader import Request
from ..models import ChannelDefinition
from . import parser
from .connector import Connector

logger = logging.getLogger(__name__)

MAX_TRIES = 3

# channel ids handled specially by set_time (see the binding's setTime)
_TIME_FIELDS = ("weekday", "hours", "minutes", "seconds", "year", "month", "day")


class CommunicationService:
    """Read and write heat pump values over a :class:`Connector`."""

    def __init__(self, connector: Connector, waiting_time_ms: int = 1200) -> None:
        self._connector = connector
        self._waiting_time = waiting_time_ms / 1000.0

    def connect(self) -> None:
        self._connector.connect()

    def close(self) -> None:
        self._connector.disconnect()

    # -- reading -------------------------------------------------------------

    def get_version(self, version_request: Request) -> str:
        data = self.read_request(version_request)
        version = data.get("version")
        if version is None:
            logger.warning("Version key not found in response data!")
            return "<UNKNOWN_VERSION>"
        return str(version)

    def read_request(self, request: Request) -> dict[str, object]:
        """Read a single request (with retries + header validation)."""
        count = 0
        while count < MAX_TRIES:
            count += 1
            try:
                response = self._connector.get_data(
                    parser.create_request_message(request.request_bytes)
                )
                if parser.header_check(response):
                    if request.request_bytes2 is None:
                        return parser.parse_records(response, request.records)
                    time.sleep(self._waiting_time)
                    response2 = self._connector.get_data(
                        parser.create_request_message(request.request_bytes2)
                    )
                    if parser.header_check(response2):
                        return parser.parse_records(response, request.records, response2)
                return {}
            except parser.ProtocolError as exc:
                logger.warning("Error reading data for %s: %s (retry %s)",
                               request.request_byte, exc, count)
                self._restart()
        logger.warning("read_request failed %s times!", MAX_TRIES)
        return {}

    def read_requests(self, requests: list[Request]) -> dict[str, object]:
        """Read a list of requests, pausing ``waiting_time`` between them."""
        data: dict[str, object] = {}
        for index, request in enumerate(requests):
            data.update(self.read_request(request))
            if len(requests) > 1 and index < len(requests) - 1:
                time.sleep(self._waiting_time)
        return data

    # -- writing -------------------------------------------------------------

    def write_data(self, new_value: object, record: ChannelDefinition) -> dict[str, object]:
        return self._write_values([(new_value, record)])

    def write_time_quarter_pair(
        self,
        start_value: object,
        end_value: object,
        record_start: ChannelDefinition,
        record_end: ChannelDefinition,
    ) -> dict[str, object]:
        return self._write_values([(start_value, record_start), (end_value, record_end)])

    def _write_values(self, items: list[tuple[object, ChannelDefinition]]) -> dict[str, object]:
        first_record = items[0][1]
        channel_ids = ";".join(record.channel_id for _, record in items)

        read_request_message = parser.create_request_message(bytes.fromhex(first_record.request_byte))
        read_response = self._connector.get_data(read_request_message)

        if read_request_message == read_response:
            logger.debug("Current value(s) for %s already set.", channel_ids)
            return {}
        if not read_response:
            logger.warning("No response while reading current value for %s", channel_ids)
            return {}

        update = bytearray(read_response)
        for value, record in items:
            parser.compose_record(value, update, record)

        time.sleep(self._waiting_time)
        set_response = self._connector.set_data(bytes(update))

        if parser.header_check(set_response):
            logger.debug("Updated parameter %s successfully.", channel_ids)
            current_state: bytes = bytes(update)
        else:
            logger.warning("Verification of header for set operation failed")
            current_state = read_response

        return {
            record.channel_id: parser.parse_record(current_state, record)
            for _, record in items
        }

    # -- time synchronisation ------------------------------------------------

    def set_time(self, time_request: Optional[Request]) -> dict[str, object]:
        """Set the heat pump clock to the current system time."""
        if time_request is None:
            logger.warning("No time request definition; skip setting time.")
            return {}

        self._start()
        read_message = parser.create_request_message(time_request.request_bytes)
        response = self._connector.get_data(read_message)
        if not parser.header_check(response):
            logger.warning("Could not read current time from heat pump.")
            return {}

        now = datetime.now()
        values = {
            "weekday": now.weekday(),      # Mon=0 .. Sun=6, as the device expects
            "hours": now.hour,
            "minutes": now.minute,
            "seconds": now.second,
            "year": now.year % 100,
            "month": now.month,
            "day": now.day,
        }

        update = bytearray(response)
        for record in time_request.records:
            if record.channel_id in _TIME_FIELDS:
                parser.compose_record(values[record.channel_id], update, record)

        time.sleep(self._waiting_time)
        self._connector.set_data(bytes(update))
        time.sleep(self._waiting_time)

        response = self._connector.get_data(read_message)
        data = parser.parse_records(response, time_request.records)
        data["lastUpdate"] = now.strftime("%Y-%m-%d %H:%M:%S")
        return data

    # -- helpers -------------------------------------------------------------

    def _start(self) -> None:
        # `set_time` in the binding sends an explicit start handshake up front;
        # get_data/set_data already start their own, so nothing extra is needed.
        return None

    def _restart(self) -> None:
        logger.debug("Restarting connector")
        self._connector.disconnect()
        time.sleep(0.5)
        self._connector.connect()
