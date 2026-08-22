import { useEffect, useState } from 'react'
import './App.css'

type BackendStatus = 'checking' | 'online' | 'offline'

const BACKEND_HEALTH_URL = 'http://localhost:8000/health'

function App() {
  const [status, setStatus] = useState<BackendStatus>('checking')

  useEffect(() => {
    let cancelled = false

    fetch(BACKEND_HEALTH_URL)
      .then((response) => {
        if (!response.ok) throw new Error(`Unexpected status ${response.status}`)
        return response.json()
      })
      .then((data) => {
        if (!cancelled) {
          setStatus(data.status === 'ok' ? 'online' : 'offline')
        }
      })
      .catch(() => {
        if (!cancelled) setStatus('offline')
      })

    return () => {
      cancelled = true
    }
  }, [])

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
    </main>
  )
}

export default App
