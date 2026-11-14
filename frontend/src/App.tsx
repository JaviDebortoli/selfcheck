import { useState } from 'react'
import { useMission } from './api/useMission'
import { ui, type Language } from './i18n/ui'
import { missionClock, missionDay } from './lib/missionTime'
import { stateClass } from './lib/states'
import { QuickView } from './views/QuickView'

export function App() {
  const { snapshot, connection } = useMission()
  const [language, setLanguage] = useState<Language>('es')
  const [crewMemberId, setCrewMemberId] = useState('crew-1')
  const t = ui(language)

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <strong>SelfCheck</strong>
          <span className="muted">{t.appSubtitle}</span>
        </div>
        <nav className="crew-tabs">
          {(snapshot?.crew ?? []).map((member, index) => {
            const state = snapshot?.subjects[member.id]?.overall?.state ?? 'NOT_EVALUABLE'
            return (
              <button
                key={member.id}
                className={`crew-tab ${stateClass(state)} ${member.id === crewMemberId ? 'selected' : ''}`}
                onClick={() => setCrewMemberId(member.id)}
              >
                <span className="dot" /> {t.crewMember} {index + 1}
              </button>
            )
          })}
        </nav>
        <div className="topbar-right">
          {snapshot && (
            <span className="mission-time">
              {t.day} {missionDay(snapshot.missionTimeMs)} · {missionClock(snapshot.missionTimeMs)}
            </span>
          )}
          <select value={language} onChange={(e) => setLanguage(e.target.value as Language)} aria-label="Language">
            <option value="es">ES</option>
            <option value="en">EN</option>
          </select>
        </div>
      </header>
      <main>
        {connection !== 'live' && <div className="banner">{connection === 'offline' ? t.offline : t.connecting}</div>}
        {snapshot && <QuickView snapshot={snapshot} crewMemberId={crewMemberId} t={t} />}
      </main>
    </div>
  )
}
