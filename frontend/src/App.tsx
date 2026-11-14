import { useEffect, useState, type CSSProperties } from 'react'
import { useCatalog } from './api/catalog'
import { useMission } from './api/useMission'
import { AppHeader } from './components/AppHeader'
import { ui, type Language } from './i18n/ui'
import { useFitScale, type FitRange } from './lib/useFitScale'
import { ControlCenter } from './views/ControlCenter'
import { QuickView } from './views/QuickView'
import { SimulatorControl } from './views/SimulatorControl'

export type Screen = 'quick' | 'control' | 'simulator'
export type Theme = 'dark' | 'light'

/** Design canvas of each screen and how far it may be scaled (see useFitScale). */
const FIT: Record<Screen, FitRange> = {
  quick: { width: 1280, height: 800, min: 0.9, max: 1.6 },
  simulator: { width: 1280, height: 800, min: 0.9, max: 1.6 },
  control: { width: 1920, height: 1080, min: 0.75, max: 1.5 },
}

function screenFromHash(): Screen {
  const hash = window.location.hash.replace(/^#\/?/, '')
  return hash === 'control' || hash === 'simulator' ? hash : 'quick'
}

/** Per-viewer conveniences only: storage may be unavailable, the app works without it. */
function stored<T extends string>(key: string, fallback: T, allowed: readonly T[]): T {
  try {
    const value = window.localStorage.getItem(key) as T | null
    return value && allowed.includes(value) ? value : fallback
  } catch {
    return fallback
  }
}

function store(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // ignore
  }
}

export function App() {
  const { snapshot, connection } = useMission()
  const [screen, setScreen] = useState<Screen>(screenFromHash)
  const [language, setLanguage] = useState<Language>(() => stored('selfcheck.language', 'es', ['es', 'en']))
  const [theme, setTheme] = useState<Theme>(() => stored('selfcheck.theme', 'dark', ['dark', 'light']))
  const [crewMemberId, setCrewMemberId] = useState('crew-1')
  const catalog = useCatalog(language)
  const scale = useFitScale(FIT[screen])
  const t = ui(language)

  useEffect(() => {
    const onHash = () => setScreen(screenFromHash())
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.documentElement.lang = language
  }, [theme, language])

  const changeLanguage = (value: Language) => {
    setLanguage(value)
    store('selfcheck.language', value)
  }
  const changeTheme = (value: Theme) => {
    setTheme(value)
    store('selfcheck.theme', value)
  }

  const environment = snapshot?.mission.environment
  const environmentName = environment ? (catalog?.environments[environment] ?? environment) : ''
  const member = t.crewNames[crewMemberId]
  const header = {
    quick: { badge: t.quickViewBadge, title: member?.name ?? crewMemberId, subtitle: `${member?.role ?? ''} · ${environmentName}` },
    control: { badge: t.controlBadge, title: t.controlTitle, subtitle: environmentName },
    simulator: { badge: t.simulatorBadge, title: t.simulatorTitle, subtitle: environmentName },
  }[screen]

  return (
    <div className={`app screen-${screen}`} style={{ zoom: scale, '--fit-scale': scale } as CSSProperties}>
      <AppHeader
        t={t}
        {...header}
        missionTimeMs={snapshot?.missionTimeMs ?? null}
        language={language}
        onLanguage={changeLanguage}
        theme={theme}
        onTheme={changeTheme}
        screen={screen}
      />
      {connection !== 'live' && <div className="banner">{connection === 'offline' ? t.offline : t.connecting}</div>}
      {screen === 'simulator' && <SimulatorControl t={t} language={language} snapshot={snapshot} catalog={catalog} />}
      {snapshot && screen === 'quick' && (
        <QuickView
          snapshot={snapshot}
          catalog={catalog}
          crewMemberId={crewMemberId}
          onCrewMember={setCrewMemberId}
          t={t}
          language={language}
        />
      )}
      {snapshot && screen === 'control' && (
        <ControlCenter
          snapshot={snapshot}
          catalog={catalog}
          crewMemberId={crewMemberId}
          onCrewMember={setCrewMemberId}
          t={t}
          language={language}
        />
      )}
    </div>
  )
}
