import type { HealthState } from '../api/types'

/** CSS class of each state (spec section 7.1); variables without state get no color. */
export function stateClass(state: HealthState | null | undefined): string {
  switch (state) {
    case 'NORMAL':
      return 'state-normal'
    case 'OBSERVATION':
      return 'state-observation'
    case 'ALERT':
      return 'state-alert'
    case 'NOT_EVALUABLE':
      return 'state-unknown'
    default:
      return 'state-none'
  }
}

/** CSS custom property holding the color of each state, for canvas drawing. */
export function stateColorVar(state: HealthState | null | undefined): string {
  switch (state) {
    case 'NORMAL':
      return '--normal'
    case 'OBSERVATION':
      return '--observation'
    case 'ALERT':
      return '--alert'
    case 'NOT_EVALUABLE':
      return '--unknown'
    default:
      return '--muted'
  }
}
