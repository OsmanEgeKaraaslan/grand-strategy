import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import type { Session, SupabaseClient } from '@supabase/supabase-js'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { PlayerProfile, TermsStatus } from '../api/types'
import { AuthProvider, useAuth, type Auth } from '../auth/AuthContext'
import { isRecoveryRedirect, readAuthRedirectError } from '../auth/redirects'
import { TermsGate } from '../auth/TermsGate'
import { Markdown } from '../legal/Markdown'
import { fakeApi, fakeAuth, fakeSession, renderPage } from '../test/render'
import { AccountPage } from './AccountPage'
import { ForgotPasswordPage } from './ForgotPasswordPage'
import { LegalPage } from './LegalPage'
import { LoginPage } from './LoginPage'
import { ResetPasswordPage } from './ResetPasswordPage'

function terms(overrides: Partial<TermsStatus> = {}): TermsStatus {
  return {
    currentTermsVersion: 'draft-1',
    currentPrivacyVersion: 'draft-1',
    accepted: true,
    acceptedTermsVersion: 'draft-1',
    acceptedPrivacyVersion: 'draft-1',
    acceptedAt: '2026-10-01T10:00:00Z',
    ...overrides,
  }
}

function profile(termsStatus: TermsStatus): PlayerProfile {
  return {
    id: 'p1',
    username: 'aldric',
    displayName: 'Aldric',
    emblem: 'crown',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    terms: termsStatus,
  }
}

const signedIn = { ...fakeSession(), user: { ...fakeSession().user, email: 'aldric@reach.test' } } as Session

describe('auth redirect parsing', () => {
  it('reads Supabase link errors from the hash or the query, and recognises recovery links', () => {
    expect(
      readAuthRedirectError({
        hash: '#error=access_denied&error_code=otp_expired&error_description=Email+link+is+invalid+or+has+expired',
        search: '',
      }),
    ).toEqual({ code: 'otp_expired', description: 'Email link is invalid or has expired' })
    expect(readAuthRedirectError({ hash: '', search: '?error=server_error' })).toEqual({ code: 'server_error', description: '' })
    expect(readAuthRedirectError({ hash: '#access_token=abc&type=recovery', search: '' })).toBeNull()
    expect(isRecoveryRedirect({ hash: '#access_token=abc&type=recovery' })).toBe(true)
    expect(isRecoveryRedirect({ hash: '#access_token=abc&type=signup' })).toBe(false)
  })
})

describe('AuthProvider recovery emails', () => {
  function supabaseWith(resetError: { message: string } | null) {
    return {
      auth: {
        getSession: vi.fn().mockResolvedValue({ data: { session: null } }),
        onAuthStateChange: vi.fn().mockReturnValue({ data: { subscription: { unsubscribe: vi.fn() } } }),
        resetPasswordForEmail: vi.fn().mockResolvedValue({ data: {}, error: resetError }),
      },
    } as unknown as SupabaseClient
  }

  function Probe({ onAuth }: { onAuth: (auth: Auth) => void }) {
    onAuth(useAuth())
    return null
  }

  async function authFor(client: SupabaseClient): Promise<Auth> {
    let latest: Auth | undefined
    render(
      <AuthProvider client={client}>
        <Probe onAuth={(auth) => (latest = auth)} />
      </AuthProvider>,
    )
    await waitFor(() => expect(latest?.loading).toBe(false))
    return latest!
  }

  it('sends the recovery link to the reset page and hides whether the address exists', async () => {
    const client = supabaseWith({ message: 'User not found' })
    const auth = await authFor(client)

    await act(() => auth.requestPasswordReset('nobody@reach.test'))

    expect(client.auth.resetPasswordForEmail).toHaveBeenCalledWith('nobody@reach.test', {
      redirectTo: `${window.location.origin}/reset-password`,
    })
  })

  it('reports throttling, which reveals nothing about the address', async () => {
    const auth = await authFor(supabaseWith({ message: 'For security purposes, you can only request this after 42 seconds.' }))
    await expect(auth.requestPasswordReset('a@reach.test')).rejects.toThrow('Please wait a minute')
  })
})

