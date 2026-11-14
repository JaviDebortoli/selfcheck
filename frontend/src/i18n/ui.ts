// Screen texts (buttons, titles) live in the frontend (spec section 4.4). Rule texts come from the api catalog.

export type Language = 'es' | 'en'

const texts = {
  es: {
    appSubtitle: 'Monitoreo de la salud de la tripulación',
    day: 'Día',
    crewMember: 'Tripulante',
    overall: 'Estado general',
    connecting: 'Conectando…',
    offline: 'Sin conexión con la api',
    noData: 'Sin datos',
    triggeredBy: 'Activado por',
    bpm: 'lpm',
    states: {
      NORMAL: 'Normal',
      OBSERVATION: 'Observación',
      ALERT: 'Alerta',
      NOT_EVALUABLE: 'No evaluable',
    },
  },
  en: {
    appSubtitle: 'Crew health monitoring',
    day: 'Day',
    crewMember: 'Crew member',
    overall: 'Overall status',
    connecting: 'Connecting…',
    offline: 'No connection to the api',
    noData: 'No data',
    triggeredBy: 'Triggered by',
    bpm: 'bpm',
    states: {
      NORMAL: 'Normal',
      OBSERVATION: 'Observation',
      ALERT: 'Alert',
      NOT_EVALUABLE: 'Not evaluable',
    },
  },
} as const

export type UiTexts = (typeof texts)[Language]

export function ui(language: Language): UiTexts {
  return texts[language]
}
