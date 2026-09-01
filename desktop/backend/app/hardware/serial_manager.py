"""CipherBeam AI — ESP32 Serial Manager (Party A, Phase 2D)

A thin, testable abstraction around pyserial used to talk to the ESP32-S3
over the debug PING/PONG serial protocol established in Phase 2C.

IMPORTANT: this is NOT the final CipherBeam optical packet protocol. It is
a development/debug link over USB serial, used only to prove that
React -> FastAPI -> pyserial -> COM3 -> ESP32-S3 -> pyserial -> FastAPI ->
React works end to end, per the Phase 2D objective. LED control, Manchester
encoding, packet framing, and encryption are explicitly out of scope here.

The rest of the backend should never import `serial` directly — everything
goes through SerialManager, so pyserial only appears in this one file.
"""

from __future__ import annotations

import threading
from enum import Enum

import serial


class SerialErrorCode(str, Enum):
    """Structured error codes returned to the frontend. Never a raw traceback."""

    NOT_CONNECTED = "ESP32_NOT_CONNECTED"
    PORT_UNAVAILABLE = "ESP32_PORT_UNAVAILABLE"
    TIMEOUT = "ESP32_TIMEOUT"
    INVALID_RESPONSE = "ESP32_INVALID_RESPONSE"
    DISCONNECTED = "ESP32_DISCONNECTED"


class SerialManagerError(Exception):
    """Base class for all SerialManager errors. Always carries a structured code."""

    def __init__(self, code: SerialErrorCode, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(message)


class NotConnectedError(SerialManagerError):
    def __init__(self, message: str = "ESP32 is not connected.") -> None:
        super().__init__(SerialErrorCode.NOT_CONNECTED, message)


class PortUnavailableError(SerialManagerError):
    def __init__(
        self, message: str = "The configured serial port is unavailable."
    ) -> None:
        super().__init__(SerialErrorCode.PORT_UNAVAILABLE, message)


class SerialTimeoutError(SerialManagerError):
    def __init__(
        self, message: str = "No response received before the timeout elapsed."
    ) -> None:
        super().__init__(SerialErrorCode.TIMEOUT, message)


class InvalidResponseError(SerialManagerError):
    def __init__(
        self, message: str = "The ESP32 responded, but not with the expected value."
    ) -> None:
        super().__init__(SerialErrorCode.INVALID_RESPONSE, message)


class DisconnectedError(SerialManagerError):
    def __init__(
        self, message: str = "The ESP32 disconnected during the operation."
    ) -> None:
        super().__init__(SerialErrorCode.DISCONNECTED, message)


DEFAULT_TIMEOUT_SECONDS = 2.0


class SerialManager:
    """Thread-safe, lazily-connecting wrapper around a single pyserial connection.

    `connect()` is idempotent and `request()` connects on demand, so callers
    don't need to manage connection state themselves. A single lock protects
    every operation that touches the underlying serial.Serial instance, both
    to prevent opening the same COM port twice and to prevent two commands
    from interleaving on the wire.
    """

    def __init__(
        self,
        port: str,
        baud_rate: int,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ) -> None:
        self._port = port
        self._baud_rate = baud_rate
        self._timeout = timeout
        self._serial: serial.Serial | None = None
        self._lock = threading.Lock()

    @property
    def port(self) -> str:
        return self._port

    @property
    def baud_rate(self) -> int:
        return self._baud_rate

    def is_connected(self) -> bool:
        return self._serial is not None and self._serial.is_open

    def connect(self) -> None:
        """Open the serial port if it isn't already open. Safe to call repeatedly."""
        with self._lock:
            self._connect_unlocked()

    def disconnect(self) -> None:
        """Close the serial port if open. Safe to call even if never connected."""
        with self._lock:
            self._disconnect_unlocked()

    def send_command(self, command: str) -> None:
        """Send a bare command line. Requires an existing connection."""
        with self._lock:
            self._send_unlocked(command)

    def read_response(self) -> str:
        """Read a single response line. Requires an existing connection."""
        with self._lock:
            return self._read_unlocked()

    def request(self, command: str, expected: str | None = None) -> str:
        """Lazily connect, send `command`, and return the (optionally validated) response.

        This is the primary entry point most callers should use — it performs
        connect + send + read as a single locked operation, so concurrent
        requests can't interleave on the wire.
        """
        with self._lock:
            self._connect_unlocked()
            self._send_unlocked(command)
            response = self._read_unlocked()

        if expected is not None and response != expected:
            raise InvalidResponseError(
                f"Expected '{expected}' but received '{response}'."
            )
        return response

    # --- internal helpers: assume `self._lock` is already held ---

    def _connect_unlocked(self) -> None:
        if self.is_connected():
            return
        try:
            self._serial = serial.Serial(
                port=self._port,
                baudrate=self._baud_rate,
                timeout=self._timeout,
                write_timeout=self._timeout,
            )
        except serial.SerialException as exc:
            self._serial = None
            raise PortUnavailableError(
                f"Could not open {self._port}: {exc}. Check that the port "
                "exists, the ESP32 is plugged in via its COM (not USB-OTG) "
                "port, and no other program (e.g. Arduino Serial Monitor) "
                "currently has it open."
            ) from exc

    def _disconnect_unlocked(self) -> None:
        if self._serial is not None:
            try:
                self._serial.close()
            finally:
                self._serial = None

    def _send_unlocked(self, command: str) -> None:
        if not self.is_connected():
            raise NotConnectedError()
        try:
            self._serial.write(f"{command}\n".encode("ascii"))
        except serial.SerialException as exc:
            self._disconnect_unlocked()
            raise DisconnectedError(
                f"Lost connection while sending '{command}': {exc}"
            ) from exc

    def _read_unlocked(self) -> str:
        if not self.is_connected():
            raise NotConnectedError()
        try:
            raw = self._serial.readline()
        except serial.SerialException as exc:
            self._disconnect_unlocked()
            raise DisconnectedError(
                f"Lost connection while reading response: {exc}"
            ) from exc

        if not raw:
            # pyserial's readline() returns b"" when the configured timeout
            # elapses with no data — it does not raise on its own.
            raise SerialTimeoutError(
                f"No response within {self._timeout}s. Is the ESP32 running "
                "the PING/PONG firmware and powered on?"
            )
        return raw.decode("ascii", errors="replace").strip()
