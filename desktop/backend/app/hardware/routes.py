"""CipherBeam AI — Hardware routes (Party A)

Phase 2D: GET /hardware/status, POST /hardware/ping.
Phase 5: POST /hardware/led — strictly digital ON/OFF GPIO control.
Phase 7: POST /hardware/transmit — temporary basic optical message transmission.

Phase 7 uses:
- 8-bit ASCII
- 0xFE start marker
- message bytes
- 0xFF end marker
- 200 ms per bit

This is a temporary demonstration protocol. Final framing, CRC,
encryption, retransmission, and other protocol features belong to
later phases.
"""

from enum import Enum

from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field, field_validator

from app.hardware.serial_manager import SerialManager, SerialManagerError

router = APIRouter(prefix="/hardware", tags=["hardware"])


def get_serial_manager(request: Request) -> SerialManager:
    return request.app.state.serial_manager


class StatusResponse(BaseModel):
    connected: bool
    port: str
    baud_rate: int
    error: str | None = None


class PingResponse(BaseModel):
    success: bool
    response: str | None = None
    error: str | None = None


@router.get("/status", response_model=StatusResponse)
async def hardware_status(
    manager: SerialManager = Depends(get_serial_manager),
) -> StatusResponse:
    try:
        await run_in_threadpool(manager.connect)
    except SerialManagerError as exc:
        return StatusResponse(
            connected=False,
            port=manager.port,
            baud_rate=manager.baud_rate,
            error=exc.code.value,
        )

    return StatusResponse(
        connected=manager.is_connected(),
        port=manager.port,
        baud_rate=manager.baud_rate,
    )


@router.post("/ping", response_model=PingResponse)
async def hardware_ping(
    manager: SerialManager = Depends(get_serial_manager),
) -> PingResponse:
    try:
        response = await run_in_threadpool(
            manager.request,
            "PING",
            "PONG",
        )
    except SerialManagerError as exc:
        return PingResponse(
            success=False,
            error=exc.code.value,
        )

    return PingResponse(
        success=True,
        response=response,
    )


# --- Phase 5: LED control (digital ON/OFF only) ---


class LedColor(str, Enum):
    RED = "red"
    GREEN = "green"


class LedState(str, Enum):
    ON = "on"
    OFF = "off"


class LedRequest(BaseModel):
    led: LedColor
    state: LedState


class LedResponse(BaseModel):
    success: bool
    error: str | None = None


@router.post("/led", response_model=LedResponse)
async def hardware_led(
    payload: LedRequest,
    manager: SerialManager = Depends(get_serial_manager),
) -> LedResponse:
    """Sends LED_<COLOR>_<STATE> (e.g. LED_RED_ON) and expects OK back."""

    command = (
        f"LED_{payload.led.value.upper()}_"
        f"{payload.state.value.upper()}"
    )

    try:
        await run_in_threadpool(
            manager.request,
            command,
            "OK",
        )
    except SerialManagerError as exc:
        return LedResponse(
            success=False,
            error=exc.code.value,
        )

    return LedResponse(success=True)


# --- Phase 7: Basic optical transmission ---


OPTICAL_BIT_DURATION_SECONDS = 0.2
OPTICAL_MARKER_BYTES = 2
OPTICAL_TIMEOUT_MARGIN_SECONDS = 2.0


class TransmitRequest(BaseModel):
    message: str = Field(
        min_length=1,
        max_length=100,
        description="Printable ASCII message to transmit optically.",
    )

    @field_validator("message")
    @classmethod
    def validate_message(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Message cannot be empty or whitespace only.")

        try:
            value.encode("ascii")
        except UnicodeEncodeError as exc:
            raise ValueError(
                "Message must contain ASCII characters only."
            ) from exc

        # Keep the temporary Phase 7 protocol simple and camera-friendly.
        # Printable ASCII is 0x20 through 0x7E.
        if any(ord(char) < 0x20 or ord(char) > 0x7E for char in value):
            raise ValueError(
                "Message must contain printable ASCII characters only."
            )

        return value


class TransmitResponse(BaseModel):
    success: bool
    message: str | None = None
    error: str | None = None


def calculate_transmit_timeout(message: str) -> float:
    """Calculate enough time for START + payload + END.

    Every byte contains 8 bits and every bit lasts 200 ms.
    Phase 7 adds a small safety margin for serial/firmware overhead.
    """

    total_bytes = len(message) + OPTICAL_MARKER_BYTES
    transmission_seconds = (
        total_bytes * 8 * OPTICAL_BIT_DURATION_SECONDS
    )

    return transmission_seconds + OPTICAL_TIMEOUT_MARGIN_SECONDS


@router.post("/transmit", response_model=TransmitResponse)
async def hardware_transmit(
    payload: TransmitRequest,
    manager: SerialManager = Depends(get_serial_manager),
) -> TransmitResponse:
    """Transmit a message using the temporary Phase 7 optical protocol."""

    command = f"TRANSMIT:{payload.message}"
    timeout = calculate_transmit_timeout(payload.message)

    try:
        await run_in_threadpool(
            manager.request,
            command,
            "TRANSMIT_DONE",
            timeout,
        )
    except SerialManagerError as exc:
        return TransmitResponse(
            success=False,
            error=exc.code.value,
        )

    return TransmitResponse(
        success=True,
        message=payload.message,
    )