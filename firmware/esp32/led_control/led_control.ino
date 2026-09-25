/*
 * CipherBeam AI — ESP32-S3 LED Control Firmware
 * ==========================================================================
 * Phase 5: LED GPIO control
 * Phase 6: Optical test
 * Phase 7: Basic optical transport
 * Phase 9/10: CipherBeam packet transmission
 *
 * Hardware:
 *   GPIO4 -> 220R -> Red LED   -> GND
 *   GPIO5 -> 220R -> Green LED -> GND
 *
 * Optical protocol:
 *
 *   GREEN START       600 ms
 *   GREEN OFF GUARD   200 ms
 *   RED DATA          200 ms / bit, MSB first
 *   RED OFF GUARD     200 ms
 *   GREEN END         600 ms
 *
 * Logical packet:
 *
 *   SYNC       1 byte
 *   VERSION    1 byte
 *   FLAGS      1 byte
 *   LENGTH     1 byte
 *   PAYLOAD    0..100 bytes
 *   CRC        2 bytes
 *
 * Current CRC value:
 *
 *   0x0000
 *
 * Actual CRC implementation belongs to the later CRC phase.
 *
 * Example:
 *
 *   TRANSMIT:HELLO
 *
 * Produces:
 *
 *   A5 01 00 05 48 45 4C 4C 4F 00 00
 *
 * Red LED transmits those bytes MSB first.
 *
 * GREEN is used only for framing.
 * RED is used only for packet data.
 *
 * RED and GREEN are never ON simultaneously.
 */

// ==========================================================================
// Hardware
// ==========================================================================

const int RED_PIN = 4;
const int GREEN_PIN = 5;


// ==========================================================================
// Optical timing
// ==========================================================================

const unsigned long GREEN_START_DURATION_MS = 600;
const unsigned long GREEN_END_DURATION_MS = 600;
const unsigned long OPTICAL_GUARD_DURATION_MS = 200;
const unsigned long OPTICAL_BIT_DURATION_MS = 150;


// ==========================================================================
// Packet specification
// ==========================================================================

const byte CIPHERBEAM_SYNC = 0xA5;
const byte CIPHERBEAM_VERSION = 0x01;
const byte CIPHERBEAM_FLAGS = 0x00;

const int CIPHERBEAM_MAX_PAYLOAD_LENGTH = 100;

const int CIPHERBEAM_HEADER_SIZE = 4;
const int CIPHERBEAM_CRC_SIZE = 2;

const int CIPHERBEAM_MAX_PACKET_SIZE =
  CIPHERBEAM_HEADER_SIZE +
  CIPHERBEAM_MAX_PAYLOAD_LENGTH +
  CIPHERBEAM_CRC_SIZE;


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
// Packet transmission state
// ==========================================================================

enum OpticalTxState {
  TX_IDLE,
  TX_GREEN_START,
  TX_START_GUARD,
  TX_DATA,
  TX_END_GUARD,
  TX_GREEN_END
};

OpticalTxState opticalTxState = TX_IDLE;

String opticalTxMessage = "";

byte opticalTxPacket[CIPHERBEAM_MAX_PACKET_SIZE];

int opticalTxPacketLength = 0;

int opticalTxByteIndex = 0;
int opticalTxBitIndex = 0;

unsigned long opticalTxStateStartMs = 0;


// ==========================================================================
// Phase 6 — OPTICAL_TEST
// ==========================================================================

void startOpticalTest() {

  opticalTestActive = true;

  opticalTestPulsesRemaining =
    OPTICAL_TEST_PULSE_COUNT;

  opticalTestLedOn = true;

  digitalWrite(GREEN_PIN, LOW);
  digitalWrite(RED_PIN, HIGH);

  opticalTestPhaseStartMs = millis();
}


