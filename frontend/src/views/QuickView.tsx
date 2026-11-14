import { useState, type CSSProperties, type ReactNode } from 'react'
import type { Catalog } from '../api/catalog'
import { setCrewAction, type CrewAction } from '../api/crewActions'
import type { HealthState, Snapshot, SubjectView, VariableView } from '../api/types'
import { EcgMonitor } from '../components/EcgMonitor'
import { GroupDetailSheet } from '../components/GroupDetailSheet'
import { ChevronIcon, GroupIcon, HelpIcon, StateIcon } from '../components/Icons'
import { StatusPill } from '../components/StatusPill'
import type { Language, UiTexts } from '../i18n/ui'
import { formatAgo, formatNumber } from '../lib/format'
import { GROUP_VARIABLES, groupsToReview, outOfRangeCount } from '../lib/groups'
import { stateClass, stateColorVar } from '../lib/states'
import '../styles/quick-view.css'

export interface QuickViewProps {
  snapshot: Snapshot
  catalog: Catalog | null
  crewMemberId: string
  onCrewMember: (id: string) => void
  t: UiTexts
  language: Language
}

/**
 * Quick view (spec section 8.2): the crew member's own state at a glance, without extra notifications.
 * A large panel with the ECG and the overall state, the cardiovascular card next to it, cards for the other six
 * groups and the habitat, and the detail of each card in a bottom popup.
 */
