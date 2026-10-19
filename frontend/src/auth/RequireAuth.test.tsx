import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeAuth, renderPage } from '../test/render'
import { RequireAuth } from './RequireAuth'

const protectedPage = (
  <RequireAuth>
    <p>Secret war plans</p>
  </RequireAuth>
)

describe('RequireAuth', () => {
  it('redirects anonymous visitors to the login page', () => {
    renderPage(protectedPage, {
      route: '/games/42',
      path: '/games/:gameId',
      auth: fakeAuth({ session: null }),
      extraRoutes: [{ path: '/login', element: <p>Login page</p> }],
    })

    expect(screen.getByText('Login page')).toBeInTheDocument()
    expect(screen.queryByText('Secret war plans')).not.toBeInTheDocument()
  })

  it('waits for the stored session to be restored before deciding', () => {
    renderPage(protectedPage, { auth: fakeAuth({ session: null, loading: true }) })

    expect(screen.getByRole('status')).toHaveTextContent('Restoring your session')
  })

  it('renders the page for signed-in players', () => {
    renderPage(protectedPage)

    expect(screen.getByText('Secret war plans')).toBeInTheDocument()
  })
})
