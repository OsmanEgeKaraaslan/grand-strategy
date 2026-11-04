import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthContext'
import { useMe } from '../api/queries'
import { EmblemBadge } from './social/People'
import { NotificationBell } from './social/NotificationBell'
import { LegalLinks } from '../pages/LegalPage'

/** Header and frame for signed-in pages. */
export function AppShell({ children, onOpenSocial }: { children: ReactNode; onOpenSocial?: () => void }) {
  const { signOut } = useAuth()
  const me = useMe()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  async function handleSignOut() {
    await signOut()
    queryClient.clear()
    navigate('/login', { replace: true })
  }

  return (
    <div className="shell">
      <header className="shell__header">
        <Link to="/" className="brand">
          <span className="brand__mark" aria-hidden="true">⚜</span>
          <span className="brand__name">Clash of Empires</span>
        </Link>
        <div className="shell__account">
          <NotificationBell onOpenSocial={onOpenSocial} />
          {me.data && (
            <Link to="/profile" className="shell__player" title="Your profile">
              <EmblemBadge emblem={me.data.emblem} size={13} />
              {me.data.displayName}
            </Link>
          )}
          {me.data?.admin && (
            <Link to="/admin" className="button button--ghost">
              Admin
            </Link>
          )}
          <Link to="/account" className="button button--ghost">
            Account
          </Link>
          <button type="button" className="button button--ghost" onClick={handleSignOut}>
            Log out
          </button>
        </div>
      </header>
      <main className="shell__main">{children}</main>
    </div>
  )
}

/** Frame for the signed-out pages (login, register). */
export function AuthFrame({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <div className="auth-frame">
      <div className="auth-card">
        <div className="brand brand--large">
          <span className="brand__mark" aria-hidden="true">⚜</span>
          <span className="brand__name">Clash of Empires</span>
        </div>
        <h1>{title}</h1>
        <p className="muted">{subtitle}</p>
        {children}
        <footer className="auth-card__footer">
          <LegalLinks />
        </footer>
      </div>
    </div>
  )
}
