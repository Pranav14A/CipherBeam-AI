# CipherBeam AI — Desktop Backend (Party A)

FastAPI backend. Phase 1 scope: a single `GET /health` endpoint used to
verify frontend↔backend connectivity. No encryption, packetization, ESP32
communication, or AI logic exists yet — see
`docs/architecture/architecture-decision-document.md` for the approved
design those will follow later.

## Requirements

- Python 3.12
- [uv](https://docs.astral.sh/uv/)

## Run

```bash
cd desktop/backend
uv run uvicorn app.main:app --reload --port 8000
```

Backend will be available at `http://localhost:8000`. Health check:
`http://localhost:8000/health`.

## Test

```bash
cd desktop/backend
uv run pytest
```
