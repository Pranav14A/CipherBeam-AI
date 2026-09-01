# Hardware Tests — Manual Procedures

These require the real ESP32-S3 (YD-ESP32-23, N16R8) plugged into a real
Windows PC. They cannot be automated in a normal CI environment and are
**not** run as part of `uv run pytest` — the automated backend tests
(`desktop/backend/tests/test_serial_manager.py`,
`test_hardware_routes.py`) cover the Python logic with pyserial mocked
out; these procedures are what actually proves the physical link works.

**Do not report any of these as passing unless you personally ran them on
the real hardware and observed the result.**

## Prerequisites

- ESP32-S3 flashed with `firmware/esp32/ping_pong/ping_pong.ino` (see
  `firmware/esp32/README.md` for exact upload steps).
- ESP32 connected via its **COM** USB-C port (not USB-OTG).
- **Arduino Serial Monitor closed.** It holds the COM port open — if it's
  running, every test below will fail with `ESP32_PORT_UNAVAILABLE` even
  though the ESP32 and firmware are fine.
- Backend dependencies installed (`uv sync` in `desktop/backend/`).
- Frontend dependencies installed (`npm install` in `desktop/frontend/`).

## Test 1 — Baseline: Arduino Serial Monitor PING/PONG

Confirms the firmware itself still works, independent of Python. Useful
as a first check whenever something else fails, to isolate firmware vs.
backend issues.

1. Open Arduino IDE, open `ping_pong.ino`, confirm Port matches the
   ESP32's current COM port.
2. Open Serial Monitor, set baud to 115200, line ending to "Newline".
3. Type `PING`, send. Expect `PONG`.
4. Type `STATUS`, send. Expect `ESP32_READY`.
5. **Close Serial Monitor before continuing to Test 2** — this is the step
   most likely to be forgotten and cause confusing failures below.

## Test 2 — Backend startup + `/hardware/status`

1. In a terminal: `cd desktop/backend` then
   `uv run uvicorn app.main:app --reload --port 8000`.
2. In a browser or another terminal, request `http://localhost:8000/hardware/status`.
3. **Expected (ESP32 connected, Serial Monitor closed):**
   ```json
   {"connected": true, "port": "COM3", "baud_rate": 115200, "error": null}
   ```
4. Record the actual result here (edit this file or note it separately)
   — including the port value your machine actually reports, if different
   from COM3.

## Test 3 — `POST /hardware/ping` via the React frontend

1. With the backend still running, in a second terminal:
   `cd desktop/frontend` then `npm run dev`.
2. Open `http://localhost:5173` in a browser.
3. Confirm the existing Phase 1 status still shows `Backend: ● ONLINE`.
4. In the new "ESP32 HARDWARE" section, confirm `Connection: ● CONNECTED`
   and `Port: COM3` (or your actual port) are shown.
5. Click **PING ESP32**.
6. **Expected:** `Last response: PONG`, and the connection indicator stays
   `● CONNECTED`.

## Test 4 — Negative test: ESP32 unplugged

1. With the backend still running, physically unplug the ESP32-S3.
2. Click **PING ESP32** again (or refresh the page to re-trigger
   `/hardware/status`).
3. **Expected:** `Connection: ● DISCONNECTED`, and pinging shows
   `Last response: ERROR: ESP32_PORT_UNAVAILABLE`.
4. Plug the ESP32 back in and confirm Test 3 succeeds again (may require
   restarting the backend, since the OS may briefly reassign or re-enumerate
   the COM port on replug — note whether this was necessary).

## Test 5 — Negative test: port held by another program

1. With the backend running and the ESP32 connected, open Arduino Serial
   Monitor (which will grab the COM port).
2. Click **PING ESP32** in the browser.
3. **Expected:** `Last response: ERROR: ESP32_PORT_UNAVAILABLE`.
4. Close Serial Monitor, click **PING ESP32** again.
5. **Expected:** back to `Last response: PONG`.

## Recording results

For each test, note: pass/fail, the actual COM port used, and anything
that differed from the expected result (timing, need to restart the
backend after replug, different error text, etc.). This is what will get
reported back into the project chat as the real hardware verification —
not anything claimed from the sandbox.
