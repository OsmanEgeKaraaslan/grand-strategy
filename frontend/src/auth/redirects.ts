/**
 * Supabase email links (sign-up confirmation, password recovery) and what comes back on them
 * (Phase 15A). Kept apart from AuthContext so the helpers can be used before the app renders.
 */

/** Where Supabase sends the links of its emails; each must be listed in its Redirect URLs. */
export const authRedirects = {
  confirmation: () => `${window.location.origin}/login`,
  recovery: () => `${window.location.origin}/reset-password`,
}

export interface AuthRedirectError {
  code: string
  description: string
}

/**
 * Reads the error Supabase appends to a redirect when an email link is invalid or expired
 * (`#error=access_denied&error_code=otp_expired&error_description=...`, or the same in the query).
 * Read before the Supabase client starts, because it may clean the URL.
 */
export function readAuthRedirectError(location: { hash: string; search: string }): AuthRedirectError | null {
  for (const raw of [location.hash.replace(/^#/, ''), location.search.replace(/^\?/, '')]) {
    const params = new URLSearchParams(raw)
    const code = params.get('error_code') ?? params.get('error')
    if (code) return { code, description: params.get('error_description') ?? '' }
  }
  return null
}

/**
 * A recovery link lands with `type=recovery` in its hash. Supabase also emits a PASSWORD_RECOVERY
 * event, but possibly before the app subscribed, so the URL is checked too.
 */
export function isRecoveryRedirect(location: { hash: string }): boolean {
  return new URLSearchParams(location.hash.replace(/^#/, '')).get('type') === 'recovery'
}

/** Player-facing text for a rejected email link. */
export function describeRedirectError(error: AuthRedirectError): string {
  if (error.code === 'otp_expired' || /expired|invalid/i.test(error.description)) {
    return 'This link is invalid or has expired. Links work once and only for a limited time — request a new one.'
  }
  return 'This link could not be used. Please request a new one.'
}
