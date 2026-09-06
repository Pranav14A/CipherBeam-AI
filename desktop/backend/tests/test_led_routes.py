"""Route-layer tests for POST /hardware/led.

SerialManager is dependency-overridden — no real serial port or ESP32
required. Confirms correct LED_<COLOR>_<STATE> command construction and
structured error mapping.
"""

from unittest.mock import MagicMock

from fastapi.testclient import TestClient

from app.hardware.routes import get_serial_manager
from app.hardware.serial_manager import PortUnavailableError
from app.main import app


def _client_with_manager(mock_manager: MagicMock) -> TestClient:
    app.dependency_overrides[get_serial_manager] = lambda: mock_manager
    return TestClient(app)


def teardown_function() -> None:
    app.dependency_overrides.clear()


def test_led_red_on_sends_correct_command():
    mock_manager = MagicMock()
    mock_manager.request.return_value = "OK"
    client = _client_with_manager(mock_manager)

    response = client.post("/hardware/led", json={"led": "red", "state": "on"})

    assert response.status_code == 200
    assert response.json() == {"success": True, "error": None}
    mock_manager.request.assert_called_once_with("LED_RED_ON", "OK")


def test_led_green_off_sends_correct_command():
    mock_manager = MagicMock()
    mock_manager.request.return_value = "OK"
    client = _client_with_manager(mock_manager)

    response = client.post("/hardware/led", json={"led": "green", "state": "off"})

    assert response.status_code == 200
    mock_manager.request.assert_called_once_with("LED_GREEN_OFF", "OK")


def test_led_reports_structured_error_when_port_unavailable():
    mock_manager = MagicMock()
    mock_manager.request.side_effect = PortUnavailableError()
    client = _client_with_manager(mock_manager)

    response = client.post("/hardware/led", json={"led": "red", "state": "on"})

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is False
    assert body["error"] == "ESP32_PORT_UNAVAILABLE"


def test_led_rejects_invalid_color():
    mock_manager = MagicMock()
    client = _client_with_manager(mock_manager)

    response = client.post("/hardware/led", json={"led": "blue", "state": "on"})

    assert response.status_code == 422  # Pydantic enum validation
