import { act, render, screen, waitFor } from '@testing-library/react'
import type { Session, SupabaseClient } from '@supabase/supabase-js'
import { describe, expect, it, vi } from 'vitest'
import { AuthProvider, describeAuthError, useAuth, type Auth } from './AuthContext'

function fakeSupabase(storedSession: Session | null, signUpSession: Session | null = null) {
  return {
    auth: {
      getSession: vi.fn().mockResolvedValue({ data: { session: storedSession } }),
      onAuthStateChange: vi.fn().mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } }),
      signInWithPassword: vi.fn().mockResolvedValue({ data: {}, error: null }),
      signUp: vi.fn().mockResolvedValue({ data: { session: signUpSession }, error: null }),
      signOut: vi.fn().mockResolvedValue({ error: null }),
    },
  } as unknown as SupabaseClient
}

const storedSession = { access_token: 'stored-token' } as Session

function Probe({ onAuth }: { onAuth: (auth: Auth) => void }) {
  const auth = useAuth()
  onAuth(auth)
  return <p>{auth.loading ? 'loading' : auth.session ? 'signed in' : 'signed out'}</p>
}

describe('AuthProvider', () => {
  it('restores an existing session on start', async () => {
    let latest: Auth | undefined
    render(
      <AuthProvider client={fakeSupabase(storedSession)}>
        <Probe onAuth={(auth) => (latest = auth)} />
      </AuthProvider>,
    )

    expect(screen.getByText('loading')).toBeInTheDocument()
    expect(await screen.findByText('signed in')).toBeInTheDocument()
    await expect(latest!.accessToken()).resolves.toBe('stored-token')
  })

  it('reports when sign-up needs email confirmation and stores the chosen display name', async () => {
    const client = fakeSupabase(null)
    let latest: Auth | undefined
    render(
      <AuthProvider client={client}>
        <Probe onAuth={(auth) => (latest = auth)} />
      </AuthProvider>,
    )
    await screen.findByText('signed out')

    let result
    await act(async () => {
      result = await latest!.signUp('queen@reach.test', 'long-enough-1', 'Queen of Varn')
    })

    expect(result).toEqual({ status: 'confirmation-required', email: 'queen@reach.test' })
    expect(client.auth.signUp).toHaveBeenCalledWith(
      expect.objectContaining({
        email: 'queen@reach.test',
        options: expect.objectContaining({ data: { display_name: 'Queen of Varn' } }),
      }),
    )
  })

  it('surfaces sign-in errors in player-facing words', async () => {
    const client = fakeSupabase(null)
    vi.mocked(client.auth.signInWithPassword).mockResolvedValue({
      data: { user: null, session: null },
      error: { message: 'Invalid login credentials' },
    } as never)
    let latest: Auth | undefined
    render(
      <AuthProvider client={client}>
        <Probe onAuth={(auth) => (latest = auth)} />
      </AuthProvider>,
    )
    await waitFor(() => expect(latest?.loading).toBe(false))

    await expect(latest!.signIn('a@b.c', 'x')).rejects.toThrow('Wrong email or password.')
  })

  it('maps known Supabase messages', () => {
    expect(describeAuthError('Email not confirmed')).toMatch(/confirm your email/)
    expect(describeAuthError('User already registered')).toMatch(/already exists/)
    expect(describeAuthError('Failed to fetch')).toMatch(/Cannot reach the sign-in service/)
    expect(describeAuthError('Something unexpected')).toBe('Something unexpected')
  })
})
