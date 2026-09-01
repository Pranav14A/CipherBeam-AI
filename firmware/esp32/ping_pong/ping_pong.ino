/*
 * CipherBeam AI — ESP32-S3 Debug Serial Firmware (Phase 2)
 * ==========================================================
 *
 * THIS IS A DEVELOPMENT/DEBUG PROTOCOL ONLY.
 * It is NOT the final CipherBeam optical packet protocol described in
 * docs/architecture/architecture-decision-document.md. It exists solely to
 * prove basic USB serial communication between the laptop and the
 * ESP32-S3 before any optical/LED/encryption work begins.
 *
 * Confirmed hardware (Phase 2):
 *   Board:  YD-ESP32-23 2022-V1.3
 *   Module: ESP32-S3-N16R8 (16MB Flash, 8MB OPI PSRAM)
 *   Connected via the board's "COM" USB-C port (CH343 USB-to-serial
 *   bridge), NOT the "USB-OTG" port.
 *
 * Confirmed Arduino IDE configuration (do not change without a reason —
 * see the architecture decision document and Phase 2 chat history):
 *   Board:             ESP32S3 Dev Module
 *   USB CDC On Boot:   Disabled   <- required for Serial to route over
 *                                    UART0 -> CH343 -> the COM port above,
 *                                    rather than the native USB-OTG port.
 *   Upload Mode:        UART0 / Hardware CDC
 *   USB Mode:           Hardware CDC and JTAG
 *   (CPU/Flash/PSRAM/upload speed settings: see firmware/esp32/README.md)
 *
 * MESSAGE FORMAT
 * --------------
 * - ASCII text only.
 * - Every command from the laptop is a single line terminated with '\n'.
 * - Every response from the ESP32 is a single line terminated with '\n'
 *   (Serial.println() appends "\r\n"; either is fine to parse on the
 *   laptop side — trim whitespace).
 * - Commands are case-sensitive and compared exactly, no partial matches.
 * - Baud rate: 115200.
 *
 * Supported commands:
 *
 *   Laptop -> ESP32      ESP32 -> Laptop      Meaning
 *   --------------------------------------------------------------
 *   PING\n                PONG\n               liveness check
 *   STATUS\n               ESP32_READY\n        firmware is running
 *
 * Any other command is currently ignored (no response is sent). This is
 * deliberate for Phase 2 — malformed/unknown-command handling on the
 * Python side is covered by SerialManager's timeout logic, not by this
 * firmware.
 *
 * NOT implemented here (future phases): LED/MOSFET control, Manchester
 * encoding, packet framing, CRC, encryption, AI.
 */

void setup() {
  Serial.begin(115200);

  // Give the USB CDC/serial bridge a moment to enumerate before we send
  // anything — sending immediately on some boards can drop the first line.
  delay(1000);

  Serial.println("ESP32_READY");
}

void loop() {
  if (Serial.available()) {
    String command = Serial.readStringUntil('\n');
    command.trim();

    if (command == "PING") {
      Serial.println("PONG");
    } else if (command == "STATUS") {
      Serial.println("ESP32_READY");
    }
    // Unknown commands are intentionally ignored — see MESSAGE FORMAT above.
  }
}
