from pathlib import Path

from stiebel_heatpump.config_loader import load_config, load_xml, load_yaml
from stiebel_heatpump.models import DataType, ValueKind

ROOT = Path(__file__).resolve().parent.parent


def test_load_all_xml_configs():
    for xml in (ROOT / "device_configs").glob("*.xml"):
        config = load_xml(xml)
        assert config.channels, f"{xml.name} produced no channels"
        # every channel must belong to exactly one request group
        grouped = sum(len(r.records) for r in config.requests)
        assert grouped == len(config.channels)


def test_grouping_shares_request(thz504_config):
    # All FB sensor channels share a single request round trip.
    fb = thz504_config.request_by_bytes("FB")
    assert fb is not None
    assert len(fb.records) > 5
    assert all(r.request_byte.upper() == "FB" for r in fb.records)


def test_value_kinds(thz504_config):
    cooling = thz504_config.channel("p99CoolingHC1Switch")
    assert cooling.value_kind == ValueKind.BOOLEAN
    assert cooling.writable is True
    inside = thz504_config.channel("insideTemperatureRC")
    assert inside.value_kind == ValueKind.NUMBER
    assert inside.data_type == DataType.SENSOR


def test_load_yaml_native_format():
    config = load_yaml(ROOT / "config" / "device.example.yaml")
    ids = {c.channel_id for c in config.channels}
    assert "outsideTemperature" in ids
    assert config.channel("coolingEnabled").value_kind == ValueKind.BOOLEAN


def test_load_config_dispatch():
    assert load_config(ROOT / "device_configs" / "LWZ_THZ504_7_59.xml").channels
