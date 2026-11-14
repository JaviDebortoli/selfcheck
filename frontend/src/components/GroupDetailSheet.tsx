import { useEffect } from 'react'
import type { Catalog } from '../api/catalog'
import type { SubjectView, SummaryView } from '../api/types'
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
  const guidance = state ? info?.guidance?.[state] : undefined
  const reason = formatReason(view?.reason, catalog, language)
  const derived = variable === 'CAREER_EFFECTIVE_DOSE' || variable === 'SOLAR_EVENT_DOSE' || variable === 'ECG_RHYTHM'
  const shown =
    variable === 'ECG_RHYTHM'
      ? (catalog?.rhythms[subject?.variables.HEART_RATE?.rhythm ?? ''] ?? '—')
      : derived
        ? (view?.reason?.params.value ? `${view.reason.params.value} ${info?.unit ?? ''}` : '—')
        : formatValue(view, language)

  return (
    <div className={`variable-row ${stateClass(state)}`}>
      <div>
        <strong>{info?.name ?? variable}</strong>
        {variable === 'AIRBORNE_DUST' && (
          <span className="help" title={t.dustHelp}>
            <HelpIcon />
          </span>
        )}
        <div className="faint" style={{ fontSize: '0.8rem' }}>
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
        {info?.review && <span className="flag">{t.toReview}</span>}
        {variable === 'AIRBORNE_DUST' && <div className="faint">{t.dustHelp}</div>}
      </div>
    </div>
  )
}
