import { Icon, type IconName } from './icons'

/** The management windows the left toolbar opens over the map. */
export type PanelKey =
  | 'nation'
  | 'economy'
  | 'military'
  | 'technology'
  | 'diplomacy'
  | 'history'
  | 'players'
  | 'social'
  | 'notifications'
  | 'settings'

interface ToolbarEntry {
  key: PanelKey
  label: string
  icon: IconName
}

const GAME_PANELS: ToolbarEntry[] = [
  { key: 'nation', label: 'Nation', icon: 'crown' },
  { key: 'economy', label: 'Economy', icon: 'economy' },
  { key: 'military', label: 'Military', icon: 'military' },
  { key: 'technology', label: 'Technology', icon: 'technology' },
  { key: 'diplomacy', label: 'Diplomacy', icon: 'diplomacy' },
  { key: 'history', label: 'History', icon: 'history' },
  { key: 'players', label: 'Players', icon: 'players' },
  { key: 'social', label: 'Social', icon: 'social' },
  { key: 'notifications', label: 'Notifications', icon: 'bell' },
  { key: 'settings', label: 'Settings', icon: 'settings' },
]

interface GameToolbarProps {
  active: PanelKey | null
  /** Opens a window, or closes it when it is already open (null). */
  onSelect: (panel: PanelKey | null) => void
  /** Items waiting for the player per window (shown as a badge), e.g. a conference to answer. */
  badges?: Partial<Record<PanelKey, number>>
}

/**
 * The narrow icon toolbar on the left of the campaign map: each icon opens one management window
 * over the map (clicking it again closes it). A badge counts what waits for the player.
 */
export function GameToolbar({ active, onSelect, badges = {} }: GameToolbarProps) {
  return (
    <nav className="game-toolbar" aria-label="Game panels">
      {GAME_PANELS.map((entry) => {
        const badge = badges[entry.key] ?? 0
        const pressed = active === entry.key
        return (
          <button
            key={entry.key}
            type="button"
            className={`game-toolbar__button ${pressed ? 'game-toolbar__button--active' : ''}`}
            title={entry.label}
            aria-label={`Show ${entry.label.toLowerCase()}${badge > 0 ? ` (${badge} waiting)` : ''}`}
            aria-pressed={pressed}
            onClick={() => onSelect(pressed ? null : entry.key)}
          >
            <Icon name={entry.icon} size={20} />
            {badge > 0 && (
              <span className="game-toolbar__badge" aria-hidden="true">
                {badge}
              </span>
            )}
          </button>
        )
      })}
    </nav>
  )
}
