# ESP32-S3 Firmware

## Current status: PING/PONG debug link only (Phase 2)

`ping_pong/ping_pong.ino` is confirmed working on the actual hardware —
uploaded via Arduino IDE and manually verified over Serial Monitor
(`PING` → `PONG`, `STATUS` → `ESP32_READY`). This is a debug serial link
only; see the header comment in the sketch for the exact message format.

**Not yet implemented:** LED/MOSFET control, Manchester encoding, optical
transmission, packet framing, CRC, encryption, AI. Those follow the design
in `docs/architecture/architecture-decision-document.md` in later phases.

## Confirmed hardware

- Board: YD-ESP32-23 2022-V1.3
- Module: ESP32-S3-N16R8 (16 MB Flash, 8 MB OPI PSRAM)
- Connected via the board's **COM** USB-C port (CH343 USB-to-serial bridge)
  — not the USB-OTG port.
- Windows enumerates it as `USB-Enhanced-SERIAL CH343 (COM3)`, but the
  backend does not hardcode this — see `ESP32_COM_PORT` in
  `desktop/backend/README.md`.

## Confirmed Arduino IDE configuration

| Setting | Value |
|---|---|
| Board | ESP32S3 Dev Module |
| Port | COM3 (may vary — check Device Manager) |
| USB CDC On Boot | **Disabled** — required so `Serial` routes over UART0 → CH343 → the COM port, not the native USB-OTG port |
| CPU Frequency | 240MHz (WiFi) |
| Flash Mode | QIO 80MHz |
| Flash Size | 16MB (128Mb) |
| Partition Scheme | Default 4MB with spiffs |
| PSRAM | OPI PSRAM |
| Upload Mode | UART0 / Hardware CDC |
| Upload Speed | 921600 |
| USB Mode | Hardware CDC and JTAG |
| USB DFU On Boot | Disabled |
| JTAG Adapter | Disabled |
| Zigbee Mode | Disabled |

Do not change `USB CDC On Boot` — see the reasoning in the sketch's header
comment and the Phase 2D discussion in the project chat history.

## Uploading `ping_pong.ino`

1. Open `firmware/esp32/ping_pong/ping_pong.ino` in Arduino IDE.
2. Confirm **Tools → Board** is `ESP32S3 Dev Module` and **Tools → Port**
   matches the ESP32's COM port (check Device Manager if unsure — it may
   not always be COM3).
3. Confirm the remaining settings match the table above.
4. **Close Arduino Serial Monitor if it's open** — it holds the COM port
   open, which will block the FastAPI backend from connecting.
5. Click Upload. Wait for "Hard resetting via RTS pin..." / success in the
   IDE's output console.
6. Optional sanity check: open Serial Monitor at 115200 baud, type `PING`,
   confirm `PONG` comes back — then close Serial Monitor again before
   testing from the backend.
