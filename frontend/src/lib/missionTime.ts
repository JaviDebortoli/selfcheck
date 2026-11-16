export const HOUR_MS = 3_600_000
export const DAY_MS = 24 * HOUR_MS

/** Mission day, starting at day 1 (spec section 5). */
export function missionDay(missionTimeMs: number): number {
  return Math.floor(missionTimeMs / DAY_MS) + 1
}

/** Hour of the mission day as HH:MM. */
export function missionClock(missionTimeMs: number): string {
  const msOfDay = ((missionTimeMs % DAY_MS) + DAY_MS) % DAY_MS
  const hours = Math.floor(msOfDay / HOUR_MS)
  const minutes = Math.floor((msOfDay % HOUR_MS) / 60_000)
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`
}
