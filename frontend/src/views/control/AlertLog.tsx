import type { Catalog } from '../../api/catalog'
import type { AlertView } from '../../api/types'
import { StatusPill } from '../../components/StatusPill'
import type { Language, UiTexts } from '../../i18n/ui'
import { formatReason } from '../../lib/format'
import { missionClock, missionDay } from '../../lib/missionTime'
import { stateClass } from '../../lib/states'

interface Props {
  alerts: AlertView[]
  catalog: Catalog | null
  t: UiTexts
  language: Language
}

/** Alert log of the whole crew and the habitat, newest first (spec section 7.3). Times are mission time. */
export function AlertLog({ alerts, catalog, t, language }: Props) {
  const sorted = [...alerts].sort((a, b) => b.missionTimeMs - a.missionTimeMs)
  return (
    <section className="card cc-side-panel cc-alerts">
      <div className="cc-side-head">
        <h2 className="label">{t.alertsLog}</h2>
        <span className="faint cc-small">{t.wholeCrew}</span>
      </div>
      {sorted.length === 0 ? (
        <p className="muted">{t.noAlerts}</p>
      ) : (
        <ol className="cc-alert-list">
          {sorted.map((alert, i) => (
            <AlertItem key={`${alert.subjectId}-${alert.code}-${alert.variable}-${alert.missionTimeMs}-${i}`} alert={alert} catalog={catalog} t={t} language={language} />
          ))}
        </ol>
      )}
    </section>
  )
}

function AlertItem({ alert, catalog, t, language }: { alert: AlertView; catalog: Catalog | null; t: UiTexts; language: Language }) {
  const subject = alert.subjectType === 'HABITAT' ? t.habitat : (t.crewNames[alert.subjectId]?.name ?? alert.subjectId)
  const combination = alert.combination ? catalog?.combinations[alert.code] : undefined
  const variableName = alert.variable ? (catalog?.variables[alert.variable]?.name ?? alert.variable) : ''
  const reason = formatReason({ code: alert.code, params: alert.params }, catalog, language, alert.variable ?? undefined)

  return (
    <li className={`cc-alert ${stateClass(alert.state)} ${alert.combination ? 'combination' : ''}`}>
      <div className="cc-alert-top">
        <StatusPill state={alert.state} t={t} small />
        {alert.combination && <span className="cc-priority">{t.highPriority}</span>}
        <span className="faint cc-small mono cc-alert-time">
          {t.day} {missionDay(alert.missionTimeMs)} · {missionClock(alert.missionTimeMs)}
        </span>
      </div>
      {alert.combination ? (
        <>
          <div className="cc-alert-title">{combination?.name ?? alert.code}</div>
          {combination?.interpretation && <div className="muted cc-small">{combination.interpretation}</div>}
          <div className="faint cc-small">
            {t.combination} · {subject}
          </div>
        </>
      ) : (
        <>
          <div className="cc-alert-title">
            {variableName}
            {variableName && reason ? ': ' : ''}
            <span className="cc-alert-reason">{reason}</span>
          </div>
          <div className="faint cc-small">{subject}</div>
        </>
      )}
    </li>
  )
}
