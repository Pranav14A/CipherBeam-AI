# CipherBeam AI — Architecture Decision Document (Revised)

**Status:** Overall architecture approved. Implementation is **not** approved. This revision incorporates the required changes; still no code, no packages, no protocol implementation.
**Scope:** Optical encoding, packet protocol, cryptography, key/session management, message/fragmentation model, camera receiver architecture, security invariants.

---

## 1. Optical Encoding

### 1.1 Governing constraint (unchanged)

A smartphone camera is a frame-rate-limited sampler, not a photodiode. Any encoding scheme that depends on sub-frame timing precision fights the hardware; any scheme that only needs to know *which frame* a transition landed in works with it. This is still why Manchester is the Phase 1 choice.

### 1.2 KEPT: Manchester encoding for Phase 1

No change to the recommendation. Manchester remains the Phase 1 optical encoding: self-clocking (a guaranteed transition every bit period means a missing expected transition is itself a detectable fault), DC-balanced (average brightness is data-independent, avoiding AGC/auto-exposure drift chasing the signal), and matched to what a frame-rate-limited sensor can resolve.

### 1.3 Manchester timing terminology

The earlier draft's shorthand ("2 frame-slots per bit") undersold the real design space and should not be used if the implementation ends up sampling multiple frames per half-bit. Precise terms, defined once and used consistently from here on:

| Term | Definition |
|---|---|
| **Camera sampling interval** | The time between two consecutive camera frames, determined by the camera's frame rate: `1 / FPS`. At 30 fps this is ≈33.3 ms; at 60 fps ≈16.7 ms. This is a hardware-determined constant for a given capture configuration, not a protocol parameter. |
| **Frame** | A single captured image delivered by the camera pipeline (one CameraX `ImageAnalysis` callback). The receiver's only usable observation of the LED's state is "what did frame N show," never a continuous signal. |
| **Half-bit period** | The duration during which the LED holds one constant level within a single Manchester-encoded bit. Each bit consists of exactly two half-bit periods: one "high" and one "low" (in either order, depending on which value is being encoded). |
| **Bit period** | The total duration of one Manchester-encoded bit = two half-bit periods. The defining Manchester transition occurs at the midpoint of the bit period, i.e., at the boundary between the two half-bit periods. |
| **Frames per half-bit (N)** | The number of camera frames the receiver samples during a single half-bit period. This is a calibration parameter, not a constant — it must be ≥1, and in practice should be ≥2 to give margin against a frame landing ambiguously close to a transition boundary (camera exposure integration time is not instantaneous either, so a frame captured right at a transition can show a blended/uncertain state). |
| **Frames per bit** | `2 × N` — the total number of frames spanning one full Manchester bit, since a bit is two half-bit periods. |

**Revised description of the Phase 1 symbol timing:** a Manchester bit occupies `2 × N` camera frames, where `N` (frames per half-bit) is an experimentally calibrated parameter per Section 11 of the original brief — not a fixed "2 frame-slots per bit" claim, since the real implementation will very likely need `N > 1` for reliability margin. The resulting bit rate is `FPS / (2 × N)` bits/second. For illustration only (not a locked value): at 30 fps with `N = 2`, a bit spans 4 frames (~133 ms), giving roughly 7–8 bits/second — deliberately slow, consistent with the project's stated Phase 1 priority of reliability over speed. The actual value of `N` is determined during calibration, not fixed here.

### 1.4 RED = DATA, GREEN = CONTROL — marked as experimental pending physical validation

The physical-layer split (RED carries Manchester-encoded data, GREEN signals control/frame boundaries) is kept as the experimental design for Phase 1. It is explicitly **not** treated as a proven assumption. In particular, the green-channel start-of-frame/end-of-frame (SOF/EOF) approach described in §2.1 depends on things that can only be confirmed on the actual Samsung M52 camera pipeline: whether the phone's Bayer-filter color separation gives clean enough red/green channel discrimination at the relevant distances and lighting conditions, whether ambient green light sources cause false SOF triggers, and whether CameraX's color reproduction is stable enough under auto-exposure to threshold green reliably. This is flagged as a Phase 7/8 empirical validation item, not an assumed-working mechanism — if it doesn't hold up, the fallback is an in-band (red-channel) sync word, at the cost of the overhead that approach was designed to avoid.

---

## 2. Packet Protocol

### 2.1 Preamble vs. frame sync (unchanged in principle, cross-referenced to §1.4)

