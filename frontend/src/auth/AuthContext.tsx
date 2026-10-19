import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Session, SupabaseClient } from '@supabase/supabase-js'

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
  signIn(email: string, password: string): Promise<void>
  signUp(email: string, password: string, displayName: string): Promise<SignUpResult>
  signOut(): Promise<void>
  /** A current (auto-refreshed) access token, or null when signed out. */
  accessToken(): Promise<string | null>
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

export function AuthProvider({ client, children }: { client: SupabaseClient; children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    // Restore a persisted session (localStorage), refreshing it if needed.
    client.auth.getSession().then(({ data }) => {
      if (active) {
        setSession(data.session)
        setLoading(false)
      }
    })
    const { data: subscription } = client.auth.onAuthStateChange((_event, nextSession) => {
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
      async signIn(email, password) {
        const { error } = await client.auth.signInWithPassword({ email, password })
        if (error) throw new Error(describeAuthError(error.message))
      },
      async signUp(email, password, displayName) {
        const { data, error } = await client.auth.signUp({
          email,
          password,
          options: {
            // Read once by the backend when it creates the player profile (validated there).
            data: { display_name: displayName },
            emailRedirectTo: `${window.location.origin}/login`,
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
    }),
    [client, session, loading],
  )

  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
}

/** Turns Supabase's messages into player-facing text. */
export function describeAuthError(message: string): string {
  const normalized = message.toLowerCase()
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
