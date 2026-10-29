import { useState, type FormEvent } from 'react'
import { Link, Navigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { describeRedirectError } from '../auth/redirects'
import { NewPasswordFields } from '../auth/NewPasswordFields'
import { checkNewPassword } from '../auth/passwords'
import { AuthFrame } from '../components/AppShell'
import { Alert } from '../components/Alert'
import { Spinner } from '../components/Spinner'

/**
 * Target of the recovery email. Supabase turns a valid link into a short recovery session; with it
 * the player sets a new password. Expired or reused links come back with an error instead.
 */
export function ResetPasswordPage() {
  const { session, loading, passwordRecovery, redirectError, updatePassword } = useAuth()
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  if (loading) return <Spinner label="Checking your link…" />

  if (done) {
    return (
      <AuthFrame title="Password changed" subtitle="You are signed in with your new password.">
        <Alert kind="success">Your password has been changed.</Alert>
        <Link to="/" className="button button--primary">
          Continue to your realm
        </Link>
      </AuthFrame>
    )
  }

  if (redirectError || !session) {
    return (
      <AuthFrame title="Link not valid" subtitle="Password recovery">
        <Alert>
          {redirectError
            ? describeRedirectError(redirectError)
            : 'This page only works from the link in a password-recovery email. The link may have expired or been used already.'}
        </Alert>
        <Link to="/forgot-password" className="button button--primary">
          Request a new link
        </Link>
      </AuthFrame>
    )
  }

  // Signed in normally (not through a recovery link): changing the password needs the current one.
  if (!passwordRecovery) return <Navigate to="/account" replace />

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const problem = checkNewPassword(password, confirmation)
    setError(problem)
    if (problem) return
    setSubmitting(true)
    try {
      await updatePassword(password)
      setDone(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The password could not be changed.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthFrame title="Choose a new password" subtitle="Password recovery">
      <form className="form" onSubmit={handleSubmit} noValidate>
        {error && <Alert>{error}</Alert>}
        <NewPasswordFields
          password={password}
          confirmation={confirmation}
          onPassword={setPassword}
          onConfirmation={setConfirmation}
        />
        <button type="submit" className="button button--primary" disabled={submitting}>
          {submitting ? 'Saving…' : 'Save new password'}
        </button>
      </form>
    </AuthFrame>
  )
}
