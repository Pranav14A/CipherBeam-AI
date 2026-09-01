# CipherBeam AI — Desktop Backend (Party A)

FastAPI backend.

- **Phase 1:** `GET /health` — verifies frontend↔backend connectivity.
- **Phase 2D:** a debug ESP32 serial link (`GET /hardware/status`,
  `POST /hardware/ping`) — verifies React → FastAPI → pyserial → COM3 →
  ESP32-S3 → PONG. This is a development/debug protocol, not the final
  CipherBeam optical packet protocol.

No encryption, packetization, LED control, optical communication, or AI
logic exists yet — see `docs/architecture/architecture-decision-document.md`
for the approved design those will follow later.

## Requirements

- Python 3.12
- [uv](https://docs.astral.sh/uv/)

## Configuration

The ESP32's serial port and baud rate are configurable via environment
variables (never hardcoded), with these defaults:

| Variable | Default | Purpose |
|---|---|---|
| `ESP32_COM_PORT` | `COM3` | Serial port the ESP32-S3 is connected on |
| `ESP32_BAUD_RATE` | `115200` | Must match the firmware's `Serial.begin(...)` value |

To override, e.g. if your ESP32 enumerates on a different port:

```powershell
$env:ESP32_COM_PORT = "COM5"
uv run uvicorn app.main:app --reload --port 8000
```

## Run

```bash
cd desktop/backend
uv run uvicorn app.main:app --reload --port 8000
```

Backend will be available at `http://localhost:8000`.

- Health check: `http://localhost:8000/health`
- Hardware status: `http://localhost:8000/hardware/status`
- Hardware ping: `POST http://localhost:8000/hardware/ping`

**Before starting the backend, close Arduino Serial Monitor if it's open**
— it holds the COM port open, which will make every `/hardware/*` request
fail with `ESP32_PORT_UNAVAILABLE` even though the ESP32 itself is fine.

## Test

```bash
cd desktop/backend
uv run pytest
```

All backend tests are mocked (no serial port or ESP32 required) and will
pass in any environment, including one with no ESP32 attached at all. See
`tests/hardware/README.md` for the manual procedure to test against the
real device.
