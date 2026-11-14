import { useEffect, useState } from 'react'
import type { Snapshot } from './types'

export type Connection = 'connecting' | 'live' | 'offline'

/** Live mission snapshot: the api pushes a `snapshot` event over SSE whenever something changes. */
export function useMission(): { snapshot: Snapshot | null; connection: Connection } {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [connection, setConnection] = useState<Connection>('connecting')

  useEffect(() => {
    const source = new EventSource('/api/stream')
    source.addEventListener('snapshot', (event) => {
      setSnapshot(JSON.parse((event as MessageEvent<string>).data) as Snapshot)
      setConnection('live')
    })
    source.onerror = () => setConnection('offline')
    return () => source.close()
  }, [])

  return { snapshot, connection }
}
