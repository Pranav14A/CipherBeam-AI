# CipherBeam-AI

### Offline Visible-Light Communication with AI-Assisted Optical Decoding

CipherBeam-AI is an experimental **air-gapped visible-light communication system** that demonstrates encrypted-communication architecture over a physical optical channel instead of Wi-Fi, Bluetooth, or the Internet.

The project uses a computer-controlled ESP32-S3 to transmit data through **visible LED light**, while an Android device uses its camera to observe and decode the optical signal.

> **No Wi-Fi. No Bluetooth. No Internet connection is required for the optical communication channel.**

---

## Overview

CipherBeam-AI is designed around a simple idea:

**Can two devices communicate using nothing except visible light between them?**

The current system consists of:

```text
┌───────────────────────┐
│   DESKTOP SENDER UI   │
│      React + Vite     │
└───────────┬───────────┘
            │ HTTP
            ▼
┌───────────────────────┐
│     FASTAPI BACKEND    │
└───────────┬───────────┘
            │ USB Serial
            ▼
┌───────────────────────┐
│       ESP32-S3         │
│                       │
│  GREEN → CONTROL      │
│  RED   → DATA         │
└───────────┬───────────┘
            │
            │ Visible Light
            ▼
┌───────────────────────┐
│    ANDROID CAMERA      │
│      CameraX/YUV       │
└───────────┬───────────┘
            ▼
┌───────────────────────┐
│ OPTICAL DECODER       │
│ Signal Processing     │
│ Frame Detection       │
└───────────┬───────────┘
            ▼
┌───────────────────────┐
│ PACKET PARSER         │
│ CRC Validation        │
└───────────┬───────────┘
            ▼
┌───────────────────────┐
│ RECEIVER DASHBOARD    │
└───────────────────────┘
```

---

# Current Features

## Desktop Control Interface

The desktop sender is built with:

* React
* Vite
* TypeScript
* CSS
* FastAPI backend

The interface provides:

* Backend health monitoring
* ESP32 hardware status
* Serial connection information
* Hardware ping
* Manual LED control
* Message transmission
* Algorithm selection
* Transmission status
* Packet-flow visualization
* Optical-channel visualization
* Hardware diagnostics
* Matrix/engineering diagnostics
* Binary data visualization

The UI is intentionally designed as a **cinematic optical communication control console**, rather than a generic CRUD dashboard.

---

# Optical Communication Channel

CipherBeam uses two optical channels:

| LED      | Role              |
| -------- | ----------------- |
| 🟢 GREEN | Control / framing |
| 🔴 RED   | Data              |

The two channels are deliberately separated.

The basic transmission sequence is:

```text
GREEN START
     ↓
GREEN OFF GUARD
     ↓
RED DATA
     ↓
RED OFF GUARD
     ↓
GREEN END
```

The system does not rely on simultaneous red and green illumination.

---

# Optical Timing

The current validated optical bit duration is:

```text
150 ms per RED data bit
```

This timing is part of the currently validated optical protocol and should not be changed casually.

Other current timing parameters include:

```text
GREEN START       600 ms
GREEN END         600 ms
OPTICAL GUARD     200 ms
RED DATA BIT      150 ms
```

The desktop backend calculates the expected transmission timeout from the message size and protocol overhead.

---

# Packet Protocol

CipherBeam packets currently use the following structure:

```text
SYNC       1 byte
VERSION    1 byte
FLAGS      1 byte
LENGTH     1 byte
PAYLOAD    0–100 bytes
CRC        2 bytes
```

### Packet layout

```text
┌──────┬─────────┬───────┬────────┬─────────┬─────┐
│ SYNC │ VERSION │ FLAGS │ LENGTH │ PAYLOAD │ CRC │
│ 1 B  │   1 B   │  1 B  │   1 B  │ 0–100 B │ 2 B │
└──────┴─────────┴───────┴────────┴─────────┴─────┘
```

### SYNC

```text
0xA5
```

### VERSION

Current protocol version:

```text
0x01
```

### FLAGS

The low nibble identifies the selected algorithm.

Current algorithm IDs:

```text
0x01 → ChaCha20-Poly1305
0x02 → AES-256-GCM
```

The current implementation uses these IDs as **algorithm metadata only**.

The payload is currently transmitted as plaintext.

Actual cryptographic encryption/decryption is intentionally not part of the current transmission implementation.

---

# CRC

CipherBeam uses:

```text
CRC-16/CCITT-FALSE
```

Parameters:

```text
Polynomial : 0x1021
Initial    : 0xFFFF
RefIn      : false
RefOut     : false
XorOut     : 0x0000
```

CRC coverage:

```text
VERSION + FLAGS + LENGTH + PAYLOAD
```

The `SYNC` byte is excluded from CRC calculation.

CRC is transmitted in **big-endian** order.

Example:

```text
A5 01 01 01 41 AE 90
```

For this packet:

```text
SYNC       = A5
VERSION    = 01
FLAGS      = 01
LENGTH     = 01
PAYLOAD    = 41
CRC        = AE90
```

A valid packet is accepted only after CRC verification.

---

# Example Packets

