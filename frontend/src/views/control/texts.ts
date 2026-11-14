import type { Language } from '../../i18n/ui'

/** Labels used only by the control center; everything else comes from `ui` or the api catalog. */
const es = {
  crewSummary: (total: number, alerts: number, observations: number, notEvaluable: number) =>
    [
      `${total} en total`,
      alerts > 0 ? (alerts === 1 ? '1 alerta' : `${alerts} alertas`) : null,
      observations > 0 ? `${observations} con observación` : null,
      notEvaluable > 0 ? `${notEvaluable} no evaluable${notEvaluable === 1 ? '' : 's'}` : null,
    ]
      .filter(Boolean)
      .join(' · '),
  sexLabel: 'Sexo',
  oxygenSaturation: 'Saturación de oxígeno',
  respiratoryRate: 'Frecuencia respiratoria',
  mmHg: 'mmHg',
  rpm: 'rpm',
  units: { mSv: 'mSv', strength: 'N·m', kg: 'kg', bone: 'g/cm²', kcal: 'kcal', ml: 'mL', min: 'min' },
  openDetail: (group: string) => `Ver el detalle de ${group}`,
  selectMember: (name: string) => `Seleccionar a ${name}`,
}

export type ControlTexts = typeof es

const en: ControlTexts = {
  crewSummary: (total, alerts, observations, notEvaluable) =>
    [
      `${total} in total`,
      alerts > 0 ? (alerts === 1 ? '1 alert' : `${alerts} alerts`) : null,
      observations > 0 ? `${observations} under observation` : null,
      notEvaluable > 0 ? `${notEvaluable} not evaluable` : null,
    ]
      .filter(Boolean)
      .join(' · '),
  sexLabel: 'Sex',
  oxygenSaturation: 'Oxygen saturation',
  respiratoryRate: 'Respiratory rate',
  mmHg: 'mmHg',
  rpm: 'rpm',
  units: { mSv: 'mSv', strength: 'N·m', kg: 'kg', bone: 'g/cm²', kcal: 'kcal', ml: 'mL', min: 'min' },
  openDetail: (group) => `Open the detail of ${group}`,
  selectMember: (name) => `Select ${name}`,
}

export function controlTexts(language: Language): ControlTexts {
  return language === 'es' ? es : en
}
