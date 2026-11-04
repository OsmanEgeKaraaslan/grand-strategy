import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Session, SupabaseClient } from '@supabase/supabase-js'
import { SIGN_UP_VERSIONS } from '../legal/documents'
import { authRedirects, type AuthRedirectError } from './redirects'

/** Result of a sign-up: either signed in immediately, or waiting for email confirmation. */
export type SignUpResult = { status: 'signed-in' } | { status: 'confirmation-required'; email: string }

/**
 * Everything the UI may do with authentication. Supabase is used for identity only; all game
 * data goes through the Spring API (see api/client.ts), authenticated with accessToken().
 */
export interface Auth {
  session: Session | null
  /** True until the stored session (if any) has been restored. */
  loading: boolean
  /** The session came from a password-recovery link: the player should choose a new password. */
  passwordRecovery: boolean
  /** An email link (confirmation, recovery) that Supabase rejected, e.g. because it expired. */
  redirectError: AuthRedirectError | null
  /**
   * Every method that talks to Supabase on behalf of a not-yet-proven person takes the CAPTCHA token
   * of the form (Turnstile; components/Captcha.tsx) when CAPTCHA protection is on.
   */
  signIn(email: string, password: string, captchaToken?: string): Promise<void>
  signUp(email: string, password: string, displayName: string, captchaToken?: string): Promise<SignUpResult>
  signOut(): Promise<void>
  /** A current (auto-refreshed) access token, or null when signed out. */
  accessToken(): Promise<string | null>
  /**
   * Asks Supabase to email a recovery link. Never reveals whether the address has an account: only
   * rate-limit and connection problems are reported.
   */
  requestPasswordReset(email: string, captchaToken?: string): Promise<void>
  /** Sends the sign-up confirmation email again; as enumeration-safe as requestPasswordReset. */
  resendConfirmation(email: string, captchaToken?: string): Promise<void>
  /** Sets a new password for the signed-in (or recovering) user. */
  updatePassword(newPassword: string): Promise<void>
  /** Confirms the current password by signing in again, which also refreshes the session's sign-in time. */
  reauthenticate(password: string, captchaToken?: string): Promise<void>
}

const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (!auth) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return auth
}

export { AuthContext }

