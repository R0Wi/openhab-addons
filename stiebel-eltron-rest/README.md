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
pip install -e .          # add `.[dev]` instead to also get pytest/httpx

# Runs against the built-in simulator — no hardware needed.
stiebel-heatpump-api --config config/app.example.yaml
# ... or: python -m stiebel_heatpump.main --config config/app.example.yaml
```

Then open <http://localhost:8000/docs> for interactive Swagger UI, or
<http://localhost:8000/openapi.json> for the generated spec.

Dependencies are declared once, in [`pyproject.toml`](pyproject.toml) — it's
also what makes this an installable package with a `stiebel-heatpump-api`
console script. There is no separate `requirements.txt` to keep in sync.

### Docker

```bash
docker compose up --build
```

This builds the `runtime` stage of the [`Dockerfile`](Dockerfile) and starts
the API on <http://localhost:8000>, using the in-memory simulator by default
(see `environment:` in [`docker-compose.yml`](docker-compose.yml) — no
hardware needed). To talk to a real device instead, set `STIEBEL_TRANSPORT=serial`
and uncomment the `devices:` passthrough for your USB-serial adapter (Linux
hosts; see the comments in `docker-compose.yml`).

#### Dev container

Open the `stiebel-eltron-rest` folder in VS Code and "Reopen in Container".
The [`.devcontainer/devcontainer.json`](.devcontainer/devcontainer.json) builds
the same [`Dockerfile`](Dockerfile) as production, targeting its `dev` stage —
same Python version, OS packages and non-root user as the `runtime` image used
by `docker-compose.yml`, just with pytest/httpx and a few CLI tools added on
top. The workspace is bind-mounted over `/app` and `postCreateCommand`
editable-installs the package (`pip install -e '.[dev]'`), so local edits take
effect immediately — no rebuild, no reinstall.

### Talking to a real device

Either a YAML file, environment variables, or both:

```yaml
# config/app.yaml
device_config: device_configs/LWZ_THZ504_7_59.xml   # match your firmware!
transport: serial
port: /dev/ttyUSB0
baud_rate: 9600
waiting_time_ms: 1200
```

```bash
stiebel-heatpump-api --config config/app.yaml
```

Pick the `device_config` whose firmware version matches your heat pump (the
version is reported by `GET /version`).

#### Environment variables

Every setting in [`AppSettings`](stiebel_heatpump/settings.py) can be set via
an environment variable:

* **Prefix:** `STIEBEL_`
* **Naming convention:** `STIEBEL_` + the field name in `UPPER_SNAKE_CASE`,
  e.g. `device_config` → `STIEBEL_DEVICE_CONFIG`, `waiting_time_ms` →
  `STIEBEL_WAITING_TIME_MS`, `baud_rate` → `STIEBEL_BAUD_RATE`.
* **Locating a YAML file via env var:** `STIEBEL_APP_CONFIG=/path/to/app.yaml`
  is equivalent to passing `--config /path/to/app.yaml`.
* **Precedence when a value is set in more than one place (highest wins):**

  ```
  environment variable  >  YAML file  >  built-in default
  ```

  This is deliberate: an image/deployment can ship a baked-in YAML file as its
  base config, and individual values can still be overridden per-environment
  with plain `docker run -e` / `environment:` entries, without touching the
  file. See `docker-compose.yml` for an example that configures everything
  purely via env vars (no YAML file at all).

  ```bash
  STIEBEL_TRANSPORT=serial STIEBEL_PORT=/dev/ttyUSB0 stiebel-heatpump-api --config config/app.yaml
  # -> transport=serial (env wins), port=/dev/ttyUSB0 (env wins), everything
  #    else (baud_rate, waiting_time_ms, ...) comes from app.yaml
  ```

## Quick start: wiring a real THZ / LWZ 504 (firmware 7.59)

The 504 generation (**LWZ 504**, **THZ 504**, firmware **7.59** — the
[`LWZ_THZ504_7_59.xml`](device_configs/LWZ_THZ504_7_59.xml) definition) is
controlled through the **RS232 service / diagnostic interface** on its internal
WPM controller board. No cloud, no ISG, no extra gateway: you wire that port to
the machine running this service and talk to it over a plain serial line.

### 1. Wire the serial interface

The controller exposes three signals — **TX**, **RX** and **GND** — on the
service connector. Cross TX↔RX and share the ground with a USB-to-serial
adapter:

```
 Heat pump (WPM service port)             USB-serial adapter → host
 ┌────────────────────────────┐          ┌─────────────────────────┐
 │ TX  (pump → host) ─────────┼────────► │ RX                      │
 │ RX  (host → pump) ◄────────┼──────────┤ TX                      │
 │ GND ───────────────────────┼──────────┤ GND ───► /dev/ttyUSB0   │
 └────────────────────────────┘          └─────────────────────────┘
```

* Use a **galvanically isolated USB-RS232 adapter** (FTDI / CP210x) — it
  protects both the pump electronics and your host.
* If your adapter is **TTL level** while the pump port is **true RS232**
  (±12 V), put a **MAX232** level shifter in between. Wiring and schematics come
  from the [heatpumpmonitor](https://launchpad.net/heatpumpmonitor) /
  [robert.penz.name](http://robert.penz.name/heat-pump-lwz/) projects this
  binding is based on.
* This cable carries **signalling only** — nothing is powered over it.
* Do the wiring with the heat pump **powered down**, and check your unit's
  installation manual for the exact service-connector pinout.

### 2. Find the port and baud rate

Plug the adapter into the host; on Linux it appears as `/dev/ttyUSB0`
(run `dmesg | tail` right after plugging in to confirm the name).

The **303 / 403** run at **9600 baud**; the newer **404 / 504** generation at
**57600** (some units at **115200**). Start at `57600` for a 504 — if reads time
out or come back as garbage, try `115200` (or `9600`). It must match your unit.

### 3. Point the service at it

```yaml
# config/app.yaml
device_config: device_configs/LWZ_THZ504_7_59.xml   # match your 7.59 firmware
transport: serial
port: /dev/ttyUSB0
baud_rate: 57600           # 9600 for 303/403; try 115200 if 57600 fails
waiting_time_ms: 1200      # the 504 CPU is slow — leave slack between requests
```

```bash
stiebel-heatpump-api --config config/app.yaml

curl localhost:8000/version                    # confirms the link + firmware
curl "localhost:8000/values?data_type=Sensor"  # live sensor readings
curl -X POST localhost:8000/actions/set-time   # sync the pump clock to the host
```

If `GET /version` returns the expected firmware, the wiring and baud rate are
correct and every channel in the definition is reachable over HTTP.

> **Pump far from the host?** Put a small serial-to-network bridge (`ser2net`)
> next to the heat pump, then re-expose it locally as a virtual serial port with
> `socat pty,link=/dev/ttyUSB0 tcp:pumphost:5555` and point `port` at that pty.

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
