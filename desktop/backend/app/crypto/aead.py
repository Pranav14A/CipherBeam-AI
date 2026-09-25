"""CipherBeam AI — Phase 11 authenticated encryption.

Supported algorithms:
- ChaCha20-Poly1305
- AES-256-GCM

Both use:
- 32-byte keys
- 12-byte nonces
- 16-byte authentication tags

Nonce construction:
    Session ID    4 bytes, big-endian
    Sequence No.  8 bytes, big-endian
    --------------------------------
    Nonce        12 bytes
"""

from enum import IntEnum
import secrets

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import (
    AESGCM,
    ChaCha20Poly1305,
)


KEY_SIZE = 32
NONCE_SIZE = 12
SESSION_ID_SIZE = 4
SEQUENCE_NUMBER_SIZE = 8
AUTH_TAG_SIZE = 16


class EncryptionAlgorithm(IntEnum):
    """CipherBeam encryption algorithm identifiers."""

    CHACHA20_POLY1305 = 0x01
    AES_256_GCM = 0x02


class CryptoError(ValueError):
    """Base error for invalid CipherBeam crypto inputs."""


class AuthenticationError(CryptoError):
    """Raised when AEAD authentication fails."""


def generate_key() -> bytes:
    """Generate a cryptographically secure 256-bit key."""
    return secrets.token_bytes(KEY_SIZE)


def generate_session_id() -> int:
    """Generate a cryptographically secure 32-bit session identifier."""
    return secrets.randbits(32)


def build_nonce(
    session_id: int,
    sequence_number: int,
) -> bytes:
    """Build the 12-byte CipherBeam nonce.

    Nonce layout:
        session_id      -> 4 bytes, big-endian
        sequence_number -> 8 bytes, big-endian
    """

    if not 0 <= session_id <= 0xFFFFFFFF:
        raise CryptoError(
            "Session ID must fit in 4 unsigned bytes."
        )

    if not 0 <= sequence_number <= 0xFFFFFFFFFFFFFFFF:
        raise CryptoError(
            "Sequence number must fit in 8 unsigned bytes."
        )

    nonce = (
        session_id.to_bytes(
            SESSION_ID_SIZE,
            byteorder="big",
        )
        + sequence_number.to_bytes(
            SEQUENCE_NUMBER_SIZE,
            byteorder="big",
        )
    )

    if len(nonce) != NONCE_SIZE:
        raise CryptoError(
            "Constructed nonce has invalid length."
        )

    return nonce


def _validate_key(key: bytes) -> None:
    if len(key) != KEY_SIZE:
        raise CryptoError(
            "CipherBeam requires a 32-byte key."
        )


def _build_cipher(
    algorithm: EncryptionAlgorithm,
    key: bytes,
):
    _validate_key(key)

    if algorithm == EncryptionAlgorithm.CHACHA20_POLY1305:
        return ChaCha20Poly1305(key)

    if algorithm == EncryptionAlgorithm.AES_256_GCM:
        return AESGCM(key)

    raise CryptoError(
        f"Unsupported encryption algorithm: {algorithm}"
    )


def encrypt(
    *,
    algorithm: EncryptionAlgorithm,
    key: bytes,
    session_id: int,
    sequence_number: int,
    plaintext: bytes,
    associated_data: bytes | None = None,
) -> bytes:
    """Encrypt and authenticate plaintext.

    Returns:
        Ciphertext followed by the 16-byte authentication tag.
    """

    cipher = _build_cipher(algorithm, key)

    nonce = build_nonce(
        session_id=session_id,
        sequence_number=sequence_number,
    )

    return cipher.encrypt(
        nonce,
        plaintext,
        associated_data,
    )


def decrypt(
    *,
    algorithm: EncryptionAlgorithm,
    key: bytes,
    session_id: int,
    sequence_number: int,
    ciphertext: bytes,
    associated_data: bytes | None = None,
) -> bytes:
    """Authenticate and decrypt ciphertext.

    Raises:
        AuthenticationError:
            When ciphertext/tag authentication fails.
    """

    cipher = _build_cipher(algorithm, key)

    nonce = build_nonce(
        session_id=session_id,
        sequence_number=sequence_number,
    )

    try:
        return cipher.decrypt(
            nonce,
            ciphertext,
            associated_data,
        )
    except InvalidTag as exc:
        raise AuthenticationError(
            "Ciphertext authentication failed."
        ) from exc