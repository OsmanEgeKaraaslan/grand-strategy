import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { fakeAuth, renderPage } from '../test/render'
import { LoginPage } from './LoginPage'

const signedOut = (overrides = {}) => fakeAuth({ session: null, ...overrides })

describe('LoginPage', () => {
  it('signs in with the entered credentials and goes to the dashboard', async () => {
    const auth = signedOut()
    renderPage(<LoginPage />, {
      route: '/login',
      path: '/login',
      auth,
      extraRoutes: [{ path: '/', element: <p>Dashboard</p> }],
    })

    await userEvent.type(screen.getByLabelText('Email'), ' general@reach.test ')
    await userEvent.type(screen.getByLabelText('Password'), 'correct-horse')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(auth.signIn).toHaveBeenCalledWith('general@reach.test', 'correct-horse', undefined)
    expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  })

  it('shows authentication errors and stays on the page', async () => {
    const auth = signedOut({ signIn: vi.fn().mockRejectedValue(new Error('Wrong email or password.')) })
    renderPage(<LoginPage />, { route: '/login', path: '/login', auth })

    await userEvent.type(screen.getByLabelText('Email'), 'general@reach.test')
    await userEvent.type(screen.getByLabelText('Password'), 'nope')
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.')
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
  })

  it('links to registration', () => {
    renderPage(<LoginPage />, { route: '/login', path: '/login', auth: signedOut() })
    expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/register')
  })

  it('sends already signed-in players onwards', () => {
    renderPage(<LoginPage />, {
      route: '/login',
      path: '/login',
      extraRoutes: [{ path: '/', element: <p>Dashboard</p> }],
    })
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
  })
})
