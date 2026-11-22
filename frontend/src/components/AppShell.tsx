import { lazy, Suspense, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../auth/AuthContext'
import { useMe } from '../api/queries'
import { BrandMark } from './BrandMark'
import { EmblemBadge } from './social/People'
import { NotificationBell } from './social/NotificationBell'
import { LegalLinks } from '../pages/LegalPage'

// The decorative map beside the sign-in forms is loaded on its own so it never delays the form.
const Atlas = lazy(() => import('../landing/Atlas').then((m) => ({ default: m.Atlas })))

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
          <BrandMark />
          <span className="brand__name">Blaspi</span>
        </Link>
        <div className="shell__account">
          <NotificationBell onOpenSocial={onOpenSocial} />
          {me.data && (
            <Link to="/profile" className="shell__player" title="Your profile">
              <EmblemBadge emblem={me.data.emblem} size={13} />
              {me.data.displayName}
            </Link>
          )}
          <span className="shell__divider" aria-hidden="true" />
          {me.data?.admin && (
            <Link to="/admin" className="shell__nav-link">
              Admin
            </Link>
          )}
          <Link to="/account" className="shell__nav-link">
            Account
          </Link>
          <button type="button" className="shell__nav-link" onClick={handleSignOut}>
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
        <Link to="/" className="brand brand--large">
          <BrandMark />
          <span className="brand__name">Blaspi</span>
        </Link>
        <div className="auth-card__body">
          <h1>{title}</h1>
          <p className="muted">{subtitle}</p>
          {children}
        </div>
        <footer className="auth-card__footer">
          <LegalLinks />
        </footer>
      </div>
      <div className="auth-art" aria-hidden="true">
        <Suspense fallback={null}>
          <Atlas />
        </Suspense>
        <p className="auth-art__caption">The Grand Campaign: seventy-two provinces, ten nations, thirty rounds.</p>
      </div>
    </div>
  )
}
