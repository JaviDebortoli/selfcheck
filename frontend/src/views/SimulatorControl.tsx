import type { Catalog } from '../api/catalog'
import type { Snapshot } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'

export interface SimulatorControlProps {
  t: UiTexts
  language: Language
  snapshot: Snapshot | null
  catalog: Catalog | null
}

/** Simulator control screen (spec section 8.4). Placeholder until it is built. */
export function SimulatorControl(_props: SimulatorControlProps) {
  return <main className="simulator-control" />
}
