import type { Catalog } from '../../api/catalog'
import { setCrewAction } from '../../api/crewActions'
import type { CrewStatusView, HealthState, SubjectView } from '../../api/types'
import { EcgMonitor } from '../../components/EcgMonitor'
import { StateIcon } from '../../components/Icons'
import { Sparkline } from '../../components/Sparkline'
import { StatusPill } from '../../components/StatusPill'
import type { Language, UiTexts } from '../../i18n/ui'
import { digitsFor, formatNumber } from '../../lib/format'
import { groupsToReview } from '../../lib/groups'
import { missionClock, missionDay } from '../../lib/missionTime'
import { stateClass, stateColorVar } from '../../lib/states'
import type { ControlTexts } from './texts'
import type { DailyHistory } from './useHistory'
import { isFlagged, shownState, worstState } from './variables'

interface Props {
  memberId: string
  subject: SubjectView | undefined
  status: CrewStatusView | undefined
  history: DailyHistory
  catalog: Catalog | null
  t: UiTexts
  ct: ControlTexts
  language: Language
  onMedicalHistory: () => void
}

/** Center column header: selected crew member, live ECG, vital signs with 14-day trends and the groups to review. */
export function MemberPanel({ memberId, subject, status, history, catalog, t, ct, language, onMedicalHistory }: Props) {
  const training = status?.training ?? false
  const since = (ms: number | null | undefined) => (ms == null ? '' : `${t.day} ${missionDay(ms)} · ${missionClock(ms)}`)
  const names = t.crewNames[memberId]
  const overall = subject?.overall?.state ?? 'NOT_EVALUABLE'
  const heart = subject?.variables.HEART_RATE
  const value = (variable: string) => {
    const v = subject?.variables[variable]?.value
    return v == null ? '—' : formatNumber(v, language, digitsFor(variable))
  }
  const state = (variable: string) => shownState(variable, subject, catalog)
  const review = groupsToReview(subject)
  const rhythm = heart?.rhythm ? (catalog?.rhythms[heart.rhythm] ?? heart.rhythm) : '—'

  return (
    <section className={`card stateful cc-member ${stateClass(overall)} ${isFlagged(overall) ? 'tinted' : ''}`}>
      <div className="cc-member-head">
        <h2>{names?.name ?? memberId}</h2>
        <span className="muted">{names?.role}</span>
        <span className="cc-spacer" />
        <StatusPill state={overall} t={t} />
        <button type="button" className="cc-history-button" onClick={onMedicalHistory}>
          {t.medicalHistory}
        </button>
      </div>

      {status?.assistance && (
        <div className="cc-banner assistance" role="alert">
          <strong>{t.assistanceAlert}</strong>
          <span>{t.assistanceNote(since(status.assistanceSinceMs))}</span>
          <button type="button" className="cc-banner-button" onClick={() => setCrewAction(memberId, 'assistance', false)}>
            {t.markAttended}
          </button>
        </div>
      )}
      {training && (
        <div className="cc-banner training">
          <strong>{t.trainingActive}</strong>
          <span>{t.trainingNote(since(status?.trainingSinceMs))}</span>
        </div>
      )}

      <div className="cc-ecg">
        <div className="faint cc-small">{t.liveEcg}</div>
        <EcgMonitor heartRate={heart?.value ?? null} rhythm={heart?.rhythm ?? null} colorVar={stateColorVar(state('HEART_RATE') ?? overall)} height={140} />
      </div>

      <div className="cc-vitals" aria-label={t.vitalSigns}>
        <Vital label={t.heartRate} value={value('HEART_RATE')} unit={t.bpm} state={state('HEART_RATE')} points={history.HEART_RATE} exercise={training} t={t} />
        <Vital
          label={t.bloodPressure}
          value={subject?.variables.SYSTOLIC_BP?.value == null ? '—' : `${value('SYSTOLIC_BP')}/${value('DIASTOLIC_BP')}`}
          unit={ct.mmHg}
          state={worstState([state('SYSTOLIC_BP'), state('DIASTOLIC_BP')])}
          points={history.SYSTOLIC_BP}
          exercise={training}
          t={t}
        />
        <Vital label={ct.oxygenSaturation} value={value('SPO2')} unit="%" state={state('SPO2')} points={history.SPO2} toConfirm={subject?.variables.SPO2?.toConfirm} exercise={training} t={t} />
        <Vital label={ct.respiratoryRate} value={value('RESPIRATORY_RATE')} unit={ct.rpm} state={state('RESPIRATORY_RATE')} points={history.RESPIRATORY_RATE} exercise={training} t={t} />
        <Vital label={t.temperature} value={value('BODY_TEMPERATURE')} unit="°C" state={state('BODY_TEMPERATURE')} points={history.BODY_TEMPERATURE} exercise={training} t={t} />
        <Vital label={t.rhythm} value={rhythm} state={state('ECG_RHYTHM')} trend={false} />
      </div>

      <div className="cc-review">
        <strong>{t.review}:</strong>{' '}
        {review.length === 0 ? (
          <span className="muted">{t.allClear}</span>
        ) : (
          review.map((g, i) => (
            <span key={g.group} className={`cc-review-item ${stateClass(g.state)}`}>
              {i > 0 && <span className="faint"> · </span>}
              {catalog?.groups[g.group]?.name ?? g.group}
            </span>
          ))
        )}
      </div>
    </section>
  )
}

function Vital({
  label,
  value,
  unit,
  state,
  points,
  toConfirm,
  t,
  trend = true,
  exercise = false,
}: {
  label: string
  value: string
  unit?: string
  state: HealthState | null
  points?: { day: number; value: number }[]
  toConfirm?: boolean
  t?: UiTexts
  trend?: boolean
  /** Training in progress: the value may be altered by exercise. */
  exercise?: boolean
}) {
  return (
    <div className={`cc-vital ${stateClass(state)}`}>
      <div className="cc-vital-top">
        <span className="muted cc-small">{label}</span>
        {state && <StateIcon state={state} size={15} />}
      </div>
      <div className="cc-vital-value">
        <span className="mono">{value}</span>
        {unit && value !== '—' && <span className="faint cc-small">{unit}</span>}
        {toConfirm && t && <span className="flag">{t.toConfirm}</span>}
        {exercise && t && <span className="cc-exercise-tag">{t.exerciseTag}</span>}
      </div>
      {trend && <Sparkline points={points} width={110} height={24} className="cc-spark" />}
    </div>
  )
}
