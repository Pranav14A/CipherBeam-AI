/*
 * CipherBeam AI — ESP32-S3 LED Control Firmware
 * ==========================================================================
 * Original Roadmap Phase 5: digital ON/OFF GPIO control.
 * Original Roadmap Phase 6: deterministic OPTICAL_TEST pulse sequence.
 * Original Roadmap Phase 7: basic optical ASCII message transmission.
 *
 * Hardware:
 *   GPIO4 -> 220R -> Red LED   -> GND   (optical data carrier)
 *   GPIO5 -> 220R -> Green LED -> GND   (control/sync indicator)
 *
 * Serial:
 *   115200 baud
 *   Commands are '\n'-terminated ASCII strings.
 *
 * Existing commands:
 *
 *   PING\n
 *       -> PONG\n
 *
 *   STATUS\n
 *       -> ESP32_READY\n
 *
 *   LED_RED_ON\n
 *       -> OK\n
 *
 *   LED_RED_OFF\n
 *       -> OK\n
 *
 *   LED_GREEN_ON\n
 *       -> OK\n
 *
 *   LED_GREEN_OFF\n
 *       -> OK\n
 *
 *   OPTICAL_TEST\n
 *       -> OPTICAL_TEST_DONE\n
 *
 * Phase 7 command:
 *
 *   TRANSMIT:<message>\n
 *       -> Red LED transmits the message optically
 *       -> TRANSMIT_DONE\n
 *
 * --------------------------------------------------------------------------
 * PHASE 7 TEMPORARY OPTICAL ENCODING
 * --------------------------------------------------------------------------
 *
 * Each bit lasts 200 ms.
 *
 *   Bit 1 = Red LED ON
 *   Bit 0 = Red LED OFF
 *
 * Transmission:
 *
 *   START marker = 0xFE = 11111110
 *   PAYLOAD      = 8-bit ASCII for each message character
 *   END marker   = 0xFF = 11111111
 *
 * Example:
 *
 *   HELLO
 *
 *   11111110
 *   01001000   H
 *   01000101   E
 *   01001100   L
 *   01001100   L
 *   01001111   O
 *   11111111
 *
 * The LED is always left OFF after transmission.
 *
 * This is intentionally NOT the final CipherBeam protocol.
 * Manchester encoding, packet framing, payload length, CRC,
 * encryption, retransmission, AI decoding, etc. belong to later phases.
 *
 * During OPTICAL_TEST or TRANSMIT, serial commands are not processed.
 * They remain in the serial input buffer and are handled after the
 * optical operation finishes.
 *
 * Messages are expected to be printable ASCII. The backend validates
 * this before sending TRANSMIT:<message> to the ESP32.
 */

// ==========================================================================
// Hardware
// ==========================================================================

const int RED_PIN = 4;
const int GREEN_PIN = 5;


// ==========================================================================
// Phase 6 — OPTICAL_TEST
// ==========================================================================

const unsigned long OPTICAL_TEST_PULSE_ON_MS = 200;
const unsigned long OPTICAL_TEST_PULSE_OFF_MS = 200;
const int OPTICAL_TEST_PULSE_COUNT = 5;

bool opticalTestActive = false;
bool opticalTestLedOn = false;
int opticalTestPulsesRemaining = 0;
unsigned long opticalTestPhaseStartMs = 0;


// ==========================================================================
// Phase 7 — Basic Optical Transmission
// ==========================================================================

const unsigned long OPTICAL_BIT_DURATION_MS = 200;

// Start and end markers.
const byte OPTICAL_START_MARKER = 0xFE;
const byte OPTICAL_END_MARKER = 0xFF;

// Maximum message length supported by this firmware.
// The backend currently enforces the same practical limit.
const int OPTICAL_MAX_MESSAGE_LENGTH = 100;

String opticalTxMessage = "";

bool opticalTxActive = false;

int opticalTxByteIndex = 0;
int opticalTxBitIndex = 0;

unsigned long opticalTxBitStartMs = 0;


// ==========================================================================
// Phase 6 — OPTICAL_TEST functions
// ==========================================================================

void startOpticalTest() {
  opticalTestActive = true;
  opticalTestPulsesRemaining = OPTICAL_TEST_PULSE_COUNT;
  opticalTestLedOn = true;

  digitalWrite(RED_PIN, HIGH);

  opticalTestPhaseStartMs = millis();
}


void updateOpticalTest() {
  if (!opticalTestActive) {
    return;
  }

  unsigned long elapsed = millis() - opticalTestPhaseStartMs;

  if (opticalTestLedOn) {

    if (elapsed >= OPTICAL_TEST_PULSE_ON_MS) {

      digitalWrite(RED_PIN, LOW);

      opticalTestLedOn = false;
      opticalTestPhaseStartMs = millis();
    }

  } else {

    if (elapsed >= OPTICAL_TEST_PULSE_OFF_MS) {

      opticalTestPulsesRemaining--;

      if (opticalTestPulsesRemaining <= 0) {

        opticalTestActive = false;

        // Always leave the optical LED OFF.
        digitalWrite(RED_PIN, LOW);

        Serial.println("OPTICAL_TEST_DONE");

      } else {

        digitalWrite(RED_PIN, HIGH);

        opticalTestLedOn = true;
        opticalTestPhaseStartMs = millis();
      }
    }
  }
}


// ==========================================================================
// Phase 7 — Optical transmission helpers
// ==========================================================================

