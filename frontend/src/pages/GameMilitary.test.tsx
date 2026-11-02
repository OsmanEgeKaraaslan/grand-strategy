import { fireEvent, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fakeApi, openForces, openProvince, renderPage, showPanel } from '../test/render'
import {
  OSTRA,
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

function renderGame(overrides = {}, state = gameStateFixture()) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(state),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getBattalion: vi.fn().mockResolvedValue(battalionDetailsFixture()),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

describe('Game military', () => {
  it('marks friendly and hostile battalions on the map', async () => {
    renderGame()

    const map = await screen.findByRole('group', { name: /^Map of/ })
    expect(await within(map).findByRole('button', { name: /^Elmsford.*2 of your battalions/ })).toBeInTheDocument()
    expect(within(map).getByRole('button', { name: /^Sablewater.*1 enemy battalions/ })).toBeInTheDocument()
  })

  it('lists the battalions of a province with type, strength, owner and eligibility', async () => {
    const military = militaryFixture({
      battalions: [
        battalionFixture({ id: 'b1' }),
        battalionFixture({ id: 'b2', manpower: 650, eligibility: 'ALREADY_MOVED' }),
        battalionFixture({ id: 'b3', unitType: 'CAVALRY', eligibility: 'RECRUITED_THIS_TURN' }),
      ],
    })
    renderGame({ getMilitary: vi.fn().mockResolvedValue(military) })

    const panel = await openForces('Elmsford')
    expect(await within(panel).findByText('Your battalions')).toBeInTheDocument()
    const composition = within(panel).getByRole('list', { name: 'Composition' })
    expect(composition).toHaveTextContent('2 Infantry')
    expect(composition).toHaveTextContent('1 Cavalry')
    expect(composition).toHaveTextContent('0 Artillery')
    expect(within(panel).getByText('650 / 1,000')).toBeInTheDocument()
    expect(within(panel).getByText('Moved this turn')).toBeInTheDocument()
    expect(within(panel).getByText('Recruited this turn — defends, cannot move yet')).toBeInTheDocument()
    expect(within(panel).getByRole('checkbox', { name: 'Select Cavalry, 1,000 of 1,000 men' })).toBeInTheDocument()
  })

  it('shows the enemy forces of a province without selection controls', async () => {
    renderGame()

    const panel = await openForces('Sablewater')
    expect(panel).toHaveAccessibleName('Forces in Sablewater')
    expect(await within(panel).findByText('Enemy battalions')).toBeInTheDocument()
    expect(within(panel).getByText('· Ostran Confederacy')).toBeInTheDocument()
    expect(within(panel).queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('recruits a battalion and refreshes the treasury and city from the server', async () => {
    const getGameState = vi
      .fn()
      .mockResolvedValueOnce(gameStateFixture())
      .mockResolvedValue(gameStateFixture({ stateVersion: 4 }))
    const getEconomy = vi
      .fn()
      .mockResolvedValueOnce(economyFixture())
      .mockResolvedValue(economyFixture({ treasuryMg: 25000, projectedUpkeepMg: 1500, projectedNetMg: 48500 }))
    const recruit = vi.fn().mockResolvedValue({
      battalion: battalionFixture({ id: 'new', unitType: 'CAVALRY', eligibility: 'RECRUITED_THIS_TURN' }),
      treasuryMg: 25000,
      cityPopulation: 7000,
    })
    renderGame({ getGameState, getEconomy, recruit })

    const panel = await openProvince('Elmsford')
    const cavalry = await within(panel).findByRole('button', { name: 'Recruit Cavalry' })
    // The hover card carries the full quote.
    expect(cavalry).toHaveAccessibleDescription(/Cost 25 gold/)
    expect(cavalry).toHaveAccessibleDescription(/Civilians −1,000 \(city: 8,000 → 7,000\)/)
    expect(cavalry).toHaveAccessibleDescription(/Upkeep 1\.5 gold per round/)
    await userEvent.click(cavalry)

    expect(recruit).toHaveBeenCalledWith('g1', 'city-elmsford', 'CAVALRY', expect.stringMatching(/^[0-9a-f-]{36}$/))
    await showPanel('economy')
    const treasury = await screen.findByRole('region', { name: 'Treasury' })
    expect(await within(treasury).findByText('25 gold')).toBeInTheDocument()
    expect(getEconomy).toHaveBeenCalledTimes(2)
  })

  it('disables recruitment with the server’s reasons', async () => {
    const military = militaryFixture()
    military.recruitment[1].options = military.recruitment[1].options.map((o) => ({
      ...o,
      allowed: false,
      blockers: ['INSUFFICIENT_GOLD'],
    }))
    renderGame({ getMilitary: vi.fn().mockResolvedValue(military) })

    const panel = await openProvince('Elmsford')
    const infantry = await within(panel).findByRole('button', { name: 'Recruit Infantry' })
    expect(infantry).toBeDisabled()
    expect(infantry).toHaveAccessibleDescription(/Not enough gold in the treasury\./)
  })

  it('moves a selection into an enemy province and shows the battle report', async () => {
    const move = vi.fn().mockResolvedValue({
      outcome: 'ATTACK_WON',
      toProvinceKey: 'sablewater',
      battalions: [battalionFixture({ provinceKey: 'sablewater', manpower: 880, eligibility: 'ALREADY_MOVED' })],
      battle: battleFixture(),
    })
    renderGame({ move })

    const panel = await openForces('Elmsford')
    expect(await within(panel).findByText('Select battalions to move them.')).toBeInTheDocument()
    // Orders are given on the map only: the window has no duplicate move or attack buttons.
    expect(within(panel).queryByRole('button', { name: /^(Attack|Move to) / })).not.toBeInTheDocument()

    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    expect(within(panel).getByText(/Right-click a highlighted province/)).toBeInTheDocument()
    const map = screen.getByRole('group', { name: /^Map of/ })
    expect(within(map).getByRole('button', { name: /^Varnhold.*move target/ })).toBeInTheDocument()
    fireEvent.contextMenu(within(map).getByRole('button', { name: /^Sablewater.*attack target/ }))
    // A right-click attack asks for confirmation first.
    expect(move).not.toHaveBeenCalled()
    const confirm = screen.getByRole('alertdialog', { name: 'Confirm attack' })
    await userEvent.click(within(confirm).getByRole('button', { name: 'Attack Sablewater' }))

    expect(move).toHaveBeenCalledWith('g1', ['b1'], 'sablewater', expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await screen.findByText('Victory at Sablewater.')).toBeInTheDocument()
    const report = screen.getByRole('region', { name: 'Battle of Sablewater' })
    expect(within(report).getByText('Kingdom of Varn won.')).toBeInTheDocument()
    expect(within(report).getByText('River crossing ×0.8')).toBeInTheDocument()
    expect(within(report).getByText('The defenders retreated to Varnhold.')).toBeInTheDocument()
    expect(within(report).getByText('120 (12.0%)')).toBeInTheDocument()
  })

  it('explains why a mixed selection cannot move and lets the server have the last word', async () => {
    const military = militaryFixture({
      battalions: [battalionFixture({ id: 'b1' }), battalionFixture({ id: 'b2', manpower: 650, eligibility: 'ALREADY_MOVED' })],
    })
    const move = vi.fn().mockRejectedValue(new ApiError({ status: 422, code: 'BATTALION_ALREADY_MOVED' }))
    renderGame({ getMilitary: vi.fn().mockResolvedValue(military), move })

    const panel = await openForces('Elmsford')
    await userEvent.click(await within(panel).findByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Select Infantry, 650 of 1,000 men' }))
    expect(within(panel).getByText('1 selected battalion cannot move this turn.')).toBeInTheDocument()
    const map = screen.getByRole('group', { name: /^Map of/ })
    expect(within(map).queryByRole('button', { name: /move target/ })).not.toBeInTheDocument()

    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Select Infantry, 650 of 1,000 men' }))
    // Keyboard path: focus the province on the map and press M.
    within(map).getByRole('button', { name: /^Varnhold.*move target/ }).focus()
    await userEvent.keyboard('m')
    expect(await screen.findByText('A selected battalion has already moved this turn.')).toBeInTheDocument()
  })

  it('disables every command while waiting for the other player', async () => {
    renderGame(
      { getMilitary: vi.fn().mockResolvedValue(militaryFixture({ yourTurn: false })) },
      gameStateFixture({ activeSeat: 1, yourTurn: false }),
    )

    const panel = await openForces('Elmsford')
    expect(await within(panel).findByText('You can move on your turn.')).toBeInTheDocument()
    within(panel)
      .getAllByRole('checkbox')
      .forEach((box) => expect(box).toBeDisabled())
    expect(within(screen.getByRole('group', { name: /^Map of/ })).queryByRole('button', { name: /move target/ })).not.toBeInTheDocument()
  })

  it('names selected battalions as a group, explains that it is only a label, and re-selects it from its tag', async () => {
    const regroup = vi.fn().mockResolvedValue({
      army: { id: 'a1', nationId: 'nation-varn', name: 'First Army', provinceKey: 'elmsford', battalionIds: ['b1'] },
    })
    renderGame({ regroup })

    const panel = await openForces('Elmsford')
    await userEvent.click(await within(panel).findByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    const tool = within(panel).getByRole('button', { name: 'Name as army group' })
    expect(tool).toHaveAttribute('title', expect.stringContaining('does not change movement or combat'))
    await userEvent.click(tool)
    expect(within(panel).getByText(/Only a label: battalions in a province always fight together/)).toBeInTheDocument()
    await userEvent.type(within(panel).getByRole('textbox', { name: 'Group name' }), 'First Army')
    await userEvent.click(within(panel).getByRole('button', { name: 'Name 1 as a group' }))

    expect(regroup).toHaveBeenCalledWith('g1', 'First Army', ['b1'], expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(panel).findByText('Formed First Army.')).toBeInTheDocument()
  })

  it('shows the upkeep of your forces and opens past battle reports', async () => {
    const getBattle = vi.fn().mockResolvedValue(battleFixture({ winner: 'DEFENDER' }))
    renderGame({
      getMilitary: vi.fn().mockResolvedValue(
        militaryFixture({
          recentBattles: [
            {
              id: 'battle-1',
              round: 1,
              turnNumber: 1,
              provinceKey: 'sablewater',
              attackerNationId: 'nation-varn',
              defenderNationId: OSTRA,
              winner: 'DEFENDER',
              attackerLosses: 120,
              defenderLosses: 310,
              createdAt: '2026-10-18T12:00:00Z',
            },
          ],
        }),
      ),
      getBattle,
    })

    await showPanel('military')
    const forces = await screen.findByRole('region', { name: 'Your forces' })
    expect(within(forces).getByText('1,650')).toBeInTheDocument()
    expect(within(forces).getByText('−0.825 gold')).toBeInTheDocument()
    expect(within(forces).getByText(/Ostran Confederacy won/)).toBeInTheDocument()
    await userEvent.click(within(forces).getByRole('button', { name: 'Battle of Sablewater' }))

    expect(getBattle).toHaveBeenCalledWith('g1', 'battle-1')
    const report = await screen.findByRole('region', { name: 'Battle of Sablewater' })
    expect(within(report).getByText('The attackers fell back to their starting province.')).toBeInTheDocument()
    await userEvent.click(within(report).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('region', { name: 'Battle of Sablewater' })).not.toBeInTheDocument()
  })
})
