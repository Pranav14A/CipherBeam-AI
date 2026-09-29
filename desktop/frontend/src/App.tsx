import {
  useEffect,
  useMemo,
  useState,
} from 'react'
import type { CSSProperties } from 'react'
import './App.css'

type BackendStatus = 'checking' | 'online' | 'offline'
type HardwareStatus = 'checking' | 'connected' | 'disconnected'
type LedState = 'on' | 'off'
type AlgorithmId = 1 | 2

const BACKEND_HEALTH_URL = 'http://localhost:8000/health'
const HARDWARE_STATUS_URL = 'http://localhost:8000/hardware/status'
const HARDWARE_PING_URL = 'http://localhost:8000/hardware/ping'
const HARDWARE_LED_URL = 'http://localhost:8000/hardware/led'
const HARDWARE_TRANSMIT_URL = 'http://localhost:8000/hardware/transmit'
const BINARY_STREAMS = Array.from({ length: 42 }, (_, column) => {
  let value = (column * 7919 + 104729) >>> 0
  let bits = ''

  for (let index = 0; index < 34; index += 1) {
    value = (value * 1664525 + 1013904223) >>> 0
    bits += (value >>> 31) & 1
  }

  return bits
})

function crc16CcittFalse(bytes: Uint8Array): number {
  let crc = 0xffff

  for (const byte of bytes) {
    crc ^= byte << 8

    for (let bit = 0; bit < 8; bit += 1) {
      if ((crc & 0x8000) !== 0) {
        crc = ((crc << 1) ^ 0x1021) & 0xffff
      } else {
        crc = (crc << 1) & 0xffff
      }
    }
  }

  return crc & 0xffff
}

function toHex(value: number, width = 2): string {
  return value.toString(16).toUpperCase().padStart(width, '0')
}

