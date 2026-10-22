import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { fakeApi, openForces, renderPage, showPanel } from '../test/render'
import {
  battalionDetailsFixture,
  battalionFixture,
  battleFixture,
  economyFixture,
  gameStateFixture,
  mapFixture,
  militaryFixture,
} from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

function renderGame(overrides = {}) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(gameStateFixture()),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getBattalion: vi.fn().mockResolvedValue(battalionDetailsFixture()),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

/** Opens the forces in Elmsford, selects the damaged 650-man battalion b2 and picks a command for it. */
async function selectDamagedBattalion(tool: 'Reinforce' | 'Disband') {
  const panel = await openForces('Elmsford')
  const command = within(panel).getByRole('button', { name: tool })
  // Reinforce and disband act on exactly one of your battalions.
  expect(command).toBeDisabled()
  await userEvent.click(await within(panel).findByRole('checkbox', { name: 'Select Infantry, 650 of 1,000 men' }))
  await userEvent.click(command)
  return panel
}

describe('Reinforcement and disbanding', () => {
  it('shows the server quote and reinforces with the chosen number of soldiers', async () => {
    const getGameState = vi
      .fn()
      .mockResolvedValueOnce(gameStateFixture())
      .mockResolvedValue(gameStateFixture({ stateVersion: 4 }))
    const getEconomy = vi
      .fn()
      .mockResolvedValueOnce(economyFixture())
      .mockResolvedValue(economyFixture({ treasuryMg: 46500 }))
    const reinforce = vi.fn().mockResolvedValue({
      battalion: battalionFixture({ id: 'b2', eligibility: 'REINFORCED_THIS_TURN' }),
      soldiersAdded: 350,
      costMg: 3500,
      treasuryMg: 46500,
      cityId: 'city-elmsford',
      cityPopulation: 7650,
    })
    renderGame({ getGameState, getEconomy, reinforce })

    await selectDamagedBattalion('Reinforce')
    const section = await screen.findByRole('region', { name: 'Reinforce' })
    expect(within(section).getByText('350 (at most 500 per turn)')).toBeInTheDocument()
    expect(within(section).getByText('3.5 gold')).toBeInTheDocument()
    expect(within(section).getByText('−350 (Elmsford: 8,000 → 7,650)')).toBeInTheDocument()
    expect(screen.getByText('Soldiers from: Varn 650')).toBeInTheDocument()

    const amount = within(section).getByRole('spinbutton', { name: 'Soldiers to add' })
    await userEvent.clear(amount)
    await userEvent.type(amount, '100')
    expect(within(section).getByText('1 gold')).toBeInTheDocument()
    await userEvent.clear(amount)
    await userEvent.type(amount, '350')
    await userEvent.click(within(section).getByRole('button', { name: 'Reinforce with 350' }))

    expect(reinforce).toHaveBeenCalledWith('g1', 'b2', 350, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(section).findByText('Added 350 soldiers for 3.5 gold.')).toBeInTheDocument()
    await showPanel('economy')
    const treasury = await screen.findByRole('region', { name: 'Treasury' })
    expect(await within(treasury).findByText('46.5 gold')).toBeInTheDocument()
  })

  it('explains why a battalion cannot be reinforced', async () => {
    const details = battalionDetailsFixture()
    details.reinforcement = { ...details.reinforcement, allowed: false, maxSoldiers: 0, costOfMaxMg: 0, blockers: ['NOT_OWNED_CITY'] }
    renderGame({ getBattalion: vi.fn().mockResolvedValue(details) })

    await selectDamagedBattalion('Reinforce')
    const section = await screen.findByRole('region', { name: 'Reinforce' })
    expect(within(section).getByText('Only in a city your nation owns — move the battalion home first.')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: /^Reinforce with/ })).toBeDisabled()
  })

  it('asks for confirmation before disbanding and shows where the soldiers go', async () => {
    const details = battalionDetailsFixture()
    details.disband = {
      allowed: true,
      blockers: [],
      soldiers: 750,
      returns: [
        { cityId: 'city-varnhold', cityName: 'Varn', provinceKey: 'varnhold', soldiers: 500, fallback: false },
        { cityId: 'city-elmsford', cityName: 'Elmsford', provinceKey: 'elmsford', soldiers: 250, fallback: true },
      ],
      upkeepSavedMg: 375,
    }
    const disband = vi.fn().mockResolvedValue({
      battalionId: 'b2',
      unitType: 'INFANTRY',
      soldiersReturned: 750,
      returns: details.disband.returns,
      upkeepSavedMg: 375,
    })
    renderGame({ getBattalion: vi.fn().mockResolvedValue(details), disband })

    const panel = await selectDamagedBattalion('Disband')
    const section = await screen.findByRole('region', { name: 'Disband' })
    await userEvent.click(within(section).getByRole('button', { name: 'Disband…' }))
    const dialog = within(section).getByRole('alertdialog')
    expect(within(dialog).getByText(/750 surviving soldiers return/)).toBeInTheDocument()
    expect(within(dialog).getByText('Varn: +500')).toBeInTheDocument()
    expect(within(dialog).getByText('origin lost — settles here')).toBeInTheDocument()
    expect(within(dialog).getByText(/Upkeep saved: 0.375 gold per round/)).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(within(section).queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(disband).not.toHaveBeenCalled()

    await userEvent.click(within(section).getByRole('button', { name: 'Disband…' }))
    await userEvent.click(within(section).getByRole('button', { name: 'Confirm disband' }))
    expect(disband).toHaveBeenCalledWith('g1', 'b2', expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(
      await within(panel).findByText('Disbanded: 750 soldiers went home (Varn +500, Elmsford +250).'),
    ).toBeInTheDocument()
  })

  it('disables disbanding outside friendly-controlled territory', async () => {
    const details = battalionDetailsFixture()
    details.disband = { ...details.disband, allowed: false, blockers: ['NOT_FRIENDLY_TERRITORY'] }
    renderGame({ getBattalion: vi.fn().mockResolvedValue(details) })

    await selectDamagedBattalion('Disband')
    const section = await screen.findByRole('region', { name: 'Disband' })
    expect(within(section).getByText('Only in a province your nation controls.')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Disband…' })).toBeDisabled()
  })

  it('shows reinforced battalions as unable to move', async () => {
    renderGame({
      getMilitary: vi.fn().mockResolvedValue(
        militaryFixture({ battalions: [battalionFixture({ id: 'b1', eligibility: 'REINFORCED_THIS_TURN' })] }),
      ),
    })

    await openForces('Elmsford')
    expect(await screen.findByText('Reinforced this turn — defends, cannot move')).toBeInTheDocument()
  })
})

describe('Enhanced battle report', () => {
  it('breaks the armies down by unit type and explains terrain, screens and breaking', async () => {
    const battle = battleFixture()
    battle.details.attackers = [
      { battalionId: 'b1', unitType: 'INFANTRY', manpowerBefore: 1000, losses: 100, manpowerAfter: 900, fate: 'SURVIVED' },
      { battalionId: 'b3', unitType: 'ARTILLERY', manpowerBefore: 1000, losses: 20, manpowerAfter: 980, fate: 'SURVIVED' },
    ]
    battle.details.attackerInitial = 2000
    battle.details.phases = battle.details.phases.map((p) => ({
      ...p,
      attacker: { ...p.attacker, protection: 0.9, flankBonus: 0.15 },
    }))
    battle.details.attackerBroke = true
    battle.details.defenderBroke = true
    const getBattle = vi.fn().mockResolvedValue(battle)
    renderGame({
      getMilitary: vi.fn().mockResolvedValue(
        militaryFixture({
          recentBattles: [
            {
              id: 'battle-1',
              round: 1,
              turnNumber: 1,
              provinceKey: 'sablewater',
              attackerNationId: battle.attackerNationId,
              defenderNationId: battle.defenderNationId,
              winner: 'ATTACKER',
              attackerLosses: 120,
              defenderLosses: 310,
              createdAt: battle.createdAt,
            },
          ],
        }),
      ),
      getBattle,
    })

    await showPanel('military')
    const forces = await screen.findByRole('region', { name: 'Your forces' })
    await userEvent.click(within(forces).getByRole('button', { name: 'Battle of Sablewater' }))
    const report = await screen.findByRole('region', { name: 'Battle of Sablewater' })

    const types = within(report).getByRole('table', { name: 'By unit type' })
    expect(within(types).getByText('1,000 → 900 (−100)')).toBeInTheDocument()
    expect(within(types).getByText('1,000 → 690 (−310)')).toBeInTheDocument()
    expect(within(types).getByText('Artillery')).toBeInTheDocument()
    const notes = within(report).getByRole('list', { name: 'What decided the battle' })
    expect(within(notes).getByText(/attacked across a river: its damage ×0.8/)).toBeInTheDocument()
    expect(within(notes).getByText(/attacked into mountains/)).toBeInTheDocument()
    expect(within(notes).getByText(/flanked the enemy: up to \+15.0% damage/)).toBeInTheDocument()
    expect(within(notes).getByText(/artillery lacked an infantry screen/)).toBeInTheDocument()
    expect(within(report).getByText(/Both armies broke/)).toBeInTheDocument()
    expect(within(report).getByText('The defenders retreated to Varnhold.')).toBeInTheDocument()
  })
})
