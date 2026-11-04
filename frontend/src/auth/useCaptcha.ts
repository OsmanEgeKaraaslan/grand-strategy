import { useCallback, useState } from 'react'

/** The Turnstile site key of this deployment; without one, CAPTCHA protection is off (local development). */
export const CAPTCHA_SITE_KEY: string | undefined = import.meta.env.VITE_TURNSTILE_SITE_KEY?.trim() || undefined

/**
 * The CAPTCHA state of one form. {@code ready} is false until the person passed the check (always
 * true without a site key); {@code consume} hands the token to one attempt and asks the widget for a
 * fresh one, because a Turnstile token works only once.
 */
export function useCaptcha(siteKey: string | undefined = CAPTCHA_SITE_KEY) {
  const [token, setToken] = useState<string | null>(null)
  const [resetSignal, setResetSignal] = useState(0)
  const required = Boolean(siteKey)

  const consume = useCallback((): string | undefined => {
    if (!required) return undefined
    const current = token ?? undefined
    setToken(null)
    setResetSignal((n) => n + 1)
    return current
  }, [required, token])

  return { siteKey, required, ready: !required || token !== null, setToken, resetSignal, consume }
}
