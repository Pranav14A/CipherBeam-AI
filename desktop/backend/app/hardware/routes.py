"""CipherBeam AI — Hardware routes (Party A, Phase 2D)

Exposes the ESP32 debug serial link over HTTP for the React frontend.
Deliberately minimal: only enough to prove
React -> FastAPI -> pyserial -> COM3 -> ESP32-S3 -> PONG works.

No LED control, no optical protocol, no encryption here yet.

Design note: hardware-unavailable conditions (ESP32 unplugged, port busy,
timeout, etc.) are expected, ordinary outcomes for a physical device link —
they're returned as HTTP 200 with `success: false` / `connected: false` and
a structured `error` code, not as HTTP error statuses. Only a genuinely
unexpected server-side bug would surface as a 500.
"""

from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

from app.hardware.serial_manager import SerialManager, SerialManagerError

router = APIRouter(prefix="/hardware", tags=["hardware"])


def get_serial_manager(request: Request) -> SerialManager:
    """FastAPI dependency — reads the shared SerialManager off app.state.

    Tests override this dependency (see test_hardware_routes.py) to inject a
    mock manager without touching a real serial port.
    """
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
    """Reports whether the COM port is currently open/reachable.

    This checks the serial link itself (attempting a lazy connect if needed)
    — it does not send an application-level command to the firmware. Use
    POST /hardware/ping to confirm the firmware is actually responding.
    """
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
    """Sends PING to the ESP32 over COM3 and expects PONG back."""
    try:
        response = await run_in_threadpool(manager.request, "PING", "PONG")
    except SerialManagerError as exc:
        return PingResponse(success=False, error=exc.code.value)
    return PingResponse(success=True, response=response)
