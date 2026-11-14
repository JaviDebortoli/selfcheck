import type { HealthState, SubjectView } from '../api/types'

/** Body groups in display order (spec section 6.1); the habitat is shown apart. */
export const BODY_GROUPS = [
  'CARDIOVASCULAR_RESPIRATORY',
  'RADIOLOGICAL',
  'MUSCULOSKELETAL_BONE',
  'METABOLIC_RENAL_NUTRITIONAL',
  'NEUROLOGICAL_COGNITIVE_BEHAVIORAL',
  'HEMATOLOGICAL_IMMUNE',
  'DIGESTIVE_GENERAL',
] as const

export const HABITAT_GROUP = 'HABITAT'

/** Variables of each group in display order. */
export const GROUP_VARIABLES: Record<string, string[]> = {
  CARDIOVASCULAR_RESPIRATORY: ['HEART_RATE', 'ECG_RHYTHM', 'SYSTOLIC_BP', 'DIASTOLIC_BP', 'SPO2', 'RESPIRATORY_RATE'],
  RADIOLOGICAL: ['CAREER_EFFECTIVE_DOSE', 'SOLAR_EVENT_DOSE', 'MISSION_EFFECTIVE_DOSE', 'DOSE_RATE', 'ABSORBED_DOSE', 'SOLAR_PARTICLE_EVENT'],
  MUSCULOSKELETAL_BONE: ['MUSCLE_STRENGTH', 'BMD_HIP', 'BMD_SPINE', 'BMD_FEMORAL_NECK', 'BODY_MASS', 'PHYSICAL_ACTIVITY', 'MUSCULOSKELETAL_PAIN', 'BONE_RESORPTION_MARKERS'],
  METABOLIC_RENAL_NUTRITIONAL: ['GLUCOSE', 'CALORIC_INTAKE', 'PROTEIN_INTAKE', 'FLUID_INTAKE', 'CREATININE', 'UREA', 'SODIUM', 'POTASSIUM', 'URINARY_CALCIUM', 'URINE_SPECIFIC_GRAVITY'],
  NEUROLOGICAL_COGNITIVE_BEHAVIORAL: ['SLEEP_HOURS', 'SLEEP_QUALITY_PSQI', 'MOOD_PHQ9', 'FATIGUE', 'STRESS_PSS10', 'ATTENTION', 'MEMORY'],
  HEMATOLOGICAL_IMMUNE: ['HEMOGLOBIN', 'HEMATOCRIT', 'WHITE_BLOOD_CELLS', 'NEUTROPHILS', 'LYMPHOCYTES', 'PLATELETS', 'CRP'],
  DIGESTIVE_GENERAL: ['BODY_TEMPERATURE', 'NAUSEA', 'VOMITING', 'ABDOMINAL_PAIN', 'BOWEL_FREQUENCY', 'STOOL_FORM_BRISTOL', 'APPETITE'],
  HABITAT: ['CO2', 'NOISE_DAY', 'NOISE_NIGHT', 'AIRBORNE_DUST'],
}

/** Groups not in NORMAL, worst first (ALERT, OBSERVATION, NOT_EVALUABLE). */
export function groupsToReview(subject: SubjectView | undefined): { group: string; state: HealthState }[] {
  if (!subject) return []
  const order: Record<HealthState, number> = { ALERT: 0, OBSERVATION: 1, NOT_EVALUABLE: 2, NORMAL: 3 }
  return BODY_GROUPS.map((group) => ({ group, state: subject.groups[group]?.state ?? 'NOT_EVALUABLE' }))
    .filter((g) => g.state !== 'NORMAL')
    .sort((a, b) => order[a.state] - order[b.state])
}

/** Variables currently in OBSERVATION or ALERT. */
export function outOfRangeCount(subject: SubjectView | undefined): number {
  if (!subject) return 0
  return Object.values(subject.variables).filter((v) => v.state === 'OBSERVATION' || v.state === 'ALERT').length
}
