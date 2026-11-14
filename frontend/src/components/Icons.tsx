import type { HealthState } from '../api/types'

interface IconProps {
  size?: number
  className?: string
}

const svg = (size: number, className: string | undefined, children: React.ReactNode) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.8}
    strokeLinecap="round"
    strokeLinejoin="round"
    className={className}
    aria-hidden="true"
  >
    {children}
  </svg>
)

export const HeartIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" />)

export const RadiationIcon = ({ size = 20, className }: IconProps) =>
  svg(
    size,
    className,
    <>
      <circle cx="12" cy="12" r="1.6" />
      <path d="M12 10.4 9 5.2a7 7 0 0 1 6 0zM13.4 12.8l6 0a7 7 0 0 1-3 5.2zM10.6 12.8l-6 0a7 7 0 0 0 3 5.2z" />
    </>,
  )

export const BoneIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M8 16l8-8M6.5 13.5a2 2 0 1 0-2 2 2 2 0 1 0 2 2zM17.5 10.5a2 2 0 1 0 2-2 2 2 0 1 0-2-2z" />)

export const FlaskIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M9 3h6M10 3v6L5 19a1.5 1.5 0 0 0 1.3 2h11.4a1.5 1.5 0 0 0 1.3-2L14 9V3" />)

export const BrainIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M9 4a3 3 0 0 0-3 3 3 3 0 0 0-1 5.5A3 3 0 0 0 9 18v2M15 4a3 3 0 0 1 3 3 3 3 0 0 1 1 5.5A3 3 0 0 1 15 18v2M12 4v16" />)

export const DropIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z" />)

export const StomachIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M7 4v5a5 5 0 0 0 5 5h1a4 4 0 0 1 0 8H9M7 4h4" />)

export const HabitatIcon = ({ size = 20, className }: IconProps) =>
  svg(size, className, <path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />)

export const CheckIcon = ({ size = 16, className }: IconProps) =>
  svg(
    size,
    className,
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12l3 3 5-6" />
    </>,
  )

export const EyeIcon = ({ size = 16, className }: IconProps) =>
  svg(
    size,
    className,
    <>
      <path d="M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6S2 12 2 12z" />
      <circle cx="12" cy="12" r="2.6" />
    </>,
  )

export const AlertIcon = ({ size = 16, className }: IconProps) =>
  svg(
    size,
    className,
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v6M12 16.5v.5" />
    </>,
  )

export const DashIcon = ({ size = 16, className }: IconProps) =>
  svg(
    size,
    className,
    <>
      <circle cx="12" cy="12" r="9" strokeDasharray="3 3" />
      <path d="M8 12h8" />
    </>,
  )

export const HelpIcon = ({ size = 16, className }: IconProps) =>
  svg(
    size,
    className,
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.5v.5M12 17v.5" />
    </>,
  )

export const ChevronIcon = ({ size = 16, className }: IconProps) => svg(size, className, <path d="M9 6l6 6-6 6" />)

export const CloseIcon = ({ size = 18, className }: IconProps) => svg(size, className, <path d="M6 6l12 12M18 6L6 18" />)

/** Icon of each state, so the state never depends on color alone. */
export function StateIcon({ state, size = 16 }: { state: HealthState | null | undefined; size?: number }) {
  switch (state) {
    case 'NORMAL':
      return <CheckIcon size={size} />
    case 'OBSERVATION':
      return <EyeIcon size={size} />
    case 'ALERT':
      return <AlertIcon size={size} />
    default:
      return <DashIcon size={size} />
  }
}

const GROUP_ICONS: Record<string, (p: IconProps) => React.ReactNode> = {
  CARDIOVASCULAR_RESPIRATORY: HeartIcon,
  RADIOLOGICAL: RadiationIcon,
  MUSCULOSKELETAL_BONE: BoneIcon,
  METABOLIC_RENAL_NUTRITIONAL: FlaskIcon,
  NEUROLOGICAL_COGNITIVE_BEHAVIORAL: BrainIcon,
  HEMATOLOGICAL_IMMUNE: DropIcon,
  DIGESTIVE_GENERAL: StomachIcon,
  HABITAT: HabitatIcon,
}

export function GroupIcon({ group, size = 20 }: { group: string; size?: number }) {
  const Icon = GROUP_ICONS[group] ?? HabitatIcon
  return <>{Icon({ size })}</>
}
