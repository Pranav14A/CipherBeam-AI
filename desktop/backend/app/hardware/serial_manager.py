"""CipherBeam AI — ESP32 Serial Manager (Party A, Phase 2D)

A thin, testable abstraction around pyserial used to talk to the ESP32-S3
over the development/debug serial protocol.

The rest of the backend should never import `serial` directly — everything
goes through SerialManager, so pyserial only appears in this one file.
"""

from __future__ import annotations

import threading
import time
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
        self,
        message: str = "The ESP32 responded, but not with the expected value.",
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
    don't need to manage connection state themselves.

    A single lock protects every operation that touches the underlying
    serial.Serial instance. This prevents opening the same COM port twice
    and prevents two commands from interleaving on the wire.

    The manager uses a default 2-second timeout for normal commands.
    Individual long-running commands may provide their own timeout through
    `request(..., timeout=...)` or `request_until(...)`.
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

    def request(
        self,
        command: str,
        expected: str | None = None,
        timeout: float | None = None,
    ) -> str:
        """Lazily connect, send `command`, and return the response.

        Args:
            command: Command to send to the ESP32.
            expected: Optional response string that must be received.
            timeout: Optional per-request read timeout in seconds.

        When `timeout` is omitted, the manager's normal timeout is used.
        This allows normal commands to keep their short timeout while
        long-running commands use `request_until(...)`.
        """
        with self._lock:
            self._connect_unlocked()
            self._send_unlocked(command)
            response = self._read_unlocked(timeout=timeout)

        if expected is not None and response != expected:
            raise InvalidResponseError(
                f"Expected '{expected}' but received '{response}'."
            )

        return response

    def request_until(
        self,
        command: str,
        expected: str,
        timeout: float | None = None,
    ) -> str:
        """Send a command and wait until the expected response line is received.

        This is intended for long-running ESP32 operations whose serial port
        may produce intermediate output before the final completion response.

        The timeout applies to the entire operation, not to each individual
        response line.

        All intermediate response lines are consumed and ignored until the
        expected terminal response is received.

        The serial-manager lock remains held for the entire operation, so a
        second command cannot be sent until the first operation has actually
        produced its expected completion response.
        """
        effective_timeout = (
            self._timeout if timeout is None else timeout
        )

        if effective_timeout <= 0:
            raise ValueError("Timeout must be greater than zero.")

        deadline = time.monotonic() + effective_timeout

        with self._lock:
            self._connect_unlocked()
            self._send_unlocked(command)

            while True:
                remaining = deadline - time.monotonic()

                if remaining <= 0:
                    raise SerialTimeoutError(
                        f"No '{expected}' response within "
                        f"{effective_timeout}s."
                    )

                response = self._read_unlocked(timeout=remaining)

                if response == expected:
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
                f"exists, the ESP32 is plugged in via its COM (not USB-OTG) "
                f"port, and no other program (e.g. Arduino Serial Monitor) "
                f"currently has it open."
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

    def _read_unlocked(self, timeout: float | None = None) -> str:
        if not self.is_connected():
            raise NotConnectedError()

        original_timeout = self._serial.timeout

        if timeout is not None:
            self._serial.timeout = timeout

        try:
            raw = self._serial.readline()
        except serial.SerialException as exc:
            self._disconnect_unlocked()
            raise DisconnectedError(
                f"Lost connection while reading response: {exc}"
            ) from exc
        finally:
            self._serial.timeout = original_timeout

        if not raw:
            effective_timeout = (
                self._timeout if timeout is None else timeout
            )

            raise SerialTimeoutError(
                f"No response within {effective_timeout}s. Is the ESP32 "
                "running and powered on?"
            )

        return raw.decode("ascii", errors="replace").strip()