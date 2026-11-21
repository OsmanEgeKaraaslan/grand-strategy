import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { formatGold } from '../lib/gold'
import { fakeApi, renderPage, showPanel } from '../test/render'
import { OSTRA, cityEconomyFixture, economyFixture, gameStateFixture, mapFixture, militaryFixture } from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

function renderGame(overrides = {}, state = gameStateFixture()) {
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

async function openProvince(name: RegExp) {
  const map = await screen.findByRole('group', { name: /^Map of/ })
  await userEvent.click(within(map).getByRole('button', { name }))
}

describe('formatGold', () => {
  it('formats integer milligold exactly', () => {
    expect(formatGold(50000)).toBe('50')
    expect(formatGold(24500)).toBe('24.5')
    expect(formatGold(250)).toBe('0.25')
    expect(formatGold(1350)).toBe('1.35')
    expect(formatGold(1234567)).toBe('1,234.567')
    expect(formatGold(0)).toBe('0')
  })
})

describe('Game economy', () => {
  it('shows the treasury and next round projection from the server', async () => {
    renderGame({
      getEconomy: vi.fn().mockResolvedValue(
        economyFixture({
          treasuryMg: 10000,
          projectedGrossIncomeMg: 58000,
          projectedNetMg: 58000,
          lastRound: { round: 1, grossIncomeMg: 50000, upkeepMg: 0, deficitMg: 0, treasuryAfterMg: 60000, inArrears: false },
        }),
      ),
    })
    await showPanel('economy')

    const treasury = await screen.findByRole('region', { name: 'Treasury' })
    expect(within(treasury).getByText('10 gold')).toBeInTheDocument()
    expect(within(treasury).getAllByText('+58')).toHaveLength(2) // income and net
    expect(within(treasury).getByText(/Round 1: \+50 income/)).toBeInTheDocument()
  })

  it('warns about arrears', async () => {
    renderGame({ getEconomy: vi.fn().mockResolvedValue(economyFixture({ inArrears: true })) })
    await showPanel('economy')

    expect(await screen.findByText(/Your nation is in arrears/)).toBeInTheDocument()
  })

  it('shows population, employment, production and workshops of the selected city', async () => {
    renderGame()
    await showPanel('economy')
    await openProvince(/^Varnhold/)

    const panel = screen.getByRole('region', { name: 'Varnhold' })
    expect(within(panel).getByText('12,000')).toBeInTheDocument()
    expect(within(panel).getByText('8,000')).toBeInTheDocument() // farmers
    expect(within(panel).getByText('4,000')).toBeInTheDocument() // tier 1 workers
    expect(within(panel).getAllByText('20 gold / round')).toHaveLength(2) // production and collected
    expect(within(panel).getByText('Workshops (1/6)')).toBeInTheDocument()
    expect(within(panel).getByText('Tier 1 workshop')).toBeInTheDocument()
  })

  it('explains that an occupied city produces but pays nobody', async () => {
    renderGame({
      getEconomy: vi.fn().mockResolvedValue(
        economyFixture({}, [
          cityEconomyFixture({
            controllerNationId: OSTRA,
            incomeStatus: 'OCCUPIED',
            collectibleIncomeMg: 0,
            construction: { ...cityEconomyFixture().construction, allowed: false, blockers: ['OCCUPIED'] },
          }),
        ]),
      ),
    })
    await showPanel('economy')
    await openProvince(/^Varnhold/)

    const panel = screen.getByRole('region', { name: 'Varnhold' })
    expect(within(panel).getByText('20 gold / round')).toBeInTheDocument()
    expect(within(panel).getByText('0 gold / round')).toBeInTheDocument()
    expect(within(panel).getByText(/neither collects/)).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: /Build Tier 1 workshop/ })).toBeDisabled()
    expect(within(panel).getByText('Occupied by another nation — liberate it first.')).toBeInTheDocument()
  })

  it('starts construction with a fresh idempotency key and reports success', async () => {
    const buildWorkshop = vi.fn().mockResolvedValue({
      workshop: { id: 'w2', tier: 1, status: 'UNDER_CONSTRUCTION', orderedRound: 1, completesAfterRound: 1, operationalFromRound: 2 },
      treasuryMg: 10000,
      city: cityEconomyFixture({ cityId: 'city-elmsford', provinceKey: 'elmsford', cityName: 'Elmsford' }),
    })
    renderGame({ buildWorkshop })
    await showPanel('economy')
    await openProvince(/^Elmsford/)

    const panel = screen.getByRole('region', { name: 'Elmsford' })
    expect(within(panel).getByText(/Ready at the end of round 1; produces from round\s+2/)).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: 'Build Tier 1 workshop · 40 gold' }))

    expect(buildWorkshop).toHaveBeenCalledWith('g1', 'city-elmsford', 1, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(panel).findByText(/Construction started. Treasury: 10 gold/)).toBeInTheDocument()
  })

  it('shows why a construction was rejected', async () => {
    const buildWorkshop = vi.fn().mockRejectedValue(new ApiError({ status: 422, code: 'INSUFFICIENT_GOLD' }))
    renderGame({ buildWorkshop })
    await showPanel('economy')
    await openProvince(/^Elmsford/)

    await userEvent.click(screen.getByRole('button', { name: /Build Tier 1 workshop/ }))

    expect(await screen.findByText('Your treasury cannot pay for this.')).toBeInTheDocument()
  })

  it('disables construction when the server says it is not allowed and lists the reasons', async () => {
    renderGame({
      getEconomy: vi.fn().mockResolvedValue(
        economyFixture({}, [
          cityEconomyFixture({
            construction: {
              ...cityEconomyFixture().construction,
              allowed: false,
              blockers: ['NOT_YOUR_TURN', 'INSUFFICIENT_GOLD'],
            },
          }),
        ]),
      ),
    })
    await showPanel('economy')
    await openProvince(/^Varnhold/)

    expect(screen.getByRole('button', { name: /Build Tier 1 workshop/ })).toBeDisabled()
    const reasons = screen.getByRole('list', { name: 'Why you cannot build' })
    expect(within(reasons).getByText('You can build on your turn.')).toBeInTheDocument()
    expect(within(reasons).getByText('Not enough gold in the treasury.')).toBeInTheDocument()
  })

  it('offers no construction in other nations’ cities', async () => {
    renderGame()
    await showPanel('economy')
    await openProvince(/^Sablewater/)

    const panel = screen.getByRole('region', { name: 'Sablewater' })
    expect(within(panel).queryByRole('button', { name: /Build Tier 1 workshop/ })).not.toBeInTheDocument()
    expect(within(panel).getByText('Workshops (0/6)')).toBeInTheDocument()
  })

  it('refreshes the economy when the game state version changes (e.g. after the round ends)', async () => {
    const getGameState = vi
      .fn()
      .mockResolvedValueOnce(gameStateFixture({ stateVersion: 3 }))
      .mockResolvedValue(gameStateFixture({ stateVersion: 4, round: 2 }))
    const getEconomy = vi
      .fn()
      .mockResolvedValueOnce(economyFixture({ treasuryMg: 10000 }))
      .mockResolvedValue(economyFixture({ treasuryMg: 60000 }))
    const { queryClient } = renderGame({ getGameState, getEconomy })
    await showPanel('economy')

    expect(await screen.findByText('10 gold')).toBeInTheDocument()
    await act(() => queryClient.invalidateQueries({ queryKey: ['games', 'g1', 'state'] }))

    await waitFor(() => expect(screen.getByText('60 gold')).toBeInTheDocument())
    expect(getEconomy).toHaveBeenCalledTimes(2)
  })
})

