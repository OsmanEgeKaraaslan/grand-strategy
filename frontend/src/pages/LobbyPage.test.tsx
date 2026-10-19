import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { Lobby } from '../api/types'
import { fakeApi, renderPage } from '../test/render'
import { VARN, lobbyFixture, readyLobbyFixture } from '../test/fixtures'
import { LobbyPage } from './LobbyPage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

function renderLobby(getGame = vi.fn().mockResolvedValue(lobbyFixture()), overrides = {}) {
  const api = fakeApi({ getMe: vi.fn().mockResolvedValue(me), getGame, ...overrides })
  return renderPage(<LobbyPage />, {
    route: '/games/g1',
    path: '/games/:gameId',
    api,
    extraRoutes: [
      { path: '/', element: <p>Dashboard</p> },
      { path: '/games/:gameId/play', element: <p>Game screen</p> },
    ],
  })
}

describe('LobbyPage', () => {
  it('shows the lobby, its participants, open slots and the invitation code', async () => {
    const { api } = renderLobby()

    expect(await screen.findByRole('heading', { name: 'Twin Shores' })).toBeInTheDocument()
    expect(api.getGame).toHaveBeenCalledWith('g1')
    expect(screen.getByText('The Sundered Reach · 1 of 2 players · 1 open slot')).toBeInTheDocument()
    expect(screen.getByText('Waiting for players')).toBeInTheDocument()
    const invite = screen.getByRole('region', { name: 'Invitation code' })
    expect(within(invite).getByText('ABCD-EFGH-JKLM')).toBeInTheDocument()

    const players = screen.getByRole('region', { name: 'Players' })
    expect(within(players).getByText('Aldric')).toBeInTheDocument()
    expect(within(players).getByText('No nation chosen')).toBeInTheDocument()
    expect(within(players).getByText(/Open slot/)).toBeInTheDocument()
  })

  it('lets a player choose an available nation', async () => {
    const chosen: Lobby = lobbyFixture({
      participants: [{ ...lobbyFixture().participants[0], nationId: VARN }],
      nations: [
        { ...lobbyFixture().nations[0], chosenBySeat: 0, chosenByYou: true },
        lobbyFixture().nations[1],
      ],
    })
    const chooseNation = vi.fn().mockResolvedValue(chosen)
    renderLobby(vi.fn().mockResolvedValue(lobbyFixture()), { chooseNation })

    const nations = await screen.findByRole('region', { name: 'Choose your nation' })
    await userEvent.click(within(nations).getByRole('button', { name: /Kingdom of Varn/ }))

    expect(chooseNation).toHaveBeenCalledWith('g1', VARN)
    expect(await within(nations).findByRole('button', { name: /Kingdom of Varn.*your nation/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('disables nations chosen by other players', async () => {
    renderLobby(
      vi.fn().mockResolvedValue(
        readyLobbyFixture({
          nations: [
            { ...readyLobbyFixture().nations[0], chosenBySeat: null, chosenByYou: false },
            readyLobbyFixture().nations[1],
          ],
          participants: [{ ...readyLobbyFixture().participants[0], nationId: null }, readyLobbyFixture().participants[1]],
          canStart: false,
        }),
      ),
    )

    const nations = await screen.findByRole('region', { name: 'Choose your nation' })
    expect(within(nations).getByRole('button', { name: /Ostran Confederacy.*chosen by Bryn/ })).toBeDisabled()
    expect(within(nations).getByRole('button', { name: /Kingdom of Varn/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Start game' })).toBeDisabled()
  })

  it('shows a conflict when the nation was taken in the meantime', async () => {
    const chooseNation = vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'NATION_TAKEN' }))
    renderLobby(vi.fn().mockResolvedValue(lobbyFixture()), { chooseNation })

    await userEvent.click(await screen.findByRole('button', { name: /Ostran Confederacy/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Another player has already chosen that nation.')
  })

  it('lets the host start once everyone has a distinct nation, then opens the game', async () => {
    const startGame = vi.fn().mockResolvedValue(readyLobbyFixture({ status: 'IN_PROGRESS', canStart: false }))
    renderLobby(vi.fn().mockResolvedValue(readyLobbyFixture()), { startGame })

    const start = await screen.findByRole('button', { name: 'Start game' })
    expect(start).toBeEnabled()
    await userEvent.click(start)

    expect(startGame).toHaveBeenCalledWith('g1')
    expect(await screen.findByText('Game screen')).toBeInTheDocument()
  })

  it('shows no start button to other players', async () => {
    renderLobby(vi.fn().mockResolvedValue(readyLobbyFixture({ youAreHost: false, canStart: false, yourSeat: 1 })))

    expect(await screen.findByText(/Waiting for the host to start/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start game' })).not.toBeInTheDocument()
  })

  it('sends players of a started game to the game screen', async () => {
    renderLobby(vi.fn().mockResolvedValue(readyLobbyFixture({ status: 'IN_PROGRESS' })))

    expect(await screen.findByText('Game screen')).toBeInTheDocument()
  })

  it('tells non-members that the game is not available', async () => {
    renderLobby(vi.fn().mockRejectedValue(new ApiError({ status: 404, code: 'GAME_NOT_FOUND', detail: 'Game not found.' })))

    expect(await screen.findByRole('alert')).toHaveTextContent('not one of its players')
    expect(screen.getByRole('link', { name: /Back to dashboard/ })).toHaveAttribute('href', '/')
  })
})