describe('ForgotPasswordPage', () => {
  it('answers the same way for every address', async () => {
    const auth = fakeAuth({ session: null })
    renderPage(<ForgotPasswordPage />, { auth })

    await userEvent.type(screen.getByLabelText('Email'), 'queen@reach.test')
    await userEvent.click(screen.getByRole('button', { name: 'Send recovery link' }))

    expect(auth.requestPasswordReset).toHaveBeenCalledWith('queen@reach.test', undefined)
    expect(await screen.findByText(/If an account exists for/)).toBeInTheDocument()
  })

  it('shows a throttling error', async () => {
    const auth = fakeAuth({ session: null, requestPasswordReset: vi.fn().mockRejectedValue(new Error('Please wait a minute.')) })
    renderPage(<ForgotPasswordPage />, { auth })

    await userEvent.type(screen.getByLabelText('Email'), 'queen@reach.test')
    await userEvent.click(screen.getByRole('button', { name: 'Send recovery link' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Please wait a minute.')
  })
})

describe('ResetPasswordPage', () => {
  it('explains an expired link and offers a new one', () => {
    renderPage(<ResetPasswordPage />, {
      auth: fakeAuth({ session: null, redirectError: { code: 'otp_expired', description: 'Email link is invalid or has expired' } }),
    })
    expect(screen.getByRole('alert')).toHaveTextContent('invalid or has expired')
    expect(screen.getByRole('link', { name: 'Request a new link' })).toHaveAttribute('href', '/forgot-password')
  })

  it('refuses to work without a recovery session', () => {
    renderPage(<ResetPasswordPage />, { auth: fakeAuth({ session: null }) })
    expect(screen.getByRole('alert')).toHaveTextContent('only works from the link')
  })

  it('sends a normally signed-in player to the account page, where the current password is needed', () => {
    renderPage(<ResetPasswordPage />, {
      route: '/reset-password',
      path: '/reset-password',
      extraRoutes: [{ path: '/account', element: <p>Account settings</p> }],
    })
    expect(screen.getByText('Account settings')).toBeInTheDocument()
  })

  it('sets the new password during recovery', async () => {
    const auth = fakeAuth({ passwordRecovery: true })
    renderPage(<ResetPasswordPage />, { auth })

    await userEvent.type(screen.getByLabelText('New password'), 'brand-new-pass')
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'brand-new-pass')
    await userEvent.click(screen.getByRole('button', { name: 'Save new password' }))

    expect(auth.updatePassword).toHaveBeenCalledWith('brand-new-pass')
    expect(await screen.findByText('Your password has been changed.')).toBeInTheDocument()
  })

  it('checks the new password before sending it', async () => {
    const auth = fakeAuth({ passwordRecovery: true })
    renderPage(<ResetPasswordPage />, { auth })

    await userEvent.type(screen.getByLabelText('New password'), 'short')
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'short')
    await userEvent.click(screen.getByRole('button', { name: 'Save new password' }))

    expect(screen.getByRole('alert')).toHaveTextContent('at least 8 characters')
    expect(auth.updatePassword).not.toHaveBeenCalled()
  })
})

describe('LoginPage account help', () => {
  it('links to password recovery and shows a rejected email link with a resend option', () => {
    renderPage(<LoginPage />, {
      route: '/login',
      path: '/login',
      auth: fakeAuth({ session: null, redirectError: { code: 'otp_expired', description: '' } }),
    })
    expect(screen.getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute('href', '/forgot-password')
    expect(screen.getAllByRole('alert')[0]).toHaveTextContent('invalid or has expired')
    expect(screen.getByRole('button', { name: 'Resend confirmation email' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Privacy Policy' })).toHaveAttribute('href', '/legal/privacy')
  })

  it('offers to resend the confirmation when the email is not confirmed yet', async () => {
    const auth = fakeAuth({
      session: null,
      signIn: vi.fn().mockRejectedValue(new Error('Please confirm your email address first — check your inbox.')),
    })
    renderPage(<LoginPage />, { route: '/login', path: '/login', auth })

    await userEvent.type(screen.getByLabelText('Email'), 'queen@reach.test')
    await userEvent.type(screen.getByLabelText('Password'), 'long-enough-1')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Resend confirmation email' }))

    expect(auth.resendConfirmation).toHaveBeenCalledWith('queen@reach.test', undefined)
  })
})

describe('AccountPage', () => {
  it('changes the password only after confirming the current one', async () => {
    const auth = fakeAuth({ session: signedIn })
    const api = fakeApi({ getMe: vi.fn().mockResolvedValue(profile(terms())) })
    renderPage(<AccountPage />, { auth, api })

    expect(screen.getByText('aldric@reach.test')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Current password'), 'old-password')
    await userEvent.type(screen.getByLabelText('New password'), 'new-password-1')
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'new-password-1')
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByText('Your password has been changed.')).toBeInTheDocument()
    expect(auth.reauthenticate).toHaveBeenCalledWith('old-password', undefined)
    expect(auth.updatePassword).toHaveBeenCalledWith('new-password-1')
    expect(await screen.findByText(/You accepted Terms draft-1/)).toBeInTheDocument()
  })

  it('does not change the password when the current one is wrong', async () => {
    const auth = fakeAuth({
      session: signedIn,
      reauthenticate: vi.fn().mockRejectedValue(new Error('Your current password is not correct.')),
    })
    renderPage(<AccountPage />, { auth, api: fakeApi({ getMe: vi.fn().mockResolvedValue(profile(terms())) }) })

    await userEvent.type(screen.getByLabelText('Current password'), 'wrong')
    await userEvent.type(screen.getByLabelText('New password'), 'new-password-1')
    await userEvent.type(screen.getByLabelText('Confirm new password'), 'new-password-1')
    await userEvent.click(screen.getByRole('button', { name: 'Change password' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('not correct')
    expect(auth.updatePassword).not.toHaveBeenCalled()
  })

  it('deletes the account after a typed confirmation and a fresh sign-in, then signs out', async () => {
    const auth = fakeAuth({ session: signedIn })
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(profile(terms())),
      deleteAccount: vi.fn().mockResolvedValue({ deletedAt: '2026-10-22T10:00:00Z' }),
    })
    renderPage(<AccountPage />, {
      auth,
      api,
      extraRoutes: [{ path: '/account-deleted', element: <p>Deleted page</p> }],
    })

    await userEvent.type(screen.getByLabelText('Password'), 'my-password')
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Type DELETE')
    expect(api.deleteAccount).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Type DELETE to confirm'), 'DELETE')
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }))

    expect(await screen.findByText('Deleted page')).toBeInTheDocument()
    expect(auth.reauthenticate).toHaveBeenCalledWith('my-password', undefined)
    expect(api.deleteAccount).toHaveBeenCalled()
    expect(auth.signOut).toHaveBeenCalled()
  })

  it('explains why the account cannot be deleted yet', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(profile(terms())),
      deleteAccount: vi.fn().mockRejectedValue(
        new ApiError({
          status: 409,
          code: 'ACCOUNT_DELETION_BLOCKED',
          detail: 'You are still seated in 1 unfinished game (lobbies included).',
        }),
      ),
    })
    const auth = fakeAuth({ session: signedIn })
    renderPage(<AccountPage />, { auth, api })

    await userEvent.type(screen.getByLabelText('Password'), 'my-password')
    await userEvent.type(screen.getByLabelText('Type DELETE to confirm'), 'DELETE')
    await userEvent.click(screen.getByRole('button', { name: 'Delete my account' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('1 unfinished game')
    expect(auth.signOut).not.toHaveBeenCalled()
  })
})

describe('TermsGate', () => {
  it('asks accounts without the current acceptance to accept before showing the game', async () => {
    const accepted = profile(terms())
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(profile(terms({ accepted: false, acceptedTermsVersion: null, acceptedPrivacyVersion: null, acceptedAt: null }))),
      acceptTerms: vi.fn().mockResolvedValue(accepted),
    })
    renderPage(
      <TermsGate>
        <p>The realm</p>
      </TermsGate>,
      { api },
    )

    expect(await screen.findByText('Before you play')).toBeInTheDocument()
    expect(screen.queryByText('The realm')).not.toBeInTheDocument()
    const accept = screen.getByRole('button', { name: 'Accept and continue' })
    expect(accept).toBeDisabled()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(accept)

    expect(await screen.findByText('The realm')).toBeInTheDocument()
    expect(api.acceptTerms).toHaveBeenCalledWith('draft-1', 'draft-1')
  })

  it('tells players who accepted an older version that the terms changed', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(profile(terms({ accepted: false, currentTermsVersion: 'draft-2' }))),
    })
    renderPage(
      <TermsGate>
        <p>The realm</p>
      </TermsGate>,
      { api },
    )
    expect(await screen.findByText('Our terms have changed')).toBeInTheDocument()
    expect(screen.getByText(/version draft-2/)).toBeInTheDocument()
  })

  it('lets accepted players straight through', async () => {
    renderPage(
      <TermsGate>
        <p>The realm</p>
      </TermsGate>,
      { api: fakeApi({ getMe: vi.fn().mockResolvedValue(profile(terms())) }) },
    )
    expect(await screen.findByText('The realm')).toBeInTheDocument()
  })
})