export function AuthProvider({
  client,
  initialRedirectError = null,
  initialPasswordRecovery = false,
  children,
}: {
  client: SupabaseClient
  initialRedirectError?: AuthRedirectError | null
  /** The page was opened from a recovery link (see isRecoveryRedirect). */
  initialPasswordRecovery?: boolean
  children: ReactNode
}) {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)
  const [passwordRecovery, setPasswordRecovery] = useState(initialPasswordRecovery)

  useEffect(() => {
    let active = true
    // Restore a persisted session (localStorage), refreshing it if needed.
    client.auth.getSession().then(({ data }) => {
      if (active) {
        setSession(data.session)
        setLoading(false)
      }
    })
    const { data: subscription } = client.auth.onAuthStateChange((event, nextSession) => {
      if (event === 'PASSWORD_RECOVERY') setPasswordRecovery(true)
      if (event === 'SIGNED_OUT') setPasswordRecovery(false)
      setSession(nextSession)
      setLoading(false)
    })
    return () => {
      active = false
      subscription.subscription.unsubscribe()
    }
  }, [client])

  const auth = useMemo<Auth>(
    () => ({
      session,
      loading,
      passwordRecovery,
      redirectError: initialRedirectError,
      async signIn(email, password, captchaToken) {
        const { error } = await client.auth.signInWithPassword({ email, password, ...captcha(captchaToken) })
        if (error) throw new Error(describeAuthError(error.message))
      },
      async signUp(email, password, displayName, captchaToken) {
        const { data, error } = await client.auth.signUp({
          email,
          password,
          options: {
            // Read once by the backend when it creates the player profile (validated there).
            // The form requires consent to these versions; the backend records it on first login.
            data: {
              display_name: displayName,
              accepted_terms_version: SIGN_UP_VERSIONS.terms,
              accepted_privacy_version: SIGN_UP_VERSIONS.privacy,
            },
            emailRedirectTo: authRedirects.confirmation(),
            ...(captchaToken ? { captchaToken } : {}),
          },
        })
        if (error) throw new Error(describeAuthError(error.message))
        return data.session ? { status: 'signed-in' } : { status: 'confirmation-required', email }
      },
      async signOut() {
        await client.auth.signOut()
      },
      async accessToken() {
        const { data } = await client.auth.getSession()
        return data.session?.access_token ?? null
      },
      async requestPasswordReset(email, captchaToken) {
        const { error } = await client.auth.resetPasswordForEmail(email, {
          redirectTo: authRedirects.recovery(),
          ...(captchaToken ? { captchaToken } : {}),
        })
        throwIfReportable(error)
      },
      async resendConfirmation(email, captchaToken) {
        const { error } = await client.auth.resend({
          type: 'signup',
          email,
          options: { emailRedirectTo: authRedirects.confirmation(), ...(captchaToken ? { captchaToken } : {}) },
        })
        throwIfReportable(error)
      },
      async updatePassword(newPassword) {
        const { error } = await client.auth.updateUser({ password: newPassword })
        if (error) throw new Error(describeAuthError(error.message))
        setPasswordRecovery(false)
      },
      async reauthenticate(password, captchaToken) {
        const email = session?.user?.email
        if (!email) throw new Error('Your session has ended. Please sign in again.')
        const { error } = await client.auth.signInWithPassword({ email, password, ...captcha(captchaToken) })
        if (error) {
          throw new Error(
            error.message.toLowerCase().includes('invalid login credentials')
              ? 'Your current password is not correct.'
              : describeAuthError(error.message),
          )
        }
      },
    }),
    [client, session, loading, passwordRecovery, initialRedirectError],
  )

  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
}

/** Supabase's sign-in options with the CAPTCHA token, when there is one. */
function captcha(token: string | undefined) {
  return token ? { options: { captchaToken: token } } : {}
}

/**
 * For the emails that must not reveal whether an address is registered: only throttling and
 * connection failures reach the player; everything else looks like success.
 */
function throwIfReportable(error: { message: string } | null) {
  if (!error) return
  const normalized = error.message.toLowerCase()
  const reportable = ['rate limit', 'for security purposes', 'captcha', 'failed to fetch', 'networkerror', 'load failed']
  if (reportable.some((fragment) => normalized.includes(fragment))) throw new Error(describeAuthError(error.message))
}

/** Turns Supabase's messages into player-facing text. */
export function describeAuthError(message: string): string {
  const normalized = message.toLowerCase()
  if (normalized.includes('for security purposes')) return 'Please wait a minute before requesting another email.'
  if (normalized.includes('captcha')) return 'The check that you are human did not succeed. Please complete it and try again.'
  if (normalized.includes('should be different from the old')) return 'Choose a password different from your current one.'
  if (normalized.includes('auth session missing') || normalized.includes('session_not_found')) {
    return 'Your sign-in link has expired. Please request a new one.'
  }
  if (normalized.includes('invalid login credentials')) return 'Wrong email or password.'
  if (normalized.includes('email not confirmed')) return 'Please confirm your email address first — check your inbox.'
  if (normalized.includes('already registered')) return 'An account with this email already exists. Try signing in.'
  if (normalized.includes('password should be')) return 'That password is too weak. Use at least 8 characters.'
  if (normalized.includes('rate limit')) return 'Too many attempts. Please wait a moment and try again.'
  if (normalized.includes('failed to fetch') || normalized.includes('networkerror') || normalized.includes('load failed')) {
    return 'Cannot reach the sign-in service. Check your connection and try again.'
  }
  return message
}
