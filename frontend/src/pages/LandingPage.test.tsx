import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { App } from '../App'
import { fakeApi, fakeAuth, renderPage } from '../test/render'
import { LandingPage } from './LandingPage'

describe('LandingPage', () => {
  it('explains the game and leads to registration and sign-in', () => {
    renderPage(<LandingPage />)

    expect(screen.getByRole('heading', { level: 1, name: 'Thirty rounds to remake a continent.' })).toBeInTheDocument()
    const hero = screen.getByRole('region', { name: 'Thirty rounds to remake a continent.' })
    expect(within(hero).getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/register')
    expect(within(hero).getByRole('link', { name: 'Log in' })).toHaveAttribute('href', '/login')
  })

  it('lists the ten nations of the Grand Campaign and highlights the chosen one', async () => {
    renderPage(<LandingPage />)

    const factions = screen.getByRole('region', { name: 'Ten nations. Four are yours to choose.' })
    const rows = within(factions).getAllByRole('button')
    expect(rows).toHaveLength(10)
    expect(within(factions).getAllByText(/Great power · playable/)).toHaveLength(4)

    await userEvent.click(within(factions).getByRole('button', { name: /Kingdom of Krees/ }))

    expect(within(factions).getByRole('button', { name: /Kingdom of Krees/ })).toHaveAttribute('aria-pressed', 'true')
    expect(within(factions).getByText(/^Kingdom of Krees: \d+ provinces at the start$/)).toBeInTheDocument()
  })
})

describe('Home route', () => {
  it('shows the landing page to visitors instead of sending them to the login page', async () => {
    renderPage(<App />, { path: '/*', auth: fakeAuth({ session: null }) })

    expect(await screen.findByRole('heading', { level: 1, name: 'Thirty rounds to remake a continent.' })).toBeInTheDocument()
  })

  it('shows the dashboard to signed-in players', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue({ id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }),
      listGames: vi.fn().mockResolvedValue([]),
    })
    renderPage(<App />, { path: '/*', api })

    expect(await screen.findByRole('heading', { name: 'Welcome, Aldric' })).toBeInTheDocument()
    expect(screen.queryByText('Thirty rounds to remake a continent.')).not.toBeInTheDocument()
  })
})
