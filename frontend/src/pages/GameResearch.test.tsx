import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { CityInstitutions, ResearchOverview } from '../api/types'
import { fakeApi, renderPage, showPanel } from '../test/render'
import {
  VARN,
  cityEconomyFixture,
  economyFixture,
  gameStateFixture,
  mapFixture,
  militaryFixture,
} from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

const DOCTRINE = { tier: 1, costPoints: 30, riverPenaltyReductionBps: 0, mountainPenaltyReductionBps: 0, siegeArtilleryBonusBps: 0, militaryUpkeepReductionBps: 0, chosen: false, allowed: true, blockers: [] }

function researchFixture(overrides: Partial<ResearchOverview> = {}): ResearchOverview {
  return {
    gameId: 'g1',
    round: 3,
    yourTurn: true,
    available: true,
    nationId: VARN,
    pools: [
      { pool: 'MILITARY', points: 45, perRound: 10, lastRound: 10 },
      { pool: 'CIVILIAN', points: 20, perRound: 10, lastRound: 10 },
      { pool: 'DOCTRINE', points: 30, perRound: 0, lastRound: 0 },
    ],
    military: {
      track: 'MILITARY',
      level: 1,
      maxLevel: 3,
      levels: [
        { level: 1, costPoints: 0, unlocked: true, damageBonusBps: {}, unlocksWorkshopTier: null, growthBonusBps: 0 },
        { level: 2, costPoints: 40, unlocked: false, damageBonusBps: { INFANTRY: 1000, CAVALRY: 1000, ARTILLERY: 1000 }, unlocksWorkshopTier: null, growthBonusBps: 0 },
        { level: 3, costPoints: 80, unlocked: false, damageBonusBps: { INFANTRY: 2000, CAVALRY: 2000, ARTILLERY: 2000 }, unlocksWorkshopTier: null, growthBonusBps: 0 },
      ],
      next: { targetLevel: 2, costPoints: 40, allowed: true, blockers: [] },
    },
    civilian: {
      track: 'CIVILIAN',
      level: 1,
      maxLevel: 3,
      levels: [
        { level: 1, costPoints: 0, unlocked: true, damageBonusBps: {}, unlocksWorkshopTier: null, growthBonusBps: 0 },
        { level: 2, costPoints: 40, unlocked: false, damageBonusBps: {}, unlocksWorkshopTier: 2, growthBonusBps: 0 },
        { level: 3, costPoints: 80, unlocked: false, damageBonusBps: {}, unlocksWorkshopTier: null, growthBonusBps: 25 },
      ],
      next: { targetLevel: 2, costPoints: 40, allowed: false, blockers: ['INSUFFICIENT_POINTS'] },
    },
    doctrines: [
      { ...DOCTRINE, key: 'MILITARY_LOGISTICS', militaryUpkeepReductionBps: 2000 },
      { ...DOCTRINE, key: 'MOUNTAIN_WARFARE', mountainPenaltyReductionBps: 5000 },
      { ...DOCTRINE, key: 'RIVER_ENGINEERING', riverPenaltyReductionBps: 5000 },
      { ...DOCTRINE, key: 'SIEGE_ENGINEERING', siegeArtilleryBonusBps: 5000 },
    ],
    institutions: [
      { id: 'i1', cityId: 'city-varnhold', cityName: 'Varn', provinceKey: 'varnhold', type: 'MILITARY_ACADEMY', pool: 'MILITARY', status: 'ACTIVE', completesAfterRound: 1, operationalFromRound: 2, pointsPerRound: 10, upkeepMg: 1000, producing: true },
      { id: 'i2', cityId: 'city-elmsford', cityName: 'Elmsford', provinceKey: 'elmsford', type: 'UNIVERSITY', pool: 'CIVILIAN', status: 'UNDER_CONSTRUCTION', completesAfterRound: 4, operationalFromRound: 5, pointsPerRound: 10, upkeepMg: 1000, producing: false },
    ],
    effects: { militaryLevel: 1, civilianLevel: 1, damageBonusBps: {}, riverPenaltyReductionBps: 0, mountainPenaltyReductionBps: 0, siegeArtilleryBonusBps: 0, militaryUpkeepReductionBps: 0, growthBonusBps: 0, highestUnlockedWorkshopTier: 1, doctrines: [] },
    ...overrides,
  }
}

