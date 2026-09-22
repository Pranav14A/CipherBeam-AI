# CipherBeam-AI

> An offline optical communication system using visible-light transmission, ESP32 hardware, and a smartphone camera receiver.

CipherBeam-AI is an experimental **LiFi-like / Optical Wireless Communication (OWC)** system designed to transmit digital messages through visible light without relying on Wi-Fi, Bluetooth, or the Internet.

The current prototype uses:

- ESP32-S3 as the optical transmitter controller
- Red LED as the data carrier
- Green LED as the control/synchronization channel
- Android phone camera as the optical receiver
- Kotlin + CameraX for mobile image analysis
- FastAPI + React on the desktop side for transmitter control
- USB serial communication between desktop and ESP32

The project is being developed incrementally, with the optical communication pipeline established first and packet security/reliability being added afterward.

---

# 1. Project Vision

The long-term CipherBeam-AI pipeline is:

```text
Desktop / Sender
      ↓
Message
      ↓
Packetization
      ↓
Encryption
      ↓
CRC / Reliability
      ↓
FastAPI
      ↓
USB Serial
      ↓
ESP32-S3
      ↓
RED + GREEN LEDs
      ↓
Visible Light
      ↓
Android Camera
      ↓
CameraX
      ↓
Optical Signal Detection
      ↓
Optical Decoder
      ↓
Packet Parser
      ↓
CRC Verification
      ↓
Decryption
      ↓
Received Message
```

The current implementation has reached the optical transmission/receiving stage. Packetization, cryptography, CRC, retransmission, and AI-based decoding are future layers.

---

# 2. Core Design

CipherBeam-AI uses two visible-light channels:

| Channel | Purpose |
|---|---|
| GREEN | Control / synchronization |
| RED | Binary data |

The optical layer currently works as:

```text
GREEN START
     ↓
200 ms guard
     ↓
RED binary payload
     ↓
200 ms end guard
     ↓
GREEN END
```

The system intentionally separates:

1. **Physical optical framing**
2. **Logical packet structure**
3. **Cryptography**
4. **Reliability**

This allows later protocol layers to be developed without redesigning the basic LED transport.

---

# 3. Repository Structure

Current high-level structure:

```text
CipherBeam-AI/
│
├── desktop/
│   ├── backend/
│   │   └── ...
│   │
│   └── frontend/
│       └── ...
│
├── mobile/
│   └── receiver/
│       ├── app/
│       │   └── src/
│       │       └── main/
│       │           └── java/
│       │               └── com/
│       │                   └── cipherbeam/
│       │                       └── receiver/
│       │                           ├── camera/
│       │                           ├── optical/
│       │                           └── ...
│       │
│       └── README.md
│
├── firmware/
│   └── ...
│
└── README.md
```

The exact repository contents may evolve as development continues.

---

# 4. Hardware

## ESP32

Current board:

```text
YD-ESP32-23 2022 V1.3
ESP32-S3-N16R8
```

Important board characteristics:

- ESP32-S3
- 16 MB Flash
- 8 MB OPI PSRAM
- CH343 USB-UART bridge
- Native USB-OTG interface

The working USB serial connection is:

```text
USB-UART / CH343
        ↓
COM3
        ↓
FastAPI / pyserial
        ↓
ESP32-S3
```

Arduino IDE board configuration:

```text
Board:
ESP32S3 Dev Module

USB CDC On Boot:
Disabled

Flash Size:
16MB

PSRAM:
OPI PSRAM
```

---

# 5. LED Hardware

Current optical transmitter hardware:

- 1 × Red 5 mm LED
- 1 × Green 5 mm LED
- 2 × 220 Ω resistors
- ESP32-S3 GPIO pins

Wiring:

```text
ESP32 GPIO4
    ↓
220 Ω
    ↓
RED LED anode

RED LED cathode
    ↓
GND
```

and:

```text
ESP32 GPIO5
    ↓
220 Ω
    ↓
GREEN LED anode

GREEN LED cathode
    ↓
GND
```

The LEDs are driven directly from ESP32 GPIO.

Current channel assignment:

```text
GPIO4 → RED
GPIO5 → GREEN
```

---

# 6. Desktop Architecture

The desktop side contains:

```text
React Frontend
      ↓
FastAPI Backend
      ↓
pyserial
      ↓
USB / CH343
      ↓
ESP32-S3
```

The desktop application is responsible for:

- message input
- optical transmission controls
- hardware status
- hardware ping
- serial communication
- transmitter control

