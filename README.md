# CipherBeam-AI

> Offline, LiFi-like optical communication using an ESP32-S3 transmitter, dual LED optical signaling, and an Android camera receiver.

## Current Project Status

**Development branch:** `dev`  
**Stable branch:** `main`  
**Current focus:** Phase 14 — end-to-end plaintext integration and demo hardening  
**Optical bit timing:** **150 ms per bit — LOCKED FOR CURRENT DEVELOPMENT**  
**Real encryption/decryption:** intentionally **deferred** for the current demo milestone  
**Last stable checkpoint:** optical geometry tracking tested and merged into `main`

---

# 1. Project Goal

CipherBeam-AI is an experimental offline optical communication system designed to send digital messages through visible light without relying on:

- Wi-Fi
- Bluetooth
- Internet access
- cellular networking during transmission

The current prototype uses:

- ESP32-S3 as the transmitter controller
- RED LED as the optical data carrier
- GREEN LED as the optical control/synchronization channel
- Android smartphone camera as the receiver
- Kotlin + CameraX on the mobile receiver
- OpenCV-assisted optical processing / geometry tracking on the receiver
- FastAPI + React on the desktop transmitter side
- USB serial communication between desktop and ESP32-S3

The project is being developed in layers. The physical optical transport is kept stable while higher-level packet, reliability, UI, and later AI features are added.

---

# 2. System Architecture

```text
                    PARTY A / SENDER

Message
   ↓
Packet creation
   ↓
CRC / packet validation
   ↓
Demo security-profile UI selection
   ↓
FastAPI
   ↓
pyserial
   ↓
ESP32-S3
   ↓
RED + GREEN LEDs
   ↓
Visible-light optical channel
   ↓
Android camera
   ↓
CameraX
   ↓
OpenCV / optical analysis
   ↓
GREEN localization
   ↓
RED localization + geometry tracking
   ↓
OpticalDecoder
   ↓
Packet parser
   ↓
CRC validation
   ↓
Received plaintext message
   ↓
Android UI
```

### Important current decision

The **UI may display a selected security profile / algorithm**, but the current transmission path is intentionally **plaintext**. Real encryption and authenticated decryption will be implemented later as a separate secure phase.

This prevents the project from spending the remaining demo time on cryptography while the optical link and end-to-end application path are being stabilized.

---

# 3. Optical Channels

| Channel | Current purpose |
|---|---|
| GREEN | Control / synchronization / frame boundaries |
| RED | Binary data transport |

GREEN is not used as payload data.

RED carries the logical packet bytes as an MSB-first binary stream.

---

# 4. Current Optical Timing

The current optical timing is deliberately kept slow enough for the smartphone camera receiver.

```text
GREEN START       600 ms
START GUARD       200 ms
RED BIT           150 ms / bit
END GUARD         200 ms
GREEN END         600 ms
```

## Timing rule

**150 ms per RED bit is locked for the current development cycle.**

Do not change this value while implementing the current integration work unless a reproducible physical test demonstrates that the timing itself is the problem.

The project may revisit adaptive speed or a different bitrate later.

---

# 5. Optical Frame Structure

```text
IDLE
  ↓
GREEN START
  ↓
START GUARD
  ↓
RED DATA
  ↓
END GUARD
  ↓
GREEN END
  ↓
IDLE
```

The optical frame is the **transport layer**. It is separate from the logical packet format.

```text
GREEN/RED optical framing
          ≠
logical CipherBeam packet framing
```

The GREEN channel provides the outer synchronization. The RED channel carries the packet bytes.

---

# 6. Receiver Processing Pipeline

The Android receiver currently processes the camera stream through the following conceptual stages:

```text
CameraX frame
     ↓
YUV analysis
     ↓
GREEN detection
     ↓
GREEN spatial localization
     ↓
RED search / localization
     ↓
RED geometry tracking
     ↓
RED/GREEN signal scoring
     ↓
temporal signal tracking
     ↓
OpticalDecoder
     ↓
raw packet bytes
     ↓
CipherBeamPacketParser
     ↓
CRC validation
     ↓
application message
```

The current geometry tracking work is considered sufficiently stable for the present development milestone and has been committed and merged.

---

# 7. Geometry Tracking Checkpoint

