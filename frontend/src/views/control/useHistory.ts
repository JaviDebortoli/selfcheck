import { useEffect, useState } from 'react'

export type DailyHistory = Record<string, { day: number; value: number }[]>

const REFRESH_MS = 10_000

/** Daily means of the last 14 mission days for one subject (GET /api/history/{id}); refreshed every 10 s. */
export function useHistory(subjectId: string): DailyHistory {
  const [history, setHistory] = useState<DailyHistory>({})

  useEffect(() => {
    let controller: AbortController | null = null
    let cancelled = false
    setHistory({})

    const load = () => {
      controller?.abort()
      controller = new AbortController()
      fetch(`/api/history/${encodeURIComponent(subjectId)}?days=14`, { signal: controller.signal })
        .then((response) => (response.ok ? (response.json() as Promise<DailyHistory>) : null))
        .then((loaded) => {
          if (!cancelled && loaded) setHistory(loaded)
        })
        .catch(() => undefined)
    }

    load()
    const timer = window.setInterval(load, REFRESH_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      controller?.abort()
    }
  }, [subjectId])

  return history
}