int getOpticalTransmissionByte() {

  // Byte 0 = START marker.
  if (opticalTxByteIndex == 0) {
    return OPTICAL_START_MARKER;
  }

  // Bytes 1..message.length() = message characters.
  if (opticalTxByteIndex <= opticalTxMessage.length()) {
    return (byte)opticalTxMessage.charAt(opticalTxByteIndex - 1);
  }

  // Final byte = END marker.
  return OPTICAL_END_MARKER;
}


void outputCurrentOpticalBit() {

  int currentByte = getOpticalTransmissionByte();

  // MSB first:
  //
  // bit index 0 -> bit 7
  // bit index 1 -> bit 6
  // ...
  // bit index 7 -> bit 0
  //
  // 1 = LED ON
  // 0 = LED OFF

  byte mask = 0x80 >> opticalTxBitIndex;

  bool bitValue = (currentByte & mask) != 0;

  digitalWrite(
    RED_PIN,
    bitValue ? HIGH : LOW
  );

  opticalTxBitStartMs = millis();
}


void startOpticalTransmission(String message) {

  opticalTxMessage = message;

  opticalTxActive = true;

  opticalTxByteIndex = 0;
  opticalTxBitIndex = 0;

  // Start from a known OFF state before the first bit.
  digitalWrite(RED_PIN, LOW);

  // Immediately output the first bit of the START marker.
  outputCurrentOpticalBit();
}


void finishOpticalTransmission() {

  opticalTxActive = false;

  opticalTxMessage = "";

  opticalTxByteIndex = 0;
  opticalTxBitIndex = 0;

  // Critical: always leave the red LED OFF.
  digitalWrite(RED_PIN, LOW);

  Serial.println("TRANSMIT_DONE");
}


void updateOpticalTransmission() {

  if (!opticalTxActive) {
    return;
  }

  unsigned long elapsed = millis() - opticalTxBitStartMs;

  if (elapsed < OPTICAL_BIT_DURATION_MS) {
    return;
  }

  // Move to the next bit.
  opticalTxBitIndex++;

  // Finished the current byte.
  if (opticalTxBitIndex >= 8) {

    opticalTxBitIndex = 0;
    opticalTxByteIndex++;
  }

  // Total bytes:
  //
  // 1 START marker
  // + message length
  // + 1 END marker
  //
  int totalBytes = opticalTxMessage.length() + 2;

  if (opticalTxByteIndex >= totalBytes) {

    finishOpticalTransmission();

    return;
  }

  // Output the next bit.
  outputCurrentOpticalBit();
}


// ==========================================================================
// Setup
// ==========================================================================

void setup() {

  Serial.begin(115200);

  pinMode(RED_PIN, OUTPUT);
  pinMode(GREEN_PIN, OUTPUT);

  // Known initial state.
  digitalWrite(RED_PIN, LOW);
  digitalWrite(GREEN_PIN, LOW);

  delay(1000);

  Serial.println("ESP32_READY");
}


// ==========================================================================
// Main loop
// ==========================================================================

void loop() {

  // ------------------------------------------------------------------------
  // Phase 6 optical test
  // ------------------------------------------------------------------------

  updateOpticalTest();


  // ------------------------------------------------------------------------
  // Phase 7 optical transmission
  // ------------------------------------------------------------------------

  updateOpticalTransmission();


  // ------------------------------------------------------------------------
  // Serial command processing
  //
  // Do not process new commands while either optical operation is active.
  // ------------------------------------------------------------------------

  if (
    !opticalTestActive &&
    !opticalTxActive &&
    Serial.available()
  ) {

    String command = Serial.readStringUntil('\n');

    command.trim();


    // ----------------------------------------------------------------------
    // Phase 2D — PING
    // ----------------------------------------------------------------------

    if (command == "PING") {

      Serial.println("PONG");
    }


    // ----------------------------------------------------------------------
    // Phase 2D — STATUS
    // ----------------------------------------------------------------------

    else if (command == "STATUS") {

      Serial.println("ESP32_READY");
    }


    // ----------------------------------------------------------------------
    // Phase 5 — RED LED
    // ----------------------------------------------------------------------

    else if (command == "LED_RED_ON") {

      digitalWrite(RED_PIN, HIGH);

      Serial.println("OK");
    }

    else if (command == "LED_RED_OFF") {

      digitalWrite(RED_PIN, LOW);

      Serial.println("OK");
    }


    // ----------------------------------------------------------------------
    // Phase 5 — GREEN LED
    // ----------------------------------------------------------------------

    else if (command == "LED_GREEN_ON") {

      digitalWrite(GREEN_PIN, HIGH);

      Serial.println("OK");
    }

    else if (command == "LED_GREEN_OFF") {

      digitalWrite(GREEN_PIN, LOW);

      Serial.println("OK");
    }


    // ----------------------------------------------------------------------
    // Phase 6 — OPTICAL_TEST
    // ----------------------------------------------------------------------

    else if (command == "OPTICAL_TEST") {

      startOpticalTest();

      // No immediate response.
      // OPTICAL_TEST_DONE is sent when the test finishes.
    }


    // ----------------------------------------------------------------------
    // Phase 7 — TRANSMIT:<message>
    // ----------------------------------------------------------------------

    else if (command.startsWith("TRANSMIT:")) {

      String message = command.substring(9);

      // Basic firmware-side safety checks.
      //
      // The backend performs the main validation, but the firmware
      // also protects itself in case somebody sends a command manually.

      if (
        message.length() > 0 &&
        message.length() <= OPTICAL_MAX_MESSAGE_LENGTH
      ) {

        startOpticalTransmission(message);

        // No immediate response.
        // TRANSMIT_DONE is sent after the complete transmission.
      }
    }


    // ----------------------------------------------------------------------
    // Unknown commands intentionally ignored.
    // ----------------------------------------------------------------------
  }
}
