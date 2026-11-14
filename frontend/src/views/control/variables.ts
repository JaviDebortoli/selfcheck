import type { Catalog } from '../../api/catalog'
import type { HealthState, SubjectView } from '../../api/types'
import type { Language } from '../../i18n/ui'
import { formatNumber, formatValue } from '../../lib/format'

/** Doses whose value lives in the reason parameters (computed, not measured). */
const DERIVED = new Set(['CAREER_EFFECTIVE_DOSE', 'SOLAR_EVENT_DOSE'])

/**
 * State shown for a variable: its own state, or "not evaluable" for a stateful variable never measured
 * (never green by default, spec section 3). Null means "shown as data, without color".
 */
export function shownState(variable: string, subject: SubjectView | undefined, catalog: Catalog | null): HealthState | null {
  const view = subject?.variables[variable]
  if (view?.state) return view.state
  return catalog?.variables[variable]?.hasState && !view?.reason ? 'NOT_EVALUABLE' : null
}

/** Value with its unit as the control center shows it. */
export function shownValue(variable: string, subject: SubjectView | undefined, catalog: Catalog | null, language: Language): string {
  const view = subject?.variables[variable]
  if (DERIVED.has(variable)) {
    const raw = view?.reason?.params.value
    if (raw == null || raw === '') return '—'
    const number = Number(raw)
    const unit = catalog?.variables[variable]?.unit ?? view?.reason?.params.unit ?? ''
    return `${Number.isFinite(number) ? formatNumber(number, language, 1) : raw} ${unit}`.trim()
  }
  return formatValue(view, language)
}

/** Worst of several states (Alert, Observation, Not evaluable, Normal); null when none has a state. */
export function worstState(states: (HealthState | null | undefined)[]): HealthState | null {
  const order: HealthState[] = ['ALERT', 'OBSERVATION', 'NOT_EVALUABLE', 'NORMAL']
  return order.find((s) => states.includes(s)) ?? null
}

/** Observation and alert get a tinted background, like the design. */
export function isFlagged(state: HealthState | null | undefined): boolean {
  return state === 'OBSERVATION' || state === 'ALERT'
}
