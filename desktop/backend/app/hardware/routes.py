"""CipherBeam AI — Hardware routes (Party A)

Phase 2D: GET /hardware/status, POST /hardware/ping.
Phase 5 (original roadmap): POST /hardware/led — strictly digital ON/OFF
GPIO control via the ESP32. No PWM, no optical protocol here.
"""

from enum import Enum

from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

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
async def hardware_status(manager: SerialManager = Depends(get_serial_manager)) -> StatusResponse:
    try:
        await run_in_threadpool(manager.connect)
    except SerialManagerError as exc:
        return StatusResponse(connected=False, port=manager.port, baud_rate=manager.baud_rate, error=exc.code.value)
    return StatusResponse(connected=manager.is_connected(), port=manager.port, baud_rate=manager.baud_rate)


@router.post("/ping", response_model=PingResponse)
async def hardware_ping(manager: SerialManager = Depends(get_serial_manager)) -> PingResponse:
    try:
        response = await run_in_threadpool(manager.request, "PING", "PONG")
    except SerialManagerError as exc:
        return PingResponse(success=False, error=exc.code.value)
    return PingResponse(success=True, response=response)


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
    payload: LedRequest, manager: SerialManager = Depends(get_serial_manager)
) -> LedResponse:
    """Sends LED_<COLOR>_<STATE> (e.g. LED_RED_ON) and expects OK back."""
    command = f"LED_{payload.led.value.upper()}_{payload.state.value.upper()}"
    try:
        await run_in_threadpool(manager.request, command, "OK")
    except SerialManagerError as exc:
        return LedResponse(success=False, error=exc.code.value)
    return LedResponse(success=True)
