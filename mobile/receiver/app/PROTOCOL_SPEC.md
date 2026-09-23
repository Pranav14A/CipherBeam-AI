# CipherBeam-AI Optical Protocol Specification

**Protocol Version:** 1  
**Phase:** 9 — Packet Specification  
**Status:** Draft for implementation

---

## 1. Purpose

This document defines the logical packet format used by CipherBeam-AI.

The packet layer sits above the existing physical optical transport.

```text
Application Message
        ↓
CipherBeam Packet
        ↓
Optical Bit Encoding
        ↓
RED/GREEN LED Transport
2. Physical Optical Transport

The existing physical transport remains unchanged.

Control channel
GREEN LED

GREEN is used for:

frame start
frame end
synchronization
Data channel
RED LED

RED carries the packet bytes as binary data.

3. Physical Frame

The current physical frame is:

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

Current timing:

GREEN START       600 ms
START GUARD       200 ms
RED BIT           200 ms
END GUARD         200 ms
GREEN END         600 ms

The physical optical framing is not part of the logical packet bytes.

4. Logical Packet Format

The logical packet is:

┌────────┬─────────┬────────┬────────┬─────────────┬───────┐
│ SYNC   │ VERSION │ FLAGS  │ LENGTH │ PAYLOAD     │ CRC   │
│ 1 byte │ 1 byte  │ 1 byte │ 1 byte │ 0–100 bytes │ 2 byte│
└────────┴─────────┴────────┴────────┴─────────────┴───────┘

Minimum packet size:

6 bytes

Maximum packet size:

106 bytes
5. Field Definitions
5.1 SYNC

Size:

1 byte

Value:

0xA5

Purpose:

identifies the beginning of a logical packet
allows the packet parser to find packet boundaries
provides a fixed known byte for parser synchronization

The SYNC byte is a logical packet field.

It does not replace GREEN optical frame synchronization.

5.2 VERSION

Size:

1 byte

Current value:

0x01

Purpose:

identifies the packet format version
allows future protocol versions to coexist

For Phase 9/10:

VERSION = 0x01

A receiver that does not support the version must reject the packet.

5.3 FLAGS

Size:

1 byte

The FLAGS byte is reserved for security and algorithm metadata.

Current layout:

bit 7  bit 6  bit 5  bit 4 | bit 3  bit 2  bit 1  bit 0
       Security Profile     |        Algorithm ID

Therefore:

bits 7–4 = Security Profile
bits 3–0 = Algorithm ID

For the initial plaintext implementation:

Security Profile = 0
Algorithm ID      = 0

FLAGS = 0x00

The complete security-profile and algorithm assignments will be finalized during Phase 12.

5.4 LENGTH

Size:

1 byte

Meaning:

Number of bytes in PAYLOAD

Valid range:

0–100

Examples:

LENGTH = 0x00

means zero payload bytes.

LENGTH = 0x05

means five payload bytes.

The receiver must reject a packet whose declared payload length exceeds:

100 bytes
5.5 PAYLOAD

Size:

0–100 bytes

The payload contains the application data.

For the initial plaintext implementation, payload bytes are printable ASCII.

Example:

HELLO

is:

48 45 4C 4C 4F

Therefore:

LENGTH = 05
PAYLOAD = 48 45 4C 4C 4F

Later phases may allow encrypted binary payloads.

Therefore packet parsing must treat PAYLOAD as bytes rather than assuming it is always a string.

5.6 CRC

Size:

2 bytes

Purpose:

detect optical transmission errors
detect corrupted packet data
prevent corrupted payloads from being delivered to the application

The exact CRC algorithm and byte-order convention will be frozen during Phase 13.

Until then, the packet specification reserves exactly:

2 bytes

for CRC.

The CRC is calculated over:

VERSION
FLAGS
LENGTH
PAYLOAD

The SYNC byte is not included in the CRC calculation.

6. Example Packet

For:

HELLO

the logical packet before CRC is:

A5 01 00 05 48 45 4C 4C 4F

where:

A5 = SYNC
01 = VERSION
00 = FLAGS
05 = LENGTH
48 = H
45 = E
4C = L
4C = L
4F = O

The final packet is:

A5 01 00 05 48 45 4C 4C 4F XX XX

where:

XX XX = CRC

The CRC value is intentionally not specified in Phase 9.

7. Packet Parsing Order

The receiver must parse packets in this order:

1. Find SYNC
       ↓
2. Read VERSION
       ↓
3. Validate VERSION
       ↓
4. Read FLAGS
       ↓
5. Read LENGTH
       ↓
6. Validate LENGTH
       ↓
7. Read PAYLOAD
       ↓
8. Read CRC
       ↓
9. Verify CRC
       ↓
10. Deliver valid packet

A packet must not be delivered to the application before integrity validation is completed.

8. Packet Rejection Rules

A packet must be rejected if:

SYNC cannot be found
VERSION is unsupported
LENGTH is greater than 100
required packet bytes are missing
CRC validation fails
the packet is otherwise structurally invalid

Rejected packets must not be delivered as valid application messages.

9. Optical Encoding of the Packet

The logical packet bytes are converted into the existing RED optical bit stream.

Each byte is transmitted:

8 bits
MSB first

Example:

0x48

01001000

Optical mapping:

1 → RED ON
0 → RED OFF

Each bit currently lasts:

200 ms

Therefore the packet is transmitted byte-by-byte as one continuous RED bit stream.

No additional optical gap is inserted between bytes.

10. Separation of Layers

CipherBeam-AI uses the following separation:

┌──────────────────────────────┐
│ Application                  │
│ Message / encrypted data     │
├──────────────────────────────┤
│ Packet                       │
│ SYNC VERSION FLAGS LENGTH    │
│ PAYLOAD CRC                  │
├──────────────────────────────┤
│ Optical Transport            │
│ GREEN synchronization        │
│ RED binary transmission      │
├──────────────────────────────┤
│ Hardware                     │
│ ESP32-S3 + LEDs + Camera     │
└──────────────────────────────┘

The optical decoder should not perform encryption or CRC validation.

The packet layer should not depend on camera pixels or LED detection.

11. Initial Plaintext Mode

The first packet implementation will use:

VERSION = 0x01
FLAGS   = 0x00

This means:

Security Profile = 0
Algorithm ID = 0

The payload is plaintext application data.

This mode exists to validate packet transport before cryptography is introduced.

12. Maximum Payload

Maximum payload:

100 bytes

Maximum logical packet:

1 + 1 + 1 + 1 + 100 + 2
= 106 bytes

At the current optical bitrate:

200 ms / bit

each byte requires:

8 × 200 ms
= 1.6 seconds

Therefore the maximum payload alone requires:

100 × 1.6 s
= 160 seconds

This is intentionally conservative for the current prototype.

The maximum payload may be reduced or the optical bitrate increased in later optimization phases.

13. Phase 9 Scope

Phase 9 defines the packet structure only.

Phase 9 does not implement:

packet serialization
packet parsing code
CRC algorithm
encryption
retransmission
authentication
adaptive bitrate
AI decoding

Those are handled in later phases.

14. Next Phase
Phase 10 — Packet Implementation

Phase 10 will implement:

Packet data structure
       ↓
Packet serializer
       ↓
Packet parser
       ↓
Validation

The implementation must follow this specification exactly unless this document is intentionally revised first.

15. Future Extensions

The packet structure intentionally leaves room for:

encrypted payloads
different security profiles
different cryptographic algorithms
authenticated encryption
retransmission metadata
future protocol versions
binary application data

Any change to the packet layout should increment the protocol version or be explicitly documented as a backward-compatible change.

16. Status
Phase 9 specification: IN PROGRESS
Packet implementation: NOT STARTED
CRC: NOT STARTED
Encryption: NOT STARTED
Security Profiles: NOT STARTED
Retransmission: NOT STARTED