Preamble (AGC/threshold convergence, carries no information) remains distinct from frame sync (SOF/EOF, proposed to run on the green control channel per §1.4, pending physical validation). A lightweight Magic/Version check remains the first field inside the logical packet, to sanity-check that a green-channel SOF trigger wasn't a false positive before committing to parsing the rest of the packet.

### 2.2 No separate nonce field

The packet format does **not** include a transmitted nonce field. The nonce is derived deterministically from fields already present in the header (Session ID, Sequence Number — see §5.2 for the exact construction). This isn't a placeholder decision anymore; §5.2 below specifies it exactly.

### 2.3 CRC and AEAD both retained (unchanged)

CRC remains a fast, non-cryptographic corruption check, performed first, before any AEAD attempt. AEAD authentication remains the cryptographic authenticity/integrity mechanism. They answer different questions (optical noise vs. actual tampering/attack) and neither substitutes for the other — see Security Invariant "never accept a packet solely because CRC passes" in §9.

---

## 3. Cryptography

### 3.1 Nonce uniqueness is mandatory for both candidates — not a differentiator

Both AES-256-GCM and ChaCha20-Poly1305 require the (key, nonce) pair to never repeat, and both fail badly if it does. This requirement is treated as **non-negotiable and identical for either algorithm** — it does not get relaxed for one and tightened for the other, and it is not the basis for choosing between them. The exact-construction nonce scheme in §5.2 applies unchanged regardless of which Algorithm ID is selected.

### 3.2 Revised comparison and recommendation

| Criterion | AES-256-GCM | ChaCha20-Poly1305 |
|---|---|---|
| Nonce-reuse tolerance | Not relied upon as a selection criterion — both are treated as strictly nonce-uniqueness-dependent | Not relied upon as a selection criterion — same treatment |
| Hardware acceleration availability | Guaranteed on Party A (Windows/x86 with AES-NI) | Not hardware-dependent by design |
| Hardware acceleration availability on Party B | Depends on the specific Android device/SoC's crypto extensions and whether the runtime/library actually uses them — not guaranteed to be consistent across the M52 today and whatever devices the project targets later | Consistent constant-time software performance regardless of hardware, with no dependency on a particular device exposing accelerated AES |
| Side-channel consistency | Safe when hardware-accelerated; a software fallback path (if one is ever silently taken by a library on some device) risks a non-constant-time implementation | Constant-time by construction (ARX design, no data-dependent table lookups), so its security properties don't depend on which code path a given device happens to take |
| Relevance of raw throughput | Low — the optical channel is the bottleneck by orders of magnitude versus either cipher's software throughput on modern hardware | Low, same reasoning |
| Standardization | NIST standard | IETF standard (RFC 8439) |

**Recommendation (unchanged conclusion, revised justification): ChaCha20-Poly1305 remains the default Security Profile; AES-256-GCM remains the selectable alternate.** The deciding factor is no longer framed around nonce-reuse forgiveness — that requirement is identical and mandatory for both. The actual technical basis is **hardware heterogeneity on the Android side**: Party A's platform (Windows/x86) reliably has AES-NI, but Party B's platform is Android, where accelerated-AES availability and whether the runtime library actually engages it can vary by device and isn't something this project controls or should depend on for its security properties — especially once testing extends beyond the M52 to other devices. ChaCha20-Poly1305's software performance and constant-time behavior don't depend on that variable. AES-256-GCM remains fully available as the alternate profile (useful for interoperability, comparison, and taking advantage of hardware acceleration where it's confirmed present), but the default is chosen for consistency across the receiver side of the link rather than for raw speed on either side.

### 3.3 Algorithm ID as AAD (unchanged mechanism, field list updated in §8)

Unchanged from the prior draft: Algorithm ID is included in the AAD, so tampering it invalidates the AEAD tag and the packet is rejected — Security Profile selection changes both the actual cipher used and the authenticated bytes Party B must reproduce, never just a UI label. Full AAD field list in §8.

---

## 4. Message ID and Fragmentation

### 4.1 Why Message ID is separate from Sequence Number

Sequence Number (§5.3) identifies **individual optical packets** — every single encryption operation gets a new one, including retransmissions of the same content. It exists purely to guarantee nonce uniqueness and to support the low-level packet replay window. It says nothing about which logical, user-composed message a packet belongs to.

