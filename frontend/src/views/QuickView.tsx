import type { Snapshot } from '../api/types'
import { EcgMonitor } from '../components/EcgMonitor'
import type { UiTexts } from '../i18n/ui'
import { stateClass, stateColorVar } from '../lib/states'

interface Props {
  snapshot: Snapshot
  crewMemberId: string
  t: UiTexts
}

/** Quick view (spec section 8.2): the crew member's own state at a glance. */
export function QuickView({ snapshot, crewMemberId, t }: Props) {
  const subject = snapshot.subjects[crewMemberId]
  const overall = subject?.overall ?? null
  const heartRate = subject?.variables.HEART_RATE ?? null
  const overallState = overall?.state ?? 'NOT_EVALUABLE'

  return (
    <section className={`monitor-panel ${stateClass(overallState)}`}>
      <div className="monitor-header">
        <div>
          <div className="label">{t.overall}</div>
          <div className="overall-state">{t.states[overallState]}</div>
          {overall?.triggerVariable && (
            <div className="muted">
              {t.triggeredBy}: {overall.triggerVariable}
            </div>
          )}
        </div>
        <div className="heart-rate">
          <span className="heart-rate-value">{heartRate?.value != null ? Math.round(heartRate.value) : '--'}</span>
          <span className="muted"> {t.bpm}</span>
          {heartRate?.rhythm && <div className="muted">{heartRate.rhythm}</div>}
        </div>
      </div>
      <EcgMonitor
        heartRate={heartRate?.value ?? null}
        rhythm={heartRate?.rhythm ?? null}
        colorVar={stateColorVar(overallState)}
      />
    </section>
  )
}