The backend currently exposes endpoints including:

```text
GET /health
GET /hardware/status
GET /hardware/ping
```

The backend uses the ESP32 serial connection through `pyserial`.

---

# 7. Starting the Desktop Backend

From the repository root:

```powershell
cd desktop\backend
uv run uvicorn app.main:app --reload
```

Expected:

```text
Uvicorn running on http://127.0.0.1:8000
```

Health check:

```powershell
curl http://127.0.0.1:8000/health
```

Important:

> Keep Arduino Serial Monitor closed while the FastAPI backend is using COM3.

---

# 8. ESP32 Serial Communication

ESP32 communication was established before optical transmission.

The communication path is:

```text
React
  ↓
FastAPI
  ↓
pyserial
  ↓
COM3
  ↓
ESP32-S3
  ↓
Response
  ↑
```

A React → FastAPI → pyserial → ESP32 → response round trip was physically tested.

The ESP32 supports the required serial commands used by the desktop backend.

---

# 9. Optical Transmitter

The transmitter uses a non-blocking `millis()` state machine.

Current logical states include:

```text
TX_IDLE
TX_GREEN_START
TX_START_GUARD
TX_DATA
TX_END_GUARD
TX_GREEN_END
```

The transmitter sequence is:

```text
RED OFF
GREEN OFF

GREEN ON
    600 ms

GREEN OFF
    200 ms guard

RED DATA
    8-bit ASCII
    MSB first
    200 ms / bit

RED OFF
    200 ms end guard

GREEN ON
    600 ms

GREEN OFF
RED OFF

TRANSMIT_DONE
```

During the RED data section:

```text
GREEN = OFF
RED = current data bit
```

During GREEN framing:

```text
RED = OFF
```

Both LEDs are therefore not intentionally ON at the same time.

---

# 10. Current Optical Protocol

## Physical Frame

```text
IDLE
  ↓
GREEN START
  ↓
GREEN OFF / START GUARD
  ↓
RED DATA
  ↓
RED OFF / END GUARD
  ↓
GREEN END
  ↓
IDLE
```

Timing constants:

```text
GREEN_START_DURATION = 600 ms
GREEN_END_DURATION   = 600 ms
GUARD_DURATION       = 200 ms
BIT_DURATION         = 200 ms
```

## Data Encoding

Current Phase 7/8 payload encoding:

```text
1 = RED ON
0 = RED OFF
```

Each character:

```text
8 bits
MSB first
```

Example:

```text
H = 01001000
E = 01000101
L = 01001100
L = 01001100
O = 01001111
```

There is currently no inter-bit gap.

---

# 11. Android Receiver

The receiver is a Kotlin Android application.

Current technology:

```text
Kotlin 2.0.0
Android Gradle Plugin 8.5.2
CameraX 1.3.4
Jetpack Compose
compileSdk 34
targetSdk 34
minSdk 33
Java target 17
```

The receiver uses:

```text
CameraX Preview
+
CameraX ImageAnalysis
```

The phone camera provides the optical signal.

No network connection is required for optical reception.

---

# 12. Android Receiver Architecture

```text
CameraX
   ↓
ImageAnalysis
   ↓
RedLedAnalyzer
   ↓
YUV colour analysis
   ↓
GREEN localization
   ↓
RED localization
   ↓
Signal trackers
   ↓
OpticalDecoder
   ↓
Decoded plaintext
   ↓
Android UI
```

Main mobile areas:

```text
camera/
optical/
```

Important files include:

```text
camera/RedLedAnalyzer.kt
optical/SignalProcessing.kt
optical/OpticalDecoder.kt
```

---

# 13. Colour Detection

The receiver analyzes YUV camera data.

RED score is derived from the chroma channels:

```text
RED score = (V - U) / 255
```

The signal is constrained before being used.

GREEN uses a separate colour score.

The system maintains independent trackers for RED and GREEN.

Each tracker maintains:

```text
baseline
envelope
threshold
isOn
```

This allows the receiver to adapt to the camera/background instead of using one fixed absolute brightness threshold.

---

# 14. Spatial LED Localization

The receiver does not assume that the LEDs stay at a fixed camera coordinate.

GREEN localization uses a coarse grid search.

Current grid:

```text
5 × 5
```

GREEN acts as the primary control/reference signal.

RED localization searches around the previously detected RED position.

The RED location can therefore move as the phone or LED position changes.

The current implementation has successfully followed LEDs across substantially different areas of the camera frame.

