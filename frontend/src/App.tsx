import { useEffect, useState } from 'react'

interface PipelineStatus {
  received: number
  lastMeasurement: Record<string, unknown> | null
}

/** Phase 0 skeleton: shows that measurements reach the api. Replaced by the quick view in phase 1. */
export function App() {
  const [status, setStatus] = useState<PipelineStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/pipeline')
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`)
        return response.json() as Promise<PipelineStatus>
      })
      .then(setStatus)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: 24 }}>
      <h1>SelfCheck</h1>
      {error && <p>api: {error}</p>}
      {status && <pre>{JSON.stringify(status, null, 2)}</pre>}
    </main>
  )
}
