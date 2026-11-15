import { LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, MarkAreaComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { useEffect, useRef, useState } from 'react'
import type { Catalog } from '../../api/catalog'
import type { HealthState } from '../../api/types'
import type { Language, UiTexts } from '../../i18n/ui'
import { formatNumber } from '../../lib/format'
import { missionClock, missionDay } from '../../lib/missionTime'
import { stateClass } from '../../lib/states'
import { type StatePeriod, type TimeWindow, useVariableSeries } from './useVariableSeries'

echarts.use([LineChart, GridComponent, TooltipComponent, DataZoomComponent, MarkAreaComponent, CanvasRenderer])

const HOUR_MS = 3_600_000
const DAY_MS = 24 * HOUR_MS
/** Few points (questionnaires, bone density): every measurement is drawn as a dot. */
const DOTS_UP_TO = 60

interface Props {
  subjectId: string
  variable: string
  catalog: Catalog | null
  t: UiTexts
  language: Language
}

/** Theme tokens read from the page, so the chart follows the light and dark themes. */
function themeColors() {
  const style = getComputedStyle(document.documentElement)
  const token = (name: string) => style.getPropertyValue(name).trim()
  return {
    line: token('--text-2'),
    band: token('--text-3'),
    axis: token('--text-3'),
    grid: token('--border'),
    surface: token('--surface'),
    raised: token('--raised'),
    text: token('--text'),
    brand: token('--brand'),
    NORMAL: token('--normal'),
    OBSERVATION: token('--observation'),
    ALERT: token('--alert'),
    NOT_EVALUABLE: token('--unknown'),
  }
}

/** Re-renders when the theme attribute of the page changes. */
function useThemeVersion() {
  const [version, setVersion] = useState(0)
  useEffect(() => {
    const observer = new MutationObserver(() => setVersion((v) => v + 1))
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
    return () => observer.disconnect()
  }, [])
  return version
}

function digitsFor(value: number) {
  return Math.abs(value) < 10 ? 2 : 1
}

function stateAt(states: StatePeriod[], t: number): HealthState | undefined {
  return states.find((p) => t >= p.fromMs && t <= p.toMs)?.state
}

/**
 * History of one variable along the mission (control center detail): mean per time bucket as a line, the range
 * between minimum and maximum as a band, and below it a strip with the state the engine computed at each moment.
 * The slider under the chart shows the whole mission; its handles, the mouse wheel or the presets pick a period, and
 * the period is loaded again with more detail.
 */
export function VariableChart({ subjectId, variable, catalog, t, language }: Props) {
  const host = useRef<HTMLDivElement>(null)
  const chart = useRef<echarts.ECharts | null>(null)
  const [period, setPeriod] = useState<TimeWindow | null>(null)
  const windowRef = useRef<TimeWindow | null>(null)
  const followEnd = useRef(false)
  const applying = useRef(false)
  const themeVersion = useThemeVersion()
  const { overview, points, loading } = useVariableSeries(subjectId, variable, period)
  const info = catalog?.variables[variable]
  const unit = info?.unit ?? ''
  const states = overview?.states ?? []
  const hasValues = points.length > 0

  // A new variable starts on the whole mission.
  useEffect(() => {
    windowRef.current = null
    followEnd.current = false
    setPeriod(null)
  }, [subjectId, variable])

  useEffect(() => {
    if (!host.current) return
    const instance = echarts.init(host.current)
    chart.current = instance
    const resize = new ResizeObserver(() => instance.resize())
    resize.observe(host.current)
    instance.on('datazoom', () => {
      if (applying.current) return
      const option = instance.getOption() as { dataZoom?: { start?: number; end?: number }[]; xAxis?: { min?: number; max?: number }[] }
      const zoom = option.dataZoom?.[0]
      const axis = option.xAxis?.[0]
      if (!zoom || axis?.min == null || axis.max == null) return
      const start = zoom.start ?? 0
      const end = zoom.end ?? 100
      const span = axis.max - axis.min
      followEnd.current = end >= 99.9
      const next = start <= 0.1 && end >= 99.9 ? null : { fromMs: axis.min + (span * start) / 100, toMs: axis.min + (span * end) / 100 }
      windowRef.current = next
      setPeriod(next)
    })
    return () => {
      resize.disconnect()
      instance.dispose()
      chart.current = null
    }
  }, [])

  useEffect(() => {
    const instance = chart.current
    if (!instance || !overview) return
    const c = themeColors()
    // A short period (mission start, deep zoom) needs seconds to tell its ticks apart.
    const shown = windowRef.current ?? overview
    const withSeconds = shown.toMs - shown.fromMs < 30 * 60_000
    const clock = (ms: number) =>
      withSeconds ? `${missionClock(ms)}:${String(Math.floor((ms % 60_000) / 1000)).padStart(2, '0')}` : missionClock(ms)
    const time = (ms: number) => `${clock(ms)}\n${t.day} ${missionDay(ms)}`
    const value = (v: number) => formatNumber(v, language, digitsFor(v))
    const dots = points.length <= DOTS_UP_TO

    instance.setOption(
      {
        animation: false,
        // Plot on top, the state strip right under it, the time labels under the strip and the slider at the bottom.
        grid: [
          { left: 56, right: 16, top: 12, bottom: 116 },
          { left: 56, right: 16, height: 10, bottom: 102 },
        ],
        xAxis: [
          {
            type: 'value',
            min: overview.fromMs,
            max: overview.toMs,
            gridIndex: 0,
            axisLine: { lineStyle: { color: c.grid } },
            axisTick: { show: false },
            axisLabel: { show: false },
            splitLine: { show: false },
          },
          {
            type: 'value',
            min: overview.fromMs,
            max: overview.toMs,
            gridIndex: 1,
            axisLine: { show: false },
            axisTick: { show: false },
            splitLine: { show: false },
            axisLabel: { color: c.axis, fontSize: 11, formatter: (ms: number) => time(ms), hideOverlap: true },
          },
        ],
        yAxis: [
          {
            type: 'value',
            scale: true,
            gridIndex: 0,
            splitNumber: 4,
            axisLabel: { color: c.axis, fontSize: 11, formatter: (v: number) => value(v) },
            splitLine: { lineStyle: { color: c.grid, width: 1 } },
          },
          { type: 'value', min: 0, max: 1, gridIndex: 1, show: false },
        ],
        dataZoom: [
          { type: 'inside', xAxisIndex: [0, 1], filterMode: 'none' },
          {
            type: 'slider',
            xAxisIndex: [0, 1],
            filterMode: 'none',
            height: 26,
            bottom: 8,
            left: 56,
            right: 16,
            borderColor: c.grid,
            backgroundColor: 'transparent',
            fillerColor: `color-mix(in srgb, ${c.brand} 18%, transparent)`,
            handleStyle: { color: c.raised, borderColor: c.axis },
            moveHandleStyle: { color: c.axis },
            dataBackground: { lineStyle: { color: c.axis, opacity: 0.6 }, areaStyle: { color: c.band, opacity: 0.15 } },
            selectedDataBackground: { lineStyle: { color: c.line }, areaStyle: { color: c.band, opacity: 0.25 } },
            textStyle: { color: c.axis, fontSize: 11 },
            labelFormatter: (ms: number) => time(ms).replace('\n', ' · '),
          },
        ],
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'line', lineStyle: { color: c.axis, width: 1 } },
          backgroundColor: c.raised,
          borderColor: c.grid,
          textStyle: { color: c.text, fontSize: 12 },
          formatter: (params: unknown) => {
            const first = (Array.isArray(params) ? params : [params]).find((p: { seriesIndex: number }) => p.seriesIndex === 0) as
              | { dataIndex: number }
              | undefined
            const point = first ? points[first.dataIndex] : undefined
            if (!point) return ''
            const state = stateAt(states, point.t)
            const lines = [
              `<div style="opacity:.75">${echarts.format.encodeHTML(time(point.t).replace('\n', ' · '))}</div>`,
              `<div><strong style="font-size:14px">${value(point.avg)} ${echarts.format.encodeHTML(unit)}</strong></div>`,
            ]
            if (point.n > 1) {
              lines.push(
                `<div style="opacity:.75">${echarts.format.encodeHTML(t.chartRange(value(point.min), value(point.max), point.n))}</div>`,
              )
            }
            if (state) lines.push(`<div style="opacity:.75">${echarts.format.encodeHTML(`${t.state}: ${t.states[state]}`)}</div>`)
            return lines.join('')
          },
        },
        series: [
          {
            type: 'line',
            name: info?.name ?? variable,
            data: points.map((p) => [p.t, p.avg]),
            xAxisIndex: 0,
            yAxisIndex: 0,
            color: c.line,
            lineStyle: { width: 2, color: c.line },
            showSymbol: dots,
            symbol: 'circle',
            symbolSize: 8,
            itemStyle: { color: c.line, borderColor: c.surface, borderWidth: 2 },
            z: 3,
          },
          {
            type: 'line',
            data: points.map((p) => [p.t, p.min]),
            stack: 'range',
            lineStyle: { opacity: 0 },
            symbol: 'none',
            silent: true,
            tooltip: { show: false },
            z: 1,
          },
          {
            type: 'line',
            data: points.map((p) => [p.t, p.max - p.min]),
            stack: 'range',
            lineStyle: { opacity: 0 },
            areaStyle: { color: c.band, opacity: 0.22 },
            symbol: 'none',
            silent: true,
            tooltip: { show: false },
            z: 1,
          },
          {
            type: 'line',
            data: [[overview.fromMs, 0.5]],
            xAxisIndex: 1,
            yAxisIndex: 1,
            symbol: 'none',
            lineStyle: { opacity: 0 },
            silent: true,
            tooltip: { show: false },
            markArea: {
              silent: true,
              data: states.map((p) => [
                { xAxis: p.fromMs, itemStyle: { color: c[p.state] ?? c.axis } },
                { xAxis: p.toMs },
              ]),
            },
          },
        ],
      },
      { replaceMerge: ['series'] },
    )

    // Keep the chosen period: the axis grows with the mission, so the window is set again in mission time.
    const current = windowRef.current
    if (current) {
      const width = current.toMs - current.fromMs
      const next = followEnd.current ? { fromMs: Math.max(overview.fromMs, overview.toMs - width), toMs: overview.toMs } : current
      applying.current = true
      instance.dispatchAction({ type: 'dataZoom', startValue: next.fromMs, endValue: next.toMs })
      applying.current = false
      if (next !== current) windowRef.current = next
    }
  }, [overview, points, states, themeVersion, info?.name, unit, variable, t, language])

  const preset = (spanMs: number | null) => {
    const instance = chart.current
    if (!instance || !overview) return
    const fromMs = spanMs == null ? overview.fromMs : Math.max(overview.fromMs, overview.toMs - spanMs)
    instance.dispatchAction({ type: 'dataZoom', startValue: fromMs, endValue: overview.toMs })
  }

  const shownStates = (['NORMAL', 'OBSERVATION', 'ALERT', 'NOT_EVALUABLE'] as HealthState[]).filter((s) => states.some((p) => p.state === s))

  return (
    <section className="variable-chart" aria-label={t.chartTitle(info?.name ?? variable)}>
      <div className="variable-chart-head">
        <h3>{info?.name ?? variable}</h3>
        {unit && <span className="faint">{unit}</span>}
        <div className="chart-presets" role="group" aria-label={t.chartPeriod}>
          <button type="button" onClick={() => preset(DAY_MS)}>
            {t.chartLast24h}
          </button>
          <button type="button" onClick={() => preset(7 * DAY_MS)}>
            {t.chartLast7d}
          </button>
          <button type="button" onClick={() => preset(null)}>
            {t.chartMission}
          </button>
        </div>
      </div>
      <div className="variable-chart-body">
        <div ref={host} className="variable-chart-canvas" style={{ opacity: hasValues || states.length ? 1 : 0.35 }} />
        {!loading && !hasValues && (
          <p className="variable-chart-note faint">{states.length ? t.chartStateOnly : t.chartNoData}</p>
        )}
      </div>
      {shownStates.length > 0 && (
        <div className="variable-chart-legend faint">
          <span>{t.chartStateStrip}</span>
          {shownStates.map((s) => (
            <span key={s} className="legend-item">
              <span className={`legend-swatch ${stateClass(s)}`} aria-hidden="true" />
              {t.states[s]}
            </span>
          ))}
        </div>
      )}
    </section>
  )
}
