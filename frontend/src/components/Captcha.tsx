import { useEffect, useRef } from 'react'
import { CAPTCHA_SITE_KEY } from '../auth/useCaptcha'

/** The Cloudflare Turnstile API the widget script installs on window. */
interface Turnstile {
  render(element: HTMLElement, options: Record<string, unknown>): string
  reset(widgetId: string): void
  remove(widgetId: string): void
}

declare global {
  interface Window {
    turnstile?: Turnstile
  }
}

const SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

let loading: Promise<Turnstile> | null = null

/** Loads the Turnstile script once, only on pages that show a CAPTCHA. */
function loadTurnstile(): Promise<Turnstile> {
  if (window.turnstile) return Promise.resolve(window.turnstile)
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('Turnstile did not load')))
    script.onerror = () => {
      loading = null
      reject(new Error('Turnstile did not load'))
    }
    document.head.appendChild(script)
  })
  return loading
}

/**
 * Cloudflare Turnstile ("are you human?") for the sign-up, sign-in, recovery and resend forms
 * (SECURITY_AUDIT §7, P1). Supabase verifies the token on its side when its CAPTCHA protection is
 * enabled. A token works once: change {@code resetSignal} after every attempt to get a new one.
 * Renders nothing when no site key is configured.
 */
export function Captcha({
  onToken,
  resetSignal = 0,
  siteKey = CAPTCHA_SITE_KEY,
}: {
  onToken: (token: string | null) => void
  resetSignal?: number
  siteKey?: string
}) {
  const container = useRef<HTMLDivElement>(null)
  const widget = useRef<string | null>(null)
  const callback = useRef(onToken)
  // The latest handler, without re-rendering the widget when the parent passes a new function.
  useEffect(() => {
    callback.current = onToken
  }, [onToken])

  useEffect(() => {
    if (!siteKey) return
    let cancelled = false
    loadTurnstile()
      .then((turnstile) => {
        if (cancelled || !container.current) return
        widget.current = turnstile.render(container.current, {
          sitekey: siteKey,
          callback: (token: string) => callback.current(token),
          'expired-callback': () => callback.current(null),
          'error-callback': () => callback.current(null),
        })
      })
      .catch(() => callback.current(null))
    return () => {
      cancelled = true
      if (widget.current && window.turnstile) window.turnstile.remove(widget.current)
      widget.current = null
    }
  }, [siteKey])

  useEffect(() => {
    if (resetSignal > 0 && widget.current && window.turnstile) {
      callback.current(null)
      window.turnstile.reset(widget.current)
    }
  }, [resetSignal])

  if (!siteKey) return null
  return <div className="captcha" ref={container} aria-label="Human check" />
}