export function QuickView({ snapshot, catalog, crewMemberId, t, language }: QuickViewProps) {
  const [openGroup, setOpenGroup] = useState<string | null>(null)
  const subject = snapshot.subjects[crewMemberId]
  const habitat = snapshot.subjects.habitat
  const now = snapshot.missionTimeMs
  const groupName = (group: string) => catalog?.groups[group]?.name ?? group
  const card = (group: string, source: SubjectView | undefined, body: ReactNode, footer?: ReactNode) => (
    <GroupCard
      key={group}
      group={group}
      name={groupName(group)}
      state={source?.groups[group]?.state ?? 'NOT_EVALUABLE'}
      lastMeasured={lastMeasured(source, group)}
      now={now}
      t={t}
      onOpen={() => setOpenGroup(group)}
      footer={footer}
    >
      {body}
    </GroupCard>
  )
  const v = (name: string) => subject?.variables[name]
  const num = (value: number | null | undefined, digits = 1) => (value == null ? '—' : formatNumber(value, language, digits))

  return (
    <main className="quick-view">
      <StatusPanel snapshot={snapshot} subject={subject} crewMemberId={crewMemberId} catalog={catalog} t={t} language={language} />

      {card(
        'CARDIOVASCULAR_RESPIRATORY',
        subject,
        <div className="metric-row">
          <PulseRing heartRate={v('HEART_RATE')?.value ?? null} state={subject?.groups.CARDIOVASCULAR_RESPIRATORY?.state} />
          <div className="metric-list">
            <Metric value={num(v('SPO2')?.value, 0)} unit="% SpO2" state={v('SPO2')?.state} />
            <Metric value={num(v('RESPIRATORY_RATE')?.value, 0)} unit="rpm" state={v('RESPIRATORY_RATE')?.state} />
            <Metric
              value={`${num(v('SYSTOLIC_BP')?.value, 0)}/${num(v('DIASTOLIC_BP')?.value, 0)}`}
              unit="mmHg"
              state={worst(v('SYSTOLIC_BP')?.state, v('DIASTOLIC_BP')?.state)}
            />
          </div>
        </div>,
      )}

      {card(
        'HABITAT',
        habitat,
        <div className="metric-row">
          <span className="fan" aria-hidden="true">
            <GroupIcon group="HABITAT" size={34} />
          </span>
          <div className="metric-list">
            <Metric value={num(meanOf(habitat?.variables.CO2), 2)} unit="mmHg CO2" state={habitat?.variables.CO2?.state} />
            <Metric
              value={num(meanOf(habitat?.variables.NOISE_DAY), 0)}
              unit={`dBA · ${t.noiseDay.toLowerCase()}`}
              state={habitat?.variables.NOISE_DAY?.state}
            />
            <Metric
              value={num(habitat?.variables.AIRBORNE_DUST?.value, 2)}
              unit="mg/m³"
              state={habitat?.variables.AIRBORNE_DUST?.state}
              extra={
                <span className="help" title={t.dustHelp}>
                  <HelpIcon size={15} />
                </span>
              }
            />
          </div>
        </div>,
        <span className="faint">{t.habitatShared}</span>,
      )}

      {card('RADIOLOGICAL', subject, <RadiationBody subject={subject} t={t} language={language} />)}

      {card(
        'MUSCULOSKELETAL_BONE',
        subject,
        <div className="bars">
          <Bar
            label={t.strengthVsBaseline}
            percent={paramNumber(v('MUSCLE_STRENGTH'), 'evaluated')}
            mark={80}
            state={v('MUSCLE_STRENGTH')?.state}
            language={language}
          />
          <Bar
            label={t.boneVsBaseline}
            percent={minOf(['BMD_HIP', 'BMD_SPINE', 'BMD_FEMORAL_NECK'].map((n) => paramNumber(v(n), 'evaluated')))}
            mark={95}
            state={worst(v('BMD_HIP')?.state, v('BMD_SPINE')?.state, v('BMD_FEMORAL_NECK')?.state)}
            language={language}
          />
        </div>,
        <span className="faint">{t.nasaMark}</span>,
      )}

      {card(
        'METABOLIC_RENAL_NUTRITIONAL',
        subject,
        <div className="metric-row">
          <Flask percent={paramNumber(v('FLUID_INTAKE'), 'evaluated')} state={v('FLUID_INTAKE')?.state} />
          <div>
            <div className="big-number">
              {num(v('FLUID_INTAKE')?.value, 0)} <span className="unit">mL</span>
            </div>
            <div className="muted small">
              {t.fluidsToday(num(snapshot.crew.find((c) => c.id === crewMemberId)?.dailyTargets.fluidsMl, 0))}
            </div>
          </div>
        </div>,
      )}

      {card(
        'NEUROLOGICAL_COGNITIVE_BEHAVIORAL',
        subject,
        <div className="metric-row">
          <SleepWave state={v('SLEEP_HOURS')?.state} />
          <div>
            <div className="big-number">
              {num(v('SLEEP_HOURS')?.value)} <span className="unit">{t.sleepHours}</span>
            </div>
            <div className="muted small">
              {t.qualityShort} {num(v('SLEEP_QUALITY_PSQI')?.value, 0)} · {t.fatigueShort} {num(v('FATIGUE')?.value, 0)}/10
            </div>
          </div>
        </div>,
      )}

      {card(
        'HEMATOLOGICAL_IMMUNE',
        subject,
        <div className="metric-row">
          <Bubbles state={subject?.groups.HEMATOLOGICAL_IMMUNE?.state} />
          <div>
            <div className="big-number">
              Hb {num(v('HEMOGLOBIN')?.value)} <span className="unit">g/dL</span>
            </div>
            <div className="muted small">
              {t.whiteCells} {num(v('WHITE_BLOOD_CELLS')?.value, 0)}/µL
            </div>
          </div>
        </div>,
      )}

      {card(
        'DIGESTIVE_GENERAL',
        subject,
        <div className="metric-row">
          <Thermometer celsius={v('BODY_TEMPERATURE')?.value ?? null} state={v('BODY_TEMPERATURE')?.state} />
          <div>
            <div className="big-number">
              {num(v('BODY_TEMPERATURE')?.value)} <span className="unit">°C</span>
            </div>
            <div className="muted small">
              {t.nauseaPain}: {num(v('NAUSEA')?.value, 0)}/10 · {num(v('ABDOMINAL_PAIN')?.value, 0)}/10
            </div>
          </div>
        </div>,
      )}

      {openGroup && (
        <GroupDetailSheet
          group={openGroup}
          subject={openGroup === 'HABITAT' ? habitat : subject}
          missionTimeMs={now}
          catalog={catalog}
          t={t}
          language={language}
          onClose={() => setOpenGroup(null)}
        />
      )}
    </main>
  )
}

