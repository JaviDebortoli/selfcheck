import type { Catalog } from '../api/catalog'
import type { Reason, VariableView } from '../api/types'
import type { Language, UiTexts } from '../i18n/ui'
import { DAY_MS, HOUR_MS } from './missionTime'

/** Number in the reader's locale (decimal comma in Spanish), with at most [digits] decimals. */
export function formatNumber(value: number, language: Language, digits = 1): string {
  return new Intl.NumberFormat(language === 'es' ? 'es-AR' : 'en-US', { maximumFractionDigits: digits }).format(value)
}

/** Decimals that suit each variable. */
export function digitsFor(variable: string): number {
  switch (variable) {
    case 'URINE_SPECIFIC_GRAVITY':
    case 'BMD_HIP':
    case 'BMD_SPINE':
    case 'BMD_FEMORAL_NECK':
    case 'BONE_RESORPTION_MARKERS':
    case 'MISSION_EFFECTIVE_DOSE':
    case 'ABSORBED_DOSE':
    case 'AIRBORNE_DUST':
      return 3
    case 'HEART_RATE':
    case 'SPO2':
    case 'RESPIRATORY_RATE':
    case 'WHITE_BLOOD_CELLS':
    case 'NEUTROPHILS':
    case 'LYMPHOCYTES':
    case 'PLATELETS':
    case 'CALORIC_INTAKE':
    case 'FLUID_INTAKE':
    case 'SYSTOLIC_BP':
    case 'DIASTOLIC_BP':
    case 'DOSE_RATE':
      return 0
    default:
      return 1
  }
}

/** Value of a variable with its unit, or a dash. */
export function formatValue(view: VariableView | undefined, language: Language): string {
  if (!view || view.value == null) return '—'
  const unit = view.unit ? ` ${view.unit}` : ''
  if (view.variable === 'SOLAR_PARTICLE_EVENT') return view.value >= 1 ? (language === 'es' ? 'Activo' : 'Active') : language === 'es' ? 'Ninguno' : 'None'
  return `${formatNumber(view.value, language, digitsFor(view.variable))}${unit}`
}

/** Translates a reason code with its parameters using the catalog templates. */
export function formatReason(reason: Reason | null | undefined, catalog: Catalog | null, language: Language): string {
  if (!reason || !catalog) return ''
  const template = catalog.reasons[reason.code] ?? reason.code
  return template.replace(/\{(\w+)\}/g, (_, key: string) => {
    const raw = reason.params[key]
    if (raw == null) return ''
    if (key === 'signs') return raw.split(',').map((s) => catalog.alarmSigns[s] ?? s).join(', ')
    if (key === 'rhythm') return catalog.rhythms[raw] ?? raw
    if (key === 'context') return catalog.contexts[raw] ?? raw
    const number = Number(raw)
    return Number.isFinite(number) && /^-?\d+(\.\d+)?$/.test(raw) ? formatNumber(number, language, 3) : raw
  })
}

/** "hace 3 h" against mission time (spec section 5): never wall-clock time. */
export function formatAgo(missionNow: number, then: number | null | undefined, t: UiTexts): string {
  if (then == null) return '—'
  const delta = Math.max(0, missionNow - then)
  if (delta < 60_000) return t.justNow
  if (delta < HOUR_MS) return t.ago(`${Math.floor(delta / 60_000)} ${t.minutes}`)
  if (delta < DAY_MS) return t.ago(`${Math.floor(delta / HOUR_MS)} ${t.hours}`)
  return t.ago(t.days(Math.floor(delta / DAY_MS)))
}
