import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { AuthFrame } from '../components/AppShell'
import { Alert } from '../components/Alert'
import { Captcha } from '../components/Captcha'
import { useCaptcha } from '../auth/useCaptcha'

/**
 * Starts Supabase's password recovery. The answer is the same whether or not the address has an
 * account, so the page cannot be used to find out who plays here.
 */
export function ForgotPasswordPage() {
  const { requestPasswordReset } = useAuth()
  const [email, setEmail] = useState('')
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const captcha = useCaptcha()

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const address = email.trim()
    if (!address.includes('@')) {
      setError('Enter a valid email address.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await requestPasswordReset(address, captcha.consume())
      setSentTo(address)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The request failed. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  if (sentTo) {
    return (
      <AuthFrame title="Check your inbox" subtitle="Password recovery">
        <Alert kind="success">
          If an account exists for <strong>{sentTo}</strong>, we have sent it a link to choose a new password. The link
          works once and expires after a while; check your spam folder if nothing arrives.
        </Alert>
        <Link to="/login" className="button button--primary">
          Back to sign in
        </Link>
      </AuthFrame>
    )
  }

  return (
    <AuthFrame title="Forgot your password?" subtitle="We will email you a link to choose a new one.">
      <form className="form" onSubmit={handleSubmit} noValidate>
        {error && <Alert>{error}</Alert>}
        <label className="field">
          <span>Email</span>
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <Captcha onToken={captcha.setToken} resetSignal={captcha.resetSignal} />
        <button type="submit" className="button button--primary" disabled={submitting || !email || !captcha.ready}>
          {submitting ? 'Sending…' : 'Send recovery link'}
        </button>
      </form>
      <p className="muted auth-switch">
        Remembered it? <Link to="/login">Sign in</Link>
      </p>
    </AuthFrame>
  )
}
