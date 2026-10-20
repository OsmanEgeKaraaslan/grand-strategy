import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { DiplomacyOverview, Lobby, NationRelation } from '../api/types'
import { fakeApi, renderPage } from '../test/render'
import { OSTRA, VARN, economyFixture, gameStateFixture, lobbyFixture, mapFixture, militaryFixture } from '../test/fixtures'
import { GamePage } from './GamePage'
import { LobbyPage } from './LobbyPage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }
const NPC = 'nation-aurelia'

function relation(overrides: Partial<NationRelation>): NationRelation {
  return {
    nationId: OSTRA,
    name: 'Ostran Confederacy',
    color: '#b5503a',
    you: false,
    status: 'PEACE',
    allianceKind: null,
    allianceId: null,
    warId: null,
    truceUntilRound: null,
    weMayEnter: false,
    theyMayEnter: false,
    warBlockers: [],
    allianceBlockers: [],
    ...overrides,
  }
}

function overview(overrides: Partial<DiplomacyOverview> = {}): DiplomacyOverview {
  return {
    gameId: 'g1',
    available: true,
    rulesetVersion: 'v6',
    round: 2,
    yourTurn: true,
    yourNationId: VARN,
    rules: {
      version: 'v6',
      truceRounds: 10,
      proposalExpiryRounds: 3,
      maxPeaceTerms: 12,
      tributeRateBps: 1000,
      tributeRounds: 10,
      populationMinPeople: 1000,
      populationMaxPeople: 2000,
      populationMaxShareBps: 2000,
      populationMinResidual: 1000,
      reparationsMaxMg: 1_000_000,
      maxScore: 100,
      victoryMaxRounds: 30,
      victoryProvincePoints: 20,
      victoryPointsPerThousandPopulation: 1,
      normalAllianceGrantsAccess: true,
      defensiveAllianceGrantsAccess: false,
      coBelligerentAccess: true,
      giftMaxMg: 500_000,
      giftsToEnemies: false,
    },
    nations: [
      relation({ nationId: VARN, name: 'Kingdom of Varn', you: true, status: 'SELF' }),
      relation({}),
      relation({ nationId: NPC, name: 'Aurelian Empire', status: 'WAR', warId: 'w1' }),
    ],
    wars: [],
    alliances: [],
    proposals: [],
    treaties: [],
    tributes: [],
    events: [],
    gifts: [{ id: 'gift-1', senderNationId: OSTRA, recipientNationId: VARN, amountMg: 5000, round: 1, createdAt: '' }],
    ...overrides,
  }
}

const campaignState = gameStateFixture({
  nations: [
    { id: VARN, name: 'Kingdom of Varn', color: '#3f6fb5', seat: 0, control: 'HUMAN', eliminatedRound: null },
    { id: OSTRA, name: 'Ostran Confederacy', color: '#b5503a', seat: 1, control: 'HUMAN', eliminatedRound: null },
    { id: NPC, name: 'Aurelian Empire', color: '#5b6fb8', seat: null, control: 'NPC', eliminatedRound: null },
  ],
})

function renderGame(overrides = {}) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(campaignState),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getDiplomacy: vi.fn().mockResolvedValue(overview()),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