The recent receiver work added / stabilized:

- GREEN spatial localization
- RED local search
- RED geometry tracking relative to GREEN
- frame-to-frame position tracking
- tolerance to moderate camera movement
- continued tracking when the detected RED/ GREEN dimensions vary between frames

Observed behavior during physical tests included successful locking with changing ROI dimensions and preserved geometry offsets / rotation across subsequent frames.

### Current decision

The geometry layer is **frozen for now**.

Do not keep modifying thresholds, ROI rules, or tracking logic based on isolated failures while repeated physical tests are passing. Re-open this layer only when a reproducible failure pattern appears.

---

# 8. Optical Decoder

The optical decoder is responsible only for converting the GREEN-framed RED signal stream into raw logical bytes.

It should **not** perform:

- encryption
- decryption
- CRC policy
- UI logic
- camera geometry decisions

The intended boundary is:

```text
OpticalDecoder
      ↓
raw bytes
      ↓
PacketParser
```

This layer separation is important because later packet/security features should not require changing the camera processing stack.

---

# 9. Logical Packet Format

The current packet format is based on the following fields:

```text
┌────────┬─────────┬────────┬────────┬─────────────┬───────┐
│ SYNC   │ VERSION │ FLAGS  │ LENGTH │ PAYLOAD     │ CRC   │
│ 1 byte │ 1 byte  │ 1 byte │ 1 byte │ 0–100 bytes │ 2 byte│
└────────┴─────────┴────────┴────────┴─────────────┴───────┘
```

Current protocol constants:

```text
SYNC     = 0xA5
VERSION  = 0x01
MAX PAYLOAD = 100 bytes
CRC      = 2 bytes
```

Minimum logical packet size:

```text
1 + 1 + 1 + 1 + 2 = 6 bytes
```

Maximum logical packet size:

```text
1 + 1 + 1 + 1 + 100 + 2 = 106 bytes
```

---

# 10. Packet Layer

The packet layer has been implemented separately from the camera / optical detector.

Current responsibilities include:

- packet data structure
- packet serialization
- packet parsing
- SYNC handling
- VERSION validation
- payload-length validation
- CRC field handling
- malformed-packet rejection
- conversion between packet objects and raw byte arrays

The packet parser is intentionally independent from camera pixels and LED detection.

---

# 11. CRC / Integrity

CRC is treated as an **optical corruption detector**, not as cryptographic authentication.

Current CRC convention:

```text
CRC algorithm: CRC-16/CCITT-FALSE
Coverage: VERSION + FLAGS + LENGTH + PAYLOAD
SYNC: excluded from CRC calculation
CRC byte order: big-endian
```

Example for `HELLO`:

```text
A5 01 00 05 48 45 4C 4C 4F 6D 36
```

where:

```text
CRC = 0x6D36
```

The receiver must reject a packet when the received CRC does not match the calculated CRC.

### Validation tests include

- correct serialization / parsing
- expected CRC for known payloads
- corrupted CRC rejection
- missing SYNC rejection
- malformed packet handling
- optical transport → packet parser integration tests

---

# 12. Reliability / Retransmission

Reliability is treated as a separate layer from optical detection.

The target behavior is:

```text
Transmit packet
      ↓
Receive packet
      ↓
CRC check
      ↓
Valid → accept
Invalid → reject / retry according to reliability policy
```

Important security/reliability rules remain:

- corrupted packets must not be delivered as valid messages
- CRC failure must not silently become a successful message
- retransmission must be distinguishable from a new logical message when the full reliability protocol is enabled

For the current demo milestone, the priority is a clean one-way end-to-end path. A true optical ACK channel can be added later when a return transmitter exists on Party B.

---

# 13. Cryptography — Intentionally Deferred

## Real cryptography is NOT part of the current transmission path.

The architecture has already identified standard authenticated-encryption options such as:

- ChaCha20-Poly1305
- AES-256-GCM

Those options remain planned for the eventual secure mode.

However, **the current demo does not depend on real encryption**.

### Current UI-only behavior

The sender UI may expose:

```text
Security Profile
Algorithm selection
Encryption status
```

For example:

```text
Selected algorithm:
ChaCha20-Poly1305
```

but the actual current payload remains plaintext before packet transmission.

### Why this is intentional

