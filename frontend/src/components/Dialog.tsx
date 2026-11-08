import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Icon } from './icons'

/**
 * A small modal dialog: Escape and the backdrop close it, focus moves into it on open and back to the
 * opening control on close. Rendered only while open, so its form state starts fresh each time.
 */
export function Dialog({ title, description, onClose, children }: { title: string; description?: string; onClose: () => void; children: ReactNode }) {
  const id = useId()
  const panel = useRef<HTMLDivElement>(null)
  // Kept in a ref so a new onClose on each parent render does not re-run the focus effect.
  const close = useRef(onClose)
  useEffect(() => {
    close.current = onClose
  }, [onClose])

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null
    const first = panel.current?.querySelector<HTMLElement>('input, select, textarea, button:not(.dialog__close)')
    first?.focus()
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') close.current()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      opener?.focus()
    }
  }, [])

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div
        ref={panel}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        aria-describedby={description ? `${id}-desc` : undefined}
      >
        <header className="dialog__header">
          <div>
            <h2 id={`${id}-title`}>{title}</h2>
            {description && (
              <p id={`${id}-desc`} className="muted">
                {description}
              </p>
            )}
          </div>
          <button type="button" className="icon-button dialog__close" aria-label="Close" onClick={onClose}>
            <Icon name="close" />
          </button>
        </header>
        <div className="dialog__body">{children}</div>
      </div>
    </div>
  )
}
