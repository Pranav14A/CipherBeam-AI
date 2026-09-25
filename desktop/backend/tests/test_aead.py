from app.crypto.aead import (
    AUTH_TAG_SIZE,
    KEY_SIZE,
    NONCE_SIZE,
    AuthenticationError,
    CryptoError,
    EncryptionAlgorithm,
    build_nonce,
    decrypt,
    encrypt,
    generate_key,
    generate_session_id,
)


def test_generate_key_has_correct_length():
    key = generate_key()

    assert isinstance(key, bytes)
    assert len(key) == KEY_SIZE
    assert KEY_SIZE == 32


def test_generate_session_id_fits_32_bits():
    session_id = generate_session_id()

    assert 0 <= session_id <= 0xFFFFFFFF


def test_build_nonce_uses_big_endian_session_and_sequence():
    nonce = build_nonce(
        session_id=1,
        sequence_number=2,
    )

    assert len(nonce) == NONCE_SIZE
    assert nonce.hex() == (
        "00000001"
        "0000000000000002"
    )


def test_build_nonce_uses_full_field_widths():
    nonce = build_nonce(
        session_id=0x12345678,
        sequence_number=0x0102030405060708,
    )

    assert nonce.hex() == (
        "12345678"
        "0102030405060708"
    )


def test_chacha20_poly1305_round_trip():
    key = generate_key()
    plaintext = b"CipherBeam"

    ciphertext = encrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=123,
        sequence_number=0,
        plaintext=plaintext,
    )

    assert len(ciphertext) == len(plaintext) + AUTH_TAG_SIZE

    decrypted = decrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=123,
        sequence_number=0,
        ciphertext=ciphertext,
    )

    assert decrypted == plaintext


def test_aes_256_gcm_round_trip():
    key = generate_key()
    plaintext = b"CipherBeam"

    ciphertext = encrypt(
        algorithm=EncryptionAlgorithm.AES_256_GCM,
        key=key,
        session_id=123,
        sequence_number=0,
        plaintext=plaintext,
    )

    assert len(ciphertext) == len(plaintext) + AUTH_TAG_SIZE

    decrypted = decrypt(
        algorithm=EncryptionAlgorithm.AES_256_GCM,
        key=key,
        session_id=123,
        sequence_number=0,
        ciphertext=ciphertext,
    )

    assert decrypted == plaintext


def test_different_algorithms_produce_different_ciphertexts():
    key = generate_key()
    plaintext = b"CipherBeam"

    chacha_ciphertext = encrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=1,
        sequence_number=1,
        plaintext=plaintext,
    )

    aes_ciphertext = encrypt(
        algorithm=EncryptionAlgorithm.AES_256_GCM,
        key=key,
        session_id=1,
        sequence_number=1,
        plaintext=plaintext,
    )

    assert chacha_ciphertext != aes_ciphertext


def test_wrong_key_fails_authentication():
    key = generate_key()
    wrong_key = generate_key()

    ciphertext = encrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=1,
        sequence_number=1,
        plaintext=b"CipherBeam",
    )

    try:
        decrypt(
            algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
            key=wrong_key,
            session_id=1,
            sequence_number=1,
            ciphertext=ciphertext,
        )
    except AuthenticationError:
        return

    raise AssertionError("Wrong key unexpectedly authenticated")


def test_tampered_ciphertext_fails_authentication():
    key = generate_key()

    ciphertext = bytearray(
        encrypt(
            algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
            key=key,
            session_id=1,
            sequence_number=1,
            plaintext=b"CipherBeam",
        )
    )

    ciphertext[0] ^= 0x01

    try:
        decrypt(
            algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
            key=key,
            session_id=1,
            sequence_number=1,
            ciphertext=bytes(ciphertext),
        )
    except AuthenticationError:
        return

    raise AssertionError("Tampered ciphertext unexpectedly authenticated")


def test_tampered_aad_fails_authentication():
    key = generate_key()

    ciphertext = encrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=1,
        sequence_number=1,
        plaintext=b"CipherBeam",
        associated_data=b"header-v1",
    )

    try:
        decrypt(
            algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
            key=key,
            session_id=1,
            sequence_number=1,
            ciphertext=ciphertext,
            associated_data=b"header-v2",
        )
    except AuthenticationError:
        return

    raise AssertionError("Tampered AAD unexpectedly authenticated")


def test_different_sequence_number_changes_ciphertext():
    key = generate_key()
    plaintext = b"CipherBeam"

    ciphertext_1 = encrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=1,
        sequence_number=1,
        plaintext=plaintext,
    )

    ciphertext_2 = encrypt(
        algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
        key=key,
        session_id=1,
        sequence_number=2,
        plaintext=plaintext,
    )

    assert ciphertext_1 != ciphertext_2


def test_invalid_key_length_is_rejected():
    try:
        encrypt(
            algorithm=EncryptionAlgorithm.CHACHA20_POLY1305,
            key=b"too-short",
            session_id=1,
            sequence_number=1,
            plaintext=b"CipherBeam",
        )
    except CryptoError:
        return

    raise AssertionError("Invalid key length was accepted")


def test_invalid_session_id_is_rejected():
    try:
        build_nonce(
            session_id=0x100000000,
            sequence_number=0,
        )
    except CryptoError:
        return

    raise AssertionError("Invalid session ID was accepted")


def test_invalid_sequence_number_is_rejected():
    try:
        build_nonce(
            session_id=0,
            sequence_number=0x10000000000000000,
        )
    except CryptoError:
        return

    raise AssertionError("Invalid sequence number was accepted")