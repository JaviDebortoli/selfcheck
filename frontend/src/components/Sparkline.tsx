interface Props {
  /** Points in order (daily means by mission day). */
  points: { day: number; value: number }[] | undefined
  width?: number
  height?: number
  className?: string
}

/** Tiny trend line without axes; stroke follows `currentColor`. Draws nothing without data and a flat line with one point. */
export function Sparkline({ points, width = 80, height = 24, className }: Props) {
  const data = (points ?? []).filter((p) => Number.isFinite(p.value))
  const pad = 2
  let path = ''
  if (data.length === 1) {
    path = `${pad},${height / 2} ${width - pad},${height / 2}`
  } else if (data.length > 1) {
    const values = data.map((p) => p.value)
    const min = Math.min(...values)
    const max = Math.max(...values)
    const span = max - min
    path = data
      .map((p, i) => {
        const x = pad + (i / (data.length - 1)) * (width - 2 * pad)
        const y = span === 0 ? height / 2 : pad + (1 - (p.value - min) / span) * (height - 2 * pad)
        return `${x.toFixed(1)},${y.toFixed(1)}`
      })
      .join(' ')
  }
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={className} aria-hidden="true">
      {path && (
        <polyline points={path} fill="none" stroke="currentColor" strokeWidth={1.4} strokeLinejoin="round" strokeLinecap="round" />
      )}
    </svg>
  )
}
