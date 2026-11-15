import { useEffect } from 'react'
import type { Catalog } from '../api/catalog'
import type { CrewMemberView, HealthState, Reason, SubjectView, SummaryView } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'
import { formatAgo, formatNumber, formatReason, formatValue, helpFor } from '../lib/format'
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
  /** Sex of the crew member, for recommendations that depend on it; absent for the habitat. */
  sex?: CrewMemberView['sex']
  /**
   * Show the information column (what it measures, what to do). The control center hides it: its readers are health
   * professionals, so "what it measures" moves to a "?" after the variable name and there is no "what to do".
   */
  showInformation?: boolean
  /** Show the reason under each state ("En rango: 86 mg/dL"). The quick view hides it to keep the detail short. */
  showReason?: boolean
  onClose: () => void
}

/**
 * Detail of one group (spec sections 8.2 and 8.3): every variable with its value, state, reason, what it measures
 * and the guidance for its state. Variables without state are shown as data, without color.
 */
export function GroupDetailSheet({ group, subject, missionTimeMs, catalog, t, language, sex, showInformation = true, showReason = true, onClose }: Props) {
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
        <div className={`variable-table${showInformation ? '' : ' compact'}`} role="table">
          <div className="variable-row variable-head" role="row">
            <span>{t.variable}</span>
            <span>{t.value}</span>
            <span>{t.state}</span>
            {showInformation && <span>{t.information}</span>}
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
              sex={sex}
              showInformation={showInformation}
              showReason={showReason}
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
  sex,
  showInformation,
  showReason,
}: {
  variable: string
  subject: SubjectView | undefined
  missionTimeMs: number
  catalog: Catalog | null
  t: UiTexts
  language: Language
  sex?: CrewMemberView['sex']
  showInformation: boolean
  showReason: boolean
}) {
  const view = subject?.variables[variable]
  const info = catalog?.variables[variable]
  // A stateful variable never measured is "not evaluable": never green by default (spec section 3).
  const state = view?.state ?? (info?.hasState && !view?.reason ? 'NOT_EVALUABLE' : null)
  const guidance = guidanceFor(state, view?.reason, sex, info?.guidance)
  const reason = formatReason(view?.reason, catalog, language, variable)
  const help = helpFor(variable, t)
  // Without the information column the "?" carries what the variable measures, plus its explanation (dust, absorbed dose).
  const compactHelp = [info?.measures, help].filter(Boolean).join(' ')
  const derived = variable === 'CAREER_EFFECTIVE_DOSE' || variable === 'SOLAR_EVENT_DOSE' || variable === 'ECG_RHYTHM'
  const shown =
    variable === 'ECG_RHYTHM'
      ? (catalog?.rhythms[subject?.variables.HEART_RATE?.rhythm ?? ''] ?? '—')
      : derived
        ? (view?.reason?.params.value
          ? `${formatNumber(Number(view.reason.params.value), language, 2)} ${info?.unit ?? ''}`
          : '—')
        : formatValue(view, language)

  return (
    <div className={`variable-row ${stateClass(state)}`} role="row">
      <div>
        <strong>{info?.name ?? variable}</strong>
        {showInformation
          ? help && (
              <span className="help" title={help} aria-label={help}>
                <HelpIcon />
              </span>
            )
          : compactHelp && (
              <span className="help" title={compactHelp} aria-label={compactHelp}>
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
        {showReason && reason && <div className="reason muted">{reason}</div>}
      </div>
      {showInformation && (
        <div className="explain">
          <div>
            <strong>{t.whatItMeasures}:</strong> {info?.measures}
          </div>
          {guidance && (
            <div>
              <strong>{t.guidance}:</strong> {guidance}
            </div>
          )}
          {help && <div className="faint">{help}</div>}
        </div>
      )}
    </div>
  )
}

/**
 * Recommendation of the professional rules document, only in OBSERVATION and ALERT. The most specific key wins:
 * state + direction of the value + sex, then state + direction, then the state alone (spec section 8.2).
 */
function guidanceFor(
  state: HealthState | null,
  reason: Reason | null | undefined,
  sex: CrewMemberView['sex'] | undefined,
  texts: Record<string, string> | null | undefined,
): string | undefined {
  if (!texts || (state !== 'OBSERVATION' && state !== 'ALERT')) return undefined
  const direction = reason?.code === 'HIGH' || reason?.code === 'LOW' ? reason.code : undefined
  const keys = [
    direction && sex ? `${state}_${direction}_${sex}` : undefined,
    direction ? `${state}_${direction}` : undefined,
    state,
  ]
  for (const key of keys) if (key && texts[key]) return texts[key]
  return undefined
}