### HELLO — no algorithm metadata

```text
A5 01 00 05 48 45 4C 4C 4F 6D 36
```

### HELLO — Algorithm ID 1

```text
A5 01 01 05 48 45 4C 4C 4F D5 57
```

### HELLO — Algorithm ID 2

```text
A5 01 02 05 48 45 4C 4C 4F 0D D5
```

### Single-byte payload `A`

```text
A5 01 01 01 41 AE 90
```

A successful parser result looks like:

```text
PACKET SUCCESS:
startIndex=0
bytesConsumed=7
version=1
flags=0x01
length=1
crc=0xae90
```

---

# Algorithm Profiles

CipherBeam currently defines two algorithm profiles.

### Algorithm 1

```text
ID: 0x01
Name: ChaCha20-Poly1305
```

### Algorithm 2

```text
ID: 0x02
Name: AES-256-GCM
```

At the current development stage, selecting an algorithm changes the metadata carried by the packet.

It does **not** perform actual encryption yet.

This allows the optical protocol, packet parser, receiver routing, and UI architecture to be developed independently from the cryptographic implementation.

---

# ESP32-S3 Firmware

The ESP32-S3 acts as the physical optical transmitter.

Current hardware control:

```text
GPIO4 → RED LED
GPIO5 → GREEN LED
```

The ESP32 receives commands from the desktop backend through USB serial.

Example command:

```text
TRANSMIT:1:HELLO
```

or:

```text
TRANSMIT:2:HELLO
```

The firmware:

1. Receives the transmission command.
2. Builds the CipherBeam packet.
3. Calculates CRC.
4. Generates the optical framing sequence.
5. Uses the GREEN LED for control/framing.
6. Uses the RED LED for data.
7. Transmits the packet using the validated optical timing.
8. Reports transmission completion over serial.

---

# Desktop Backend

The backend is implemented using:

* Python
* FastAPI
* pyserial

Current API endpoints:

### Health

```http
GET /health
```

### Hardware status

```http
GET /hardware/status
```

### Hardware ping

```http
POST /hardware/ping
```

### Manual LED control

```http
POST /hardware/led
```

### Optical transmission

```http
POST /hardware/transmit
```

The backend communicates with the ESP32-S3 through the USB serial interface.

---

# Android Receiver

The receiver is being developed as a native Android application.

Current optical receiver pipeline:

```text
CameraX
   ↓
ImageProxy / YUV
   ↓
Red / Green Detection
   ↓
Optical Signal Processing
   ↓
OpticalDecoder
   ↓
Raw Bytes
   ↓
CipherBeamPacketParser
   ↓
CRC Validation
   ↓
Valid Packet
   ↓
Receiver Dashboard
```

The Android receiver is designed to recover the optical signal from camera frames rather than using a conventional digital communication interface.

---

# Optical Decoder

The optical decoder is responsible for interpreting the camera feed.

Conceptually:

```text
Camera Frame
     ↓
ROI Detection
     ↓
RED / GREEN Signal Extraction
     ↓
Signal Stabilization
     ↓
Frame / Guard Detection
     ↓
Bit Timing
     ↓
Byte Reconstruction
     ↓
Packet Parser
```

The decoder distinguishes between:

```text
GREEN → control/framing
RED   → data
```

The receiver then passes reconstructed bytes to the packet parser.

---

# Project Structure

```text
CipherBeam-AI/
│
├── desktop/
│   ├── backend/
│   │   └── app/
│   │
│   └── frontend/
│       └── src/
│
├── mobile/
│   └── receiver/
│
├── firmware/
│   └── esp32/
│       └── led_control/
│
├── protocol/
│
├── ai/
│
├── tests/
│
└── docs/
```

---

# Hardware

The current prototype is based around:

### Transmitter

* ESP32-S3
* Red LED
* Green LED
* USB serial connection
* External power where required

### Receiver

* Android smartphone
* Camera

The prototype hardware is intended to evolve into a dedicated physical enclosure containing the ESP32-S3, optical transmitters, power system, and camera-facing optical geometry.

---

# Development Environment

Current development environment includes:

```text
Python        3.12.x
FastAPI       0.141.1
uv            0.12.5
Node.js       22.x
npm           11.x
```

The desktop frontend uses:

```text
React
Vite
TypeScript
```

---

# Running the Desktop Application

## Backend

Navigate to:

```powershell
cd "C:\Users\Pranav Raj Wardhan\Desktop\CipherBeam-AI\desktop\backend"
```

Start the FastAPI backend using the project's configured environment.

The backend should become available at:

```text
http://localhost:8000
```

Health check:

```text
http://localhost:8000/health
```

---

## Frontend

Navigate to:

```powershell
cd "C:\Users\Pranav Raj Wardhan\Desktop\CipherBeam-AI\desktop\frontend"
```

Install dependencies if required:

```powershell
npm install
```

Start the Vite development server:

```powershell
npm run dev
```

---

# Serial Hardware

During development, the ESP32-S3 has been tested through a CH343 USB-to-serial interface.

The development configuration has used:

```text
COM3
```

The actual COM port can differ depending on the Windows environment.

