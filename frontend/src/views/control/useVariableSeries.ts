import { useEffect, useMemo, useState } from 'react'
import type { HealthState } from '../../api/types'

/** One time bucket of values (GET /api/history/{subject}/{variable}). */
export interface SeriesPoint {
  t: number
  avg: number
  min: number
  max: number
  n: number
}

export interface StatePeriod {
  fromMs: number
  toMs: number
  state: HealthState
}

export interface VariableSeries {
  fromMs: number
  toMs: number
  bucketMs: number
  points: SeriesPoint[]
  states: StatePeriod[]
}

/** Visible part of the mission, in mission time; null is the whole mission. */
export interface TimeWindow {
  fromMs: number
  toMs: number
}

const REFRESH_MS = 10_000
const ZOOM_DELAY_MS = 250

function load(subjectId: string, variable: string, period: TimeWindow | null, signal: AbortSignal): Promise<VariableSeries | null> {
  const range = period ? `?from=${Math.floor(period.fromMs)}&to=${Math.ceil(period.toMs)}` : ''
  return fetch(`/api/history/${encodeURIComponent(subjectId)}/${encodeURIComponent(variable)}${range}`, { signal })
    .then((response) => (response.ok ? (response.json() as Promise<VariableSeries>) : null))
    .catch(() => null)
}

/**
 * History of one variable for the chart. The whole mission is loaded and refreshed every 10 s (about 200 buckets);
 * while a window is zoomed, that window is loaded again with the same number of buckets, so zooming in shows more
 * detail. The result is the whole mission with the window's buckets in place of the coarse ones.
 */
export function useVariableSeries(subjectId: string, variable: string, period: TimeWindow | null) {
  const [overview, setOverview] = useState<VariableSeries | null>(null)
  const [detail, setDetail] = useState<VariableSeries | null>(null)

  useEffect(() => {
    let cancelled = false
    let controller: AbortController | null = null
    setOverview(null)
    const refresh = () => {
      controller?.abort()
      controller = new AbortController()
      load(subjectId, variable, null, controller.signal).then((series) => {
        if (!cancelled && series) setOverview(series)
      })
    }
    refresh()
    const timer = globalThis.setInterval(refresh, REFRESH_MS)
    return () => {
      cancelled = true
      globalThis.clearInterval(timer)
      controller?.abort()
    }
  }, [subjectId, variable])

  useEffect(() => {
    // The previous detail stays until the new one arrives, so the chart keeps its frame while loading.
    if (!period) {
      setDetail(null)
      return
    }
    const controller = new AbortController()
    const timer = globalThis.setTimeout(() => {
      load(subjectId, variable, period, controller.signal).then((series) => {
        if (!controller.signal.aborted && series) setDetail(series)
      })
    }, ZOOM_DELAY_MS)
    return () => {
      globalThis.clearTimeout(timer)
      controller.abort()
    }
  }, [subjectId, variable, period])

  const points = useMemo(() => {
    if (!overview) return []
    if (!detail) return overview.points
    return [
      ...overview.points.filter((p) => p.t < detail.fromMs),
      ...detail.points,
      ...overview.points.filter((p) => p.t > detail.toMs),
    ]
  }, [overview, detail])

  return { overview, points, loading: !overview }
}
