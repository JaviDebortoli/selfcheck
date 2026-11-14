import type { Catalog } from '../api/catalog'
import type { Snapshot } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'

export interface ControlCenterProps {
  snapshot: Snapshot
  catalog: Catalog | null
  crewMemberId: string
  onCrewMember: (id: string) => void
  t: UiTexts
  language: Language
}

/** Control center (spec section 8.3). Placeholder until it is built. */
export function ControlCenter(_props: ControlCenterProps) {
  return <main className="control-center" />
}
