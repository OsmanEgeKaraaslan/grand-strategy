import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { checkNewPassword } from '../auth/passwords'
import { ResendConfirmation } from '../auth/ResendConfirmation'
import { AuthFrame } from '../components/AppShell'
import { Alert } from '../components/Alert'
import { PasswordField } from '../components/PasswordInput'

const DISPLAY_NAME_PATTERN = /^[\p{L}\p{N}_-]+( [\p{L}\p{N}_-]+)*$/u

export function RegisterPage() {
  const { session, signUp } = useAuth()
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [confirmationSentTo, setConfirmationSentTo] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [consent, setConsent] = useState(false)

  if (session) {
    return <Navigate to="/" replace />
  }

  if (confirmationSentTo) {
    return (
      <AuthFrame title="Check your inbox" subtitle="One more step before you can play.">
        <Alert kind="success">
          We sent a confirmation link to <strong>{confirmationSentTo}</strong>. Open it to activate your account,
          then sign in.
        </Alert>
        <p className="muted">Nothing arrived after a few minutes? Check your spam folder, or:</p>
        <ResendConfirmation email={confirmationSentTo} />
        <Link to="/login" className="button button--primary">
          Go to sign in
        </Link>
      </AuthFrame>
    )
  }

  function validate(): string | null {
    const name = displayName.trim().replace(/\s+/g, ' ')
    if (name.length < 3 || name.length > 24 || !DISPLAY_NAME_PATTERN.test(name)) {
      return 'Display names have 3–24 letters, digits, spaces, "_" or "-".'
    }
    if (!email.includes('@')) return 'Enter a valid email address.'
    const passwordProblem = checkNewPassword(password, confirmPassword)
    if (passwordProblem) return passwordProblem
    if (!consent) return 'Please accept the Terms of Service and the Privacy Policy.'
    return null
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const problem = validate()
    setError(problem)
    if (problem) return
    setSubmitting(true)
    try {
      const result = await signUp(email.trim(), password, displayName.trim().replace(/\s+/g, ' '))
      if (result.status === 'confirmation-required') {
        setConfirmationSentTo(result.email)
      } else {
        navigate('/', { replace: true })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Registration failed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthFrame title="Create an account" subtitle="Found a nation. Forge an empire.">
      <form className="form" onSubmit={handleSubmit} noValidate>
        {error && <Alert>{error}</Alert>}
        <div className="field">
          <label htmlFor="register-display-name">
            <span>Display name</span>
          </label>
          <input
            id="register-display-name"
            autoComplete="nickname"
            aria-describedby="register-display-name-hint"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
          <small id="register-display-name-hint" className="muted">
            Shown to other players. You can change it later.
          </small>
        </div>
        <label className="field">
          <span>Email</span>
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <PasswordField label="Password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
        <PasswordField
          label="Confirm password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
        />
        <label className="checkbox-field">
          <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
          <span>
            I accept the{' '}
            <Link to="/legal/terms" target="_blank">
              Terms of Service
            </Link>{' '}
            and have read the{' '}
            <Link to="/legal/privacy" target="_blank">
              Privacy Policy
            </Link>
            .
          </span>
        </label>
        <button type="submit" className="button button--primary" disabled={submitting}>
          {submitting ? 'Creating account…' : 'Register'}
        </button>
      </form>
      <p className="muted auth-switch">
        Already have an account? <Link to="/login">Sign in</Link>
      </p>
    </AuthFrame>
  )
}
