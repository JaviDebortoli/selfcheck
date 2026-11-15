import { useEffect, useState } from 'react'
import type { Language } from '../i18n/ui'

/** Texts of one language served by GET /api/catalog/{lang} (spec section 4.4). */
export interface Catalog {
  language: Language
  groups: Record<string, { name: string }>
  variables: Record<
    string,
    {
      group: string
      name: string
      measures: string
      /**
       * Recommendation per state (OBSERVATION, ALERT), optionally narrowed by the direction of the value and the
       * crew member's sex: ALERT_HIGH, OBSERVATION_HIGH_FEMALE. See [guidanceKeys].
       */
      guidance: Record<string, string> | null
      unit: string
      hasState: boolean
      /** False when the state repeats another variable's; such variables are not counted twice. */
      raisesAlerts: boolean
      review: boolean
      source: string
      environments: string[] | null
      /** Set when the rule compares a % of a pre-flight value or daily target. */
      reference: string | null
      /** Set when the rule uses a mean over this mission-time window (ISO-8601 duration). */
      window: string | null
      notes: string[]
    }
  >
  reasons: Record<string, string>
  alarmSigns: Record<string, string>
  rhythms: Record<string, string>
  contexts: Record<string, string>
  environments: Record<string, string>
  combinations: Record<string, { name: string; interpretation: string }>
}

const cache = new Map<Language, Catalog>()

/** Loads the catalog of the chosen language once and keeps it. */
export function useCatalog(language: Language): Catalog | null {
  const [catalog, setCatalog] = useState<Catalog | null>(cache.get(language) ?? null)

  useEffect(() => {
    const cached = cache.get(language)
    if (cached) {
      setCatalog(cached)
      return
    }
    let cancelled = false
    fetch(`/api/catalog/${language}`)
      .then((response) => response.json() as Promise<Catalog>)
      .then((loaded) => {
        cache.set(language, loaded)
        if (!cancelled) setCatalog(loaded)
      })
      .catch(() => undefined)
    return () => {
      cancelled = true
    }
  }, [language])

  return catalog
}
