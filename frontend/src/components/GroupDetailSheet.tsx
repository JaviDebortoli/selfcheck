import { useEffect } from 'react'
import type { Catalog } from '../api/catalog'
import type { HealthState, SubjectView, SummaryView } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'
import { formatAgo, formatReason, formatValue } from '../lib/format'
import { GROUP_VARIABLES } from '../lib/groups'
import { stateClass } from '../lib/states'
import { CloseIcon, GroupIcon, HelpIcon } from './Icons'
import { StatusPill } from './StatusPill'

interface Props {
  group: string
  subject: SubjectView | undefined
  missionTimeMs: number
  catalog: Catalog | null
  t: UiTexts
  language: Language
  onClose: () => void
}

/**
 * Detail of one group (spec sections 8.1 and 8.2): every variable with its value, state, reason, what it measures
 * and the guidance for its state. Variables without state are shown as data, without color.
 */
export function GroupDetailSheet({ group, subject, missionTimeMs, catalog, t, language, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const summary: SummaryView | undefined = subject?.groups[group]
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <section className="sheet" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-header">
          <GroupIcon group={group} size={22} />
          <h2>{catalog?.groups[group]?.name ?? group}</h2>
          <StatusPill state={summary?.state ?? 'NOT_EVALUABLE'} t={t} />
          <button className="icon-button" onClick={onClose} aria-label={t.close}>
            <CloseIcon />
          </button>
        </div>
        {/* One grid for every row: columns line up and size to their content (CSS subgrid). */}
        <div className="variable-table" role="table">
          <div className="variable-row variable-head" role="row">
            <span>{t.variable}</span>
            <span>{t.value}</span>
            <span>{t.state}</span>
            <span>{t.information}</span>
          </div>
          {GROUP_VARIABLES[group]?.map((variable) => (
            <VariableRow
              key={variable}
              variable={variable}
              subject={subject}
              missionTimeMs={missionTimeMs}
              catalog={catalog}
              t={t}
              language={language}
            />
          ))}
        </div>
      </section>
    </div>
  )
}

function VariableRow({
  variable,
  subject,
  missionTimeMs,
  catalog,
  t,
  language,
}: {
  variable: string
  subject: SubjectView | undefined
  missionTimeMs: number
  catalog: Catalog | null
  t: UiTexts
  language: Language
}) {
  const view = subject?.variables[variable]
  const info = catalog?.variables[variable]
  // A stateful variable never measured is "not evaluable": never green by default (spec section 3).
  const state = view?.state ?? (info?.hasState && !view?.reason ? 'NOT_EVALUABLE' : null)
  const guidance = guidanceFor(state, state ? info?.guidance?.[state] : undefined)
  const reason = formatReason(view?.reason, catalog, language, variable)
  const derived = variable === 'CAREER_EFFECTIVE_DOSE' || variable === 'SOLAR_EVENT_DOSE' || variable === 'ECG_RHYTHM'
  const shown =
    variable === 'ECG_RHYTHM'
      ? (catalog?.rhythms[subject?.variables.HEART_RATE?.rhythm ?? ''] ?? '—')
      : derived
        ? (view?.reason?.params.value ? `${view.reason.params.value} ${info?.unit ?? ''}` : '—')
        : formatValue(view, language)

  return (
    <div className={`variable-row ${stateClass(state)}`} role="row">
      <div>
        <strong>{info?.name ?? variable}</strong>
        {variable === 'AIRBORNE_DUST' && (
          <span className="help" title={t.dustHelp}>
            <HelpIcon />
          </span>
        )}
        <div className="faint small">
          {t.lastMeasured}: {formatAgo(missionTimeMs, view?.measuredAtMs ?? view?.stateAtMs, t)}
        </div>
      </div>
      <div className="value">
        {shown}
        {view?.toConfirm && <span className="flag">{t.toConfirm}</span>}
      </div>
      <div>
        <StatusPill state={state} t={t} small />
        {reason && <div className="reason muted">{reason}</div>}
      </div>
      <div className="explain">
        <div>
          <strong>{t.whatItMeasures}:</strong> {info?.measures}
        </div>
        {guidance && (
          <div>
            <strong>{t.guidance}:</strong> {guidance}
          </div>
        )}
        {variable === 'AIRBORNE_DUST' && <div className="faint">{t.dustHelp}</div>}
      </div>
    </div>
  )
}

/**
 * "What to do" only when there is something to do: hidden in NORMAL; in OBSERVATION and ALERT only the
 * instructions (the first sentence of each guidance text restates the state, already shown next to it).
 */
function guidanceFor(state: HealthState | null, text: string | undefined): string | undefined {
  if (!text || state == null || state === 'NORMAL') return undefined
  if (state !== 'OBSERVATION' && state !== 'ALERT') return text
  const sentences = text.split(/(?<=[.!?])\s+(?=[A-ZÁÉÍÓÚÑ¿¡])/)
  return sentences.length > 1 ? sentences.slice(1).join(' ') : text
}
