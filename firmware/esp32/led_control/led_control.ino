/*
 * CipherBeam AI — ESP32-S3 LED Control Firmware (Original Roadmap Phase 5)
 * ==========================================================================
 *
 * STRICTLY digital ON/OFF GPIO control. NOT the optical protocol.
 * No PWM, no Manchester encoding, no packet framing, no encryption.
 *
 * Hardware (confirmed on YD-ESP32-23 / ESP32-S3-N16R8):
 *   GPIO4 -> 220R -> Red LED   -> GND
 *   GPIO5 -> 220R -> Green LED -> GND
 *   Standard 5mm low-current LEDs, no MOSFET, no external supply.
 *
 * Extends the Phase 2 debug protocol (ping_pong.ino) with LED commands.
 * Same message format: ASCII, '\n'-terminated, case-sensitive, 115200 baud.
 *
 *   Laptop -> ESP32          ESP32 -> Laptop     Meaning
 *   -----------------------------------------------------------------
 *   PING\n                    PONG\n              liveness check
 *   STATUS\n                  ESP32_READY\n       firmware is running
 *   LED_RED_ON\n               OK\n                red LED on
 *   LED_RED_OFF\n              OK\n                red LED off
 *   LED_GREEN_ON\n             OK\n                green LED on
 *   LED_GREEN_OFF\n            OK\n                green LED off
 *
 * Unknown commands are ignored (no response), same as ping_pong.ino.
 */

const int RED_PIN = 4;
const int GREEN_PIN = 5;

void setup() {
  Serial.begin(115200);

  pinMode(RED_PIN, OUTPUT);
  pinMode(GREEN_PIN, OUTPUT);
  digitalWrite(RED_PIN, LOW);
  digitalWrite(GREEN_PIN, LOW);

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
    } else if (command == "LED_RED_ON") {
      digitalWrite(RED_PIN, HIGH);
      Serial.println("OK");
    } else if (command == "LED_RED_OFF") {
      digitalWrite(RED_PIN, LOW);
      Serial.println("OK");
    } else if (command == "LED_GREEN_ON") {
      digitalWrite(GREEN_PIN, HIGH);
      Serial.println("OK");
    } else if (command == "LED_GREEN_OFF") {
      digitalWrite(GREEN_PIN, LOW);
      Serial.println("OK");
    }
    // Unknown commands intentionally ignored.
  }
}
