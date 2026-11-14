// Mirrors api/src/main/kotlin/io/selfcheck/api/snapshot/SnapshotViews.kt

export type HealthState = 'NORMAL' | 'OBSERVATION' | 'ALERT' | 'NOT_EVALUABLE'

export interface Reason {
  code: string
  params: Record<string, string>
}

export interface VariableView {
  variable: string
  value: number | null
  unit: string | null
  measuredAtMs: number | null
  quality: string | null
  context: string | null
  fasting: boolean | null
  rhythm: string | null
  alarmSigns: string[]
  boneMarker: string | null
  referenceRange: [number, number] | null
  /** Null when the value is shown as data, without state. */
  state: HealthState | null
  toConfirm: boolean
  reason: Reason | null
  stateAtMs: number | null
}

export interface SummaryView {
  state: HealthState
  triggerVariable: string | null
  atMs: number
}

export interface SubjectView {
  id: string
  type: 'CREW_MEMBER' | 'HABITAT'
  overall: SummaryView | null
  groups: Record<string, SummaryView>
  variables: Record<string, VariableView>
}

export interface AlertView {
  subjectId: string
  subjectType: string
  variable: string | null
  state: HealthState
  code: string
  params: Record<string, string>
  missionTimeMs: number
  /** Clinical combination: high priority, never changes a color (spec section 7.13). */
  combination: boolean
}

export interface CrewMemberView {
  id: string
  sex: 'MALE' | 'FEMALE'
  priorEffectiveDoseMsv: number
  preflightMuscleStrength: number
  preflightBodyMassKg: number
  preflightBoneDensity: Record<string, number>
  dailyTargets: Record<string, number>
}

export interface Snapshot {
  version: number
  missionTimeMs: number
  mission: { environment: string | null; plannedDurationDays: number | null }
  crew: CrewMemberView[]
  subjects: Record<string, SubjectView>
  alerts: AlertView[]
}
