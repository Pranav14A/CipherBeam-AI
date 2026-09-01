"""CipherBeam AI — Desktop Backend (Party A)

Phase 1: a health-check endpoint verifying frontend <-> backend HTTP.
Phase 2D: adds a debug ESP32 serial link (see app/hardware/) — PING/PONG
over COM3 only. Still no LED control, optical protocol, encryption, or AI;
see docs/architecture/architecture-decision-document.md for what those
will eventually look like.
"""

import os

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.hardware.routes import router as hardware_router
from app.hardware.serial_manager import SerialManager

app = FastAPI(title="CipherBeam AI Backend", version="0.1.0")

# Restricted to the Vite dev server origin only — never "*".
# This will need to grow (e.g. a configurable origin list) once the frontend
# is no longer only running locally in dev mode, but that's out of scope
# for Phase 1/2.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# Single shared SerialManager for the process. Configurable via environment
# variables so the COM port isn't hardcoded — set ESP32_COM_PORT / \
# ESP32_BAUD_RATE before starting the server to override the defaults below.
app.state.serial_manager = SerialManager(
    port=os.getenv("ESP32_COM_PORT", "COM3"),
    baud_rate=int(os.getenv("ESP32_BAUD_RATE", "115200")),
)

app.include_router(hardware_router)


class HealthResponse(BaseModel):
    status: str
    service: str


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service="cipherbeam-backend")