The firmware configuration currently uses:

```text
USB CDC On Boot: Disabled
```

---

# Testing

The project has been developed incrementally with hardware and protocol checkpoints.

Previously validated areas include:

* Backend health endpoint
* Hardware connection detection
* ESP32 serial communication
* Backend → ESP32 command transmission
* Manual LED control
* RED/GREEN optical sequencing
* Packet construction
* CRC-16/CCITT-FALSE
* Algorithm metadata IDs
* Transmission completion feedback
* Desktop transmission UI
* Optical chamber visualization
* Android optical decoding pipeline
* Packet parsing and CRC validation

Backend test checkpoints have also been maintained throughout development.

---

# Design Philosophy

CipherBeam-AI is intentionally being developed as a **physical communication system**, not simply a software simulation.

The architecture separates:

```text
UI
 ↓
Backend
 ↓
Hardware
 ↓
Optical Channel
 ↓
Camera
 ↓
Signal Processing
 ↓
Protocol
 ↓
Application
```

This separation makes it possible to independently improve:

* Optical hardware
* Signal processing
* Packet protocol
* Receiver algorithms
* Cryptographic implementation
* User interface
* AI-assisted decoding

without coupling every subsystem together.

---

# Security Direction

The long-term goal is to support authenticated encrypted communication over the optical channel.

The current architecture already reserves packet metadata for algorithm selection.

Planned cryptographic profiles include:

```text
ChaCha20-Poly1305
AES-256-GCM
```

However, the current prototype intentionally keeps the payload plaintext while the physical communication and protocol layers are stabilized.

Future security work can introduce:

```text
Key management
      ↓
Encryption
      ↓
Authentication
      ↓
Algorithm negotiation
      ↓
Secure packet transmission
```

without requiring a complete redesign of the optical transport layer.

---

# Current Development Status

### Desktop

**Operational**

* React/Vite sender interface
* FastAPI backend
* ESP32 serial communication
* LED control
* Optical transmission
* Algorithm metadata selection
* Transmission feedback
* Engineering diagnostics

### Firmware

**Operational**

* ESP32-S3 control
* RED data LED
* GREEN control LED
* Packet generation
* CRC generation
* Optical transmission

### Protocol

**Operational**

* Packet framing
* Versioning
* Flags
* Algorithm IDs
* Payload length
* CRC validation

### Android Receiver

**Active development**

* Camera acquisition
* Optical signal detection
* RED/GREEN processing
* Optical decoding
* Packet parsing
* Receiver dashboard

### Cryptography

**Not yet implemented**

Algorithm IDs currently represent metadata only.

---

# Roadmap

## Phase 1 — Core Architecture

* [x] Repository structure
* [x] Desktop frontend
* [x] FastAPI backend
* [x] ESP32 serial communication
* [x] Basic hardware control

## Phase 2 — Optical Transmission

* [x] RED data channel
* [x] GREEN control channel
* [x] Optical framing
* [x] Packet generation
* [x] CRC validation
* [x] Algorithm metadata

## Phase 3 — Android Receiver

* [x] Camera pipeline
* [x] Optical signal extraction
* [x] RED/GREEN detection
* [x] Optical decoding
* [x] Packet parsing
* [x] CRC validation

## Phase 4 — Integrated System

* [x] Desktop → FastAPI → ESP32
* [x] Optical transmission
* [x] Receiver pipeline
* [x] Transmission feedback
* [x] Desktop control interface

## Phase 5 — Next Development

* [ ] Improve optical reliability
* [ ] Improve receiver synchronization
* [ ] Improve camera movement tolerance
* [ ] Improve signal processing
* [ ] Complete end-to-end sender → optical → receiver testing
* [ ] Implement actual cryptographic payload protection
* [ ] Key management
* [ ] Physical enclosure refinement
* [ ] Dedicated hardware prototype
* [ ] Performance characterization

---

# Important Development Constraints

The following are part of the currently validated system and should not be changed casually:

### Optical timing

```text
150 ms / RED data bit
```

### Optical channel roles

```text
GREEN → control/framing
RED   → data
```

### Packet synchronization

```text
SYNC = 0xA5
```

### Protocol version

```text
VERSION = 0x01
```

### Maximum payload

```text
100 bytes
```

### CRC

```text
CRC-16/CCITT-FALSE
```

Changes to these should be treated as **protocol changes**, not ordinary UI or refactoring changes.

---

# Vision

CipherBeam-AI explores a communication architecture where information moves through a physical optical path rather than conventional wireless networking.

The goal is to evolve the prototype from:

```text
LED blinking
```

into:

```text
Reliable optical communication
        ↓
Structured protocol
        ↓
Authenticated encryption
        ↓
AI-assisted optical decoding
        ↓
A complete air-gapped communication system
```

The project combines:

**Embedded Systems + Computer Vision + Communication Protocols + AI + Cybersecurity**

to build a communication system from the physical layer upward.

---

## Author

**Pranav Raj Wardhan**

B.Tech — Computer Science & Engineering

CipherBeam-AI is an independent experimental engineering project focused on visible-light communication, embedded systems, computer vision, and secure communication architecture.