---

# 15. Temporal Signal Processing

Short RED flicker can occur because of:

- camera frame timing
- sensor noise
- exposure changes
- LED/camera geometry
- small localization changes

The current decoder includes a short RED temporal stability filter.

The goal is to prevent extremely short RED changes from becoming false optical bits while preserving the 200 ms protocol timing.

The current implementation should not be changed casually while physical tests are passing.

---

# 16. Phase 7 — Basic Phone Receiver

Phase 7 established the basic physical optical link.

Completed:

- Android project
- CameraX preview
- camera analysis
- RED detection
- GREEN detection
- optical synchronization
- 8-bit ASCII decoding
- physical transmitter → phone testing
- persistent decoded-message UI

The basic system was able to transmit messages such as:

```text
HELLO
TEST
```

from the optical transmitter to the Android receiver.

---

# 17. Phase 8 — Optical Signal Detection

Phase 8 improved the receiver substantially.

Implemented:

- adaptive RED baseline
- adaptive GREEN baseline
- RED signal tracking
- GREEN signal tracking
- GREEN spatial localization
- RED spatial localization
- temporal RED stability filtering
- frame-to-frame localization
- diagnostic logging
- optical decoder synchronization
- moving LED tests

Recent physical tests showed approximately:

```text
22–28 FPS
```

Camera analysis.

Typical observed RED values:

```text
RED ON:
rawR ≈ 0.10–0.33

RED OFF:
rawR ≈ 0.02–0.055
```

Typical threshold:

```text
≈ 0.094–0.096
```

These values are diagnostic observations, not frozen protocol specifications.

---

# 18. Phase 8 Physical Validation

Recent testing included repeated transmissions with the phone moved:

```text
top → bottom
```

and across different areas of the camera frame.

The receiver continued to decode successfully.

GREEN and RED remained distinguishable.

An isolated run produced:

```text
hELLO
```

instead of:

```text
HELLO
```

but subsequent repeated transmissions passed.

Therefore:

> Phase 8 is considered sufficiently functional for the current demo milestone, while not being claimed as 100% reliable.

The current strategy is to stop repeatedly tuning the optical detector and move to the protocol layer.

---

# 19. Current Diagnostics

Diagnostic logging uses:

```text
CipherBeamDiag
```

Example fields:

```text
RY
RU
RV
rawR
GY
GU
GV
rawG
RED
RBASE
RTHR
GREEN
GBASE
GTHR
GLOC
RLOC
FPS
```

These are useful for investigating optical failures without changing the protocol.

---

# 20. Current Android UI

The Android UI retains the last completed message.

The basic behavior is:

```text
Decoder completes message
        ↓
completedMessage
        ↓
lastReceivedMessage
        ↓
UI continues displaying message
```

This prevents a successful message from disappearing immediately after the decoder returns to its waiting state.

---

# 21. Protocol Layer — Current State

The optical transport currently sends raw ASCII data.

The next architecture is:

```text
Application message
       ↓
Packet
       ↓
CRC
       ↓
Encryption
       ↓
Optical encoding
       ↓
LED transmission
```

Receiver:

```text
LED signal
       ↓
Optical decoding
       ↓
Packet
       ↓
CRC verification
       ↓
Decryption
       ↓
Application message
```

This separation is important because the optical layer should not need to understand cryptography.

---

# 22. Phase 9 — Packet Specification

**Current next phase.**

The proposed initial packet structure is:

```text
┌────────┬─────────┬────────┬────────┬─────────────┬───────┐
│ SYNC   │ VERSION │ FLAGS  │ LENGTH │ PAYLOAD     │ CRC   │
│ 1 byte │ 1 byte  │ 1 byte │ 1 byte │ 0–100 bytes │ 2 byte│
└────────┴─────────┴────────┴────────┴─────────────┴───────┘
```

Proposed meanings:

```text
SYNC
    Logical packet synchronization marker

VERSION
    Packet/protocol version

FLAGS
    Security/profile/algorithm information

LENGTH
    Payload length

PAYLOAD
    Actual application data

CRC
    Packet integrity check
```

Important:

> `SYNC` is a logical packet field. It is not a replacement for the physical GREEN/RED optical framing.

The physical optical framing remains:

```text
GREEN START
RED DATA
GREEN END
```

---

# 23. Planned FLAGS Structure

The proposed FLAGS byte is reserved for future security metadata.

Initial conceptual structure:

