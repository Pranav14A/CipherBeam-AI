"""Mocked unit tests for SerialManager.

No physical ESP32 is used or required — pyserial's serial.Serial is fully
mocked out. These tests prove the Python logic is correct; they do NOT
prove real hardware communication works. See tests/hardware/README.md for
the real-device manual test procedure to run on the actual Windows PC.
"""

from unittest.mock import MagicMock, patch

import pytest
import serial as pyserial

from app.hardware.serial_manager import (
    InvalidResponseError,
    PortUnavailableError,
    SerialManager,
    SerialTimeoutError,
)


def make_manager() -> SerialManager:
    return SerialManager(port="COM3", baud_rate=115200, timeout=2.0)


# 1. successful connection
@patch("app.hardware.serial_manager.serial.Serial")
def test_connect_opens_serial_port_successfully(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    manager.connect()

    mock_serial_cls.assert_called_once_with(
        port="COM3", baudrate=115200, timeout=2.0, write_timeout=2.0
    )
    assert manager.is_connected() is True


# 2. successful PING (command actually sent correctly)
@patch("app.hardware.serial_manager.serial.Serial")
def test_request_sends_ping_command(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_instance.readline.return_value = b"PONG\n"
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    manager.request("PING", expected="PONG")

    mock_instance.write.assert_called_once_with(b"PING\n")


# 3. PONG response (parsed/returned correctly)
@patch("app.hardware.serial_manager.serial.Serial")
def test_request_returns_pong_response(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_instance.readline.return_value = b"PONG\r\n"
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    response = manager.request("PING", expected="PONG")

    assert response == "PONG"


# 4. timeout
@patch("app.hardware.serial_manager.serial.Serial")
def test_request_raises_timeout_when_no_response(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_instance.readline.return_value = b""  # pyserial returns b"" on read timeout
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    with pytest.raises(SerialTimeoutError):
        manager.request("PING", expected="PONG")


# 5. disconnected device (port cannot be opened — ESP32 unplugged/absent)
@patch("app.hardware.serial_manager.serial.Serial")
def test_connect_raises_port_unavailable_when_device_absent(mock_serial_cls):
    mock_serial_cls.side_effect = pyserial.SerialException(
        "could not open port 'COM3'"
    )

    manager = make_manager()
    with pytest.raises(PortUnavailableError):
        manager.connect()
    assert manager.is_connected() is False


# 6. invalid response
@patch("app.hardware.serial_manager.serial.Serial")
def test_request_raises_invalid_response_for_unexpected_reply(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_instance.readline.return_value = b"GARBAGE\n"
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    with pytest.raises(InvalidResponseError):
        manager.request("PING", expected="PONG")


# 7. disconnect cleanup
@patch("app.hardware.serial_manager.serial.Serial")
def test_disconnect_closes_port_and_clears_state(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    manager.connect()
    manager.disconnect()

    mock_instance.close.assert_called_once()
    assert manager.is_connected() is False


# Bonus: explicitly verifies the "prevent multiple connections to the same
# COM port" requirement — calling connect() twice must not reopen the port.
@patch("app.hardware.serial_manager.serial.Serial")
def test_connect_is_idempotent_and_does_not_reopen_port(mock_serial_cls):
    mock_instance = MagicMock()
    mock_instance.is_open = True
    mock_serial_cls.return_value = mock_instance

    manager = make_manager()
    manager.connect()
    manager.connect()

    mock_serial_cls.assert_called_once()
