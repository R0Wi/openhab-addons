"""An in-memory heat pump simulator.

It speaks the same byte protocol as a real device, so the whole stack -- from
the connector up to the REST API -- can run and be tested without any hardware.
It keeps a response frame per request command, decodes SET frames to update its
state (making reads-after-writes consistent) and answers the start / data /
receive handshake.
"""

from __future__ import annotations

from typing import Optional

from .config_loader import HeatPumpConfig, Request
from .protocol import parser

_IDLE = "idle"
_COLLECTING = "collecting"
_AWAIT_ACK = "await_ack"


class HeatPumpSimulator:
    """A fake Stiebel Eltron heat pump driven purely by a config."""

    def __init__(self, config: HeatPumpConfig, seed_values: Optional[dict[str, object]] = None) -> None:
        self._config = config
        self._store: dict[str, bytearray] = {}
        for request in config.requests:
            self._store[request.request_byte] = self._build_frame(request)

        seeds = dict(seed_values or {})
        seeds.setdefault("version", self._default_version())
        for channel_id, value in seeds.items():
            self.set_value(channel_id, value)

        self._state = _IDLE
        self._frame_buffer = bytearray()
        self._pending: bytes = b""

    # -- seeding -------------------------------------------------------------

    def _default_version(self) -> float:
        name = self._config.name or ""
        digits = [part for part in name.replace("-", "_").split("_") if part.isdigit()]
        if len(digits) >= 2:
            return float(f"{digits[-2]}.{digits[-1]}")
        return 7.59

    def _build_frame(self, request: Request) -> bytearray:
        data_end = max((r.position + r.length for r in request.records), default=5)
        frame = bytearray(data_end + 2)
        frame[0] = parser.HEADER_START
        frame[1] = parser.GET
        request_bytes = request.request_bytes
        frame[3 : 3 + len(request_bytes)] = request_bytes
        frame[-2] = parser.ESCAPE
        frame[-1] = parser.END
        frame[2] = parser.calculate_checksum(frame)
        return frame

    def set_value(self, channel_id: str, value: object) -> None:
        """Seed/override the value a channel will report on the next read."""
        record = self._config.channel(channel_id)
        if record is None:
            return
        request = self._config.request_for_channel(channel_id)
        if request is None:
            return
        frame = self._store[request.request_byte]
        self._encode(frame, record, value)
        frame[1] = parser.GET
        frame[2] = parser.calculate_checksum(frame)

    @staticmethod
    def _encode(frame: bytearray, record, value: object) -> None:
        if isinstance(value, bool):
            if record.bit_position > 0:
                frame[record.position] = parser._set_bit(  # noqa: SLF001
                    frame[record.position], record.bit_position, value
                )
            else:
                frame[record.position] = 1 if value else 0
            return
        if isinstance(value, float):
            short_value = int(round(value / record.scale))
        else:
            short_value = int(value)
        encoded = parser.short_to_bytes(short_value)
        if record.length == 1:
            frame[record.position] = encoded[0]
        elif record.length == 2:
            frame[record.position] = encoded[1]
            frame[record.position + 1] = encoded[0]

    # -- protocol state machine ---------------------------------------------

    def feed(self, data: bytes) -> bytes:
        """Consume bytes written by the client; return the bytes to send back."""
        out = bytearray()
        for byte in data:
            out.extend(self._feed_byte(byte))
        return bytes(out)

    def _feed_byte(self, byte: int) -> bytes:
        if self._state == _IDLE:
            if byte == parser.START_COMMUNICATION:
                self._state = _COLLECTING
                self._frame_buffer = bytearray()
                return bytes([parser.ESCAPE])
            return b""

        if self._state == _COLLECTING:
            self._frame_buffer.append(byte)
            if (
                len(self._frame_buffer) >= 6
                and self._frame_buffer[-2] == parser.ESCAPE
                and self._frame_buffer[-1] == parser.END
            ):
                self._pending = parser.fix_duplicated_bytes(bytes(self._frame_buffer))
                self._state = _AWAIT_ACK
                return bytes(parser.DATA_AVAILABLE)
            return b""

        if self._state == _AWAIT_ACK:
            if byte == parser.ESCAPE:
                response = self._build_response(self._pending)
                self._state = _IDLE
                return parser.add_duplicated_bytes(response)
            return b""

        return b""

    def _match_request(self, frame: bytes) -> Optional[Request]:
        best: Optional[Request] = None
        for request in self._config.requests:
            request_bytes = request.request_bytes
            if frame[3 : 3 + len(request_bytes)] == request_bytes:
                if best is None or len(request_bytes) > len(best.request_bytes):
                    best = request
        return best

    def _build_response(self, frame: bytes) -> bytes:
        if len(frame) < 4:
            return bytes([parser.HEADER_START, parser.GET, 0x00, 0x00]) + parser.FOOTER
        request = self._match_request(frame)
        get_or_set = frame[1]

        if request is None:
            command = frame[3:4] if len(frame) > 4 else b"\x00"
            error = bytearray([parser.HEADER_START, 0x03, 0x00]) + command + parser.FOOTER
            error[2] = parser.calculate_checksum(error)
            return bytes(error)

        if get_or_set == parser.SET:
            # The incoming frame carries the new machine state; store it as GET.
            updated = bytearray(frame)
            updated[1] = parser.GET
            updated[2] = parser.calculate_checksum(updated)
            self._store[request.request_byte] = updated
            confirm = bytearray([parser.HEADER_START, parser.SET, 0x00]) + request.request_bytes + parser.FOOTER
            confirm[2] = parser.calculate_checksum(confirm)
            return bytes(confirm)

        return bytes(self._store[request.request_byte])