/** ECG and overall state of the crew member, with the crew member's own actions (training, medical assistance). */
function StatusPanel({
  snapshot,
  subject,
  crewMemberId,
  catalog,
  t,
  language,
}: {
  snapshot: Snapshot
  subject: SubjectView | undefined
  crewMemberId: string
  catalog: Catalog | null
  t: UiTexts
  language: Language
}) {
  const overall: HealthState = subject?.overall?.state ?? 'NOT_EVALUABLE'
  const review = groupsToReview(subject)
  const heartRate = subject?.variables.HEART_RATE
  const alerts = review.filter((g) => g.state === 'ALERT').length
  const status = snapshot.crewStatus?.[crewMemberId]
  const headline =
    overall === 'NORMAL'
      ? t.allClear
      : overall === 'ALERT'
        ? t.alertHeadline(alerts)
        : overall === 'OBSERVATION'
          ? t.systemsToReview(review.filter((g) => g.state !== 'NOT_EVALUABLE').length)
          : t.notEvaluableSystems(review.length)

  return (
    <section className={`card stateful status-panel ${stateClass(overall)}`}>
      <div className="ecg-area">
        <div className="ecg-caption">
          <span className="faint">{t.liveEcg}</span>
        </div>
        <div className="ecg-screen">
          <EcgMonitor heartRate={heartRate?.value ?? null} rhythm={heartRate?.rhythm ?? null} colorVar={stateColorVar(overall)} height={190} />
        </div>
      </div>
      <div className="status-area">
        <div>
          <div className="label">{t.overall}</div>
          <div className="headline">
            <StateIcon state={overall} size={30} />
            <span>{headline}</span>
          </div>
          <div className="status-figures">
            <div>
              <div className="muted small">{t.heartRate}</div>
              <div className="big-number">
                {heartRate?.value != null ? formatNumber(heartRate.value, language, 0) : '—'} <span className="unit">{t.bpm}</span>
              </div>
              {heartRate?.rhythm && <div className="faint small">{catalog?.rhythms[heartRate.rhythm] ?? heartRate.rhythm}</div>}
            </div>
            <div>
              <div className="muted small">{t.outOfRange}</div>
              <div className="big-number">{outOfRangeCount(subject)}</div>
            </div>
          </div>
        </div>
        <CrewActions crewMemberId={crewMemberId} training={status?.training ?? false} assistance={status?.assistance ?? false} t={t} />
      </div>
    </section>
  )
}

/**
 * The crew member's own actions: start or end a training session (exercise alters vital signs) and ask for medical
 * assistance. Both are reflected in the control center.
 */
function CrewActions({ crewMemberId, training, assistance, t }: { crewMemberId: string; training: boolean; assistance: boolean; t: UiTexts }) {
  const [busy, setBusy] = useState<CrewAction | null>(null)
  const [failed, setFailed] = useState(false)
  const act = async (action: CrewAction, active: boolean) => {
    setBusy(action)
    setFailed(false)
    try {
      await setCrewAction(crewMemberId, action, active)
    } catch {
      setFailed(true)
    } finally {
      setBusy(null)
    }
  }
  return (
    <div className="crew-actions">
      {failed && <span className="small action-error">{t.actionFailed}</span>}
      <button
        type="button"
        className={`action-button training ${training ? 'active' : ''}`}
        aria-pressed={training}
        disabled={busy !== null}
        onClick={() => act('training', !training)}
      >
        <ActivityIcon />
        {training ? t.stopTraining : t.startTraining}
      </button>
      <button
        type="button"
        className={`action-button assistance ${assistance ? 'active' : ''}`}
        aria-pressed={assistance}
        disabled={busy !== null}
        onClick={() => act('assistance', !assistance)}
      >
        <MedicalIcon />
        {assistance ? t.cancelAssistance : t.requestAssistance}
      </button>
    </div>
  )
}

const ActivityIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 12h4l3-8 4 16 3-8h4" />
  </svg>
)

const MedicalIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="3" width="18" height="18" rx="4" />
    <path d="M12 8v8M8 12h8" />
  </svg>
)

function GroupCard({
  group,
  name,
  state,
  lastMeasured,
  now,
  t,
  onOpen,
  footer,
  children,
}: {
  group: string
  name: string
  state: HealthState
  lastMeasured: number | null
  now: number
  t: UiTexts
  onOpen: () => void
  footer?: ReactNode
  children: ReactNode
}) {
  return (
    <button className={`card stateful group-card area-${group.toLowerCase()} ${stateClass(state)}`} onClick={onOpen}>
      <div className="group-card-header">
        <GroupIcon group={group} />
        <h2>{name}</h2>
        <StatusPill state={state} t={t} small />
      </div>
      <div className="group-card-body">{children}</div>
      <div className="group-card-footer">
        {footer ?? (
          <span className="faint">
            {t.lastMeasured}: {formatAgo(now, lastMeasured, t)}
          </span>
        )}
        <span className="details">
          {t.details} <ChevronIcon size={14} />
        </span>
      </div>
    </button>
  )
}

function Metric({ value, unit, state, extra }: { value: string; unit: string; state: HealthState | null | undefined; extra?: ReactNode }) {
  return (
    <div className={`metric ${stateClass(state)}`}>
      <strong>{value}</strong> <span className="unit">{unit}</span>
      {extra}
      {(state === 'OBSERVATION' || state === 'ALERT' || state === 'NOT_EVALUABLE') && (
        <span className="metric-state">
          <StateIcon state={state} size={14} />
        </span>
      )}
    </div>
  )
}

function RadiationBody({ subject, t, language }: { subject: SubjectView | undefined; t: UiTexts; language: Language }) {
  const career = subject?.variables.CAREER_EFFECTIVE_DOSE
  const solar = subject?.variables.SOLAR_EVENT_DOSE
  const dose = paramNumber(career, 'value')
  const limit = paramNumber(career, 'limit') ?? 600
  const fraction = dose == null ? 0 : Math.min(dose / limit, 1)
  const solarActive = solar?.state === 'OBSERVATION' || solar?.state === 'ALERT'
  return (
    <div className="metric-row">
      <svg className={`dose-ring ${stateClass(career?.state)}`} viewBox="0 0 64 64" width="70" height="70" aria-hidden="true">
        <circle cx="32" cy="32" r="26" className="ring-track" />
        <circle cx="32" cy="32" r="26" className="ring-fill" pathLength={100} strokeDasharray={`${fraction * 100} 100`} />
      </svg>
      <div>
        <div className="big-number">
          {dose == null ? '—' : formatNumber(dose, language, 0)} <span className="unit">mSv</span>
        </div>
        <div className="muted small">{t.careerDoseOf(formatNumber(limit, language, 0))}</div>
        {solarActive && (
          <div className={`metric small ${stateClass(solar?.state)}`}>
            <StateIcon state={solar?.state} size={13} /> ☀ {formatNumber(paramNumber(solar, 'value') ?? 0, language, 0)} mSv
          </div>
        )}
      </div>
    </div>
  )
}

function Bar({
  label,
  percent,
  mark,
  state,
  language,
}: {
  label: string
  percent: number | null
  mark: number
  state: HealthState | null | undefined
  language: Language
}) {
  return (
    <div className={`bar ${stateClass(state)}`}>
      <div className="bar-label">
        <span className="muted small">{label}</span>
        <strong>{percent == null ? '—' : `${formatNumber(percent, language, 0)} %`}</strong>
      </div>
      <div className="bar-track">
        <div className="bar-fill" style={{ width: `${Math.min(percent ?? 0, 100)}%` }} />
        <div className="bar-mark" style={{ left: `${mark}%` }} />
      </div>
    </div>
  )
}