describe('Send gold', () => {
  it('previews the treasury, asks for confirmation and sends integer milligold', async () => {
    const sendGold = vi.fn().mockResolvedValue({
      gift: { id: 'gift-2', senderNationId: VARN, recipientNationId: OSTRA, amountMg: 12500, round: 2, createdAt: '' },
      treasuryMg: 37500,
    })
    renderGame({ sendGold })

    const group = await screen.findByRole('group', { name: 'Send gold' })
    const recipient = within(group).getByRole('combobox', { name: 'Recipient' })
    expect(within(recipient).queryByRole('option', { name: 'Aurelian Empire' })).not.toBeInTheDocument()
    await userEvent.selectOptions(recipient, OSTRA)
    await userEvent.type(within(group).getByRole('textbox', { name: 'Gold' }), '12.5')
    expect(within(group).getByText(/Treasury: 50 → 37.5 gold/)).toBeInTheDocument()
    await userEvent.click(within(group).getByRole('button', { name: 'Send gold' }))
    expect(sendGold).not.toHaveBeenCalled()
    expect(within(group).getByRole('alert')).toHaveTextContent('Send 12.5 gold to Ostran Confederacy?')
    await userEvent.click(within(group).getByRole('button', { name: 'Confirm gift' }))

    expect(sendGold).toHaveBeenCalledWith('g1', OSTRA, 12500, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(group).findByText(/Sent 12.5 gold to Ostran Confederacy. Your treasury: 37.5 gold./)).toBeInTheDocument()
    expect(within(group).getByText(/Round 1: Ostran Confederacy → Kingdom of Varn, 5 gold/)).toBeInTheDocument()
  })

  it('refuses amounts above the treasury before asking the server', async () => {
    const sendGold = vi.fn()
    renderGame({ sendGold })

    const group = await screen.findByRole('group', { name: 'Send gold' })
    await userEvent.selectOptions(within(group).getByRole('combobox', { name: 'Recipient' }), OSTRA)
    await userEvent.type(within(group).getByRole('textbox', { name: 'Gold' }), '51')
    expect(within(group).getByText('Your treasury does not hold that much gold.')).toBeInTheDocument()
    expect(within(group).getByRole('button', { name: 'Send gold' })).toBeDisabled()
  })

  it('is hidden in games without gifts', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(overview({ rules: { ...overview().rules!, giftMaxMg: null } })) })

    await screen.findByRole('region', { name: 'Diplomacy' })
    expect(screen.queryByRole('group', { name: 'Send gold' })).not.toBeInTheDocument()
  })
})

describe('Larger maps', () => {
  it('zooms in and back out without changing the selection', async () => {
    renderGame()

    const map = await screen.findByRole('group', { name: /^Map of/ })
    const svg = map as unknown as SVGSVGElement
    expect(svg.getAttribute('viewBox')).toBe('0 0 1000 640')
    expect(screen.getByRole('button', { name: 'Zoom out' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    const zoomed = svg.getAttribute('viewBox')!.split(' ').map(Number)
    expect(zoomed[2]).toBeCloseTo(1000 / 1.5)
    await userEvent.click(within(map).getByRole('button', { name: /^Elmsford/ }))
    expect(screen.getByRole('region', { name: 'Elmsford' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show the whole map' }))
    expect(svg.getAttribute('viewBox')).toBe('0 0 1000 640')
    expect(screen.getByRole('region', { name: 'Elmsford' })).toBeInTheDocument()
  })
})

describe('Lobby with game-controlled nations', () => {
  it('only offers the playable nations', async () => {
    const lobby: Lobby = lobbyFixture({
      maxPlayers: 3,
      availableSlots: 2,
      nations: [
        ...lobbyFixture().nations,
        { id: NPC, key: 'aurelia', name: 'Aurelian Empire', color: '#5b6fb8', provinceCount: 12, chosenBySeat: null, chosenByYou: false, tier: 'MAJOR', playable: false },
      ],
    })
    renderPage(<LobbyPage />, {
      route: '/games/g1',
      path: '/games/:gameId',
      api: fakeApi({ getMe: vi.fn().mockResolvedValue(me), getGame: vi.fn().mockResolvedValue(lobby) }),
    })

    const aurelia = await screen.findByRole('button', { name: /Aurelian Empire/ })
    expect(aurelia).toBeDisabled()
    expect(aurelia).toHaveTextContent('12 provinces · major realm · controlled by the game')
    expect(screen.getByRole('button', { name: /Kingdom of Varn/ })).toBeEnabled()
    expect(screen.getByRole('button', { name: /Kingdom of Varn/ })).toHaveTextContent('balanced power')
    expect(screen.getAllByText('Open slot — waiting for a player')).toHaveLength(2)
  })
})