```text
bits 7–4:
Security Profile

bits 3–0:
Algorithm ID
```

Initial plaintext mode:

```text
FLAGS = 0x00
```

This allows the receiver to determine how to interpret a future encrypted packet without redesigning the packet format.

The exact security-profile and algorithm assignments will be frozen during Phase 12.

---

# 24. Remaining Roadmap

The broader project roadmap is:

```text
1. Repository and architecture                    DONE
2. Desktop FastAPI foundation                     DONE
3. Desktop React frontend foundation              DONE
4. ESP32 USB serial communication                 DONE
5. LED hardware control                            DONE
6. Basic optical transmitter                      DONE
7. Basic phone camera receiver                    DONE
8. Optical signal detection                       DONE
9. Protocol / packet specification                NEXT
10. Packet implementation
11. Cryptography
12. Security Profiles + Algorithm ID
13. CRC + reliability + retransmission
14. Kotlin Android receiver integration
15. OpenCV optimization
16. AI pulse classification
17. AI adaptive transmission
18. Full integration
19. Testing
20. Performance optimization
```

Phase 14 originally considered React Native, but the receiver direction has been changed to:

```text
Kotlin-only Android
```

---

# 25. Demo Sprint Priority

The immediate goal is a working end-to-end demo rather than completing every advanced feature before the demo.

Priority order:

```text
Reliable optical link
        ↓
Packet
        ↓
CRC
        ↓
Encryption
        ↓
Full desktop ↔ ESP32 ↔ optical ↔ Android integration
```

Lower priority until the core demo works:

```text
AI pulse classification
AI adaptive transmission
Multiple advanced crypto modes
Perfect edge-of-frame performance
Adaptive bitrate
Advanced OpenCV optimization
```

---

# 26. Git Workflow

The repository uses:

```text
main = stable tested code
dev  = active development
```

Development workflow:

```text
dev
 ↓
implement
 ↓
build
 ↓
physical/software test
 ↓
commit
 ↓
push dev
 ↓
merge into main after validation
 ↓
push main
```

Avoid committing experimental changes directly to `main` unless intentionally doing so.

---

# 27. Important Recent Git History

Relevant recent commits include:

```text
1eeecf6
Complete Phase 2D ESP32 serial communication

a780e02
Complete Phase 5 LED hardware control

2f57d51
Update desktop optical transmission controls

91bb700
Fix receiver reset and retain decoded message

f10c3f6
Improve optical signal localization

ac8fc25
Improve optical decoder stability
```

The latest optical decoder change:

```text
ac8fc25
```

has been pushed to:

```text
origin/main
origin/dev
```

The branches were synchronized using:

```text
main → dev
```

with a fast-forward merge.

---

# 28. Development Rules

For future implementation:

1. Inspect the exact current file before editing.
2. Do not invent existing project APIs or structures.
3. Make one focused change at a time.
4. Prefer complete replacement files when a file needs substantial changes.
5. Build immediately after changes.
6. Perform practical physical testing where relevant.
7. Only commit tested changes.
8. Keep `main` stable.
9. Use `dev` for active development.
10. Do not tune optical thresholds without evidence from actual logs/tests.
11. Do not introduce advanced architecture prematurely.
12. Keep the physical optical protocol stable while implementing higher protocol layers.

---

# 29. Current Status Summary

```text
ESP32 serial communication        ✅
LED hardware                      ✅
Desktop transmitter               ✅
Physical RED transmission          ✅
Physical GREEN framing             ✅
Android CameraX receiver           ✅
RED detection                      ✅
GREEN detection                    ✅
Spatial localization               ✅
Optical synchronization             ✅
Plaintext decoding                 ✅
Repeated physical testing          ✅

Packet specification               ← CURRENT PHASE
Packet implementation              ⏳
CRC                                ⏳
Encryption                         ⏳
Security profiles                  ⏳
Retransmission                     ⏳
AI decoding                        ⏳
Advanced optimization              ⏳
```

---

# 30. Next Immediate Step

Start **Phase 9 — Packet Specification**.

Before writing packet implementation code:

1. Freeze the packet fields.
2. Freeze field sizes.
3. Freeze byte ordering.
4. Define valid payload length.
5. Define packet validation rules.
6. Define how malformed packets are rejected.
7. Define the relationship between packet framing and optical framing.
8. Document the final specification.
9. Only then implement the packet serializer/parser in Phase 10.

The current optical detection layer should remain unchanged unless new physical tests demonstrate a reproducible failure pattern.
