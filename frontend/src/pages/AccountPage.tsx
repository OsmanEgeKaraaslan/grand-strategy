import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../api/ApiContext'
import { ApiError, describeError } from '../api/client'
import { useMe } from '../api/queries'
import { useAuth } from '../auth/AuthContext'
import { NewPasswordFields } from '../auth/NewPasswordFields'
import { checkNewPassword } from '../auth/passwords'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { LegalLinks } from './LegalPage'

/**
 * Account settings (Phase 15A): password, legal acceptance and account deletion. Password and email
 * are changed with Supabase Auth directly; the game API never sees them. Profile settings (name,
 * handle, emblem) live on the profile page.
 */
export function AccountPage() {
  const { session } = useAuth()
  const me = useMe()
  const terms = me.data?.terms

  return (
    <AppShell>
      <section className="hero">
        <div>
          <h1>Account</h1>
          <p className="muted">
            Signed in as <strong>{session?.user?.email ?? 'unknown'}</strong>. Your name, handle and emblem are on{' '}
            <Link to="/profile">your profile</Link>.
          </p>
        </div>
      </section>
      <div className="account-grid">
        <ChangePassword />
        <section className="card" aria-labelledby="account-legal">
          <h2 id="account-legal">Terms and privacy</h2>
          {terms ? (
            <p className="muted">
              {terms.acceptedAt
                ? `You accepted Terms ${terms.acceptedTermsVersion} and Privacy Policy ${terms.acceptedPrivacyVersion} on ${new Date(terms.acceptedAt).toLocaleDateString()}.`
                : 'You have not accepted the Terms of Service yet.'}
              {!terms.accepted && ' New versions are waiting for your review.'}
            </p>
          ) : null}
          <p>
            <LegalLinks />
          </p>
        </section>
        <DeleteAccount />
      </div>
    </AppShell>
  )
}

function ChangePassword() {
  const { reauthenticate, updatePassword } = useAuth()
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [changed, setChanged] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setChanged(false)
    const problem = !current ? 'Enter your current password.' : checkNewPassword(password, confirmation)
    setError(problem)
    if (problem) return
    setSubmitting(true)
    try {
      await reauthenticate(current)
      await updatePassword(password)
      setChanged(true)
      setCurrent('')
      setPassword('')
      setConfirmation('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The password could not be changed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="card" aria-labelledby="account-password">
      <h2 id="account-password">Change password</h2>
      <form className="form" onSubmit={handleSubmit} noValidate>
        {error && <Alert>{error}</Alert>}
        {changed && <Alert kind="success">Your password has been changed.</Alert>}
        <label className="field">
          <span>Current password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </label>
        <NewPasswordFields
          password={password}
          confirmation={confirmation}
          onPassword={setPassword}
          onConfirmation={setConfirmation}
        />
        <button type="submit" className="button button--primary" disabled={submitting}>
          {submitting ? 'Saving…' : 'Change password'}
        </button>
      </form>
      <p className="muted">
        Forgot it? <Link to="/forgot-password">Request a recovery link</Link>.
      </p>
    </section>
  )
}

function DeleteAccount() {
  const api = useApi()
  const { reauthenticate, signOut } = useAuth()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<string | null>(null)

  const deletion = useMutation({
    mutationFn: async () => {
      // A fresh sign-in proves the password and satisfies the server's recent-sign-in check.
      await reauthenticate(password)
      return api.deleteAccount()
    },
    onSuccess: async () => {
      await signOut()
      queryClient.clear()
      navigate('/account-deleted', { replace: true })
    },
    onError: (err) => setError(err instanceof ApiError ? describeError(err) : (err as Error).message),
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    if (confirmation !== 'DELETE') {
      setError('Type DELETE in capital letters to confirm.')
      return
    }
    if (!password) {
      setError('Enter your password.')
      return
    }
    deletion.mutate()
  }

  return (
    <section className="card card--danger" aria-labelledby="account-delete">
      <h2 id="account-delete">Delete account</h2>
      <p className="muted">
        Your name, handle and emblem are replaced by a placeholder, and your friends, blocks, notifications and open
        invitations are removed. Finished matches stay in the other players' history under the placeholder. You cannot
        delete the account while you are still in an unfinished game. Your sign-in (email and password) is then
        removed by the operator; until then it can no longer be used to play.
      </p>
      <form className="form" onSubmit={handleSubmit} noValidate>
        {error && <Alert>{error}</Alert>}
        <label className="field">
          <span>Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <label className="field">
          <span>Type DELETE to confirm</span>
          <input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} autoComplete="off" />
        </label>
        <button type="submit" className="button button--danger" disabled={deletion.isPending}>
          {deletion.isPending ? 'Deleting…' : 'Delete my account'}
        </button>
      </form>
    </section>
  )
}

/** Public page shown after deletion (the session is already gone). */
export function AccountDeletedPage() {
  return (
    <div className="auth-frame">
      <div className="auth-card">
        <h1>Account deleted</h1>
        <p className="muted">
          Your game profile has been anonymized and your social data removed. Your sign-in details are removed from the
          authentication service by the operator; see the <Link to="/legal/privacy">Privacy Policy</Link> for details.
        </p>
        <Link to="/register" className="button button--ghost">
          Create a new account
        </Link>
      </div>
    </div>
  )
}
