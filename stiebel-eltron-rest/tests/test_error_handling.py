"""Regression tests for the code-review findings on
https://github.com/R0Wi/openhab-addons/pull/1#issuecomment-5037252829 :

1. A failed pre-write read must not be reported as a successful write with
   the caller's requested value silently substituted in.
2. A device that cannot be reached must surface as a communication failure
   (503), not as "channel not found" (404).
3. An unrecognized boolean string must be rejected (400), not silently
   coerced to False.
"""

import pytest
from fastapi.testclient import TestClient

from stiebel_heatpump.api import build_app
from stiebel_heatpump.protocol import parser
from stiebel_heatpump.protocol.communication import CommunicationService
from stiebel_heatpump.protocol.connector import Connector
from stiebel_heatpump.protocol.transport import SimulatorTransport
from stiebel_heatpump.service import HeatPumpService
from stiebel_heatpump.settings import AppSettings
from stiebel_heatpump.simulator import HeatPumpSimulator


class NeverDataAvailableTransport:
    """Always acks the start-communication handshake but never reports the
    "data available" signal, so establish_request exhausts its retries and
    get_data()/set_data() return an empty response -- simulating a device
    that is connected but not actually responding to requests."""

    def open(self) -> None:
        pass

    def close(self) -> None:
        pass

    def write(self, data) -> None:
        pass

    def get(self, timeout: float = 1.0) -> int:
        return parser.ESCAPE


# -- 1. write failure must not be masked as success --------------------------


def test_write_data_raises_when_pre_write_read_fails(thz504_config):
    service = CommunicationService(Connector(NeverDataAvailableTransport()), waiting_time_ms=0)
    service.connect()
    record = thz504_config.channel("p99CoolingHC1Switch")

    with pytest.raises(parser.ProtocolError):
        service.write_data(True, record)


def test_api_write_returns_503_not_200_when_device_unreachable(thz504_config):
    service = HeatPumpService(thz504_config, NeverDataAvailableTransport(), waiting_time_ms=0)
    settings = AppSettings(device_config="unused", transport="simulator")
    app = build_app(settings, config=thz504_config, service=service)
    with TestClient(app) as client:
        resp = client.put("/channels/p99CoolingHC1Switch", json={"value": True})
    assert resp.status_code == 503
    assert "communicate" in resp.json()["detail"].lower()


# -- 2. communication failure must not look like "channel not found" --------


def test_read_request_raises_after_retries_exhausted(thz504_config):
    service = CommunicationService(Connector(NeverDataAvailableTransport()), waiting_time_ms=0)
    service.connect()
    request = thz504_config.request_by_bytes("FB")

    with pytest.raises(parser.ProtocolError):
        service.read_request(request)


def test_api_read_returns_503_not_404_when_device_unreachable(thz504_config):
    service = HeatPumpService(thz504_config, NeverDataAvailableTransport(), waiting_time_ms=0)
    settings = AppSettings(device_config="unused", transport="simulator")
    app = build_app(settings, config=thz504_config, service=service)
    with TestClient(app) as client:
        resp = client.get("/channels/outsideTemperature")
    assert resp.status_code == 503
    assert "communicate" in resp.json()["detail"].lower()


def test_api_unknown_channel_still_404_when_device_is_reachable(thz504_config):
    # Sanity check that the 503 fix didn't blur the other direction: a
    # genuinely unknown channel id against a *working* device is still 404.
    simulator = HeatPumpSimulator(thz504_config)
    service = HeatPumpService(thz504_config, SimulatorTransport(simulator), waiting_time_ms=0)
    settings = AppSettings(device_config="unused", transport="simulator")
    app = build_app(settings, config=thz504_config, service=service)
    with TestClient(app) as client:
        resp = client.get("/channels/doesNotExist")
    assert resp.status_code == 404


# -- 3. unrecognized boolean strings must be rejected, not silently False ----


def test_coerce_rejects_unrecognized_boolean_string(thz504_config):
    simulator = HeatPumpSimulator(thz504_config)
    service = HeatPumpService(thz504_config, SimulatorTransport(simulator), waiting_time_ms=0)
    service.connect()
    with pytest.raises(ValueError):
        service.write_channel("p99CoolingHC1Switch", "banana")
    service.close()


@pytest.mark.parametrize("value,expected", [("true", True), ("ON", True), ("0", False), ("off", False)])
def test_coerce_accepts_known_boolean_strings(thz504_config, value, expected):
    simulator = HeatPumpSimulator(thz504_config)
    service = HeatPumpService(thz504_config, SimulatorTransport(simulator), waiting_time_ms=0)
    service.connect()
    result = service.write_channel("p99CoolingHC1Switch", value)
    assert result.value is expected
    service.close()


def test_api_write_invalid_boolean_string_returns_400(thz504_config):
    simulator = HeatPumpSimulator(thz504_config)
    service = HeatPumpService(thz504_config, SimulatorTransport(simulator), waiting_time_ms=0)
    settings = AppSettings(device_config="unused", transport="simulator")
    app = build_app(settings, config=thz504_config, service=service)
    with TestClient(app) as client:
        resp = client.put("/channels/p99CoolingHC1Switch", json={"value": "banana"})
    assert resp.status_code == 400
