import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ProvinceFortification, SiegeOverview, SiegeSummary } from '../api/types'
import { fakeApi, renderPage, showPanel } from '../test/render'
import {
  OSTRA,
  VARN,
  cityEconomyFixture,
  economyFixture,
  gameStateFixture,
  mapFixture,
  militaryFixture,
} from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

function siegeFixture(overrides: Partial<SiegeSummary> = {}): SiegeSummary {
  return {
    id: 's1',
    provinceKey: 'sablewater',
    cityName: 'Sable',
    cityId: 'city-sablewater',
    besiegerNationId: VARN,
    defenderNationId: OSTRA,
    status: 'ACTIVE',
    startedRound: 1,
    endedRound: null,
    endReason: null,
    fortressLevel: 1,
    requiredProgress: 600,
    progress: 300,
    ratePerRound: 200,
    artilleryManpower: 2000,
    artilleryBonusBps: 0,
    estimatedRoundsLeft: 2,
    nextAttrition: 361,
    populationLost: 980,
    artillerySiege: null,
    damagedBuildingId: null,
    ...overrides,
  }
}

function overviewFixture(overrides: Partial<SiegeOverview> = {}): SiegeOverview {
  return {
    gameId: 'g1',
    round: 3,
    fortificationsAvailable: true,
    fortifications: [
      { cityId: 'city-sablewater', provinceKey: 'sablewater', cityName: 'Sable', level: 1, effectiveLevel: 1, status: 'ACTIVE', integrity: 100, maxIntegrity: 100, project: null },
      { cityId: 'city-varnhold', provinceKey: 'varnhold', cityName: 'Varn', level: 2, effectiveLevel: 2, status: 'ACTIVE', integrity: 200, maxIntegrity: 200, project: null },
      { cityId: 'city-elmsford', provinceKey: 'elmsford', cityName: 'Elmsford', level: 1, effectiveLevel: 0, status: 'DAMAGED', integrity: 0, maxIntegrity: 100, project: null },
    ],
    activeSieges: [siegeFixture()],
    recentSieges: [],
    ...overrides,
  }
}

const works = { targetLevel: 2, costMg: 100000, durationRounds: 2, completesAfterRound: 4, operationalFromRound: 5, allowed: true, blockers: [] }

function provinceFixture(key: string): ProvinceFortification {
  if (key === 'sablewater') {
    return {
      provinceKey: 'sablewater', cityId: 'city-sablewater', cityName: 'Sable', population: 7220,
      ownerNationId: OSTRA, controllerNationId: OSTRA, fortificationsAvailable: true,
      fortification: overviewFixture().fortifications[0], activeSiege: siegeFixture(),
      upgrade: { ...works, allowed: false, blockers: ['NOT_OWNER', 'BESIEGED'] }, repair: null,
    }
  }
  if (key === 'elmsford') {
    return {
      provinceKey: 'elmsford', cityId: 'city-elmsford', cityName: 'Elmsford', population: 8000,
      ownerNationId: VARN, controllerNationId: VARN, fortificationsAvailable: true,
      fortification: overviewFixture().fortifications[2], activeSiege: null,
      upgrade: { ...works, allowed: false, blockers: ['FORTRESS_DAMAGED'] },
      repair: { targetLevel: 1, costMg: 15000, durationRounds: 1, completesAfterRound: 3, operationalFromRound: 4, allowed: true, blockers: [] },
    }
  }
  return {
    provinceKey: key, cityId: `city-${key}`, cityName: 'Varn', population: 12000,
    ownerNationId: VARN, controllerNationId: VARN, fortificationsAvailable: true,
    fortification: overviewFixture().fortifications[1], activeSiege: null,
    upgrade: { ...works, targetLevel: 3, costMg: 150000, allowed: false, blockers: ['INSUFFICIENT_GOLD'] },
    repair: { ...works, allowed: false, blockers: ['NOT_DAMAGED'] },
  }
}

function renderGame(overrides = {}) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(gameStateFixture()),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getSieges: vi.fn().mockResolvedValue(overviewFixture()),
    getProvinceFortification: vi.fn((_game: string, key: string) => Promise.resolve(provinceFixture(key))),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