function App() {
  
  const [backendStatus, setBackendStatus] =
    useState<BackendStatus>('checking')

  const [hardwareStatus, setHardwareStatus] =
    useState<HardwareStatus>('checking')

  const [port, setPort] = useState('—')
  const [baudRate, setBaudRate] = useState<number | null>(null)

  const [pinging, setPinging] = useState(false)
  const [lastResponse, setLastResponse] = useState('—')

  const [redState, setRedState] = useState<LedState>('off')
  const [greenState, setGreenState] = useState<LedState>('off')
  const [ledMessage, setLedMessage] = useState('')

  const [message, setMessage] = useState('')
  const [algorithmId, setAlgorithmId] = useState<AlgorithmId>(1)

  const [transmitting, setTransmitting] = useState(false)
  const [transmitMessage, setTransmitMessage] = useState('')

  useEffect(() => {
    checkBackend()
    checkHardware()

    const hardwareInterval = window.setInterval(() => {
      checkHardware()
    }, 2000)

    const backendInterval = window.setInterval(() => {
      checkBackend()
    }, 5000)

    return () => {
      window.clearInterval(hardwareInterval)
      window.clearInterval(backendInterval)
    }
  }, [])

  async function checkBackend() {
    try {
      const response = await fetch(BACKEND_HEALTH_URL, {
        cache: 'no-store',
      })

      if (!response.ok) {
        throw new Error('Backend unavailable')
      }

      await response.json()
      setBackendStatus('online')
    } catch {
      setBackendStatus('offline')
    }
  }

  async function checkHardware() {
    try {
      const response = await fetch(HARDWARE_STATUS_URL, {
        cache: 'no-store',
      })

      if (!response.ok) {
        throw new Error('Hardware status unavailable')
      }

      const data = await response.json()

      setPort(data.port || '—')

      setBaudRate(
        typeof data.baud_rate === 'number'
          ? data.baud_rate
          : null,
      )

      const connected = Boolean(data.connected)

      setHardwareStatus(
        connected ? 'connected' : 'disconnected',
      )

      if (!connected) {
        setRedState('off')
        setGreenState('off')
      }
    } catch {
      setHardwareStatus('disconnected')
      setPort('—')
      setBaudRate(null)
      setRedState('off')
      setGreenState('off')
    }
  }

  async function handlePing() {
    setPinging(true)
    setLastResponse('WAITING...')
    setLedMessage('')

    try {
      const response = await fetch(HARDWARE_PING_URL, {
        method: 'POST',
      })

      const data = await response.json()

      if (!response.ok || !data.success) {
        throw new Error(data.error || 'Ping failed')
      }

      setLastResponse(data.response || 'PONG')
      setHardwareStatus('connected')
    } catch (error) {
      setLastResponse(
        error instanceof Error ? error.message : 'PING FAILED',
      )

      await checkHardware()
    } finally {
      setPinging(false)
    }
  }

  async function toggleLed(led: 'red' | 'green') {
    if (!hardwareConnected) {
      setLedMessage('ESP32-S3 IS NOT CONNECTED')
      return
    }

    const currentState = led === 'red' ? redState : greenState

    const nextState: LedState =
      currentState === 'on' ? 'off' : 'on'

    setLedMessage('')

    try {
      const response = await fetch(HARDWARE_LED_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          led,
          state: nextState,
        }),
      })

      const data = await response.json()

      if (!response.ok || !data.success) {
        throw new Error(data.error || 'LED command failed')
      }

      if (led === 'red') {
        setRedState(nextState)
      } else {
        setGreenState(nextState)
      }

      setLedMessage(
        `${led.toUpperCase()} ${nextState.toUpperCase()}`,
      )
    } catch (error) {
      setLedMessage(
        error instanceof Error
          ? error.message
          : 'LED COMMAND FAILED',
      )

      await checkHardware()
    }
  }

  async function handleTransmit() {
    const trimmedMessage = message.trim()

    if (!trimmedMessage) {
      setTransmitMessage('ENTER A MESSAGE')
      return
    }

    if (trimmedMessage.length > 100) {
      setTransmitMessage('MAXIMUM PAYLOAD IS 100 BYTES')
      return
    }

    if (!hardwareConnected) {
      setTransmitMessage('ESP32-S3 IS NOT CONNECTED')
      await checkHardware()
      return
    }

    setTransmitting(true)
    setTransmitMessage('TRANSMISSION INITIALIZING...')

    try {
      const response = await fetch(HARDWARE_TRANSMIT_URL, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          message: trimmedMessage,
          algorithm_id: algorithmId,
        }),
      })

      const data = await response.json()

      if (!response.ok || !data.success) {
        throw new Error(
          data.error || 'Transmission failed',
        )
      }

      setTransmitMessage(
        `TRANSMISSION COMPLETE: ${
          data.message || 'TRANSMIT_DONE'
        }`,
      )
    } catch (error) {
      setTransmitMessage(
        error instanceof Error
          ? error.message
          : 'TRANSMISSION FAILED',
      )

      await checkHardware()
    } finally {
      setTransmitting(false)
    }
  }

  const backendOnline = backendStatus === 'online'
  const hardwareConnected = hardwareStatus === 'connected'

  const packetData = useMemo(() => {
    const encoder = new TextEncoder()
    const payload = encoder.encode(message)

    const version = 0x01
    const flags = algorithmId
    const length = payload.length

    const crcInput = new Uint8Array(
      3 + payload.length,
    )

    crcInput[0] = version
    crcInput[1] = flags
    crcInput[2] = length
    crcInput.set(payload, 3)

    const crc = crc16CcittFalse(crcInput)

    const payloadHex = Array.from(payload)
      .map((byte) => toHex(byte))
      .join(' ')

    const payloadPreview =
      message.length > 0 ? message : '—'

    return {
      sync: '0xA5',
      version: `0x${toHex(version)}`,
      flags: `0x${toHex(flags)}`,
      length,
      payload: payloadPreview,
      payloadHex: payloadHex || '—',
      crc: `0x${toHex(crc, 4)}`,
    }
  }, [message, algorithmId])

  return (
  <div className="app-shell">
    <div className="ambient-grid" />
    <div className="scanlines" />

    <div className="binary-waterfall" aria-hidden="true">
      {BINARY_STREAMS.map((stream, columnIndex) => (
        <div
          className="binary-stream"
          key={columnIndex}
          style={
            {
              '--stream-delay': `${-(columnIndex * 0.17)}s`,
              '--stream-opacity': `${0.025 + (columnIndex % 5) * 0.008}`,
            } as React.CSSProperties
          }
        >
          {stream.split('').map((bit, bitIndex) => (
            <span key={bitIndex}>{bit}</span>
          ))}
        </div>
      ))}
    </div>

      <header className="system-header">
        <div>
          <div className="brand-mark">
            <span className="brand-symbol">◈</span>
            CIPHERBEAM AI
          </div>

          <div className="system-subtitle">
            VISIBLE-LIGHT COMMUNICATION SYSTEM
          </div>
        </div>

        <div className="system-state">
          <span
            className={`status-dot ${
              backendOnline ? 'online' : 'offline'
            }`}
          />

          <div>
            <div className="system-state-label">
              SYSTEM
            </div>

            <div className="system-state-value">
              {backendStatus === 'checking'
                ? 'CHECKING'
                : backendOnline
                  ? 'ONLINE'
                  : 'OFFLINE'}
            </div>
          </div>
        </div>
      </header>

      <div className="dashboard-grid">
        <aside className="sidebar">
          <div className="sidebar-title">
            CONTROL DECK
          </div>

          <div className="nav-item active">
            <span>01</span>
            COMPOSE
          </div>

          <div className="nav-item">
            <span>02</span>
            TRANSMIT
          </div>

          <div className="nav-item">
            <span>03</span>
            HARDWARE
          </div>

          <div className="nav-item">
            <span>04</span>
            PACKET
          </div>

          <div className="nav-item">
            <span>05</span>
            MATRIX CORE
          </div>

          <div className="sidebar-spacer" />

          <div className="sidebar-footer">
            <div>CB-AI / DESKTOP</div>
            <div>OPTICAL LINK v1.0</div>
          </div>
        </aside>

        <main className="workspace">
          <section className="hero-panel">
            <div className="panel-kicker">
              OPTICAL TRANSMISSION / LIVE CONSOLE
            </div>

            <h1>Transmit data through light.</h1>

            <p>
              Air-gapped visible-light communication between
              the desktop transmitter and CipherBeam receiver.
            </p>

            <div
              className={`optical-chamber${
                transmitting
                  ? ' transmitting'
                  : transmitMessage.startsWith('TRANSMISSION COMPLETE')
                    ? ' transmission-complete'
        : ''
             }`}
          >
              <div className="chamber-grid" />

              <div className="chamber-node green-node">
                <div className="node-light green-light" />
                <span>CONTROL</span>
                <strong>GREEN</strong>
              </div>

              <div className="beam-track">
                <div
                  className={`beam-line ${
                    transmitting ? 'beam-active' : ''
                  }`}
                />

                <div
                  className={`beam-pulse ${
                    transmitting ? 'pulse-active' : ''
                  }`}
                />

                <div className="beam-label">
                  VISIBLE LIGHT CHANNEL
                </div>
              </div>

              <div className="chamber-node red-node">
                <div className="node-light red-light" />
                <span>DATA</span>
                <strong>RED</strong>
              </div>

              <div className="chamber-status">
  {transmitting
    ? 'TRANSMITTING'
    : transmitMessage.startsWith('TRANSMISSION COMPLETE')
      ? 'TRANSMISSION COMPLETE'
      : hardwareConnected
        ? 'CHANNEL READY'
        : 'HARDWARE OFFLINE'}
</div>
            </div>
          </section>

          <section className="main-console-grid">
            <div className="panel compose-panel">
              <div className="panel-heading">
                <span>01 / COMPOSE</span>
                <span className="panel-code">
                  PAYLOAD
                </span>
              </div>

              <label className="field-label">
                MESSAGE
              </label>

              <textarea
                value={message}
                onChange={(event) =>
                  setMessage(event.target.value)
                }
                maxLength={100}
                placeholder="ENTER TRANSMISSION PAYLOAD..."
                disabled={transmitting}
              />

              <div className="character-count">
                {new TextEncoder().encode(message).length} / 100
                BYTES
              </div>

              <label className="field-label">
                ALGORITHM PROFILE
              </label>

              <select
                value={algorithmId}
                onChange={(event) =>
                  setAlgorithmId(
                    Number(event.target.value) as AlgorithmId,
                  )
                }
                disabled={transmitting}
              >
                <option value={1}>
                  01 — CHACHA20-POLY1305
                </option>

                <option value={2}>
                  02 — AES-256-GCM
                </option>
              </select>

              <div className="payload-meta">
                <div>
                  <span>PAYLOAD</span>
                  <strong>PLAINTEXT</strong>
                </div>

                <div>
                  <span>PROFILE ID</span>
                  <strong>
                    0x0{algorithmId}
                  </strong>
                </div>
              </div>

              <button
                className="transmit-button"
                onClick={handleTransmit}
                disabled={
                  transmitting ||
                  !message.trim() ||
                  !hardwareConnected
                }
              >
                <span>
                  {transmitting
                    ? 'TRANSMITTING...'
                    : 'TRANSMIT'}
                </span>

                <span className="button-arrow">
                  →
                </span>
              </button>

              {transmitMessage && (
                <div
                  className={`message-banner ${
                    transmitMessage.startsWith(
                      'TRANSMISSION COMPLETE',
                    )
                      ? 'success'
                      : 'error'
                  }`}
                >
                  {transmitMessage}
                </div>
              )}
            </div>

            <div className="panel hardware-panel">
              <div className="panel-heading">
                <span>03 / HARDWARE</span>
                <span className="panel-code">
                  ESP32-S3
                </span>
              </div>

              <div className="hardware-status">
                <div className="hardware-icon">
                  ESP
                </div>

                <div>
                  <div className="hardware-name">
                    ESP32-S3
                  </div>

                  <div className="hardware-state">
                    <span
                      className={`status-dot ${
                        hardwareConnected
                          ? 'online'
                          : 'offline'
                      }`}
                    />

                    {hardwareStatus === 'checking'
                      ? 'CHECKING CONNECTION'
                      : hardwareConnected
                        ? 'CONNECTED'
                        : 'DISCONNECTED'}
                  </div>
                </div>
              </div>

              <div className="telemetry-grid">
                <div>
                  <span>PORT</span>
                  <strong>{port}</strong>
                </div>

                <div>
                  <span>BAUD</span>
                  <strong>
                    {baudRate ?? '—'}
                  </strong>
                </div>

                <div>
                  <span>CHANNEL</span>
                  <strong>USB SERIAL</strong>
                </div>

                <div>
                  <span>PROTOCOL</span>
                  <strong>UART</strong>
                </div>
              </div>

              <button
                className="secondary-button"
                onClick={handlePing}
                disabled={pinging}
              >
                {pinging
                  ? 'PINGING...'
                  : 'PING HARDWARE'}
              </button>

              <div className="response-line">
                <span>LAST RESPONSE</span>
                <strong>{lastResponse}</strong>
              </div>

              <div className="led-controls">
                <div className="led-control">
                  <div className="led-info">
                    <span className="led-indicator red-indicator" />
                    <span>RED / DATA</span>
                  </div>

                  <button
                    className={
                      redState === 'on'
                        ? 'led-button active-red'
                        : 'led-button'
                    }
                    onClick={() => toggleLed('red')}
                    disabled={!hardwareConnected}
                  >
                    {redState === 'on'
                      ? 'ON'
                      : 'OFF'}
                  </button>
                </div>

                <div className="led-control">
                  <div className="led-info">
                    <span className="led-indicator green-indicator" />
                    <span>GREEN / CONTROL</span>
                  </div>

                  <button
                    className={
                      greenState === 'on'
                        ? 'led-button active-green'
                        : 'led-button'
                    }
                    onClick={() => toggleLed('green')}
                    disabled={!hardwareConnected}
                  >
                    {greenState === 'on'
                      ? 'ON'
                      : 'OFF'}
                  </button>
                </div>
              </div>

              {ledMessage && (
                <div className="hardware-message">
                  {ledMessage}
                </div>
              )}
            </div>
          </section>

          <section className="lower-grid">
            <div className="panel packet-panel">
              <div className="panel-heading">
                <span>04 / PACKET</span>
                <span className="panel-code">
                  CIPHERBEAM FRAME
                </span>
              </div>

              <div className="packet-flow">
                <div className="packet-block">
                  <span>SYNC</span>
                  <strong>{packetData.sync}</strong>
                </div>

                <div className="packet-arrow">
                  →
                </div>

                <div className="packet-block">
                  <span>VERSION</span>
                  <strong>{packetData.version}</strong>
                </div>

                <div className="packet-arrow">
                  →
                </div>

                <div className="packet-block">
                  <span>FLAGS</span>
                  <strong>{packetData.flags}</strong>
                </div>

                <div className="packet-arrow">
                  →
                </div>

                <div className="packet-block">
                  <span>LENGTH</span>
                  <strong>{packetData.length}</strong>
                </div>

                <div className="packet-arrow">
                  →
                </div>

                <div className="packet-block payload-block">
                  <span>PAYLOAD</span>
                  <strong>
                    {packetData.payload}
                  </strong>
                </div>

                <div className="packet-arrow">
                  →
                </div>

                <div className="packet-block">
                  <span>CRC</span>
                  <strong>{packetData.crc}</strong>
                </div>
              </div>

              <div className="packet-details">
                <div>
                  <span>PAYLOAD HEX</span>
                  <strong>{packetData.payloadHex}</strong>
                </div>

                <div>
                  <span>CRC INPUT</span>
                  <strong>
                    VERSION + FLAGS + LENGTH + PAYLOAD
                  </strong>
                </div>
              </div>
            </div>

            <div className="panel matrix-panel">
              <div className="panel-heading">
                <span>05 / MATRIX CORE</span>
                <span className="panel-code">
                  LINK PARAMETERS
                </span>
              </div>

              <div className="matrix-grid">
                <div>
                  <span>DATA CHANNEL</span>
                  <strong>RED LED</strong>
                </div>

                <div>
                  <span>CONTROL CHANNEL</span>
                  <strong>GREEN LED</strong>
                </div>

                <div>
                  <span>BIT WINDOW</span>
                  <strong>150 MS</strong>
                </div>

                <div>
                  <span>GUARD</span>
                  <strong>200 MS</strong>
                </div>

                <div>
                  <span>MAX PAYLOAD</span>
                  <strong>100 BYTES</strong>
                </div>

                <div>
                  <span>CRC</span>
                  <strong>CCITT-FALSE</strong>
                </div>
              </div>
            </div>
          </section>
        </main>
      </div>

      <footer className="system-footer">
        <div>
          CIPHERBEAM AI / DESKTOP TRANSMITTER
        </div>

        <div className="footer-center">
          <span
            className={`status-dot ${
              hardwareConnected ? 'online' : 'offline'
            }`}
          />
          USB SERIAL LINK
        </div>

        <div>
          RED DATA · GREEN CONTROL · 150MS/BIT
        </div>
      </footer>
    </div>
  )
}

export default App