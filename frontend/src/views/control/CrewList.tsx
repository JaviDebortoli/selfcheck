import type { Snapshot } from '../../api/types'
import { EcgMonitor } from '../../components/EcgMonitor'
import { StatusPill } from '../../components/StatusPill'
import type { Language, UiTexts } from '../../i18n/ui'
import { formatNumber } from '../../lib/format'
import { outOfRangeCount } from '../../lib/groups'
import { stateClass, stateColorVar } from '../../lib/states'
import type { ControlTexts } from './texts'
import { isFlagged } from './variables'

interface Props {
  snapshot: Snapshot
  selectedId: string
  onSelect: (id: string) => void
  t: UiTexts
  ct: ControlTexts
  language: Language
}

/** Left column: the four crew members with their overall state, a mini ECG and three key figures. */
export function CrewList({ snapshot, selectedId, onSelect, t, ct, language }: Props) {
  const overall = (id: string) => snapshot.subjects[id]?.overall?.state ?? 'NOT_EVALUABLE'
  const states = snapshot.crew.map((m) => overall(m.id))
  const count = (state: string) => states.filter((s) => s === state).length

  return (
    <section className="cc-crew" aria-label={t.crew}>
      <div className="cc-crew-head">
        <h2 className="label">{t.crew}</h2>
        <div className="faint cc-small">
          {ct.crewSummary(states.length, count('ALERT'), count('OBSERVATION'), count('NOT_EVALUABLE'))}
        </div>
      </div>
      {snapshot.crew.map((member) => {
        const subject = snapshot.subjects[member.id]
        const state = overall(member.id)
        const names = t.crewNames[member.id]
        const heart = subject?.variables.HEART_RATE
        const sleep = subject?.variables.SLEEP_HOURS?.value
        const selected = member.id === selectedId
        return (
          <button
            key={member.id}
            type="button"
            className={`card stateful cc-crew-card ${stateClass(state)} ${isFlagged(state) ? 'tinted' : ''} ${selected ? 'selected' : ''}`}
            aria-pressed={selected}
            aria-label={ct.selectMember(names?.name ?? member.id)}
            onClick={() => onSelect(member.id)}
          >
            <div className="cc-crew-top">
              <div>
                <div className="cc-crew-name">{names?.name ?? member.id}</div>
                <div className="muted cc-small">{names?.role}</div>
              </div>
              <StatusPill state={state} t={t} small />
            </div>
            <div className="cc-mini-ecg">
              <EcgMonitor
                heartRate={heart?.value ?? null}
                rhythm={heart?.rhythm ?? null}
                colorVar={stateColorVar(state)}
                height={46}
                compact
              />
            </div>
            <div className="cc-figures">
              <Figure value={heart?.value != null ? formatNumber(heart.value, language, 0) : '—'} label={t.bpm} />
              <Figure value={sleep != null ? formatNumber(sleep, language, 1) : '—'} label={t.sleepShort} />
              <Figure value={formatNumber(outOfRangeCount(subject), language, 0)} label={t.outOfRangeShort} />
            </div>
          </button>
        )
      })}
    </section>
  )
}

function Figure({ value, label }: { value: string; label: string }) {
  return (
    <div className="cc-figure">
      <span className="cc-figure-value mono">{value}</span>
      <span className="faint cc-small">{label}</span>
    </div>
  )
}