**Message ID** identifies a **logical message** as composed by the user (one "Compose Message" send action). A message may be split across multiple fragments, and any individual fragment may need retransmission. All fragments — and all retransmissions of those fragments — belonging to one user-composed message share the same Message ID, while each individual optical packet (original send or retransmit) still gets its own unique Sequence Number. This separation is what makes retransmission and fragmentation both cleanly solvable without conflating "is this cryptographically a new operation" (Sequence Number's job) with "does this belong to the same logical message" (Message ID's job).

Message ID is generated by Party A once per composed message (not per packet, not per fragment). Exact width is not finalized here (see §4.4), consistent with the instruction not to lock byte sizes for this field yet.

### 4.2 Fragment Number and Total Fragments

To support messages larger than a single optical packet's payload capacity, two conceptual fields are added alongside Message ID:

- **Fragment Number** — this fragment's position within the message (e.g., 0-indexed).
- **Total Fragments** — how many fragments the complete message consists of.

Making Total Fragments explicit (rather than relying solely on a "last fragment" flag) gives the receiver more diagnostic power: it can immediately show progress ("packet 12/18," as mocked in the original receiver UI) and can explicitly detect *which* fragment is missing rather than only detecting that the stream ended early. The `Flags` field's already-reserved `LAST_FRAGMENT` bit can still be kept as a redundant cross-check — if `Fragment Number == Total Fragments − 1`, `LAST_FRAGMENT` should also be set; a mismatch between the two is itself a corruption/tampering signal, obtained almost for free.

### 4.3 How Party B distinguishes a retransmission from a new message

This directly answers the retransmission-identification requirement:

