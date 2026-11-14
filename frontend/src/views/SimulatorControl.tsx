import { useCallback, useEffect, useState } from 'react'
import type { Catalog } from '../api/catalog'
import type { Snapshot } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'
import { missionClock, missionDay } from '../lib/missionTime'
import '../styles/simulator.css'

export interface SimulatorControlProps {
  t: UiTexts
  language: Language
  snapshot: Snapshot | null
  catalog: Catalog | null
}

interface SimulatorStatus {
  missionTimeMs: number
  running: boolean
  speed: number
  maxSpeed: number
  environment: string
  plannedDurationDays: number
  crew: string[]
  activeScenarios: { id: string; subjects: string[]; startedAt: number; endsAt: number }[]
}

interface ScenarioInfo {
  id: string
  target: 'CREW_MEMBER' | 'ALL_CREW' | 'HABITAT'
  texts: Record<string, { name: string; description: string }>
  durationMs: number
}

const SPEEDS = [1, 60, 600, 3600]
const ENVIRONMENTS = ['ORBITAL_STATION', 'LUNAR_BASE', 'DEEP_SPACE_TRANSIT']
const BASE = '/api/simulator'

async function send(method: string, path: string, body?: unknown): Promise<SimulatorStatus> {
  const response = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return (await response.json()) as SimulatorStatus
}

/** Simulator control screen (spec section 8.4): start, pause, speed, scenarios, reset and environment. */
export function SimulatorControl({ t, language, catalog }: SimulatorControlProps) {
  const [status, setStatus] = useState<SimulatorStatus | null>(null)
  const [scenarios, setScenarios] = useState<ScenarioInfo[]>([])
  const [targets, setTargets] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [confirmReset, setConfirmReset] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(() => {
    send('GET', '/status')
      .then((s) => {
        setStatus(s)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }, [])

  useEffect(() => {
    refresh()
    const timer = window.setInterval(refresh, 1000)
    fetch(`${BASE}/scenarios`)
      .then((r) => r.json() as Promise<ScenarioInfo[]>)
      .then(setScenarios)
      .catch(() => undefined)
    return () => window.clearInterval(timer)
  }, [refresh])

  const act = async (method: string, path: string, body?: unknown) => {
    setBusy(true)
    try {
      setStatus(await send(method, path, body))
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const crewName = (id: string) => (id === 'habitat' ? t.habitat : (t.crewNames[id]?.name ?? id))
  const scenarioName = (id: string) => scenarios.find((s) => s.id === id)?.texts[language]?.name ?? id

  return (
    <main className="simulator-control">
      {error && <div className="banner">{error}</div>}

      <section className="card sim-card sim-clock">
        <div className="label">{t.missionTime}</div>
        <div className="sim-time mono">
          {status ? `${t.day} ${missionDay(status.missionTimeMs)} · ${missionClock(status.missionTimeMs)}` : '—'}
        </div>
        <div className={`sim-running ${status?.running ? 'on' : ''}`}>
          <span className="dot" /> {status?.running ? t.running : t.paused} · {t.speedLabel(status?.speed ?? 1)}
        </div>
        <div className="sim-buttons">
          <button className="btn primary" disabled={busy || status?.running} onClick={() => act('POST', '/start')}>
            ▶ {t.start}
          </button>
          <button className="btn" disabled={busy || !status?.running} onClick={() => act('POST', '/pause')}>
            ❚❚ {t.pause}
          </button>
        </div>
        <div className="label">{t.speed}</div>
        <div className="sim-buttons">
          {SPEEDS.map((speed) => (
            <button
              key={speed}
              className={`btn ${status?.speed === speed ? 'selected' : ''}`}
              disabled={busy}
              onClick={() => act('PUT', '/speed', { speed })}
            >
              {t.speedLabel(speed)}
            </button>
          ))}
        </div>
      </section>

      <section className="card sim-card">
        <div className="label">{t.environment}</div>
        <div className="sim-buttons vertical">
          {ENVIRONMENTS.map((environment) => (
            <button
              key={environment}
              className={`btn ${status?.environment === environment ? 'selected' : ''}`}
              disabled={busy}
              onClick={() => act('PUT', '/environment', { environment })}
            >
              {catalog?.environments[environment] ?? environment}
            </button>
          ))}
        </div>
        {status && <p className="muted">{t.plannedDuration(status.plannedDurationDays)}</p>}

        <div className="label sim-spaced">{t.activeScenarios}</div>
        {status?.activeScenarios.length ? (
          <ul className="sim-active">
            {status.activeScenarios.map((s) => (
              <li key={`${s.id}-${s.startedAt}`}>
                <strong>{scenarioName(s.id)}</strong>
                <span className="muted">
                  {' '}
                  · {s.subjects.map(crewName).join(', ')} · {t.until} {t.day} {missionDay(s.endsAt)} {missionClock(s.endsAt)}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">{t.none}</p>
        )}

        <div className="sim-reset">
          {confirmReset ? (
            <>
              <p>{t.resetConfirm}</p>
              <div className="sim-buttons">
                <button
                  className="btn danger"
                  disabled={busy}
                  onClick={async () => {
                    await act('POST', '/reset')
                    setConfirmReset(false)
                  }}
                >
                  {busy ? t.resetting : t.reset}
                </button>
                <button className="btn" disabled={busy} onClick={() => setConfirmReset(false)}>
                  {t.close}
                </button>
              </div>
            </>
          ) : (
            <button className="btn danger" onClick={() => setConfirmReset(true)}>
              ↺ {t.reset}
            </button>
          )}
        </div>
      </section>

      <section className="card sim-card sim-scenarios">
        <div className="label">{t.scenarios}</div>
        <div className="scenario-grid">
          {scenarios.map((scenario) => {
            const texts = scenario.texts[language] ?? scenario.texts.en
            const target = targets[scenario.id] ?? status?.crew[0] ?? 'crew-1'
            return (
              <article key={scenario.id} className="scenario">
                <h3>{texts?.name}</h3>
                <p className="muted">{texts?.description}</p>
                <div className="scenario-actions">
                  {scenario.target === 'CREW_MEMBER' ? (
                    <select
                      className="chip"
                      aria-label={t.target}
                      value={target}
                      onChange={(e) => setTargets({ ...targets, [scenario.id]: e.target.value })}
                    >
                      {(status?.crew ?? []).map((id) => (
                        <option key={id} value={id}>
                          {crewName(id)}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="faint">{scenario.target === 'HABITAT' ? t.habitat : t.allCrew}</span>
                  )}
                  <button
                    className="btn primary"
                    disabled={busy}
                    onClick={() =>
                      act('POST', `/scenarios/${scenario.id}`, scenario.target === 'CREW_MEMBER' ? { crewMemberId: target } : {})
                    }
                  >
                    {t.trigger}
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      </section>
    </main>
  )
}
