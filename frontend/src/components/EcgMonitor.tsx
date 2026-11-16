import { useEffect, useRef } from 'react'

interface Props {
  /** Beats per minute; null draws a flat line (no data). */
  heartRate: number | null
  /** Rhythm label from the ship's cardiac monitor. */
  rhythm: string | null
  /** CSS custom property for the trace color, e.g. `--normal`. */
  colorVar: string
  /** Canvas height in px (220 by default; small for the crew list). */
  height?: number
  /** Thinner trace and lower sweep speed for small monitors. */
  compact?: boolean
}

const SWEEP_PX_PER_S = 160
const GAP_PX = 18

const gauss = (t: number, center: number, width: number) => Math.exp(-((t - center) ** 2) / (2 * width * width))

/** One sinus beat, `t` seconds after its start (P wave, QRS complex, T wave). */
function sinusBeat(t: number): number {
  return (
    0.12 * gauss(t, 0.1, 0.025) -
    0.12 * gauss(t, 0.19, 0.01) +
    1.0 * gauss(t, 0.21, 0.011) -
    0.28 * gauss(t, 0.235, 0.012) +
    0.3 * gauss(t, 0.4, 0.045)
  )
}

/**
 * Atrial fibrillation: no P wave, a small fast wavy baseline (fibrillatory waves) and normal narrow QRS complexes;
 * the irregular spacing comes from the beat period (valueAt).
 */
function fibrillationBeat(t: number, nowS: number): number {
  return (
    0.035 * Math.sin(2 * Math.PI * 7 * nowS) +
    0.02 * Math.sin(2 * Math.PI * 11.3 * nowS) -
    0.12 * gauss(t, 0.19, 0.01) +
    1.0 * gauss(t, 0.21, 0.011) -
    0.28 * gauss(t, 0.235, 0.012) +
    0.22 * gauss(t, 0.36, 0.04)
  )
}

/** Ventricular tachycardia: wide, regular complexes without P waves. */
function ventricularBeat(t: number, period: number): number {
  return 0.8 * Math.sin((2 * Math.PI * t) / period) * gauss(t, period / 2, period / 2.2)
}

/**
 * Bedside-monitor style sweep: a cursor writes the trace left to right and erases just ahead of itself.
 * Each beat lasts 60 / heart rate seconds, read from the latest value when the beat starts.
 */
export function EcgMonitor({ heartRate, rhythm, colorVar, height = 220, compact = false }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const live = useRef({ heartRate, rhythm, colorVar })
  live.current = { heartRate, rhythm, colorVar }

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    let frame = 0
    let x = 0
    let lastY: number | null = null
    let beatStart = performance.now() / 1000
    let beatPeriod = 1
    let beatIndex = 0
    let previous = performance.now()

    const resize = () => {
      // Draw at the scaled size (CSS zoom from useFitScale) so the trace stays sharp.
      const zoom = parseFloat(getComputedStyle(canvas).getPropertyValue('--fit-scale')) || 1
      const ratio = (window.devicePixelRatio || 1) * zoom
      canvas.width = canvas.clientWidth * ratio
      canvas.height = canvas.clientHeight * ratio
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
      ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight)
      x = 0
      lastY = null
    }
    resize()
    window.addEventListener('resize', resize)

    const valueAt = (nowS: number): number => {
      const { heartRate: hr, rhythm: label } = live.current
      if (hr == null || hr <= 0) return 0
      while (nowS - beatStart >= beatPeriod) {
        beatStart += beatPeriod
        beatIndex += 1
        beatPeriod = 60 / hr
        // Isolated premature beats: every 7th beat comes early.
        if (label === 'PREMATURE_BEATS' && beatIndex % 7 === 0) beatPeriod *= 0.65
        // Atrial fibrillation: irregularly irregular beats, between 60 % and 140 % of the mean period.
        if (label === 'ATRIAL_FIBRILLATION_RAPID') beatPeriod *= 0.6 + 0.8 * Math.random()
      }
      const t = nowS - beatStart
      if (label === 'VENTRICULAR_TACHYCARDIA') return ventricularBeat(t, beatPeriod)
      return label === 'ATRIAL_FIBRILLATION_RAPID' ? fibrillationBeat(t, nowS) : sinusBeat(t)
    }

    const draw = (nowMs: number) => {
      const width = canvas.clientWidth
      const height = canvas.clientHeight
      const elapsed = Math.min((nowMs - previous) / 1000, 0.1)
      previous = nowMs
      const color = getComputedStyle(canvas).getPropertyValue(live.current.colorVar).trim() || '#5fbfa0'
      const baseline = height * 0.62
      const amplitude = height * 0.45

      const steps = Math.max(1, Math.round(elapsed * (compact ? SWEEP_PX_PER_S / 2 : SWEEP_PX_PER_S)))
      ctx.lineWidth = compact ? 1.5 : 2
      ctx.strokeStyle = color
      ctx.lineJoin = 'round'
      for (let i = 0; i < steps; i++) {
        const sampleTime = nowMs / 1000 - (elapsed * (steps - 1 - i)) / steps
        const y = baseline - valueAt(sampleTime) * amplitude
        ctx.clearRect(x, 0, GAP_PX, height)
        if (lastY !== null) {
          ctx.beginPath()
          ctx.moveTo(x - 1, lastY)
          ctx.lineTo(x, y)
          ctx.stroke()
        }
        lastY = y
        x += 1
        if (x >= width) {
          x = 0
          lastY = null
        }
      }
      frame = requestAnimationFrame(draw)
    }
    frame = requestAnimationFrame(draw)

    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('resize', resize)
    }
  }, [compact])

  return <canvas ref={canvasRef} className="ecg-canvas" style={{ height }} aria-label="ECG" />
}
