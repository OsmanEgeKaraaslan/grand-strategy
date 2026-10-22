import { useId, useRef, useState, type ReactNode } from 'react'
import { Icon, type IconName } from './icons'

/**
 * A window floating over the campaign map. It is not modal: the map around it stays clickable,
 * and only its body scrolls. The heading names the region (tests and screen readers find it so).
 */
export function FloatingWindow({
  title,
  subtitle,
  marker,
  placement,
  className,
  onClose,
  closeLabel,
  headerExtra,
  children,
}: {
  title: string
  subtitle?: ReactNode
  /** Small visual before the title: a nation swatch or an icon. */
  marker?: ReactNode
  placement: 'right' | 'left' | 'center'
  className?: string
  onClose?: () => void
  closeLabel?: string
  headerExtra?: ReactNode
  children: ReactNode
}) {
  const id = useId()
  return (
    <section className={`float-window float-window--${placement} ${className ?? ''}`} aria-labelledby={id}>
      <header className="float-window__header">
        {marker}
        <div className="float-window__titles">
          <h2 id={id} className="float-window__title">
            {title}
          </h2>
          {subtitle && <div className="float-window__subtitle">{subtitle}</div>}
        </div>
        {headerExtra}
        {onClose && (
          <button type="button" className="icon-button" aria-label={closeLabel ?? `Close ${title}`} title="Close (Esc)" onClick={onClose}>
            <Icon name="close" />
          </button>
        )}
      </header>
      <div className="float-window__body">{children}</div>
    </section>
  )
}

/**
 * A small icon action with its price under the icon and a hover/focus card carrying the details:
 * name, costs, timing, effects and — when the server refuses — its reasons. The card is always in
 * the document (described-by), but only shown while the action is hovered or focused; it is fixed
 * to the viewport so a scrolling window never clips it.
 */
export function CompactAction({
  icon,
  label,
  title,
  cost,
  details = [],
  blockers = [],
  blockersLabel = 'Why this is not possible',
  disabled = false,
  pending = false,
  tone,
  pressed,
  onClick,
}: {
  icon: IconName
  /** Accessible name of the button, e.g. "Recruit Cavalry". */
  label: string
  /** Heading of the hover card; the label when omitted. */
  title?: string
  cost?: string
  details?: string[]
  blockers?: string[]
  blockersLabel?: string
  disabled?: boolean
  pending?: boolean
  tone?: 'danger' | 'primary'
  pressed?: boolean
  onClick: () => void
}) {
  const tipId = useId()
  const anchor = useRef<HTMLSpanElement>(null)
  const [position, setPosition] = useState<{ left: number; top: number } | null>(null)

  function show() {
    const rect = anchor.current?.getBoundingClientRect()
    if (!rect) return
    const width = 240
    const left = Math.max(8, Math.min(window.innerWidth - width - 8, rect.left + rect.width / 2 - width / 2))
    const below = rect.bottom + 6
    setPosition({ left, top: below + 160 > window.innerHeight ? Math.max(8, rect.top - 166) : below })
  }

  return (
    <span
      ref={anchor}
      className="compact-action"
      onMouseEnter={show}
      onMouseLeave={() => setPosition(null)}
      onFocus={show}
      onBlur={() => setPosition(null)}
    >
      <button
        type="button"
        className={`compact-action__button ${tone ? `compact-action__button--${tone}` : ''}`}
        aria-label={label}
        aria-describedby={tipId}
        aria-pressed={pressed}
        disabled={disabled || pending}
        onClick={onClick}
      >
        <Icon name={icon} size={18} />
        {cost && <span className="compact-action__cost">{pending ? '…' : cost}</span>}
      </button>
      <span
        id={tipId}
        role="tooltip"
        className={`action-tip ${position ? 'action-tip--shown' : ''}`}
        style={position ? { left: position.left, top: position.top } : undefined}
      >
        <strong className="action-tip__title">{title ?? label}</strong>
        {details.map((line) => (
          <span key={line} className="action-tip__line">
            {line}
          </span>
        ))}
        {blockers.length > 0 && (
          <ul className="blockers action-tip__blockers" aria-label={blockersLabel}>
            {blockers.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        )}
      </span>
    </span>
  )
}

/** A compact statistic: icon, value and a title with the long explanation. */
export function Stat({ icon, label, value, hint, tone }: { icon: IconName; label: string; value: ReactNode; hint?: string; tone?: 'negative' | 'positive' | 'warning' }) {
  return (
    <div className={`stat ${tone ? `stat--${tone}` : ''}`} title={hint ?? label}>
      <Icon name={icon} size={14} />
      <dt className="visually-hidden">{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}
