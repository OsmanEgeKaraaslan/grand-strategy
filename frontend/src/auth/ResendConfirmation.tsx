import { useEffect, useState } from 'react'
import { useAuth } from './AuthContext'
import { Alert } from '../components/Alert'
import { Captcha } from '../components/Captcha'
import { useCaptcha } from './useCaptcha'

const COOLDOWN_SECONDS = 60

/**
 * Sends the sign-up confirmation email again. The message is the same whether or not the address
 * belongs to an unconfirmed account; Supabase rate-limits the emails, and the button waits a minute
 * between requests so players do not run into that limit.
 */
export function ResendConfirmation({ email }: { email: string }) {
  const { resendConfirmation } = useAuth()
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [wait, setWait] = useState(0)
  const captcha = useCaptcha()

  useEffect(() => {
    if (wait <= 0) return
    const timer = setTimeout(() => setWait((seconds) => seconds - 1), 1000)
    return () => clearTimeout(timer)
  }, [wait])

  async function resend() {
    setError(null)
    setState('sending')
    try {
      await resendConfirmation(email.trim(), captcha.consume())
      setState('sent')
      setWait(COOLDOWN_SECONDS)
    } catch (err) {
      setState('idle')
      setError(err instanceof Error ? err.message : 'The email could not be sent.')
    }
  }

  return (
    <div className="resend">
      {error && <Alert>{error}</Alert>}
      {state === 'sent' && (
        <Alert kind="success">If this address has an unconfirmed account, a new confirmation link is on its way.</Alert>
      )}
      <Captcha onToken={captcha.setToken} resetSignal={captcha.resetSignal} />
      <button
        type="button"
        className="button button--ghost"
        disabled={!email.includes('@') || state === 'sending' || wait > 0 || !captcha.ready}
        onClick={resend}
      >
        {state === 'sending' ? 'Sending…' : wait > 0 ? `Resend possible in ${wait}s` : 'Resend confirmation email'}
      </button>
    </div>
  )
}
