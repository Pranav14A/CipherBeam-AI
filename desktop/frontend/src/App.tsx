import { useEffect, useState } from 'react'
import './App.css'

type BackendStatus = 'checking' | 'online' | 'offline'
type HardwareStatus = 'checking' | 'connected' | 'disconnected'
type LedState = 'on' | 'off'

const BACKEND_HEALTH_URL = 'http://localhost:8000/health'
const HARDWARE_STATUS_URL = 'http://localhost:8000/hardware/status'
const HARDWARE_PING_URL = 'http://localhost:8000/hardware/ping'
const HARDWARE_LED_URL = 'http://localhost:8000/hardware/led'
const HARDWARE_TRANSMIT_URL = 'http://localhost:8000/hardware/transmit'

function App() {
  const [status, setStatus] = useState<BackendStatus>('checking')
  const [hardwareStatus, setHardwareStatus] = useState<HardwareStatus>('checking')
  const [port, setPort] = useState('')
  const [pinging, setPinging] = useState(false)
  const [lastResponse, setLastResponse] = useState<string | null>(null)
  const [redState, setRedState] = useState<LedState>('off')
  const [greenState, setGreenState] = useState<LedState>('off')
  const [ledMessage, setLedMessage] = useState<string | null>(null)

  const [message, setMessage] = useState('')
  const [transmitting, setTransmitting] = useState(false)
  const [transmitMessage, setTransmitMessage] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    fetch(BACKEND_HEALTH_URL)
      .then((response) => {
        if (!response.ok) throw new Error(`Unexpected status ${response.status}`)
        return response.json()
      })
      .then((data) => {
        if (!cancelled) setStatus(data.status === 'ok' ? 'online' : 'offline')
      })
      .catch(() => {
        if (!cancelled) setStatus('offline')
      })

    fetch(HARDWARE_STATUS_URL)
      .then((response) => response.json())
      .then((data) => {
        if (cancelled) return
        setPort(data.port ?? '')
        setHardwareStatus(data.connected ? 'connected' : 'disconnected')
      })
      .catch(() => {
        if (!cancelled) setHardwareStatus('disconnected')
      })

    return () => {
      cancelled = true
    }
  }, [])

  const handlePing = async () => {
    setPinging(true)

    try {
      const response = await fetch(HARDWARE_PING_URL, { method: 'POST' })
      const data = await response.json()

      if (data.success) {
        setLastResponse(data.response)
        setHardwareStatus('connected')
      } else {
        setLastResponse(`ERROR: ${data.error}`)
        setHardwareStatus('disconnected')
      }
    } catch {
      setLastResponse('ERROR: request failed')
      setHardwareStatus('disconnected')
    } finally {
      setPinging(false)
    }
  }

  const toggleLed = async (led: 'red' | 'green') => {
    const current = led === 'red' ? redState : greenState
    const nextState: LedState = current === 'on' ? 'off' : 'on'

    try {
      const response = await fetch(HARDWARE_LED_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ led, state: nextState }),
      })

      const data = await response.json()

      if (data.success) {
        if (led === 'red') setRedState(nextState)
        else setGreenState(nextState)

        setLedMessage(`${led.toUpperCase()} ${nextState.toUpperCase()}`)
      } else {
        setLedMessage(`ERROR: ${data.error}`)
      }
    } catch {
      setLedMessage('ERROR: request failed')
    }
  }

  const handleTransmit = async () => {
    if (!message.trim()) {
      setTransmitMessage('ERROR: enter a message')
      return
    }

    setTransmitting(true)
    setTransmitMessage(null)

    try {
      const response = await fetch(HARDWARE_TRANSMIT_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message }),
      })

      const data = await response.json()

      if (data.success) {
        setTransmitMessage(`TRANSMISSION COMPLETE: ${data.message}`)
        setHardwareStatus('connected')
      } else {
        setTransmitMessage(`ERROR: ${data.error}`)
      }
    } catch {
      setTransmitMessage('ERROR: transmission request failed')
    } finally {
      setTransmitting(false)
    }
  }

  return (
    <main className="status-screen">
      <h1>CIPHERBEAM AI</h1>

      <p className="label">Backend:</p>
      <p className={`indicator ${status}`}>
        <span className="dot" aria-hidden="true">●</span>
        {status === 'checking' && 'CHECKING...'}
        {status === 'online' && 'ONLINE'}
        {status === 'offline' && 'OFFLINE'}
      </p>

      <section className="hardware-panel">
        <h2>ESP32 HARDWARE</h2>

        <p className="label">Connection:</p>
        <p className={`indicator ${hardwareStatus}`}>
          <span className="dot" aria-hidden="true">●</span>
          {hardwareStatus === 'checking' && 'CHECKING...'}
          {hardwareStatus === 'connected' && 'CONNECTED'}
          {hardwareStatus === 'disconnected' && 'DISCONNECTED'}
        </p>

        <p className="label">
          Port: <span className="value">{port || '—'}</span>
        </p>

        <p className="label">
          Device: <span className="value">ESP32-S3 N16R8</span>
        </p>

        <button
          className="ping-button"
          onClick={handlePing}
          disabled={pinging || transmitting}
        >
          {pinging ? 'PINGING...' : 'PING ESP32'}
        </button>

        {lastResponse !== null && (
          <p className="label">
            Last response: <span className="value">{lastResponse}</span>
          </p>
        )}

        <h2 className="led-heading">LED CONTROL</h2>

        <div className="led-buttons">
          <button
            className="ping-button"
            onClick={() => toggleLed('red')}
            disabled={transmitting}
          >
            RED: {redState.toUpperCase()}
          </button>

          <button
            className="ping-button"
            onClick={() => toggleLed('green')}
            disabled={transmitting}
          >
            GREEN: {greenState.toUpperCase()}
          </button>
        </div>

        {ledMessage !== null && (
          <p className="label">
            LED status: <span className="value">{ledMessage}</span>
          </p>
        )}

        <h2 className="led-heading">OPTICAL TRANSMISSION</h2>

        <p className="label">
          Message:
        </p>

        <input
          className="message-input"
          type="text"
          value={message}
          onChange={(event) => setMessage(event.target.value)}
          maxLength={100}
          placeholder="Enter message..."
          disabled={transmitting}
        />

        <p className="character-count">
          {message.length}/100 characters
        </p>

        <button
          className="transmit-button"
          onClick={handleTransmit}
          disabled={transmitting || !message.trim()}
        >
          {transmitting ? 'TRANSMITTING...' : 'SEND OPTICAL MESSAGE'}
        </button>

        {transmitMessage !== null && (
          <p className="label">
            Transmission: <span className="value">{transmitMessage}</span>
          </p>
        )}
      </section>
    </main>
  )
}

export default App