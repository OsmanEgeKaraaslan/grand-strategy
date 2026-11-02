import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { SiegeOverview, SiegeSummary } from '../api/types'
import { PasswordField } from '../components/PasswordInput'
import { projectedPopulationChange } from '../lib/population'
import { fakeApi, openForces, renderPage } from '../test/render'
import {
  VARN,
  battalionFixture,
  cityEconomyFixture,
  economyFixture,
  gameStateFixture,
  mapFixture,
  militaryFixture,
} from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

function siegeOn(provinceKey: string, nextAttrition: number): SiegeSummary {
  return { id: `s-${provinceKey}`, provinceKey, nextAttrition, status: 'ACTIVE' } as SiegeSummary
}

function sieges(active: SiegeSummary[] = []): SiegeOverview {
  return { gameId: 'g1', round: 1, fortificationsAvailable: true, fortifications: [], activeSieges: active, recentSieges: [] }
}

function renderGame(overrides = {}) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(gameStateFixture()),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(
      economyFixture({}, [
        cityEconomyFixture(),
        cityEconomyFixture({ cityId: 'city-elmsford', provinceKey: 'elmsford', growth: { eligible: false, rateBps: 50, nextRoundGrowth: 0, blockers: ['BESIEGED'] } }),
        cityEconomyFixture({ cityId: 'city-sablewater', provinceKey: 'sablewater', ownerNationId: 'nation-ostra', controllerNationId: 'nation-ostra' }),
      ]),
    ),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getSieges: vi.fn().mockResolvedValue(sieges([siegeOn('elmsford', 400), siegeOn('sablewater', 400)])),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

describe('Top bar (playtest feedback)', () => {
  it('shows the projected population change per round from the server’s growth and siege figures', async () => {
    renderGame()
    const resources = await screen.findByRole('region', { name: 'Resources' })
    // Varnhold grows by 60, besieged Elmsford loses 400; Sablewater is not the player's.
    const change = await within(resources).findByText('−340')
    expect(change.closest('.resource-bar__item')).toHaveAttribute('title', expect.stringMatching(/growth \+60, siege losses −400/))
    expect(change.closest('dd')).toHaveClass('negative')
  })

  it('shows army strength without repeating its upkeep', async () => {
    renderGame()
    const resources = await screen.findByRole('region', { name: 'Resources' })
    expect(await within(resources).findByText('2 bn · 1,650 men')).toBeInTheDocument()
    expect(within(resources).queryByText(/−0\.825/)).not.toBeInTheDocument()
  })

  it('adds only the player’s own cities and is unknown until both views have loaded', () => {
    const state = gameStateFixture()
    const economy = economyFixture({}, [cityEconomyFixture()])
    expect(projectedPopulationChange(state, VARN, economy, undefined)).toBeUndefined()
    expect(projectedPopulationChange(state, VARN, economy, sieges())).toEqual({ net: 60, growth: 60, attrition: 0 })
  })
})

describe('Army groups', () => {
  it('re-selects a named group from its tag', async () => {
    const military = militaryFixture({
      armies: [{ id: 'a1', nationId: VARN, name: 'First Army', provinceKey: 'elmsford', battalionIds: ['b1'] }],
      battalions: [battalionFixture({ id: 'b1', armyId: 'a1' }), battalionFixture({ id: 'b2', manpower: 650 })],
    })
    renderGame({ getMilitary: vi.fn().mockResolvedValue(military) })

    const panel = await openForces('Elmsford')
    await userEvent.click(await within(panel).findByRole('button', { name: 'First Army' }))

    expect(within(panel).getByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' })).toBeChecked()
    expect(within(panel).getByRole('checkbox', { name: 'Select Infantry, 650 of 1,000 men' })).not.toBeChecked()
  })
})

describe('PasswordField', () => {
  it('hides the password by default and toggles it without submitting or losing the value', async () => {
    const submit = vi.fn((event: Event) => event.preventDefault())
    render(
      <form onSubmit={(e) => submit(e.nativeEvent)}>
        <PasswordField label="Password" defaultValue="" />
      </form>,
    )
    const input = screen.getByLabelText('Password')
    expect(input).toHaveAttribute('type', 'password')
    await userEvent.type(input, 'secret-1')

    await userEvent.click(screen.getByRole('button', { name: 'Show password' }))
    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveValue('secret-1')
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveValue('secret-1')
    expect(submit).not.toHaveBeenCalled()
  })
})