function institutionsFixture(): CityInstitutions {
  const option = { pool: 'CIVILIAN' as const, costMg: 60000, durationRounds: 2, completesAfterRound: 4, operationalFromRound: 5, pointsPerRound: 10, upkeepMg: 1000, allowed: true, blockers: [], existing: null }
  return {
    cityId: 'city-varnhold',
    cityName: 'Varn',
    provinceKey: 'varnhold',
    available: true,
    options: [
      { ...option, type: 'MILITARY_ACADEMY', pool: 'MILITARY', existing: researchFixture().institutions[0] },
      { ...option, type: 'UNIVERSITY' },
      { ...option, type: 'BARRACKS', pool: 'DOCTRINE', allowed: false, blockers: ['INSUFFICIENT_GOLD'] },
    ],
  }
}

function renderGame(overrides = {}) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(gameStateFixture()),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getResearch: vi.fn().mockResolvedValue(researchFixture()),
    getCityInstitutions: vi.fn().mockResolvedValue(institutionsFixture()),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

async function openVarnhold() {
  const map = await screen.findByRole('group', { name: /^Map of/ })
  await userEvent.click(within(map).getByRole('button', { name: /^Varnhold/ }))
  return screen.getByRole('region', { name: 'Varnhold' })
}

describe('Research panel', () => {
  it('shows the three pools with points and income per round', async () => {
    renderGame()
    await showPanel('technology')

    const panel = await screen.findByRole('region', { name: 'Research' })
    expect(within(panel).getByText('45 points')).toBeInTheDocument()
    expect(within(panel).getAllByText('(+10 per round)')).toHaveLength(2)
    expect(within(panel).getByText('Military Academy in Varn')).toBeInTheDocument()
    expect(within(panel).getByText(/ready at the end of round 4, produces from round 5/)).toBeInTheDocument()
  })

  it('unlocks the next military level and explains its effect', async () => {
    const unlockTechnology = vi.fn().mockResolvedValue({ kind: 'TECHNOLOGY', subject: 'MILITARY', level: 2, costPoints: 40, research: researchFixture() })
    renderGame({ unlockTechnology })
    await showPanel('technology')

    const track = await screen.findByRole('group', { name: 'Military technology' })
    expect(within(track).getByText(/Level 2: \+10% damage for all units/)).toBeInTheDocument()
    await userEvent.click(within(track).getByRole('button', { name: 'Research level 2 · 40 points' }))

    expect(unlockTechnology).toHaveBeenCalledWith('g1', 'MILITARY', 2, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(track).findByText('Level 2 researched.')).toBeInTheDocument()
  })

  it('shows why a civilian level cannot be bought and what it would unlock', async () => {
    renderGame()
    await showPanel('technology')

    const track = await screen.findByRole('group', { name: 'Civilian technology' })
    expect(within(track).getByText(/Level 2: unlocks Tier 2 workshops/)).toBeInTheDocument()
    expect(within(track).getByText(/Level 3: \+0.25% population growth per round/)).toBeInTheDocument()
    expect(within(track).getByRole('button', { name: 'Research level 2 · 40 points' })).toBeDisabled()
    expect(within(track).getByText('Not enough research points yet.')).toBeInTheDocument()
  })

  it('chooses a doctrine with doctrine points', async () => {
    const unlockDoctrine = vi.fn().mockResolvedValue({ kind: 'DOCTRINE', subject: 'RIVER_ENGINEERING', level: null, costPoints: 30, research: researchFixture() })
    renderGame({ unlockDoctrine })
    await showPanel('technology')

    const doctrines = await screen.findByRole('group', { name: 'Doctrines' })
    expect(within(doctrines).getByText(/river crossing penalty reduced by 50%/)).toBeInTheDocument()
    expect(within(doctrines).getByText(/battalion upkeep −20%/)).toBeInTheDocument()
    const river = within(doctrines).getByText('River Engineering').closest('li')!
    await userEvent.click(within(river).getByRole('button', { name: 'Choose · 30 points' }))
    expect(unlockDoctrine).toHaveBeenCalledWith('g1', 'RIVER_ENGINEERING', expect.stringMatching(/^[0-9a-f-]{36}$/))
  })

  it('marks the chosen doctrine and disables the others', async () => {
    const chosen = researchFixture()
    chosen.doctrines = chosen.doctrines.map((d) =>
      d.key === 'MOUNTAIN_WARFARE' ? { ...d, chosen: true } : { ...d, allowed: false, blockers: ['TIER_FULL'] },
    )
    renderGame({ getResearch: vi.fn().mockResolvedValue(chosen) })
    await showPanel('technology')

    const doctrines = await screen.findByRole('group', { name: 'Doctrines' })
    expect(within(doctrines).getByText('Chosen')).toBeInTheDocument()
    within(doctrines)
      .getAllByRole('button', { name: 'Choose · 30 points' })
      .forEach((button) => expect(button).toBeDisabled())
    expect(within(doctrines).getAllByText(/already chosen its doctrine/)).toHaveLength(3)
  })

  it('shows the effects the server applies now', async () => {
    const military = militaryFixture()
    military.modifiers = { ...military.modifiers, militaryLevel: 2, damageBonusBps: { INFANTRY: 1000 }, riverAttackMultiplier: 0.9, upkeepReductionBps: 2000 }
    renderGame({ getMilitary: vi.fn().mockResolvedValue(military) })
    await showPanel('technology')

    const effects = await screen.findByRole('group', { name: 'Active effects' })
    expect(within(effects).getByText('+10%')).toBeInTheDocument()
    expect(within(effects).getByText('−10%')).toBeInTheDocument()
    expect(within(effects).getByText('(base −20%)')).toBeInTheDocument()
    expect(within(effects).getByText('−35%')).toBeInTheDocument()
    expect(within(effects).getByText('−20%')).toBeInTheDocument()
  })

  it('is hidden for games without research', async () => {
    const getResearch = vi.fn().mockResolvedValue(researchFixture({ available: false }))
    renderGame({ getResearch })
    await showPanel('technology')

    await screen.findByRole('region', { name: 'Resources' })
    await waitFor(() => expect(getResearch).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: 'Research' })).not.toBeInTheDocument()
  })

  it('refreshes research after the round resolves', async () => {
    const getGameState = vi.fn().mockResolvedValueOnce(gameStateFixture()).mockResolvedValue(gameStateFixture({ stateVersion: 9, round: 2, turnNumber: 3 }))
    const later = researchFixture()
    later.pools = later.pools.map((p) => (p.pool === 'MILITARY' ? { ...p, points: 55 } : p))
    // The research view changes with the round, however often it is fetched (resource bar and panel both read it).
    let resolved = false
    const getResearch = vi.fn(() => Promise.resolve(resolved ? later : researchFixture()))
    const endTurn = vi.fn(() => {
      resolved = true
      return Promise.resolve({ round: 2, activeSeat: 0, turnNumber: 3, stateVersion: 9, roundCompleted: true, replayed: false })
    })
    renderGame({ getGameState, getResearch, endTurn })
    await showPanel('technology')

    const panel = await screen.findByRole('region', { name: 'Research' })
    expect(within(panel).getByText('45 points')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'End turn' }))
    expect(await within(panel).findByText('55 points')).toBeInTheDocument()
  })
})

