# Stiebel Eltron Heat Pump REST API

A standalone, framework-independent REST service for **Stiebel Eltron / Tecalor
LWZ / THZ** heat pumps that talk over their USB serial interface.

It is a faithful Python port of the business logic in the openHAB
[`stiebelheatpump`](../bundles/org.openhab.binding.stiebelheatpump) binding,
wrapped in a **generic, configuration-driven [FastAPI](https://fastapi.tiangolo.com)
application**. The OpenAPI specification it publishes is derived from the loaded
device definition, so the API adapts to whichever heat pump firmware you point
it at — without touching code.

The goal: get the same data and control the binding offers, but over plain HTTP,
so it can be consumed by openHAB (via the generic HTTP binding), Home Assistant,
Node-RED, custom dashboards, or scripts — anything that speaks REST.

---

## Why this exists

The openHAB binding couples three concerns that are really independent:

1. **The wire protocol** — framing, checksums, byte (de)escaping, the
   start/data/ack handshake over the serial line.
2. **The device model** — which data points exist, where they live in each
   response frame, how they are scaled, and which are writable. This is
   described entirely by per-firmware XML files.
3. **The openHAB integration** — Things, Channels, Items, item types, units.

Only #3 is openHAB-specific. This project keeps #1 and #2 verbatim and replaces
#3 with a generic REST/OpenAPI surface.

## How the binding works (analysis)

Everything the device exposes is described by **records** (see the XML files in
[`device_configs/`](device_configs), copied from the binding). Each record is a
single data point:

| attribute | meaning |
|-----------|---------|
| `channelid` | unique name of the data point |
| `requestByte` / `requestByte2` | the command(s) that fetch the value |
| `dataType` | `Sensor`, `Status` or `Settings` (only `Settings` is writable) |
| `position`, `length` | where the value sits in the response frame (1/2/4 bytes) |
| `scale` | multiplier applied to the raw integer (e.g. `0.1` for °C) |
| `bitPosition` | if > 0, the value is a single bit (a switch/contact) |
| `min`, `max`, `step` | allowed range for settable values |

Records that share a `requestByte` are read in a **single serial round trip**.

The serial dialog for a read is:

```
1. → 0x02 (start)          ← 0x10 (ack)
2. → 01 00 CS <cmd> 10 03   ← 0x10 0x02 (data available)
3. → 0x10 (ack)
4.                          ← 01 00 CS <cmd> <data…> 10 03
```

Bytes on the wire are escaped (`0x10`→`0x10 0x10`, `0x2B`→`0x2B 0x18`); frames
are checksummed (sum of all bytes except the checksum slot and footer, low
byte). Writes are the same, with the get byte `0x00` replaced by `0x80` and the
new value composed back into a previously-read frame.

All of this lives, unchanged in behaviour, in
[`stiebel_heatpump/protocol/`](stiebel_heatpump/protocol). The parser is
verified against the **exact byte vectors** from the binding's own Java tests
(see [`tests/test_parser.py`](tests/test_parser.py) and
[`tests/test_communication.py`](tests/test_communication.py)).

## Architecture

```
             HTTP  ┌─────────────────────────────────────────┐
        ───────────▶            api.py (FastAPI)              │  dynamic OpenAPI
                    │   /channels /values /version /actions   │  from config
                    └───────────────────┬─────────────────────┘
                                        │
                    ┌───────────────────▼─────────────────────┐
                    │        service.py (thread-safe)          │  one serial link
                    └───────────────────┬─────────────────────┘  = one lock
                                        │
        config_loader.py ──▶  ┌─────────▼──────────┐
        (XML / YAML)          │  communication.py  │  read/write/set-time
                              └─────────┬──────────┘
                                        │
                              ┌─────────▼──────────┐
                              │    connector.py    │  handshake
                              └─────────┬──────────┘
                                        │
                    ┌───────────────────▼─────────────────────┐
                    │  transport.py:  SerialTransport (real)   │
                    │                 SimulatorTransport (fake) │
                    └──────────────────────────────────────────┘
```

* [`protocol/parser.py`](stiebel_heatpump/protocol/parser.py) — pure protocol,
  no I/O.
* [`protocol/transport.py`](stiebel_heatpump/protocol/transport.py) — real
  `pyserial` port **and** an in-memory simulator, behind one tiny interface.
* [`simulator.py`](stiebel_heatpump/simulator.py) — a fake heat pump that speaks
  the real byte protocol, so the whole stack runs and is testable **without
  hardware**.
* [`config_loader.py`](stiebel_heatpump/config_loader.py) — loads the binding's
  XML files **or** a native YAML format.

## Quick start

```bash
cd stiebel-eltron-rest
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# Runs against the built-in simulator — no hardware needed.
stiebel-heatpump-api --config config/app.example.yaml
# ... or: python -m stiebel_heatpump.main --config config/app.example.yaml
```

Then open <http://localhost:8000/docs> for interactive Swagger UI, or
<http://localhost:8000/openapi.json> for the generated spec.

### Talking to a real device

Copy `config/app.example.yaml` to `config/app.yaml` and set:

```yaml
device_config: device_configs/LWZ_THZ504_7_59.xml   # match your firmware!
transport: serial
port: /dev/ttyUSB0
baud_rate: 9600
waiting_time_ms: 1200
```

Every setting can also be supplied via environment variable, e.g.
`STIEBEL_TRANSPORT=serial STIEBEL_PORT=/dev/ttyUSB0`.

Pick the `device_config` whose firmware version matches your heat pump (the
version is reported by `GET /version`).

## API

| Method & path | Description |
|---------------|-------------|
| `GET /` | service / configuration summary |
| `GET /health` | liveness probe |
| `GET /version` | heat pump firmware version |
| `GET /channels` | list all channels (filter by `data_type`, `writable`) |
| `GET /channels/{channel_id}` | read one value |
| `PUT /channels/{channel_id}` | write a settings value (`{"value": …}`) |
| `GET /values?ids=a,b` | read several channels at once |
| `GET /values?data_type=Sensor` | read all sensor (or status/settings) values |
| `POST /actions/set-time` | sync the heat pump clock to system time |

The published **OpenAPI depends on the loaded config**: the `{channel_id}` path
parameter is an `enum` of your device's channels, a `HeatPumpValues` schema
lists every channel with its concrete JSON type / unit / range, and an
`x-heatpump-channels` extension summarises the counts per category.

### Examples

```bash
curl localhost:8000/version
curl localhost:8000/channels/outsideTemperature
curl "localhost:8000/values?data_type=Sensor"
curl -X PUT localhost:8000/channels/p99CoolingHC1Switch -H 'content-type: application/json' -d '{"value": true}'
curl -X POST localhost:8000/actions/set-time
```

## Using it from openHAB (replacing the binding)

With the [HTTP binding](https://www.openhab.org/addons/bindings/http/), build
items straight against this service:

```
Thing http:url:heatpump "Heat Pump" [
    baseURL="http://heatpump-api:8000",
    refresh=60
] {
    Channels:
        Type number : outsideTemp "Outside" [
            stateExtension="/channels/outsideTemperature",
            stateTransformation="JSONPATH:$.value"
        ]
        Type switch : cooling "Cooling" [
            stateExtension="/channels/p99CoolingHC1Switch",
            stateTransformation="JSONPATH:$.value",
            commandExtension="/channels/p99CoolingHC1Switch",
            commandMethod="PUT",
            commandFormat="{\"value\": %s}"
        ]
}
```

The same endpoints work for Home Assistant (RESTful sensor/switch), Node-RED,
Grafana/Prometheus exporters, or any custom integration.

## Adding / customising a device definition

* Reuse a binding XML from `device_configs/`, **or**
* Author a native YAML file (see
  [`config/device.example.yaml`](config/device.example.yaml)) and point
  `device_config` at it.

New firmware protocol definitions can be derived from the
[protocol versions](http://bazaar.launchpad.net/~robert-penz-name/heatpumpmonitor/trunk/files/head:/protocolVersions/)
the binding is based on.

## Tests

```bash
source .venv/bin/activate
pytest
```

The suite covers the protocol parser and full read/write/time flows against the
binding's original byte vectors, the config loader, the simulator, and the REST
API (including the dynamically generated OpenAPI).

## Relationship to the binding

This is an alternative, platform-neutral front end for the **same** protocol and
device definitions. It does not modify or depend on the openHAB bundle; the XML
files under `device_configs/` are copies of the binding's resources so the two
stay easy to keep in sync.

## License

EPL-2.0, matching the openHAB project.
