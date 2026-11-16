import { useEffect } from 'react'
import type { CrewMemberView } from '../../api/types'
import { CloseIcon } from '../../components/Icons'
import type { Language, UiTexts } from '../../i18n/ui'
import { formatNumber } from '../../lib/format'
import type { ControlTexts } from './texts'

interface Props {
  member: CrewMemberView
  t: UiTexts
  ct: ControlTexts
  language: Language
  onClose: () => void
}

/** Pre-flight medical history of a crew member (read-only: no data-entry forms, spec section 8.1). */
export function MedicalHistory({ member, t, ct, language, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const n = (value: number | undefined, digits = 1) => (value == null ? '—' : formatNumber(value, language, digits))
  const bone = member.preflightBoneDensity
  const targets = member.dailyTargets
  const names = t.crewNames[member.id]

  const rows: [string, string][] = [
    [ct.sexLabel, t.sex[member.sex] ?? member.sex],
    [t.priorDose, `${n(member.priorEffectiveDoseMsv)} ${ct.units.mSv}`],
    [t.preflightStrength, `${n(member.preflightMuscleStrength, 0)} ${ct.units.strength}`],
    [t.preflightMass, `${n(member.preflightBodyMassKg)} ${ct.units.kg}`],
    [t.preflightBone, `${n(bone.hip, 2)} / ${n(bone.spine, 2)} / ${n(bone.femoralNeck, 2)} ${ct.units.bone}`],
  ]
  const targetRows: [string, string][] = [
    [t.calories, `${n(targets.caloriesKcal, 0)} ${ct.units.kcal}`],
    [t.fluids, `${n(targets.fluidsMl, 0)} ${ct.units.ml}`],
    [t.activity, `${n(targets.activityMinutes, 0)} ${ct.units.min}`],
  ]

  return (
    <div className="sheet-backdrop cc-modal-backdrop" onClick={onClose}>
      <section className="cc-modal" role="dialog" aria-modal="true" aria-label={t.medicalHistory} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-header">
          <div>
            <h2>{t.medicalHistory}</h2>
            <div className="muted cc-small">
              {names?.name} · {names?.role}
            </div>
          </div>
          <button className="icon-button" onClick={onClose} aria-label={t.close}>
            <CloseIcon />
          </button>
        </div>
        <dl className="cc-dl">
          {rows.map(([label, value]) => (
            <Pair key={label} label={label} value={value} />
          ))}
        </dl>
        <h3 className="label cc-modal-sub">{t.dailyTargets}</h3>
        <dl className="cc-dl">
          {targetRows.map(([label, value]) => (
            <Pair key={label} label={label} value={value} />
          ))}
        </dl>
      </section>
    </div>
  )
}

function Pair({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd className="mono">{value}</dd>
    </>
  )
}
