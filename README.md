# CipherBeam-AI — Mobile Receiver

## Current Project Status

**Project:** CipherBeam-AI  
**Component:** Android Mobile Receiver  
**Current Phase:** Phase 8 — Optical Signal Detection  
**Next Phase:** Phase 9 — Packet Specification  
**Status:** Phase 8 implementation is working in repeated physical tests.

---

# 1. Mobile Receiver Goal

The Android application acts as the optical receiver for CipherBeam-AI.

The phone camera observes two optical channels:

- **GREEN LED** → control / frame synchronization
- **RED LED** → data bits

The Android pipeline is:

```text
CameraX Preview
        +
CameraX ImageAnalysis
        ↓
Live Camera Frames
        ↓
RED + GREEN Detection
        ↓
Spatial LED Localization
        ↓
Temporal Signal Processing
        ↓
Optical Decoder
        ↓
Decoded Plaintext Message
```

The current receiver is intentionally implemented without:

- OpenCV
- Internet/network communication
- AI/ML decoding
- Bluetooth
- Wi-Fi communication

---

# 2. Android Project Configuration

## Build stack

- Android Gradle Plugin: `8.5.2`
- Kotlin: `2.0.0`
- Compose BOM: `2024.06.00`
- CameraX: `1.3.4`
- compileSdk: `34`
- targetSdk: `34`
- minSdk: `33`
- Java/Kotlin JVM target: `17`

Android Studio Gradle JVM is configured to use **JVM 21**, because the project's Gradle version does not support Java 25.

---

# 3. Current Camera Pipeline

CameraX provides:

```text
Preview
ImageAnalysis
```

`ImageAnalysis` continuously supplies YUV camera frames.

The receiver extracts chroma information from the camera image and calculates RED/GREEN colour scores.

The current implementation uses YUV-derived colour information rather than relying on raw RGB conversion.

---

# 4. Optical Channels

## GREEN — Control Channel

GREEN is used for:

- Frame START
- Frame END
- Synchronization

GREEN is not currently used for payload data.

## RED — Data Channel

RED carries the actual binary payload.

```text
RED ON  = 1
RED OFF = 0
```

Each bit currently lasts approximately:

```text
200 ms
```

---

# 5. Physical Optical Protocol — Phase 7

The current physical frame is:

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

Timing:

```text
GREEN START       = 600 ms
START GUARD       = 200 ms
RED BIT           = 200 ms
END GUARD         = 200 ms
GREEN END         = 600 ms
```

RED data uses:

```text
8-bit ASCII
MSB first
200 ms per bit
```

Example:

```text
HELLO

H = 01001000
E = 01000101
L = 01001100
L = 01001100
O = 01001111
```

The current optical layer does not yet contain CRC, encryption, retransmission, or Algorithm ID.

---

# 6. Optical Decoder

Main file:

```text
app/src/main/java/com/cipherbeam/receiver/optical/OpticalDecoder.kt
```

Current decoder states:

```text
WAITING_FOR_START
START_DETECTED
WAITING_FOR_DATA
RECEIVING_PAYLOAD
MESSAGE_COMPLETE
```

The decoder:

1. Detects sustained GREEN.
2. Waits for GREEN to turn OFF.
3. Waits for the 200 ms START guard.
4. Samples RED in 200 ms windows.
5. Converts RED windows into bits.
6. Groups bits into 8-bit bytes.
7. Accepts printable ASCII.
8. Detects GREEN END.
9. Produces a completed plaintext message.

The decoder also contains a small RED temporal-stability filter to reduce short RED flicker before the state machine consumes the signal.

The latest decoder stability change is:

```text
Commit: ac8fc25
Message: Improve optical decoder stability
```

---

# 7. Signal Processing

Main files:

```text
app/src/main/java/com/cipherbeam/receiver/optical/SignalProcessing.kt
app/src/main/java/com/cipherbeam/receiver/camera/RedLedAnalyzer.kt
```

## RED detection

The RED score is derived from YUV chroma:

```text
rawR = (V - U) / 255
```

The value is constrained to a safe range.

The RED tracker maintains:

- baseline
- envelope
- threshold
- ON/OFF state

Current typical values observed during successful testing:

```text
RED baseline       ≈ 0.04
RED threshold      ≈ 0.094–0.096
Strong RED         ≈ 0.10–0.33
Background RED     ≈ 0.02–0.055
```

These are observed diagnostic values, not protocol constants.

---

# 8. GREEN Detection

GREEN is detected separately from RED.

The current implementation uses:

- YUV colour information
- coarse spatial grid search
- temporal signal behaviour
- spatial margin

GREEN detection is also used to re-anchor RED localization.

This prevents the receiver from assuming that the RED LED remains at one fixed screen coordinate.

---

# 9. LED Spatial Localization

The receiver does not use a single fixed ROI.

Instead:

## GREEN

A coarse grid is searched for the strongest GREEN candidate.

Current grid:

```text
5 × 5
```

## RED

RED is searched locally around the previous RED position.