async function openProvince(name: RegExp) {
  const map = await screen.findByRole('group', { name: /^Map of/ })
  await userEvent.click(within(map).getByRole('button', { name }))
  return screen.getByRole('region', { name: name.source.replace(/[\^$]/g, '').split('.')[0] })
}

describe('Fortifications and sieges', () => {
  it('marks fortresses and sieges on the map', async () => {
    renderGame()

    const map = await screen.findByRole('group', { name: /^Map of/ })
    expect(await within(map).findByRole('button', { name: /^Sablewater.*fortress level 1.*under siege/ })).toBeInTheDocument()
    expect(within(map).getByRole('button', { name: /^Varnhold.*fortress level 2/ })).toBeInTheDocument()
    expect(within(map).getByRole('button', { name: /^Elmsford.*fortress level 1 \(damaged\)/ })).toBeInTheDocument()
  })

  it('shows siege progress, artillery support, civilian losses and why the city is not yet taken', async () => {
    renderGame()

    await openProvince(/^Sablewater/)
    const siege = await screen.findByRole('region', { name: 'Siege' })
    expect(within(siege).getByText(/Under siege by Kingdom of Varn/)).toBeInTheDocument()
    expect(within(siege).getByText('Your siege')).toBeInTheDocument()
    expect(within(siege).getByRole('progressbar', { name: 'Siege progress' })).toHaveAttribute('aria-valuenow', '300')
    expect(within(siege).getByText('300 / 600 (50%)')).toBeInTheDocument()
    expect(within(siege).getByText('+200')).toBeInTheDocument()
    expect(within(siege).getByText('about 2')).toBeInTheDocument()
    expect(within(siege).getByText('980 so far · −361 next round (5%)')).toBeInTheDocument()
    expect(within(siege).getByText(/Artillery support: 2,000 soldiers/)).toBeInTheDocument()
    expect(within(siege).getByText(/keeps control of Sable until the siege succeeds/)).toBeInTheDocument()
  })

  it('explains a siege without artillery', async () => {
    const slow = siegeFixture({ artilleryManpower: 0, ratePerRound: 100, estimatedRoundsLeft: 3 })
    renderGame({
      getProvinceFortification: vi.fn().mockResolvedValue({ ...provinceFixture('sablewater'), activeSiege: slow }),
    })

    await openProvince(/^Sablewater/)
    expect(await screen.findByText(/No artillery: the siege advances at its slowest/)).toBeInTheDocument()
  })

  it('repairs a damaged own fortress and explains disabled fortress work', async () => {
    const repairFortress = vi.fn().mockResolvedValue({ fortification: overviewFixture().fortifications[2], costMg: 15000, treasuryMg: 35000 })
    renderGame({ repairFortress })

    await openProvince(/^Elmsford/)
    const fort = await screen.findByRole('region', { name: 'Fortification' })
    expect(within(fort).getByText('Damaged — no resistance')).toBeInTheDocument()
    expect(within(fort).getByText('0 / 100')).toBeInTheDocument()
    await userEvent.click(within(fort).getByRole('button', { name: 'Repair fortress · 15 gold' }))
    expect(repairFortress).toHaveBeenCalledWith('g1', 'city-elmsford', expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(fort).findByText('Fortress work ordered.')).toBeInTheDocument()

    await openProvince(/^Varnhold/)
    const varnhold = await screen.findByRole('region', { name: 'Fortification' })
    expect(await within(varnhold).findByRole('button', { name: 'Raise to level 3 · 150 gold' })).toBeDisabled()
    expect(within(varnhold).getByText('Not enough gold in the treasury.')).toBeInTheDocument()
  })

  it('offers no fortress work in an enemy city', async () => {
    renderGame()

    await openProvince(/^Sablewater/)
    const fort = await screen.findByRole('region', { name: 'Fortification' })
    expect(within(fort).queryByRole('button')).not.toBeInTheDocument()
  })

  it('repairs a damaged workshop and shows when it works again', async () => {
    const damaged = cityEconomyFixture({
      workshops: [
        {
          id: 'w1', tier: 1, status: 'DAMAGED', orderedRound: 0, completesAfterRound: null, operationalFromRound: null,
          repairCompletesAfterRound: null,
          repair: { costMg: 12000, durationRounds: 1, completesAfterRound: 3, operationalFromRound: 4, allowed: true, blockers: [] },
          upgradeTargetTier: null, upgradeCompletesAfterRound: null, upgrade: null,
        },
      ],
      employment: { agricultureWorkers: 12000, tier1Workers: 0, tier2Workers: 0 },
    })
    const repairWorkshop = vi.fn().mockResolvedValue({ workshop: damaged.workshops[0], costMg: 12000, treasuryMg: 38000 })
    renderGame({ getEconomy: vi.fn().mockResolvedValue(economyFixture({}, [damaged])), repairWorkshop })

    const panel = await openProvince(/^Varnhold/)
    expect(await within(panel).findByText(/Damaged · employs nobody until repaired/)).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: 'Repair · 12 gold' }))
    expect(repairWorkshop).toHaveBeenCalledWith('g1', 'city-varnhold', 'w1', expect.stringMatching(/^[0-9a-f-]{36}$/))
  })

  it('shows a workshop under repair and blocked repairs', async () => {
    const underRepair = cityEconomyFixture({
      workshops: [
        { id: 'w1', tier: 1, status: 'UNDER_REPAIR', orderedRound: 0, completesAfterRound: null, operationalFromRound: null, repairCompletesAfterRound: 3, repair: null, upgradeTargetTier: null, upgradeCompletesAfterRound: null, upgrade: null },
        {
          id: 'w2', tier: 1, status: 'DAMAGED', orderedRound: 0, completesAfterRound: null, operationalFromRound: null, repairCompletesAfterRound: null,
          repair: { costMg: 12000, durationRounds: 1, completesAfterRound: 3, operationalFromRound: 4, allowed: false, blockers: ['BESIEGED'] },
          upgradeTargetTier: null, upgradeCompletesAfterRound: null, upgrade: null,
        },
      ],
    })
    renderGame({ getEconomy: vi.fn().mockResolvedValue(economyFixture({}, [underRepair])) })

    const panel = await openProvince(/^Varnhold/)
    expect(await within(panel).findByText(/repaired at the end of round 3, works from round 4/)).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Repair · 12 gold' })).toBeDisabled()
    expect(within(panel).getByText('The city is under siege.')).toBeInTheDocument()
  })

  it('refreshes sieges when a round resolves and reports the result', async () => {
    const getGameState = vi
      .fn()
      .mockResolvedValueOnce(gameStateFixture())
      .mockResolvedValue(gameStateFixture({ stateVersion: 9, round: 2, turnNumber: 3 }))
    const taken = siegeFixture({ status: 'SUCCEEDED', endedRound: 1, endReason: 'CAPTURED', artillerySiege: false, populationLost: 2117, ratePerRound: null })
    const getSieges = vi
      .fn()
      .mockResolvedValueOnce(overviewFixture())
      .mockResolvedValue(overviewFixture({ activeSieges: [], recentSieges: [taken] }))
    const endTurn = vi.fn().mockResolvedValue({ round: 2, activeSeat: 0, turnNumber: 3, stateVersion: 9, roundCompleted: true, replayed: false })
    renderGame({ getGameState, getSieges, endTurn })
    await showPanel('military')

    const card = await screen.findByRole('region', { name: 'Sieges' })
    expect(within(card).getByText(/besieged by Kingdom of Varn · 300\/600 · about 2 rounds left/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'End turn' }))

    expect(await within(card).findByText(/taken by Kingdom of Varn in round 1 by starvation/)).toBeInTheDocument()
    expect(within(card).getByText(/2,117 civilians lost/)).toBeInTheDocument()
    expect(within(card).getByText('No city is under siege.')).toBeInTheDocument()
    expect(getSieges).toHaveBeenCalledTimes(2)
  })
})
