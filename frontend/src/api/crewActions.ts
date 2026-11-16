/** Crew actions from the quick view (training, medical assistance); the control center sees them through the snapshot. */
export type CrewAction = 'training' | 'assistance'

export async function setCrewAction(crewMemberId: string, action: CrewAction, active: boolean): Promise<void> {
  const response = await fetch(`/api/crew/${encodeURIComponent(crewMemberId)}/${action}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ active }),
  })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
}