function PulseRing({ heartRate, state }: { heartRate: number | null; state: HealthState | undefined }) {
  const period = heartRate && heartRate > 0 ? 60 / heartRate : 1
  return (
    <span className={`pulse-ring ${stateClass(state)}`} style={{ '--beat': `${period}s` } as CSSProperties} aria-hidden="true">
      <GroupIcon group="CARDIOVASCULAR_RESPIRATORY" size={26} />
    </span>
  )
}

function Flask({ percent, state }: { percent: number | null; state: HealthState | null | undefined }) {
  const level = Math.max(0.08, Math.min((percent ?? 0) / 100, 1))
  return (
    <svg className={`flask ${stateClass(state ?? null)}`} viewBox="0 0 48 64" width="52" height="68" aria-hidden="true">
      <defs>
        <clipPath id="flask-clip">
          <path d="M18 4h12v18l14 32a4 4 0 0 1-3.6 6H7.6A4 4 0 0 1 4 54l14-32z" />
        </clipPath>
      </defs>
      <g clipPath="url(#flask-clip)">
        <rect x="0" y="0" width="48" height="64" className="flask-bg" />
        <g style={{ transform: `translateY(${64 - level * 46}px)` }} className="flask-level">
          <path className="flask-wave" d="M-48 4 q6 -4 12 0 t12 0 t12 0 t12 0 t12 0 t12 0 t12 0 t12 0 V80 H-48z" />
        </g>
      </g>
      <path d="M18 4h12v18l14 32a4 4 0 0 1-3.6 6H7.6A4 4 0 0 1 4 54l14-32z" className="flask-outline" />
    </svg>
  )
}

function SleepWave({ state }: { state: HealthState | null | undefined }) {
  return (
    <svg className={`sleep-wave ${stateClass(state ?? null)}`} viewBox="0 0 80 40" width="80" height="40" aria-hidden="true">
      <path d="M0 20 q10 -18 20 0 t20 0 t20 0 t20 0 t20 0 t20 0" />
    </svg>
  )
}

function Bubbles({ state }: { state: HealthState | undefined }) {
  return (
    <svg className={`bubbles ${stateClass(state)}`} viewBox="0 0 60 50" width="62" height="52" aria-hidden="true">
      <circle cx="16" cy="32" r="9" />
      <circle cx="40" cy="18" r="11" />
      <circle cx="40" cy="40" r="6" className="filled" />
    </svg>
  )
}

function Thermometer({ celsius, state }: { celsius: number | null; state: HealthState | null | undefined }) {
  const fill = celsius == null ? 0.1 : Math.max(0.1, Math.min((celsius - 34) / 6, 1))
  return (
    <svg className={`thermometer ${stateClass(state ?? null)}`} viewBox="0 0 24 64" width="26" height="66" aria-hidden="true">
      <rect x="8" y="4" width="8" height="44" rx="4" className="thermo-tube" />
      <rect x="10" y={6 + 40 * (1 - fill)} width="4" height={40 * fill + 4} rx="2" className="thermo-fill" />
      <circle cx="12" cy="54" r="7" className="thermo-bulb" />
    </svg>
  )
}

const severity: Record<HealthState, number> = { ALERT: 3, OBSERVATION: 2, NOT_EVALUABLE: 1, NORMAL: 0 }

function worst(...states: (HealthState | null | undefined)[]): HealthState | null {
  const present = states.filter((s): s is HealthState => s != null)
  if (present.length === 0) return null
  return present.reduce((a, b) => (severity[b] > severity[a] ? b : a))
}

function paramNumber(view: VariableView | undefined, key: string): number | null {
  const raw = view?.reason?.params[key]
  return raw == null ? null : Number(raw)
}

function meanOf(view: VariableView | undefined): number | null {
  return paramNumber(view, 'evaluated') ?? view?.value ?? null
}

function minOf(values: (number | null)[]): number | null {
  const present = values.filter((v): v is number => v != null)
  return present.length ? Math.min(...present) : null
}

function lastMeasured(subject: SubjectView | undefined, group: string): number | null {
  const times = (GROUP_VARIABLES[group] ?? [])
    .map((name) => subject?.variables[name]?.measuredAtMs)
    .filter((t): t is number => t != null)
  return times.length ? Math.max(...times) : null
}