describe('legal pages', () => {
  it('render the documents publicly, complete and without placeholders', () => {
    renderPage(<LegalPage document="privacy" />, { auth: fakeAuth({ session: null }) })
    expect(screen.getByRole('heading', { level: 1, name: 'Privacy Policy' })).toBeInTheDocument()
    expect(screen.getByText(/Version 1.0 · Blaspi/)).toBeInTheDocument()
    expect(document.querySelectorAll('mark.legal__placeholder')).toHaveLength(0)
    expect(document.body.textContent).not.toMatch(/OPERATOR|\{\{|not yet published/)
    expect(document.body.textContent).toMatch(/Osman Ege Karaaslan/)
    expect(document.body.textContent).toMatch(/blaspiteam@outlook\.com/)
    expect(screen.getAllByRole('link', { name: 'Cookie and Storage Notice' })[0]).toHaveAttribute('href', '/legal/cookies')
  })

  it('never turns document text into markup or unsafe links', () => {
    render(
      <MemoryRouter>
        <Markdown source={'<img src=x onerror=alert(1)> [bad](javascript:alert(1)) [ok](https://example.org)'} />
      </MemoryRouter>,
    )
    expect(document.querySelector('img')).toBeNull()
    expect(screen.getByText(/<img src=x/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'bad' })).toBeNull()
    expect(screen.getByRole('link', { name: 'ok' })).toHaveAttribute('rel', 'noopener noreferrer')
  })
})
