"""CipherBeam AI — Desktop Backend (Party A)

Phase 1 scope only: a single health-check endpoint used to verify that the
React frontend can reach the FastAPI backend over HTTP.

Deliberately NOT implemented yet (see docs/architecture/architecture-decision-document.md):
encryption, packetization, ESP32 serial communication, AI, database, auth.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(title="CipherBeam AI Backend", version="0.1.0")

# Restricted to the Vite dev server origin only — never "*".
# This will need to grow (e.g. a configurable origin list) once the frontend
# is no longer only running locally in dev mode, but that's out of scope
# for Phase 1.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["GET"],
    allow_headers=["*"],
)


class HealthResponse(BaseModel):
    status: str
    service: str


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service="cipherbeam-backend")
