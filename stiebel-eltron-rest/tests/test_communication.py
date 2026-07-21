"""End-to-end communication tests replaying the exact byte scripts from the
binding's CommunicationServiceTests, verifying both decoded values and the
bytes written to the wire."""

import pytest

from stiebel_heatpump.protocol import parser
from stiebel_heatpump.protocol.communication import CommunicationService
from stiebel_heatpump.protocol.connector import Connector
from stiebel_heatpump.protocol.parser import hex_to_bytes
from stiebel_heatpump.protocol.transport import NoDataAvailable


class ScriptedTransport:
    """Returns a fixed sequence of received bytes and records everything written."""

    def __init__(self, rx: bytes) -> None:
        self._rx = rx
        self._index = 0
        self.written = bytearray()

    def open(self) -> None:
        pass

    def close(self) -> None:
        pass

    def write(self, data: bytes) -> None:
        self.written.extend(data)

    def get(self, timeout: float = 1.0) -> int:
        if self._index >= len(self._rx):
            raise NoDataAvailable()
        value = self._rx[self._index]
        self._index += 1
        return value


ESC = bytes([parser.ESCAPE])
DATA = bytes(parser.DATA_AVAILABLE)


def make_service(rx: bytes):
    transport = ScriptedTransport(rx)
    service = CommunicationService(Connector(transport), waiting_time_ms=0)
    service.connect()
    return service, transport


def test_set_cooling_writes_expected_bytes(thz504_config):
    current = hex_to_bytes("0100960B028700001003")
    set_ok = hex_to_bytes("01808C0B1003")
    rx = ESC + DATA + current + ESC + DATA + set_ok
    service, transport = make_service(rx)

    record = thz504_config.channel("p99CoolingHC1Switch")
    result = service.write_data(True, record)

    assert result == {"p99CoolingHC1Switch": True}

    get_hex = "0100950B02871003"
    set_hex = "0180160B028700011003"
    expected = "02" + get_hex + "10" + "02" + set_hex + "10"
    assert parser.bytes_to_hex(transport.written) == expected


@pytest.mark.parametrize(
    "set_failed_response,snippet",
    [
        ("01018C0B1003", "timing issue"),
        ("01028D0B1003", "CRC error in request"),
        ("01038E0B1003", "unknown command"),
        ("01048F0B1003", "UNKNOWN Register REQUEST"),
    ],
)
def test_set_cooling_failure(thz504_config, set_failed_response, snippet, caplog):
    current = hex_to_bytes("0100960B028700001003")
    rx = ESC + DATA + current + ESC + DATA + hex_to_bytes(set_failed_response)
    service, _ = make_service(rx)

    record = thz504_config.channel("p99CoolingHC1Switch")
    result = service.write_data(True, record)
    # failed set -> value reflects the (unchanged) machine state = off
    assert result == {"p99CoolingHC1Switch": False}


def test_set_time_quarter_pair_writes_expected_bytes(thz504_config):
    current = hex_to_bytes("0100330A171180801003")
    after = hex_to_bytes("0100A40A17112D441003")
    rx = ESC + DATA + current + ESC + DATA + after
    service, transport = make_service(rx)

    start = thz504_config.channel("programDhwMo1Start")
    end = thz504_config.channel("programDhwMo1End")
    result = service.write_time_quarter_pair(45, 68, start, end)

    assert result == {"programDhwMo1Start": 45, "programDhwMo1End": 68}

    get_hex = "0100330A17111003"
    set_hex = "0180240A17112D441003"
    expected = "02" + get_hex + "10" + "02" + set_hex + "10"
    assert parser.bytes_to_hex(transport.written) == expected


@pytest.mark.parametrize(
    "request_byte,response1,response2,expected",
    [
        ("0A091A", "01002E0A091A01FF1003", "0100300A091B00011003", {"electrDHWDay": 1511}),
        ("0B0287", "0100960B028700011003", None, {"p99CoolingHC1Switch": True}),
        ("0A1710", "0100A80A171031451003", None, {"programDhwMo0Start": 49, "programDhwMo0End": 69}),
        ("0A1711", "0100330A171180801003", None,
         {"programDhwMo1Start": -128, "programDhwMo1End": -128}),
    ],
)
def test_read_request(thz504_config, request_byte, response1, response2, expected):
    if response2:
        rx = ESC + DATA + hex_to_bytes(response1) + ESC + DATA + hex_to_bytes(response2)
    else:
        rx = ESC + DATA + hex_to_bytes(response1)
    service, _ = make_service(rx)

    request = thz504_config.request_by_bytes(request_byte)
    result = service.read_request(request)
    for key, value in expected.items():
        assert result[key] == value