void updateOpticalTest() {

  if (!opticalTestActive) {
    return;
  }

  unsigned long elapsed =
    millis() - opticalTestPhaseStartMs;


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

        digitalWrite(RED_PIN, LOW);
        digitalWrite(GREEN_PIN, LOW);

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
// CRC-16/CCITT-FALSE
// ==========================================================================
//
// Parameters:
//   Width      = 16
//   Polynomial = 0x1021
//   Initial    = 0xFFFF
//   RefIn      = false
//   RefOut     = false
//   XorOut     = 0x0000
//
// CRC coverage:
//   VERSION + FLAGS + LENGTH + PAYLOAD
//
// SYNC is intentionally excluded.
//
// ==========================================================================

uint16_t calculateCipherBeamCrc(
  byte version,
  byte flags,
  byte payloadLength,
  const byte* payload
) {

  uint16_t crc = 0xFFFF;


  // ------------------------------------------------------------------------
  // Process one byte
  // ------------------------------------------------------------------------

  auto updateCrc = [&](byte value) {

    crc ^= ((uint16_t)value << 8);


    for (int bit = 0; bit < 8; bit++) {

      if (crc & 0x8000) {

        crc =
          (crc << 1) ^ 0x1021;

      } else {

        crc =
          crc << 1;
      }
    }
  };


  // ------------------------------------------------------------------------
  // CRC coverage
  // ------------------------------------------------------------------------

  updateCrc(version);
  updateCrc(flags);
  updateCrc(payloadLength);


  for (int i = 0; i < payloadLength; i++) {

    updateCrc(payload[i]);
  }


  return crc;
}

bool buildCipherBeamPacket(String message) {

  int payloadLength =
    message.length();


  if (
    payloadLength <= 0 ||
    payloadLength > CIPHERBEAM_MAX_PAYLOAD_LENGTH
  ) {

    return false;
  }


  /*
   * Validate printable ASCII.
   *
   * The current Phase 10 plaintext packet uses
   * printable ASCII payloads.
   */
  for (int i = 0; i < payloadLength; i++) {

    byte value =
      (byte)message.charAt(i);

    if (value < 0x20 || value > 0x7E) {

      return false;
    }
  }


  int index = 0;


  // ------------------------------------------------------------------------
  // SYNC
  // ------------------------------------------------------------------------

  opticalTxPacket[index++] =
    CIPHERBEAM_SYNC;


  // ------------------------------------------------------------------------
  // VERSION
  // ------------------------------------------------------------------------

  opticalTxPacket[index++] =
    CIPHERBEAM_VERSION;


  // ------------------------------------------------------------------------
  // FLAGS
  // ------------------------------------------------------------------------

  opticalTxPacket[index++] =
    CIPHERBEAM_FLAGS;


  // ------------------------------------------------------------------------
  // LENGTH
  // ------------------------------------------------------------------------

  opticalTxPacket[index++] =
    (byte)payloadLength;


  // ------------------------------------------------------------------------
  // PAYLOAD
  // ------------------------------------------------------------------------

  for (int i = 0; i < payloadLength; i++) {

    opticalTxPacket[index++] =
      (byte)message.charAt(i);
  }


// ------------------------------------------------------------------------
// CRC
//
// CRC-16/CCITT-FALSE
//
// Coverage:
//   VERSION + FLAGS + LENGTH + PAYLOAD
//
// SYNC is excluded.
//
// Big-endian:
//
//   CRC high byte
//   CRC low byte
// ------------------------------------------------------------------------

uint16_t crc =
  calculateCipherBeamCrc(
    CIPHERBEAM_VERSION,
    CIPHERBEAM_FLAGS,
    (byte)payloadLength,
    &opticalTxPacket[4]
  );
Serial.print("CRC: ");
Serial.println(crc, HEX);

opticalTxPacket[index++] =
  (byte)((crc >> 8) & 0xFF);

opticalTxPacket[index++] =
  (byte)(crc & 0xFF);




  opticalTxPacketLength =
    index;


  return true;
}


// ==========================================================================
// Optical transmission helpers
// ==========================================================================

void startGreenStart() {

  digitalWrite(RED_PIN, LOW);

  digitalWrite(GREEN_PIN, HIGH);

  opticalTxState =
    TX_GREEN_START;

  opticalTxStateStartMs =
    millis();
}


void startStartGuard() {

  digitalWrite(RED_PIN, LOW);

  digitalWrite(GREEN_PIN, LOW);

  opticalTxState =
    TX_START_GUARD;

  opticalTxStateStartMs =
    millis();
}


void outputCurrentOpticalBit() {

  if (
    opticalTxByteIndex < 0 ||
    opticalTxByteIndex >= opticalTxPacketLength
  ) {

    digitalWrite(RED_PIN, LOW);

    return;
  }


  byte currentByte =
    opticalTxPacket[opticalTxByteIndex];


  /*
   * MSB first:
   *
   * bit index 0 -> bit 7
   * bit index 1 -> bit 6
   * ...
   * bit index 7 -> bit 0
   */

  byte mask =
    0x80 >> opticalTxBitIndex;


  bool bitValue =
    (currentByte & mask) != 0;


  digitalWrite(
    GREEN_PIN,
    LOW
  );

  digitalWrite(
    RED_PIN,
    bitValue ? HIGH : LOW
  );


  opticalTxStateStartMs =
    millis();
}


void startDataTransmission() {

  opticalTxByteIndex = 0;
  opticalTxBitIndex = 0;

  opticalTxState =
    TX_DATA;

  outputCurrentOpticalBit();
}


void startEndGuard() {

  digitalWrite(RED_PIN, LOW);
  digitalWrite(GREEN_PIN, LOW);

  opticalTxState =
    TX_END_GUARD;

  opticalTxStateStartMs =
    millis();
}


void startGreenEnd() {

  digitalWrite(RED_PIN, LOW);
  digitalWrite(GREEN_PIN, HIGH);

  opticalTxState =
    TX_GREEN_END;

  opticalTxStateStartMs =
    millis();
}


void finishOpticalTransmission() {

  opticalTxState =
    TX_IDLE;

  opticalTxMessage = "";

  opticalTxPacketLength = 0;

  opticalTxByteIndex = 0;
  opticalTxBitIndex = 0;


  digitalWrite(RED_PIN, LOW);
  digitalWrite(GREEN_PIN, LOW);


  Serial.println("TRANSMIT_DONE");
}


// ==========================================================================
// Optical transmission state machine
// ==========================================================================

void updateOpticalTransmission() {

  if (opticalTxState == TX_IDLE) {
    return;
  }


  unsigned long elapsed =
    millis() - opticalTxStateStartMs;


  // ------------------------------------------------------------------------
  // GREEN START
  // ------------------------------------------------------------------------

  if (
    opticalTxState == TX_GREEN_START
  ) {

    if (
      elapsed >=
      GREEN_START_DURATION_MS
    ) {

      startStartGuard();
    }

    return;
  }


  // ------------------------------------------------------------------------
  // START GUARD
  // ------------------------------------------------------------------------

  if (
    opticalTxState == TX_START_GUARD
  ) {

    if (
      elapsed >=
      OPTICAL_GUARD_DURATION_MS
    ) {

      startDataTransmission();
    }

    return;
  }


  // ------------------------------------------------------------------------
  // RED DATA
  // ------------------------------------------------------------------------

  if (
    opticalTxState == TX_DATA
  ) {

    if (
      elapsed <
      OPTICAL_BIT_DURATION_MS
    ) {

      return;
    }


    opticalTxBitIndex++;


    // Finished current byte.
    if (
      opticalTxBitIndex >= 8
    ) {

      opticalTxBitIndex = 0;
      opticalTxByteIndex++;
    }


    // Finished entire packet.
    if (
      opticalTxByteIndex >=
      opticalTxPacketLength
    ) {

      startEndGuard();

      return;
    }


    outputCurrentOpticalBit();

    return;
  }


  // ------------------------------------------------------------------------
  // END GUARD
  // ------------------------------------------------------------------------

  if (
    opticalTxState == TX_END_GUARD
  ) {

    if (
      elapsed >=
      OPTICAL_GUARD_DURATION_MS
    ) {

      startGreenEnd();
    }

    return;
  }


  // ------------------------------------------------------------------------
  // GREEN END
  // ------------------------------------------------------------------------

  if (
    opticalTxState == TX_GREEN_END
  ) {

    if (
      elapsed >=
      GREEN_END_DURATION_MS
    ) {

      finishOpticalTransmission();
    }

    return;
  }
}


// ==========================================================================
// Start packet transmission
// ==========================================================================

bool startOpticalTransmission(String message) {

  if (
    !buildCipherBeamPacket(message)
  ) {

    return false;
  }


  opticalTxMessage =
    message;


  /*
   * Start from a completely known state.
   */

  digitalWrite(RED_PIN, LOW);
  digitalWrite(GREEN_PIN, LOW);


  opticalTxByteIndex = 0;
  opticalTxBitIndex = 0;


  startGreenStart();


  return true;
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
  // Packet optical transmission
  // ------------------------------------------------------------------------

  updateOpticalTransmission();


  // ------------------------------------------------------------------------
  // Serial command processing
  //
  // Do not process new commands while an optical operation
  // is active.
  // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
  // Serial command processing
  //
  // Always consume incoming commands.
  //
  // If an optical transmission is already active, reject a new
  // TRANSMIT command instead of leaving it queued in the serial buffer.
  // This prevents old messages from being transmitted later.
  // ------------------------------------------------------------------------

  if (Serial.available()) {

    String command =
      Serial.readStringUntil('\n');

    command.trim();


    // ----------------------------------------------------------------------
    // Reject commands that require idle hardware while transmission is active
    // ----------------------------------------------------------------------

    if (
      opticalTestActive ||
      opticalTxState != TX_IDLE
    ) {

      if (
        command.startsWith("TRANSMIT:")
      ) {

        Serial.println("TRANSMIT_BUSY");

      } else {

        Serial.println("BUSY");
      }

      return;
    }


    // ----------------------------------------------------------------------
    // PING
    // ----------------------------------------------------------------------

    if (
      command == "PING"
    ) {

      Serial.println("PONG");
    }


    // ----------------------------------------------------------------------
    // STATUS
    // ----------------------------------------------------------------------

    else if (
      command == "STATUS"
    ) {

      Serial.println("ESP32_READY");
    }


    // ----------------------------------------------------------------------
    // RED LED
    // ----------------------------------------------------------------------

    else if (
      command == "LED_RED_ON"
    ) {

      digitalWrite(RED_PIN, HIGH);

      Serial.println("OK");
    }

    else if (
      command == "LED_RED_OFF"
    ) {

      digitalWrite(RED_PIN, LOW);

      Serial.println("OK");
    }


    // ----------------------------------------------------------------------
    // GREEN LED
    // ----------------------------------------------------------------------

    else if (
      command == "LED_GREEN_ON"
    ) {

      digitalWrite(GREEN_PIN, HIGH);

      Serial.println("OK");
    }

    else if (
      command == "LED_GREEN_OFF"
    ) {

      digitalWrite(GREEN_PIN, LOW);

      Serial.println("OK");
    }


    // ----------------------------------------------------------------------
    // OPTICAL_TEST
    // ----------------------------------------------------------------------

    else if (
      command == "OPTICAL_TEST"
    ) {

      startOpticalTest();
    }


    // ----------------------------------------------------------------------
    // TRANSMIT:<message>
    // ----------------------------------------------------------------------

    else if (
      command.startsWith("TRANSMIT:")
    ) {

      String message =
        command.substring(9);


      if (
        startOpticalTransmission(message)
      ) {

        /*
         * TRANSMIT_DONE is sent only after the
         * complete optical transmission finishes.
         */

      } else {

        Serial.println("TRANSMIT_ERROR");
      }
    }


    // ----------------------------------------------------------------------
    // Unknown commands intentionally ignored.
    // ----------------------------------------------------------------------
  }
}