The RED search can move as the phone/camera view changes.

The current implementation allows the RED localization to follow the LED across substantially different parts of the camera frame.

---

# 10. Phase 8 Testing

Phase 8 has been physically tested using the Android receiver and the physical ESP32 + LED transmitter.

Observed camera analysis rate:

```text
approximately 22–28 FPS
```

Successful tests included:

- repeated message transmission
- `HELLO`
- `TEST`
- moving the phone vertically from top to bottom
- changing the LED location within the camera frame
- RED/ GREEN detection across different screen areas

Recent diagnostic logs showed:

```text
RED ON:
rawR ≈ 0.10–0.33

RED OFF:
rawR ≈ 0.02–0.055

GREEN:
GREEN=1 while RED=0
```

The receiver successfully decoded the repeated tests.

There was an occasional isolated decoding error such as:

```text
hELLO
```

but subsequent repeated transmissions passed successfully.

Therefore Phase 8 is considered **working and sufficiently stable for the current demo milestone**.

The system is not being claimed to have 100% optical reliability yet.

---

# 11. Important Optical Behaviour

Large camera-angle changes can sometimes affect RED detection because of optical geometry and LED viewing angle.

Small angle changes have generally worked.

Moving the phone position across the camera frame has worked successfully in recent tests.

The current strategy is therefore:

> Do not continuously modify the optical detector based on isolated failures while the repeated physical tests are passing.

The detector should remain frozen while development moves to the packet layer.

---

# 12. Current UI Behaviour

The Android UI keeps the last successfully received message visible.

The important logic is:

```text
completedMessage
      ↓
lastReceivedMessage
      ↓
UI continues displaying the last decoded message
```

This prevents the message from disappearing immediately when the decoder returns to a waiting state.

---

# 13. Current Architecture

```text
Android CameraX
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
Plaintext message
```

The next architectural layer will be added after the optical decoder:

```text
OpticalDecoder
       ↓
Packet Parser
       ↓
CRC Verification
       ↓
Decryption
       ↓
Application Message
```

---

# 14. Current Git State

The mobile receiver uses:

```text
main = stable tested code
dev  = active development
```

Latest mobile optical decoder commit:

```text
ac8fc25
Improve optical decoder stability
```

The commit was pushed to both:

```text
origin/main
origin/dev
```

Both branches were synchronized after merging:

```text
main → dev
```

---

# 15. Completed Mobile Work

## Phase 7 — Basic Phone Camera Receiver

Completed:

- Android receiver project
- CameraX integration
- live camera preview
- camera frame analysis
- RED detection
- GREEN detection
- optical timing
- basic message decoding
- transmitter-to-phone physical testing
- persistent decoded-message UI

## Phase 8 — Optical Signal Detection

Completed:

- RED signal tracking
- GREEN signal tracking
- adaptive baseline
- RED temporal stability filtering
- GREEN spatial localization
- RED spatial localization
- moving LED localization
- frame-to-frame signal tracking
- physical repeated testing

---

# 16. Not Implemented Yet

The following are intentionally not implemented yet:

```text
Packet specification             ← NEXT
Packet serialization/parsing
CRC
Encryption
Security Profiles
Algorithm ID
Retransmission
Reliability protocol
OpenCV optimization
AI pulse classification
AI adaptive transmission
Advanced camera controls
Adaptive bitrate
```

---

# 17. Next Phase — Phase 9

Phase 9 is **Packet Specification**.

The purpose is to define the binary packet that will travel through the existing optical transport.

Proposed initial structure:

```text
┌────────┬─────────┬────────┬────────┬─────────────┬───────┐
│ SYNC   │ VERSION │ FLAGS  │ LENGTH │ PAYLOAD     │ CRC   │
│ 1 byte │ 1 byte  │ 1 byte │ 1 byte │ 0–100 bytes │ 2 byte│
└────────┴─────────┴────────┴────────┴─────────────┴───────┘
```

This has not yet been implemented.

Important distinction:

```text
GREEN/RED optical framing
        ≠
logical packet framing
```

The existing physical GREEN/RED protocol will remain the transport mechanism.

The packet will become the payload carried by that transport.

---

# 18. Planned Development Order

```text
Phase 8
Optical Signal Detection
        ↓
Phase 9
Packet Specification
        ↓
Phase 10
Packet Implementation
        ↓
Phase 11
Cryptography
        ↓
Phase 12
Security Profiles + Algorithm ID
        ↓
Phase 13
CRC + Reliability + Retransmission
        ↓
Phase 14+
Integration / optimization / AI
```

The current priority is a reliable working demo rather than completing every advanced feature before the demo.

---

# 19. Development Rule

For future changes:

1. Inspect the exact current file.
2. Make one focused change.
3. Build/test immediately.
4. Report the test result.
5. Commit only tested changes.
6. Keep `main` stable.
7. Develop new work on `dev`.
8. Merge tested `dev` work into `main`.

Do not change optical detection parameters without a concrete failure pattern from physical testing.
