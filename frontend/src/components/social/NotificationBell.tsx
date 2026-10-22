import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useMarkNotificationsRead, useNotifications, useUnreadCount } from '../../api/social'
import type { NotificationItem } from '../../api/types'
import { Icon } from '../icons'

const TEXT: Record<NotificationItem['kind'], (n: NotificationItem) => string> = {
  FRIEND_REQUEST: (n) => `${n.actor?.displayName ?? 'Someone'} sent you a friend request`,
  FRIEND_ACCEPTED: (n) => `${n.actor?.displayName ?? 'Someone'} accepted your friend request`,
  GAME_INVITATION: (n) => `${n.actor?.displayName ?? 'A friend'} invited you to ${n.gameName ?? 'a game'}`,
  INVITATION_ACCEPTED: (n) => `${n.actor?.displayName ?? 'Your friend'} joined ${n.gameName ?? 'your game'}`,
  INVITATION_DECLINED: (n) => `${n.actor?.displayName ?? 'Your friend'} declined your invitation to ${n.gameName ?? 'your game'}`,
  MATCH_FOUND: (n) => `Quick Match found against ${n.actor?.displayName ?? 'an opponent'}`,
  GAME_STARTED: (n) => `${n.gameName ?? 'A game'} has started`,
  GAME_FINISHED: (n) => `${n.gameName ?? 'A game'} has finished — see the result`,
}

function target(n: NotificationItem): string | null {
  switch (n.kind) {
    case 'MATCH_FOUND':
    case 'GAME_STARTED':
    case 'GAME_FINISHED':
      return n.gameId ? `/games/${n.gameId}/play` : null
    case 'INVITATION_ACCEPTED':
    case 'INVITATION_DECLINED':
      return n.gameId ? `/games/${n.gameId}` : null
    case 'FRIEND_ACCEPTED':
      return n.actor ? `/players/${encodeURIComponent(n.actor.username)}` : null
    default:
      return null
  }
}

/**
 * The social notification bell: a cheap unread-count poll, and the list only while it is open.
 * Requests and invitations are answered in the social panel (onOpenSocial).
 */
export function NotificationBell({ onOpenSocial }: { onOpenSocial?: () => void }) {
  const [open, setOpen] = useState(false)
  const unread = useUnreadCount()
  const list = useNotifications(open)
  const markRead = useMarkNotificationsRead()
  const panel = useRef<HTMLDivElement>(null)
  const count = unread.data?.unread ?? 0

  useEffect(() => {
    if (!open) return
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }
    function onClick(event: MouseEvent) {
      if (panel.current && !panel.current.contains(event.target as Node)) setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    window.addEventListener('mousedown', onClick)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('mousedown', onClick)
    }
  }, [open])

  return (
    <div className="social-bell" ref={panel}>
      <button
        type="button"
        className="icon-button social-bell__button"
        aria-label={`Social notifications${count > 0 ? ` (${count} unread)` : ''}`}
        aria-expanded={open}
        title="Notifications"
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="bell" />
        {count > 0 && (
          <span className="game-toolbar__badge social-bell__badge" aria-hidden="true">
            {count > 99 ? '99+' : count}
          </span>
        )}
      </button>
      {open && (
        <div className="social-bell__menu" role="region" aria-label="Notifications">
          <div className="social-bell__header">
            <strong>Notifications</strong>
            <button type="button" className="link-button" disabled={count === 0 || markRead.isPending} onClick={() => markRead.mutate(undefined)}>
              Mark all read
            </button>
          </div>
          {list.isPending && <p className="muted">Loading…</p>}
          {list.data && list.data.items.length === 0 && <p className="muted">Nothing new.</p>}
          <ul className="notification-list">
            {list.data?.items.map((n) => {
              const to = target(n)
              const text = TEXT[n.kind]?.(n) ?? n.kind
              return (
                <li key={n.id} className={n.read ? 'notification--read' : 'notification--unread'}>
                  {to ? (
                    <Link to={to} onClick={() => setOpen(false)}>
                      {text}
                    </Link>
                  ) : (n.kind === 'FRIEND_REQUEST' || n.kind === 'GAME_INVITATION') && onOpenSocial ? (
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => {
                        setOpen(false)
                        onOpenSocial()
                      }}
                    >
                      {text}
                    </button>
                  ) : (
                    <span>{text}</span>
                  )}
                  <time className="muted" dateTime={n.createdAt}>
                    {new Date(n.createdAt).toLocaleString()}
                  </time>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}