- An incoming packet that **authenticates successfully** (passes both CRC and AEAD) carries a Message ID and Fragment Number in its (now-authenticated) header.
- If the Message ID matches a message the receiver already has an active reassembly buffer for, the packet is treated as belonging to that message. If the specific Fragment Number slot in that buffer is still empty, it fills the gap. If that slot is already filled (a duplicate delivery — e.g., Party A retransmitted because it didn't see an ACK in time, but the original had actually arrived), the new copy is simply discarded after authentication succeeds; no error condition, just a no-op.
- If the Message ID is not currently active, it's treated as the start of a new logical message, and a new reassembly buffer is created (sized using Total Fragments).
- **Sequence Number plays no role in this decision.** It only matters for nonce derivation and the independent, lower-layer replay window (§5.4), which rejects any packet reusing a sequence number that's already been accepted for that session — a different check operating at a different layer than message reassembly.

### 4.4 Byte widths — not finalized

Consistent with the instruction not to lock sizes yet: Message ID, Fragment Number, and Total Fragments widths remain open, to be sized once realistic maximum message lengths (given the optical link's very low throughput) are known from Phase 9 calibration data. Session ID and Sequence Number, by contrast, **are** now fixed — see §5.2, where their exact widths are required to pin down the nonce construction.

---

## 5. Key / Session Management

### 5.1 Prototype approach: pre-shared key (unchanged)

Offline-generated PSK, provisioned identically to both devices before any optical session begins, stored in local secure storage on each side, never transmitted over the optical channel in any form.

### 5.2 Exact nonce construction

This replaces the earlier abstract `nonce = f(Session ID, Sequence Number)` with a concrete, fixed-width design:

| Parameter | Value |
|---|---|
| Nonce size | 96 bits (12 bytes) — the standard nonce size for both AES-256-GCM and ChaCha20-Poly1305, chosen specifically so neither cipher needs a non-standard nonce-length code path |
| Session ID size | 32 bits (4 bytes) |
| Sequence Number size | 64 bits (8 bytes) |
| Byte order | Big-endian (network byte order) for both fields — a fixed, explicit convention independent of the internal endianness of any device on either end, chosen for unambiguous cross-platform (Python / TypeScript+native) interoperability and easier debugging |
| Concatenation method | `nonce = SessionID (4 bytes, big-endian) ‖ SequenceNumber (8 bytes, big-endian)`, i.e., Session ID occupies the first 4 bytes, Sequence Number the remaining 8 — direct concatenation, no hashing/KDF step, and no padding needed since 4 + 8 = 12 bytes exactly matches the required nonce size |

These are the **same** Session ID and Sequence Number values transmitted in the packet header (§8) — there is no separately-encoded copy for nonce purposes, which is also why no dedicated nonce field appears on the wire.

**Why this produces a unique nonce for every encryption operation under a given key:** the construction is safe *because* its two inputs are independently guaranteed unique, not because concatenation is magic. Specifically: §5.3 guarantees a Session ID is never reused under the same key, and §5.4 guarantees a Sequence Number is never reused within a given session. Therefore the pair `(SessionID, SequenceNumber)` is unique per encryption operation under a given key, and the nonce — a direct, lossless concatenation of that pair — is unique per encryption operation under that key. The security of this scheme rests entirely on §5.3 and §5.4's rules being enforced as strict invariants, not best-effort conventions (see §9).

### 5.3 Session ID: generation, persistence, reuse rules

- **Generation:** a 32-bit value generated by Party A using a cryptographically secure random number generator (CSPRNG) at the start of each session — not a simple counter, to avoid predictable collisions across independently-started sessions.
- **Collision avoidance:** Party A maintains a local record of Session IDs already used under the current key (in-memory is sufficient for the prototype). Before starting a new session, it checks the freshly generated Session ID against this record and regenerates on the rare event of a collision. Given 32 bits of randomness and the low session counts expected from a slow optical link, this is a cheap, low-overhead safeguard rather than a performance concern.
- **Reuse rule:** a Session ID must never be reused under the same key. It may be reused after a key rotation, since nonce uniqueness is scoped per-key, not global (§5.6) — though as a matter of debugging hygiene, Party A should still generate a fresh Session ID at rotation time rather than deliberately reusing the immediately-prior value.

### 5.4 Sequence Number: lifetime and restart behavior

- **Lifetime:** 64-bit, starts at 0 for each new session, strictly monotonically incremented by exactly 1 for every single encryption operation performed under that Session ID — including retransmissions of the same message/fragment (§4.3). It is never reset within the lifetime of a given Session ID.
- **Practical exhaustion:** at 64 bits, sequence number wraparound is not a realistic concern for this link's throughput. The practically relevant limit is instead the safe-usage-per-key ceiling that AEAD cipher guidance recommends staying well under — a separate, much lower threshold that is the real trigger for eventual key rotation (§5.6), not sequence-number capacity.
- **Behavior on restart:** any event that puts sequence-number continuity in doubt — an app crash, an explicit new session started by the user, or detected desync between Party A and Party B — **must** result in Party A generating a brand-new Session ID before transmitting anything further under the current key. Resuming an old Session ID with a guessed or reset Sequence Number is never acceptable, since it risks nonce reuse if any prior state was lost. This trades a small amount of Session ID churn for eliminating an entire class of crash/restart-timing nonce-reuse bugs.

### 5.5 Replay protection (unchanged mechanism)

Party B tracks the highest Sequence Number accepted (or a small sliding window, if limited reordering ever needs tolerating) per Session ID, and rejects any packet reusing a Sequence Number already accepted for that session. This operates independently from, and at a different layer than, the Message ID/Fragment reassembly logic in §4.3.

### 5.6 Key rotation

Rotating to a new key resets the nonce-uniqueness scope: uniqueness is only required *within* a given key, so a new key may reuse previously-used Session ID and Sequence Number values without conflict. The `KEY_ROTATION_PENDING` flag bit (reserved in Flags) and a `SESSION_INIT`-style packet type remain the intended signaling hook for this transition; the actual key-derivation mechanism (e.g., an HKDF construction) is still explicitly deferred to a dedicated design pass before Phase 11 — not designed here.

### 5.7 Future key establishment (unchanged, still postponed)

Bootstrapped pairing (QR-code scan of a Party-A-displayed secret) and in-band authenticated key exchange remain the two candidate future directions, both requiring dedicated cryptographic design with vetted constructions — still explicitly out of scope for this document.

---

## 6. Camera Receiver Architecture

### 6.1 Pipeline (unchanged shape)

```
Camera → CameraX → Frame acquisition → ROI detection (OpenCV) → Color extraction
  → Signal preprocessing → Pulse detection → AI-assisted classification
  → Symbol decoder → Packet parser → CRC → Algorithm selection
  → AEAD authentication + decryption → React Native UI
```

### 6.2 Native/React Native boundary — now a benchmark-driven decision, not a lock

The prior draft's placement of the boundary at the symbol-decoder → packet-parser handoff is kept as the **working hypothesis** to prototype against, not a permanent architectural lock. Everything from camera capture through pulse detection and AI classification stays native — that part isn't in question, since it needs to run at or near frame rate and routing it through the JS bridge would reintroduce the exact bottleneck the original brief warns against. Where exactly the boundary sits *above* pulse detection (symbol decoder in native vs. TypeScript; how much of packet parsing could also benefit from staying native) should be confirmed empirically once real event-rate and bridge-call-overhead numbers exist from testing on the actual Samsung M52 — not assumed correct in advance. The AEAD operation itself still routes through a native or well-audited native-backed crypto binding regardless of where this boundary lands, since a hand-rolled/pure-JS crypto implementation remains out of scope either way.

### 6.3 AI role and deterministic fallback (unchanged)

AI remains advisory only, sitting inside the native pulse-classification step as an assist — never a replacement for the deterministic decode path. With AI enabled, ambiguous pulses get a classifier-assisted decision plus a confidence score (surfaced to the UI). With AI disabled, the same front end falls back to purely rule-based thresholding, with no confidence score or a fixed default. In neither mode does AI's output bypass CRC or AEAD authentication — both gates apply identically regardless of which path produced the symbol stream, which is what makes AI safely optional rather than load-bearing for correctness (see §9).

---

## 7. Updated Conceptual Packet Format

```
MAGIC/VERSION
PACKET TYPE
ALGORITHM ID
SESSION ID              (4 bytes — fixed, see §5.2)
MESSAGE ID              (width TBD, see §4.4)
SEQUENCE NUMBER         (8 bytes — fixed, see §5.2)
FRAGMENT INFORMATION    (Fragment Number + Total Fragments; widths TBD, see §4.4)
FLAGS
PAYLOAD LENGTH
ENCRYPTED PAYLOAD
AUTHENTICATION TAG
CRC
```

No separate NONCE field — it's derived from Session ID + Sequence Number per §5.2, never transmitted on its own.

### AAD (Additional Authenticated Data)

Everything in the header **except the Encrypted Payload, Authentication Tag, and CRC** is authenticated as AAD:

```
AAD = MAGIC/VERSION ‖ PACKET TYPE ‖ ALGORITHM ID ‖ SESSION ID ‖ MESSAGE ID
      ‖ SEQUENCE NUMBER ‖ FRAGMENT INFORMATION ‖ FLAGS ‖ PAYLOAD LENGTH
```

Rationale for including each of the newly-added fields specifically:

- **Message ID and Fragment Information** are included because tampering either could misplace correctly-decrypted plaintext into the wrong reassembly slot or the wrong logical message — a security-relevant outcome even though the ciphertext itself would still decrypt correctly. Authenticating these fields means any such tampering is caught as an AEAD failure rather than silently corrupting reassembly.
- **Session ID and Sequence Number** are included even though they also feed nonce derivation (§5.2). Tampering either would already cause the receiver to derive the wrong nonce (breaking decryption on its own), but including them explicitly in AAD as well is standard practice and removes any reliance on that being the *only* protection — defense in depth, not redundancy for its own sake.

CRC remains outside the AAD and outside the AEAD trust boundary entirely — it's a pre-authentication, physical-layer sanity filter, not a cryptographic check (§2.3, §9).

---

## 8. Security Invariants

These are non-negotiable rules for the eventual implementation, not aspirational guidelines. Any implementation choice that would violate one of these needs to come back for architectural review before proceeding, not be resolved locally in code.

1. **Never reuse a nonce with the same key** — enforced structurally by §5.2's construction and §5.3/§5.4's Session ID and Sequence Number rules, for both AES-256-GCM and ChaCha20-Poly1305 equally.
2. **Never transmit secret keys in normal packets** — the PSK (and any future derived/rotated key) never appears on the optical channel in any form, plaintext or encrypted; only non-secret metadata (Algorithm ID, Session ID) is transmitted.
3. **Never accept a packet solely because CRC passes** — CRC is a corruption filter, not an authenticity check; AEAD authentication is still required before a packet is trusted.
4. **Never bypass AEAD authentication** — including for AI-assisted decode paths (§6.3); a symbol stream produced by AI classification is authenticated exactly the same way as one produced by the deterministic fallback.
5. **Never allow an unauthenticated algorithm downgrade** — Algorithm ID is AAD (§8/§3.3); tampering it invalidates the tag.
6. **Never reset sequence numbers under the same key/session combination** — a restart always mints a new Session ID (§5.4) rather than resuming with a reset counter.
7. **Unknown Algorithm IDs must be rejected** — a packet whose Algorithm ID doesn't correspond to a supported cipher is dropped, not passed through with a default/fallback algorithm.
8. **Retransmissions must not reuse ciphertext or nonces** — a retransmitted fragment is fully re-encrypted with a new Sequence Number (and therefore a new nonce), never a resend of prior ciphertext bytes (§4.3).

---

## Summary

### A. Recommended architecture (revised)

Manchester encoding with explicitly defined timing terminology (bit period / half-bit period / frame / sampling interval / frames-per-half-bit / frames-per-bit) and a calibration-determined `N`; RED/GREEN physical split kept but flagged experimental pending M52 validation; packet header carrying Session ID, Message ID, Sequence Number, and Fragment Information with no transmitted nonce field; exact fixed-width deterministic nonce construction (Session ID ‖ Sequence Number, 4+8=12 bytes, big-endian); ChaCha20-Poly1305 default / AES-256-GCM alternate, justified by Android hardware heterogeneity rather than nonce-mistake forgiveness; CRC-then-AEAD retained; native camera/signal pipeline through pulse detection, with the native/RN boundary above that point treated as benchmark-driven; AI strictly advisory with a mandatory deterministic fallback; a full set of non-negotiable security invariants.

### B. Decisions to lock now (revised)

1. Manchester encoding as the Phase 1 baseline, described using the precise terminology in §1.3.
2. The exact nonce construction in §5.2, including Session ID (4 bytes) and Sequence Number (8 bytes) widths specifically — these two fields are no longer "TBD."
3. Message ID and Fragment Number/Total Fragments as distinct, AAD-authenticated fields, separate from Sequence Number.
4. AEAD-only cryptography; ChaCha20-Poly1305 default / AES-256-GCM alternate; nonce uniqueness mandatory for both, without exception.
5. Full AAD field list per §8.
6. All eight Security Invariants in §9, unconditionally.
7. Native pipeline through pulse detection/AI classification; the boundary above that point is explicitly *not* locked (see item C below).

### C. Decisions to postpone (revised)

1. Byte widths for Magic/Version, Packet Type, Algorithm ID, Flags, Payload Length, Message ID, Fragment Number, and Total Fragments (Session ID and Sequence Number are now the exception — locked per §5.2).
2. Whether the green-channel SOF/EOF mechanism survives physical validation on the M52, or needs an in-band fallback (§1.4).
3. The exact value of `N` (frames per half-bit) — a calibration output, not a design decision.
4. The precise native/TypeScript boundary above pulse detection — deferred to benchmarking on real hardware (§6.2).
5. Key-rotation derivation mechanism (HKDF construction details) and the real key-establishment/AKE design (§5.6, §5.7).
6. AI model architecture, training data, and evaluation methodology.

### D. Technical risks (updated)

- The green-channel SOF/EOF design is now explicitly unproven; a failure here has a known fallback (in-band sync word) but at a real overhead cost that should be budgeted for even while hoping not to need it.
- Nonce-construction correctness now depends on strict enforcement of Session ID collision-avoidance and Sequence-Number-never-resets rules; these need dedicated negative-path tests (deliberately simulate a restart, a rotation, and a forced Session ID collision) rather than only positive-path tests.
- Fragmentation/reassembly introduces new receiver-side state (per-Message-ID buffers) that needs an eviction/timeout policy for incomplete messages — not designed yet, worth flagging before Phase 9 finalizes the spec.
- Leaving the native/RN boundary unlocked is a deliberate risk tradeoff: it avoids committing to a wrong guess, but means Phase 14/15 work can't fully start until M52 benchmarking data exists.

### E. Proposed Phase 1 implementation order (unchanged from prior draft)

1. Finalize and document the packet spec, with the now-fixed Session ID/Sequence Number/nonce fields locked and the remaining fields marked TBD.
2. Protocol + crypto correctness in isolation (pure Python, `pytest`) — including negative tests for nonce-reuse attempts, Session ID collisions, sequence resets, tampered Algorithm ID/Message ID/Fragment fields, and replayed sequence numbers.
3. ESP32 raw Manchester bit generator with a hardcoded test pattern — no crypto, no real packets yet.
4. Minimal native Android CameraX + OpenCV prototype to detect transitions and reconstruct Manchester bits from the ESP32's live test pattern, and to begin empirically validating the green-channel SOF/EOF hypothesis from §1.4.
5. Only after both ends independently prove reliable raw bit transfer, wire the full packet + crypto + fragmentation layer end-to-end.

---

**Waiting for approval before any code, packages, or protocol implementation begins.**
