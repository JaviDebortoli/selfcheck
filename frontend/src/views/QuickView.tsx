import type { Catalog } from '../api/catalog'
import type { Snapshot } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'

export interface QuickViewProps {
  snapshot: Snapshot
  catalog: Catalog | null
  crewMemberId: string
  onCrewMember: (id: string) => void
  t: UiTexts
  language: Language
}

/** Quick view (spec section 8.2). Placeholder while it is rebuilt. */
export function QuickView(_props: QuickViewProps) {
  return <main className="quick-view" />
}
