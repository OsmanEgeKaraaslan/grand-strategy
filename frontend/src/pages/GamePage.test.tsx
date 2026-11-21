import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fakeApi, renderPage, showPanel } from '../test/render'
import { OSTRA, VARN, economyFixture, gameStateFixture, mapFixture, militaryFixture } from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

function renderGame(state = gameStateFixture(), overrides = {}) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(state),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

describe('GamePage', () => {
  it('draws every province of the map coloured by its owner from the server state', async () => {
    const { api } = renderGame()

    const map = await screen.findByRole('group', { name: 'Map of The Sundered Reach' })
    expect(api.getGameState).toHaveBeenCalledWith('g1')
    expect(api.getMap).toHaveBeenCalledWith('map-1')
    const varnhold = within(map).getByRole('button', { name: 'Varnhold, owned by Kingdom of Varn' })
    expect(varnhold).toHaveAttribute('fill', '#3f6fb5')
    expect(within(map).getByRole('button', { name: /^Sablewater, owned by Ostran Confederacy, mountains/ })).toHaveAttribute(
      'fill',
      '#b5503a',
    )
  })

  it('shows the round and lets the active player end the turn', async () => {
    const endTurn = vi.fn().mockResolvedValue({ round: 1, activeSeat: 1, turnNumber: 2, stateVersion: 4, roundCompleted: false, replayed: false })
    renderGame(gameStateFixture({ round: 3, turnNumber: 5 }), { endTurn })

    expect(await screen.findByText('Round 3')).toBeInTheDocument()
    expect(screen.getByText('Your turn')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'End turn' }))

    expect(endTurn).toHaveBeenCalledWith('g1', 5, expect.stringMatching(/^[0-9a-f-]{36}$/))
  })

  it('shows who is active and hides End turn while waiting', async () => {
    renderGame(gameStateFixture({ activeSeat: 1, yourTurn: false }))

    expect(await screen.findByText(/Waiting for Bryn \(Ostran Confederacy\)/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'End turn' })).not.toBeInTheDocument()
    await showPanel('players')
    const players = screen.getByRole('region', { name: 'Players' })
    expect(within(players).getByText('Active')).toBeInTheDocument()
  })

  it('opens a province panel with real data when a province is clicked or chosen by keyboard', async () => {
    renderGame()

    await userEvent.click(await screen.findByRole('button', { name: /^Elmsford/ }))
    const panel = screen.getByRole('region', { name: 'Elmsford' })
    expect(within(panel).getByText('Plains')).toBeInTheDocument()
    expect(within(panel).getByText('Kingdom of Varn')).toBeInTheDocument()
    expect(within(panel).getAllByText('8,000')).toHaveLength(2) // population and farmers
    expect(within(panel).getByText('across river')).toBeInTheDocument()
    expect(await within(panel).findByRole('button', { name: 'Forces in Elmsford' })).toBeInTheDocument()

    within(screen.getByRole('group', { name: /^Map of/ })).getByRole('button', { name: /^Sablewater/ }).focus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('region', { name: 'Sablewater' })).toBeInTheDocument()
  })

  it('marks occupied provinces: owner and controller are shown separately', async () => {
    renderGame(
      gameStateFixture({
        provinces: [
          ...gameStateFixture().provinces.filter((p) => p.key !== 'elmsford'),
          { key: 'elmsford', ownerNationId: VARN, controllerNationId: OSTRA, city: { id: 'city-elmsford', name: 'Elmsford', population: 8000 } },
        ],
      }),
    )

    await userEvent.click(await screen.findByRole('button', { name: /^Elmsford, owned by Kingdom of Varn, occupied by Ostran/ }))
    const panel = screen.getByRole('region', { name: 'Elmsford' })
    expect(within(panel).getByText('Occupied')).toBeInTheDocument()
    expect(within(panel).getByText('Ostran Confederacy')).toBeInTheDocument()
  })

  it('explains a stale turn', async () => {
    const endTurn = vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'TURN_CHANGED' }))
    renderGame(gameStateFixture(), { endTurn })

    await userEvent.click(await screen.findByRole('button', { name: 'End turn' }))

    expect(await screen.findByText(/The turn changed in the meantime/)).toBeInTheDocument()
  })

  it('reports games that have not started', async () => {
    renderGame(gameStateFixture(), {
      getGameState: vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'GAME_NOT_IN_PROGRESS', detail: 'The game has not started.' })),
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('The game has not started.')
  })
})
