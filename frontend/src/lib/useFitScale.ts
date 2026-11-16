import { useEffect, useState } from 'react'

/** Reference canvas of a screen and the range it may be scaled in. */
export interface FitRange {
  width: number
  height: number
  min: number
  max: number
}

/**
 * Each screen is designed on a fixed canvas (quick view 1280x800, control center 1920x1080, spec sections 8.2-8.3).
 * The whole screen is scaled uniformly to fit the window, but only inside [min, max]: beyond it the layout stops
 * growing (content centered at its maximum size) or the page scrolls. Cards never change place, so the crew keeps
 * finding each indicator where it always is.
 */
export function useFitScale(range: FitRange): number {
  const compute = () => {
    const fit = Math.min(window.innerWidth / range.width, window.innerHeight / range.height)
    return Math.min(range.max, Math.max(range.min, fit))
  }
  const [scale, setScale] = useState(compute)

  useEffect(() => {
    const onResize = () => setScale(compute())
    onResize()
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range.width, range.height, range.min, range.max])

  return scale
}
