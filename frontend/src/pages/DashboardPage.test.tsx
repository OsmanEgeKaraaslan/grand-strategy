import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { GameSummary, Lobby } from '../api/types'
import { fakeApi, fakeAuth, renderPage } from '../test/render'
import { lobbyFixture } from '../test/fixtures'
import { DashboardPage } from './DashboardPage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

const summary: GameSummary = {
  id: 'g1',
  name: 'Twin Shores',
  status: 'WAITING_FOR_PLAYERS',
  playerCount: 1,
  maxPlayers: 2,
  yourSeat: 0,
  youAreHost: true,
  createdAt: '2026-10-17T12:00:00Z',
}

const lobby: Lobby = lobbyFixture({ id: 'g2', name: 'New campaign' })

function renderDashboard(api = fakeApi()) {
  return renderPage(<DashboardPage />, {
    api,
    extraRoutes: [
      { path: '/games/:gameId', element: <p>Lobby page</p> },
      { path: '/login', element: <p>Login page</p> },
    ],
  })
}

describe('DashboardPage', () => {
  it('shows the player and the games they belong to', async () => {
    renderDashboard(
      fakeApi({ getMe: vi.fn().mockResolvedValue(me), listGames: vi.fn().mockResolvedValue([summary]) }),
    )

    expect(await screen.findByRole('heading', { name: 'Welcome, Aldric' })).toBeInTheDocument()
    const games = screen.getByRole('region', { name: 'Your games' })
    expect(await within(games).findByText('Twin Shores')).toBeInTheDocument()
    expect(within(games).getByText('1/2 players · Host')).toBeInTheDocument()
    expect(within(games).getByText('Waiting for players')).toBeInTheDocument()
  })

  it('has a helpful empty state', async () => {
    renderDashboard(fakeApi({ getMe: vi.fn().mockResolvedValue(me), listGames: vi.fn().mockResolvedValue([]) }))

    expect(await screen.findByText(/not in any game yet/)).toBeInTheDocument()
  })

  it('creates a game and opens its lobby', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(me),
      listGames: vi.fn().mockResolvedValue([]),
      createGame: vi.fn().mockResolvedValue(lobby),
    })
    renderDashboard(api)

    await userEvent.click(screen.getByRole('button', { name: 'Create game' }))

    expect(api.createGame).toHaveBeenCalledWith('New campaign', expect.anything())
    expect(await screen.findByText('Lobby page')).toBeInTheDocument()
  })

  it('joins with an invitation code and opens the lobby', async () => {
    const api = fakeApi({
      getMe: vi.fn().mockResolvedValue(me),
      listGames: vi.fn().mockResolvedValue([]),
      joinGame: vi.fn().mockResolvedValue({ ...lobby, status: 'READY' }),
    })
    renderDashboard(api)

    await userEvent.type(screen.getByLabelText('Invitation code'), 'abcd-efgh-jklm')
    await userEvent.click(screen.getByRole('button', { name: 'Join game' }))

    expect(api.joinGame).toHaveBeenCalledWith('abcd-efgh-jklm', expect.anything())
    expect(await screen.findByText('Lobby page')).toBeInTheDocument()
  })

  it('explains why joining a full game failed', async () => {
    renderDashboard(
      fakeApi({
        getMe: vi.fn().mockResolvedValue(me),
        listGames: vi.fn().mockResolvedValue([]),
        joinGame: vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'GAME_FULL', detail: 'full' })),
      }),
    )

    await userEvent.type(screen.getByLabelText('Invitation code'), 'ABCD-EFGH-JKLM')
    await userEvent.click(screen.getByRole('button', { name: 'Join game' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('This game is already full.')
  })

  it('logs out and returns to the login page', async () => {
    const auth = fakeAuth()
    renderPage(<DashboardPage />, {
      auth,
      api: fakeApi({ getMe: vi.fn().mockResolvedValue(me), listGames: vi.fn().mockResolvedValue([]) }),
      extraRoutes: [{ path: '/login', element: <p>Login page</p> }],
    })

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }))

    expect(auth.signOut).toHaveBeenCalled()
    expect(await screen.findByText('Login page')).toBeInTheDocument()
  })
})
