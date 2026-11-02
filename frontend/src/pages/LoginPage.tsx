import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { describeRedirectError } from '../auth/redirects'
import { ResendConfirmation } from '../auth/ResendConfirmation'
import { AuthFrame } from '../components/AppShell'
import { Alert } from '../components/Alert'
import { PasswordField } from '../components/PasswordInput'

export function LoginPage() {
  const { session, signIn, redirectError } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [unconfirmed, setUnconfirmed] = useState(false)

  const from = (location.state as { from?: string } | null)?.from ?? '/'

  if (session) {
    return <Navigate to={from} replace />
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setUnconfirmed(false)
    setSubmitting(true)
    try {
      await signIn(email.trim(), password)
      navigate(from, { replace: true })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Sign-in failed.'
      setError(message)
      setUnconfirmed(message.startsWith('Please confirm your email'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthFrame title="Sign in" subtitle="Return to your realm.">
      {redirectError && (
        <div className="form">
          <Alert>{describeRedirectError(redirectError)}</Alert>
          <p className="muted">
            Confirmation link expired? Enter your email below and request a new one. Password link expired?{' '}
            <Link to="/forgot-password">Request a new recovery link</Link>.
          </p>
        </div>
      )}
      <form className="form" onSubmit={handleSubmit} noValidate>
        {error && <Alert>{error}</Alert>}
        {(unconfirmed || redirectError) && <ResendConfirmation email={email} />}
        <label className="field">
          <span>Email</span>
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <PasswordField
          label="Password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <p className="auth-forgot">
          <Link to="/forgot-password">Forgot your password?</Link>
        </p>
        <button type="submit" className="button button--primary" disabled={submitting || !email || !password}>
          {submitting ? 'Signing in…' : 'Log in'}
        </button>
      </form>
      <p className="muted auth-switch">
        New here? <Link to="/register">Create an account</Link>
      </p>
    </AuthFrame>
  )
}
