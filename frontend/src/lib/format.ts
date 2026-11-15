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
    case 'RED_BLOOD_CELLS':
    case 'DIURESIS':
    case 'CREATININE':
      return 2
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

/** Lower-cases the first letter of a name used inside a sentence, unless it starts an acronym (PCR, CO2). */
function lowerFirst(name: string): string {
  return /^[A-ZÁÉÍÓÚÑ][A-ZÁÉÍÓÚÑ0-9]/.test(name) ? name : name.charAt(0).toLowerCase() + name.slice(1)
}

/** Explanation shown behind a "?" next to a variable that needs one: the absorbed dose (no state). */
export function helpFor(variable: string, t: UiTexts): string | undefined {
  if (variable === 'ABSORBED_DOSE') return t.absorbedDoseHelp
  return undefined
}

/** Value of a variable with its unit, or a dash. */
export function formatValue(view: VariableView | undefined, language: Language): string {
  if (!view || view.value == null) return '—'
  const unit = view.unit ? ` ${view.unit}` : ''
  if (view.variable === 'SOLAR_PARTICLE_EVENT') return view.value >= 1 ? (language === 'es' ? 'Activo' : 'Active') : language === 'es' ? 'Ninguno' : 'None'
  return `${formatNumber(view.value, language, digitsFor(view.variable))}${unit}`
}

/**
 * Translates a reason code with its parameters using the catalog templates. For rules evaluated as a % of a
 * pre-flight value or target, or as a window mean, the reason shows the evaluated value next to the cut.
 */
export function formatReason(
  reason: Reason | null | undefined,
  catalog: Catalog | null,
  language: Language,
  variable?: string,
): string {
  // DATA only repeats the value of a variable without state, and it is not re-sent when the value changes.
  if (!reason || !catalog || reason.code === 'DATA') return ''
  const template = catalog.reasons[reason.code] ?? reason.code
  const info = variable ? catalog.variables[variable] : undefined
  const params: Record<string, string> = { ...reason.params }
  if (params.evaluated != null && (info?.reference || info?.window)) {
    params.value = params.evaluated
    if (info?.reference) {
      params.unit = '%'
      if (params.condition) params.condition = `${params.condition} %`
    }
  }
  return template.replace(/\{(\w+)\}/g, (_, key: string) => {
    const raw = params[key]
    if (raw == null) return ''
    if (key === 'signs') return raw.split(',').map((s) => catalog.alarmSigns[s] ?? s).join(', ')
    if (key === 'rhythm') return catalog.rhythms[raw] ?? raw
    if (key === 'context') return catalog.contexts[raw] ?? raw
    if (key === 'source') return lowerFirst(catalog.variables[raw]?.name ?? raw)
    if (key === 'findings') return raw.split(',').map((v) => lowerFirst(catalog.variables[v]?.name ?? v)).join(', ')
    const number = Number(raw)
    // A % of a target reads better whole (98 %, not 98,609 %).
    const digits = key === 'value' && params.unit === '%' ? 0 : 3
    return Number.isFinite(number) && /^-?\d+(\.\d+)?$/.test(raw) ? formatNumber(number, language, digits) : raw
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
