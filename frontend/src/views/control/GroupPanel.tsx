import type { Catalog } from '../../api/catalog'
import type { SubjectView } from '../../api/types'
import { ChevronIcon, GroupIcon, HelpIcon, StateIcon } from '../../components/Icons'
import { Sparkline } from '../../components/Sparkline'
import { StatusPill } from '../../components/StatusPill'
import type { Language, UiTexts } from '../../i18n/ui'
import { formatReason, helpFor } from '../../lib/format'
import { GROUP_VARIABLES } from '../../lib/groups'
import { stateClass } from '../../lib/states'
import type { ControlTexts } from './texts'
import type { DailyHistory } from './useHistory'
import { isFlagged, shownState, shownValue } from './variables'

interface Props {
  group: string
  subject: SubjectView | undefined
  history: DailyHistory
  catalog: Catalog | null
  t: UiTexts
  ct: ControlTexts
  language: Language
  onOpen: () => void
  /** Title override (the habitat panel says "Shared habitat"). */
  title?: string
  className?: string
}

/** One group as a table (Variable / 14 days / Value). The header opens the shared detail sheet. */
export function GroupPanel({ group, subject, history, catalog, t, ct, language, onOpen, title, className = '' }: Props) {
  const state = subject?.groups[group]?.state ?? 'NOT_EVALUABLE'
  const name = title ?? catalog?.groups[group]?.name ?? group
  return (
    <section className={`card stateful cc-panel ${stateClass(state)} ${isFlagged(state) ? 'tinted' : ''} ${className}`}>
      <button type="button" className="cc-panel-head" onClick={onOpen} aria-label={ct.openDetail(name)}>
        <GroupIcon group={group} size={18} />
        <h3>{name}</h3>
        <StatusPill state={state} t={t} small />
        <ChevronIcon size={16} className="faint" />
      </button>
      <table className="cc-table">
        <thead>
          <tr>
            <th className="label">{t.variable}</th>
            <th className="label cc-col-trend">{t.trend14}</th>
            <th className="label cc-col-value">{t.value}</th>
          </tr>
        </thead>
        <tbody>
          {GROUP_VARIABLES[group]?.map((variable) => (
            <Row key={variable} variable={variable} subject={subject} history={history} catalog={catalog} t={t} language={language} />
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Row({
  variable,
  subject,
  history,
  catalog,
  t,
  language,
}: {
  variable: string
  subject: SubjectView | undefined
  history: DailyHistory
  catalog: Catalog | null
  t: UiTexts
  language: Language
}) {
  const view = subject?.variables[variable]
  const state = shownState(variable, subject, catalog)
  const reason = formatReason(view?.reason, catalog, language, variable)
  return (
    <tr className={`cc-row ${stateClass(state)}`}>
      <td>
        <div className="cc-row-name">
          {catalog?.variables[variable]?.name ?? variable}
          {helpFor(variable, t) && (
            <span className="help" title={helpFor(variable, t)} aria-label={helpFor(variable, t)} role="img">
              <HelpIcon size={14} />
            </span>
          )}
        </div>
        {reason && (
          <div className="faint cc-row-sub" title={reason}>
            {reason}
          </div>
        )}
      </td>
      <td className="cc-col-trend">
        <Sparkline points={history[variable]} className="cc-spark" />
      </td>
      <td className="cc-col-value">
        <span className="cc-value mono">
          {shownValue(variable, subject, catalog, language)}
          {isFlagged(state) && <StateIcon state={state} size={14} />}
        </span>
        {view?.toConfirm && <span className="flag">{t.toConfirm}</span>}
      </td>
    </tr>
  )
}
