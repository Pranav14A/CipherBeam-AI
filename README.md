# CipherBeam AI

CipherBeam AI is an offline optical (visible-light) communication system.
Two parties exchange encrypted messages over a light link — no internet,
Wi-Fi, Bluetooth, or cellular data involved.

## Party A — Desktop Control Station

A Windows laptop running a React frontend and a FastAPI backend, connected
to an ESP32-S3 over USB. Party A performs message encryption and
packetization before handing data to the ESP32 for optical transmission.

## ESP32-S3 — Optical Transmitter

Receives already-encrypted, packetized data from Party A over serial and
converts it into optical pulses: a red LED for data, a green LED for
control/framing. The ESP32 does not perform encryption.

## Optical Communication

The physical channel between transmitter and receiver is visible light.
See `docs/architecture/architecture-decision-document.md` for the approved
encoding scheme (Manchester, frame-synchronous) and packet design.

## Party B — Android Receiver

A Samsung Galaxy M52 running a React Native + TypeScript application. Its
camera observes the transmitter's LEDs, and a native (CameraX/OpenCV)
pipeline reconstructs the optical signal, decodes packets, and decrypts
messages.

## High-Level Architecture

```
User message → React frontend → FastAPI backend → encryption →
packetization → serial → ESP32-S3 → optical transmission →
phone camera → signal processing → packet parsing →
authentication → decryption → React Native UI
```

Full architectural detail — optical encoding tradeoffs, packet format,
cryptography, key/session management, camera receiver design, and the
project's non-negotiable security invariants — is documented in
[`docs/architecture/architecture-decision-document.md`](docs/architecture/architecture-decision-document.md).

## Current Development Phase

**Phase 1 — Project Foundation.** Only a health-check path exists:

```
React frontend → HTTP → FastAPI backend → GET /health → response displayed in React
```

No encryption, packetization, optical communication, ESP32 firmware,
camera processing, or AI is implemented yet.

## Technology Stack

| Component | Stack |
|---|---|
| Desktop frontend | React, Vite, TypeScript |
| Desktop backend | Python 3.12, FastAPI, Pydantic, uv, pytest |
| ESP32 firmware | ESP32-S3, Arduino framework (not yet implemented) |
| Mobile receiver | React Native, TypeScript, Android (not yet initialized) |
| Cryptography | ChaCha20-Poly1305 (default) / AES-256-GCM (alternate) — not yet implemented |

## Development Roadmap

See `docs/architecture/architecture-decision-document.md` for the full
20-phase roadmap. High-level sequence: repository/architecture →
desktop foundation (current) → ESP32 serial + LED control → optical
transmitter → phone camera receiver → protocol → packetization →
cryptography → security profiles → reliability/CRC → React Native
receiver UI → OpenCV optimization → AI pulse classification → AI
adaptive transmission → full integration → testing → performance.

## Repository Structure

```
CipherBeam-AI/
├── desktop/
│   ├── frontend/   React + Vite + TypeScript
│   └── backend/    FastAPI + Pydantic (uv-managed)
├── mobile/
│   └── receiver/   React Native (not yet initialized)
├── firmware/
│   └── esp32/      ESP32-S3 firmware (not yet implemented)
├── protocol/       Specification, schemas, test vectors (not yet written)
├── ai/             Datasets, training, models, evaluation (not yet implemented)
├── tests/          unit, integration, protocol, crypto, hardware
└── docs/           architecture, protocol, hardware, security, testing
```
