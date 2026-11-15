import { useCallback, useState } from 'react'
import type { Catalog } from '../api/catalog'
import type { Snapshot } from '../api/types'
import { GroupDetailSheet } from '../components/GroupDetailSheet'
import type { Language, UiTexts } from '../i18n/ui'
import { BODY_GROUPS, HABITAT_GROUP } from '../lib/groups'
import '../styles/control-center.css'
import { AlertLog } from './control/AlertLog'
import { CrewList } from './control/CrewList'
import { GroupPanel } from './control/GroupPanel'
import { MedicalHistory } from './control/MedicalHistory'
import { MemberPanel } from './control/MemberPanel'
import { controlTexts } from './control/texts'
import { useHistory } from './control/useHistory'

export interface ControlCenterProps {
  snapshot: Snapshot
  catalog: Catalog | null
  crewMemberId: string
  onCrewMember: (id: string) => void
  t: UiTexts
  language: Language
}

const HABITAT_ID = 'habitat'
/** The cardiovascular group is shown as the vital signs block, so it gets no table. */
const PANEL_GROUPS = BODY_GROUPS.filter((g) => g !== 'CARDIOVASCULAR_RESPIRATORY')

/**
 * Control center (spec section 8.3): crew list, the selected crew member with vital signs and one panel per group,
 * the shared habitat and the alert log of the whole crew. Read-only: no ground link, reminders or data entry.
 */
export function ControlCenter({ snapshot, catalog, crewMemberId, onCrewMember, t, language }: ControlCenterProps) {
  const ct = controlTexts(language)
  const [detail, setDetail] = useState<{ group: string; subjectId: string } | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const closeDetail = useCallback(() => setDetail(null), [])
  const closeHistory = useCallback(() => setShowHistory(false), [])

  const memberId = snapshot.crew.some((m) => m.id === crewMemberId) ? crewMemberId : (snapshot.crew[0]?.id ?? crewMemberId)
  const subject = snapshot.subjects[memberId]
  const habitat = snapshot.subjects[HABITAT_ID]
  const member = snapshot.crew.find((m) => m.id === memberId)
  const history = useHistory(memberId)

  return (
    <main className="control-center">
      <CrewList snapshot={snapshot} selectedId={memberId} onSelect={onCrewMember} catalog={catalog} t={t} ct={ct} language={language} />

      <div className="cc-center">
        <MemberPanel
          memberId={memberId}
          subject={subject}
          status={snapshot.crewStatus?.[memberId]}
          history={history}
          catalog={catalog}
          t={t}
          ct={ct}
          language={language}
          onMedicalHistory={() => setShowHistory(true)}
        />
        <div className="cc-groups">
          {PANEL_GROUPS.map((group) => (
            <GroupPanel
              key={group}
              group={group}
              subject={subject}
              catalog={catalog}
              t={t}
              ct={ct}
              language={language}
              onOpen={() => setDetail({ group, subjectId: memberId })}
            />
          ))}
        </div>
      </div>

      <aside className="cc-side">
        <GroupPanel
          group={HABITAT_GROUP}
          subject={habitat}
          catalog={catalog}
          t={t}
          ct={ct}
          language={language}
          title={t.habitatShared}
          className="cc-habitat"
          onOpen={() => setDetail({ group: HABITAT_GROUP, subjectId: HABITAT_ID })}
        />
        <AlertLog alerts={snapshot.alerts} catalog={catalog} t={t} language={language} />
      </aside>

      {detail && (
        <GroupDetailSheet
          group={detail.group}
          subject={snapshot.subjects[detail.subjectId]}
          missionTimeMs={snapshot.missionTimeMs}
          catalog={catalog}
          t={t}
          language={language}
          sex={snapshot.crew.find((c) => c.id === detail.subjectId)?.sex}
          showInformation={false}
          onClose={closeDetail}
        />
      )}
      {showHistory && member && <MedicalHistory member={member} t={t} ct={ct} language={language} onClose={closeHistory} />}
    </main>
  )
}