describe('Institutions, Tier 2 and growth in the province panel', () => {
  it('builds an institution and shows existing ones and reasons', async () => {
    const buildInstitution = vi.fn().mockResolvedValue({ institution: researchFixture().institutions[1], costMg: 60000, treasuryMg: 0 })
    renderGame({ buildInstitution })
    await showPanel('technology')

    const panel = await openVarnhold()
    const institutions = await within(panel).findByRole('region', { name: 'Institutions' })
    expect(within(institutions).getByText('Active.')).toBeInTheDocument()
    expect(within(institutions).getByRole('button', { name: 'Build Barracks · 60 gold' })).toBeDisabled()
    expect(within(institutions).getByText('Not enough gold in the treasury.')).toBeInTheDocument()
    await userEvent.click(within(institutions).getByRole('button', { name: 'Build University · 60 gold' }))

    expect(buildInstitution).toHaveBeenCalledWith('g1', 'city-varnhold', 'UNIVERSITY', expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(institutions).findByText('Construction started.')).toBeInTheDocument()
  })

  it('offers the Tier 2 upgrade with its cost and production change', async () => {
    const city = cityEconomyFixture()
    city.workshops = [
      {
        ...city.workshops[0],
        upgrade: { targetTier: 2, costMg: 60000, durationRounds: 1, completesAfterRound: 1, operationalFromRound: 2, potentialIncomeAfterMg: 28000, allowed: true, blockers: [] },
      },
    ]
    const upgradeWorkshop = vi.fn().mockResolvedValue({ workshop: city.workshops[0], costMg: 60000, treasuryMg: 0 })
    renderGame({ getEconomy: vi.fn().mockResolvedValue(economyFixture({}, [city])), upgradeWorkshop })
    await showPanel('technology')

    const panel = await openVarnhold()
    expect(await within(panel).findByText(/Production 20 → 28 gold from round 2; keeps Tier 1 output until then/)).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: 'Upgrade to Tier 2 · 60 gold' }))
    expect(upgradeWorkshop).toHaveBeenCalledWith('g1', 'city-varnhold', 'w1', expect.stringMatching(/^[0-9a-f-]{36}$/))
  })

  it('explains the technology requirement and shows a running upgrade', async () => {
    const city = cityEconomyFixture()
    city.workshops = [
      {
        ...city.workshops[0],
        upgrade: { targetTier: 2, costMg: 60000, durationRounds: 1, completesAfterRound: 1, operationalFromRound: 2, potentialIncomeAfterMg: 28000, allowed: false, blockers: ['TECHNOLOGY_REQUIRED'] },
      },
      { ...city.workshops[0], id: 'w2', upgradeTargetTier: 2, upgradeCompletesAfterRound: 3, upgrade: null },
    ]
    renderGame({ getEconomy: vi.fn().mockResolvedValue(economyFixture({}, [city])) })
    await showPanel('technology')

    const panel = await openVarnhold()
    expect(await within(panel).findByText('Requires the civilian technology that unlocks Tier 2.')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Upgrade to Tier 2 · 60 gold' })).toBeDisabled()
    expect(within(panel).getByText(/upgrading to Tier 2: done at the end of round 3, Tier 2 output from round 4/)).toBeInTheDocument()
  })

  it('shows next round growth', async () => {
    renderGame()
    await showPanel('technology')

    const panel = await openVarnhold()
    expect(await within(panel).findByText(/Growth: \+60 people at the end of this round \(0.5% per round\)/)).toBeInTheDocument()
  })

  it('explains why a besieged city does not grow', async () => {
    const besieged = cityEconomyFixture({ growth: { eligible: false, rateBps: 50, nextRoundGrowth: 0, blockers: ['BESIEGED'] } })
    renderGame({ getEconomy: vi.fn().mockResolvedValue(economyFixture({}, [besieged])) })
    await showPanel('technology')

    const panel = await openVarnhold()
    expect(await within(panel).findByText(/No growth this round: the city is under siege \(it loses 5% instead\)/))
      .toBeInTheDocument()
  })
})
