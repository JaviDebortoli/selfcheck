import type { HealthState } from '../api/types'
import type { UiTexts } from '../i18n/ui'
import { stateClass } from '../lib/states'
import { StateIcon } from './Icons'

/** State badge: color, icon and word (spec section 7.1). A null state means "shown as data, without state". */
export function StatusPill({ state, t, small }: { state: HealthState | null | undefined; t: UiTexts; small?: boolean }) {
  return (
    <span className={`pill ${stateClass(state)} ${small ? 'pill-small' : ''}`}>
      <StateIcon state={state} size={small ? 13 : 15} />
      {state ? t.states[state] : t.noState}
    </span>
  )
}