The project needs a stable optical + packet + application demonstration first. Real cryptography will be added after the optical pipeline is fully integrated and the demo path is stable.

This prevents UI presentation logic from being confused with actual cryptographic security.

---

# 14. Algorithm ID / Security Profile — Current State

The packet architecture reserves metadata for future security selection.

Conceptually:

```text
Security Profile
Algorithm ID
```

These fields are currently treated as **presentation / forward-compatibility metadata only** for the demo milestone.

They must not be described as providing real confidentiality until the actual AEAD implementation is connected to transmission and receiver-side authenticated decryption is active.

---

# 15. Current Android UI

The receiver UI currently has the foundation for showing:

- camera preview
- receiver state
- optical/debug information
- decoded message
- packet information
- payload length
- CRC information
- successful packet reception state

The UI also keeps the last successfully received message visible rather than clearing it immediately when the optical decoder returns to its waiting state.

The next UI work is to make the packet / CRC / transmission status feel like one coherent receiver experience.

---

# 16. Current Desktop Sender

The desktop sender stack is:

```text
React frontend
      ↓
FastAPI backend
      ↓
pyserial
      ↓
ESP32-S3
```

The desktop side already has the foundation for:

- health checks
- serial connectivity
- hardware status
- hardware ping
- LED control
- optical transmission

The next integration task is to make the sender's packet-level behavior and UI state match the receiver's current packet / CRC model.

---

# 17. ESP32-S3 Transmitter

Current transmitter responsibilities:

- RED GPIO control
- GREEN GPIO control
- optical framing
- packet-byte transmission
- MSB-first bit output
- serial command interface

The ESP32 remains a deterministic optical transport device. Higher-level security logic should not be embedded into the LED timing state machine prematurely.

---

# 18. What Has Been Completed

## Hardware / transport

- ESP32-S3 bring-up
- USB serial communication
- desktop → FastAPI → pyserial → ESP32 path
- RED LED output
- GREEN LED output
- deterministic optical transmission

## Mobile receiver

- Android receiver application
- CameraX
- live camera analysis
- RED detection
- GREEN detection
- optical synchronization
- optical byte decoding
- packet parser integration
- GREEN localization
- RED localization
- geometry tracking
- repeated physical testing

## Packet / integrity

- packet structure
- packet serializer
- packet parser
- payload validation
- CRC-16/CCITT-FALSE
- CRC rejection tests
- optical transport → packet parser test coverage

## Git workflow

```text
main = stable checkpoint
dev  = active development
```

Current work is developed on `dev` and merged into `main` only after testing.

The geometry-tracking checkpoint has already been:

```text
dev → origin/dev
      ↓
merged into main
      ↓
origin/main
```

---

# 19. Current Development Checkpoint

```text
                 STATUS

Optical hardware                 ✅
Desktop ↔ ESP32                  ✅
CameraX receiver                ✅
RED/GREEN detection             ✅
Geometry tracking               ✅
Optical decoding                ✅
Packet serializer/parser        ✅
CRC integrity                   ✅
Current physical testing        ✅

Real encryption                 ⏸ DEFERRED
Real authenticated decryption   ⏸ DEFERRED
True optical ACK channel        ⏳ FUTURE
AI pulse classification         ⏳ FUTURE
Adaptive bitrate                ⏳ FUTURE
```

---

# 20. Current Phase — Phase 14

## Phase 14 — End-to-End Plaintext Integration + Demo Hardening

This is the active phase.

### Goal

Produce one reliable user-visible flow:

```text
Desktop message
      ↓
Packet creation
      ↓
CRC
      ↓
ESP32
      ↓
RED/GREEN optical transmission
      ↓
Android camera
      ↓
Geometry tracking
      ↓
Optical decoding
      ↓
Packet parser
      ↓
CRC validation
      ↓
Plaintext message displayed
```

### Phase 14 work order

**14A — Sender packet integration**

Make the desktop sender produce exactly the packet representation expected by the receiver.

**14B — End-to-end physical packet test**

Repeatedly send known messages and verify that the complete packet survives the optical path.

**14C — Receiver status integration**

Present packet length, CRC state, receive state, and last valid message coherently in the Android UI.

**14D — UI security presentation**

Allow the user to select/display the intended security algorithm/profile without claiming that the current payload is actually encrypted.

