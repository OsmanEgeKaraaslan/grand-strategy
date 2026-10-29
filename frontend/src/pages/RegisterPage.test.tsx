import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { fakeAuth, renderPage } from '../test/render'
import { RegisterPage } from './RegisterPage'

async function fillForm(
  values: { name?: string; email?: string; password?: string; confirm?: string; consent?: boolean } = {},
) {
  const {
    name = 'Queen of Varn',
    email = 'queen@reach.test',
    password = 'long-enough-1',
    confirm = password,
    consent = true,
  } = values
  await userEvent.type(screen.getByLabelText('Display name'), name)
  await userEvent.type(screen.getByLabelText('Email'), email)
  await userEvent.type(screen.getByLabelText('Password'), password)
  await userEvent.type(screen.getByLabelText('Confirm password'), confirm)
  if (consent) await userEvent.click(screen.getByRole('checkbox', { name: /I accept the Terms of Service/ }))
  await userEvent.click(screen.getByRole('button', { name: 'Register' }))
}

describe('RegisterPage', () => {
  it('requires consent to the Terms of Service and links the legal drafts', async () => {
    const auth = fakeAuth({ session: null })
    renderPage(<RegisterPage />, { route: '/register', path: '/register', auth })

    expect(screen.getAllByRole('link', { name: 'Terms of Service' })[0]).toHaveAttribute('href', '/legal/terms')
    expect(screen.getAllByRole('link', { name: 'Privacy Policy' })[0]).toHaveAttribute('href', '/legal/privacy')
    await fillForm({ consent: false })

    expect(await screen.findByRole('alert')).toHaveTextContent('Please accept the Terms of Service')
    expect(auth.signUp).not.toHaveBeenCalled()
  })

  it('offers to resend the confirmation email', async () => {
    const auth = fakeAuth({
      session: null,
      signUp: vi.fn().mockResolvedValue({ status: 'confirmation-required', email: 'queen@reach.test' }),
    })
    renderPage(<RegisterPage />, { route: '/register', path: '/register', auth })
    await fillForm()

    await userEvent.click(await screen.findByRole('button', { name: 'Resend confirmation email' }))

    expect(auth.resendConfirmation).toHaveBeenCalledWith('queen@reach.test')
    expect(await screen.findByText(/If this address has an unconfirmed account/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Resend possible in/ })).toBeDisabled()
  })

  it('explains that the email must be confirmed when Supabase requires it', async () => {
    const auth = fakeAuth({
      session: null,
      signUp: vi.fn().mockResolvedValue({ status: 'confirmation-required', email: 'queen@reach.test' }),
    })
    renderPage(<RegisterPage />, { route: '/register', path: '/register', auth })

    await fillForm()

    expect(auth.signUp).toHaveBeenCalledWith('queen@reach.test', 'long-enough-1', 'Queen of Varn')
    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
    expect(screen.getByText('queen@reach.test')).toBeInTheDocument()
  })

  it('goes straight to the dashboard when no confirmation is needed', async () => {
    const auth = fakeAuth({ session: null })
    renderPage(<RegisterPage />, {
      route: '/register',
      path: '/register',
      auth,
      extraRoutes: [{ path: '/', element: <p>Dashboard</p> }],
    })

    await fillForm()

    expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  })

  it('validates the form before calling Supabase', async () => {
    const auth = fakeAuth({ session: null })
    renderPage(<RegisterPage />, { route: '/register', path: '/register', auth })

    await fillForm({ password: 'long-enough-1', confirm: 'different-123' })

    expect(screen.getByRole('alert')).toHaveTextContent('The passwords do not match.')
    expect(auth.signUp).not.toHaveBeenCalled()
  })

  it('rejects short passwords and invalid display names', async () => {
    const auth = fakeAuth({ session: null })
    renderPage(<RegisterPage />, { route: '/register', path: '/register', auth })

    await fillForm({ name: '<b>', password: 'short' })

    expect(screen.getByRole('alert')).toHaveTextContent('Display names have 3–24')
    expect(auth.signUp).not.toHaveBeenCalled()
  })

  it('shows sign-up errors from Supabase', async () => {
    const auth = fakeAuth({
      session: null,
      signUp: vi.fn().mockRejectedValue(new Error('An account with this email already exists. Try signing in.')),
    })
    renderPage(<RegisterPage />, { route: '/register', path: '/register', auth })

    await fillForm()

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists')
  })
})
