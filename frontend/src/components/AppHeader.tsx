import type { Language, UiTexts } from '../i18n/ui'
import { missionClock, missionDay } from '../lib/missionTime'
import type { Screen, Theme } from '../App'

interface Props {
  t: UiTexts
  badge: string
  title: string
  subtitle: string
  missionTimeMs: number | null
  language: Language
  onLanguage: (language: Language) => void
  theme: Theme
  onTheme: (theme: Theme) => void
  screen: Screen
  /** On the quick view the title is the crew member selector: it shows the active member and changes it. */
  titleSelect?: { value: string; options: { value: string; label: string }[]; onChange: (value: string) => void; label: string }
}

/** Shared top bar: brand, screen, mission time (spec section 5), screens, theme and language selector. */
export function AppHeader({ t, badge, title, subtitle, missionTimeMs, language, onLanguage, theme, onTheme, screen, titleSelect }: Props) {
  return (
    <header className="topbar">
      <a className="brand" href="#/">
        <span className="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="9" />
            <path d="M8 12.5l2.8 2.8L16.5 9" />
          </svg>
        </span>
        <span className="brand-name">
          Self<span>Check</span>
        </span>
      </a>
      <span className="screen-badge">{badge}</span>
      <div className="topbar-title">
        {titleSelect ? (
          <h1>
            <select
              className="title-select"
              value={titleSelect.value}
              onChange={(e) => titleSelect.onChange(e.target.value)}
              aria-label={titleSelect.label}
            >
              {titleSelect.options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </h1>
        ) : (
          <h1>{title}</h1>
        )}
        {/* The line is kept when empty so the header measures the same on every screen. */}
        <div className="muted" aria-hidden={subtitle ? undefined : true}>
          {subtitle || ' '}
        </div>
      </div>
      <nav className="screen-nav" aria-label="Screens">
        {(['quick', 'control', 'simulator'] as Screen[]).map((s) => (
          <a key={s} href={`#/${s === 'quick' ? '' : s}`} className={s === screen ? 'active' : ''}>
            {t.screens[s]}
          </a>
        ))}
      </nav>
      <div className="topbar-right">
        {missionTimeMs != null && (
          <span className="chip mission-time" title={t.missionTime}>
            {t.day} {missionDay(missionTimeMs)} · {missionClock(missionTimeMs)}
          </span>
        )}
        <button className="chip" onClick={() => onTheme(theme === 'dark' ? 'light' : 'dark')}>
          {theme === 'dark' ? t.theme.light : t.theme.dark}
        </button>
        <select className="chip" value={language} onChange={(e) => onLanguage(e.target.value as Language)} aria-label="Language">
          <option value="es">ES</option>
          <option value="en">EN</option>
        </select>
        <span className="chip chip-info">{t.simulatedData}</span>
      </div>
    </header>
  )
}