**14E — Demo hardening**

Test message repetition, moderate phone movement, packet rejection, and recovery after failed/invalid transmissions.

---

# 21. What We Will Do After Phase 14

## Phase 15 — Measurements / Reliability Polish

Focus on measurable behavior instead of adding new complexity.

Target measurements:

- successful transmission count
- failed transmission count
- packet error rate
- effective bitrate
- end-to-end latency
- usable operating distance
- behavior under moderate camera movement

The purpose is to turn the demo into something that can be demonstrated with numbers.

## Phase 16 — AI Dataset + Pulse Classifier

AI will be introduced only after the deterministic path is stable.

The initial AI task is intentionally narrow:

```text
camera signal window
      ↓
normalization
      ↓
pulse classifier
      ↓
P(0), P(1)
      ↓
symbol + confidence
```

The AI model will be advisory only.

The deterministic fallback remains mandatory.

AI must never bypass:

- packet validation
- CRC
- eventual AEAD authentication

## Phase 17 — AI Evaluation / Advisory Integration

Compare AI-assisted classification against the deterministic decoder using measured test data.

Potential evaluation criteria:

- symbol error rate
- packet error rate
- false positives
- confidence calibration
- improvement under selected noise conditions

AI should only be retained where it solves a measurable problem.

## Phase 18+ — Real Secure Mode / Advanced Features

After the demo path and AI evaluation are stable, revisit the deferred secure architecture:

- real ChaCha20-Poly1305 / AES-256-GCM integration
- authenticated decryption
- real Security Profile behavior
- real Algorithm ID enforcement
- session / nonce management
- fragmentation and reassembly where required
- secure retransmission semantics
- stronger key-management design

Only after the secure path is actually implemented should the project claim encrypted optical communication.

Future optimization topics may include:

- adaptive bitrate
- advanced camera controls
- improved robustness under difficult lighting
- additional optical modes
- expanded data transfer capabilities

---

# 22. Deliberate Non-Goals for the Current Milestone

The following are **not** being implemented right now unless the roadmap is explicitly changed:

- real encryption in the transmission path
- real authenticated decryption on the receiver
- changing the 150 ms optical bit timing
- unnecessary redesign of the geometry tracker
- AI before the deterministic pipeline is stable
- speculative threshold tuning without reproducible test evidence
- advanced key exchange
- complex multi-device optical ACK hardware

---

# 23. Development Rules

1. **Inspect the actual current code before editing.**
2. Make one focused change at a time.
3. Build immediately after code changes.
4. Run relevant tests before committing.
5. Perform physical tests when the change affects the optical path.
6. Only commit tested changes.
7. Develop on `dev`.
8. Merge tested `dev` work into `main`.
9. Keep `main` stable.
10. Do not change the optical timing without explicit approval / reproducible evidence.
11. Do not tune optical thresholds because of one isolated failure.
12. Do not confuse UI security indicators with real cryptographic security.
13. Keep AI advisory-only and preserve a deterministic fallback.

---

# 24. Git Workflow

Typical development cycle:

```text
# active development
 git switch dev

# make / test changes

# stage
 git add <files>

# commit
 git commit -m "<focused change>"

# push
 git push origin dev

# once stable, merge into main
 git switch main
 git pull origin main
 git merge dev
 git push origin main

# return to development
 git switch dev
```

The repository should finish each stable checkpoint with a clean working tree.

---

# 25. Current Priority

> **Finish the complete plaintext packet + CRC optical demo first.**

The next coding work should improve the connection between already-working layers rather than replace them.

```text
                    CURRENT PRIORITY

Optical detector ────────────────┐
                                 │
Optical decoder ─────────────────┤
                                 ↓
Packet + CRC ───────────────→ END-TO-END DEMO
                                 ↓
                            UI integration
                                 ↓
                            measurements
                                 ↓
                               AI
                                 ↓
                         REAL SECURE MODE
```

---

# 26. Important Note About Older Documentation

Some older README / project-summary files contain earlier phase labels and earlier assumptions, including different optical timing descriptions and statements that packet / CRC / encryption work had not yet started.

Those documents are historical snapshots and **must not be treated as the current implementation status**.

This README is the canonical project-status summary for the current development direction.
