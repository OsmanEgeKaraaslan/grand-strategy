import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Conference, DiplomacyOverview, NationRelation, WarView } from '../api/types'
import { BattleReportView } from '../components/BattleReportView'
import { fakeApi, openForces, renderPage, showPanel } from '../test/render'
import {
  OSTRA,
  VARN,
  battalionFixture,
  battleFixture,
  economyFixture,
  gameStateFixture,
  mapFixture,
  militaryFixture,
} from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }
const ALLY = 'nation-helm'
const FEATURES = {
  coalitionWarfare: true,
  attachments: true,
  callsToArms: true,
  peaceConferences: true,
  separatePeace: true,
  capitalsCedable: true,
  elimination: true,
}

function relation(overrides: Partial<NationRelation>): NationRelation {
  return {
    nationId: OSTRA,
    name: 'Ostran Confederacy',
    color: '#b5503a',
    you: false,
    status: 'WAR',
    allianceKind: null,
    allianceId: null,
    warId: 'w1',
    truceUntilRound: null,
    weMayEnter: true,
    theyMayEnter: true,
    warBlockers: [],
    allianceBlockers: [],
    ...overrides,
  }
}

/** Ostra attacked Varn (you); Helm is Varn's defensive ally and not yet in the war. */
function war(overrides: Partial<WarView> = {}): WarView {
  const side = { battles: 0, sieges: 0, occupation: 0, total: 0, battlesWon: 0, siegesWon: 0, occupiedEnemyPopulation: 0, enemyPopulation: 1 }
  return {
    id: 'w1',
    status: 'ACTIVE',
    attackerLeaderId: OSTRA,
    defenderLeaderId: VARN,
    participants: [
      { nationId: OSTRA, name: 'Ostran Confederacy', side: 'ATTACKER', joinReason: 'DECLARED', joinedRound: 1 },
      { nationId: VARN, name: 'Kingdom of Varn', side: 'DEFENDER', joinReason: 'ATTACKED', joinedRound: 1 },
    ],
    declaredRound: 1,
    endedRound: null,
    endReason: null,
    durationRounds: 2,
    score: { attacker: side, defender: side, net: 0, maxScore: 100 },
    yourSide: 'DEFENDER',
    youLead: true,
    ...overrides,
  }
}

function conference(overrides: Partial<Conference> = {}): Conference {
  return {
    id: 'c1',
    warId: 'w1',
    kind: 'GENERAL',
    requesterNationId: OSTRA,
    counterpartNationId: VARN,
    status: 'ACTIVE',
    requestedRound: 2,
    currentRevision: 2,
    terms: [{ type: 'GOLD', fromNationId: ALLY, toNationId: OSTRA, amountMg: 5000 }],
    approvals: [
      { nationId: ALLY, name: 'Helm', npc: false, decision: null },
      { nationId: OSTRA, name: 'Ostran Confederacy', npc: false, decision: 'APPROVED' },
      { nationId: VARN, name: 'Kingdom of Varn', npc: false, decision: null },
    ],
    revisions: [
      { revision: 1, authorNationId: OSTRA, termCount: 0, createdAt: '' },
      { revision: 2, authorNationId: OSTRA, termCount: 1, createdAt: '' },
    ],
    violations: [],
    youArePrincipal: true,
    canAnswer: false,
    canCancel: true,
    canRevise: true,
    youMustDecide: true,
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
      features: FEATURES,
    },
    nations: [
      relation({ nationId: VARN, name: 'Kingdom of Varn', you: true, status: 'SELF', warId: null }),
      relation({}),
      relation({ nationId: ALLY, name: 'Helm', status: 'ALLIED', allianceKind: 'DEFENSIVE', warId: null }),
    ],
    wars: [war()],
    alliances: [],
    proposals: [],
    treaties: [],
    tributes: [],
    events: [],
    gifts: [],
    calls: [],
    conferences: [],
    ...overrides,
  }
}

const state = gameStateFixture({
  nations: [
    { id: VARN, name: 'Kingdom of Varn', color: '#3f6fb5', seat: 0, control: 'HUMAN', eliminatedRound: null },
    { id: OSTRA, name: 'Ostran Confederacy', color: '#b5503a', seat: 1, control: 'HUMAN', eliminatedRound: null },
    { id: ALLY, name: 'Helm', color: '#4f9d9a', seat: 2, control: 'HUMAN', eliminatedRound: null },
  ],
})

function renderGame(overrides = {}, gameState = state) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(gameState),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getDiplomacy: vi.fn().mockResolvedValue(overview()),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

describe('Calls to arms', () => {
  it('lets the attacked leader call an ally that is not yet in the war', async () => {
    const callToArms = vi.fn().mockResolvedValue({
      call: { id: 'call-1', warId: 'w1', callerNationId: VARN, allyNationId: ALLY, status: 'PENDING', createdRound: 2, answeredRound: null, canAnswer: false },
    })
    renderGame({ callToArms })
    await showPanel('diplomacy')
    const card = await screen.findByRole('article', { name: /^War: Ostran Confederacy against Kingdom of Varn/ })
    await userEvent.click(within(card).getByRole('button', { name: 'Call Helm to arms' }))
    expect(callToArms).toHaveBeenCalledWith('g1', 'w1', ALLY, expect.any(String))
    expect(await screen.findByText('Helm has been called to arms. It answers on its turn.')).toBeInTheDocument()
  })

  it('asks for confirmation before declining, because declining ends the alliance', async () => {
    const answerCall = vi.fn().mockResolvedValue({
      call: { id: 'call-1', warId: 'w1', callerNationId: OSTRA, allyNationId: VARN, status: 'DECLINED', createdRound: 2, answeredRound: 2, canAnswer: false },
    })
    renderGame({
      answerCall,
      getDiplomacy: vi.fn().mockResolvedValue(
        overview({
          calls: [{ id: 'call-1', warId: 'w9', callerNationId: ALLY, allyNationId: VARN, status: 'PENDING', createdRound: 2, answeredRound: null, canAnswer: true }],
        }),
      ),
    })
    await showPanel('diplomacy')
    await userEvent.click(await screen.findByRole('button', { name: 'Decline' }))
    expect(answerCall).not.toHaveBeenCalled()
    expect(screen.getByText('Declining ends your alliance with Helm.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm: decline' }))
    expect(answerCall).toHaveBeenCalledWith('g1', 'call-1', false, expect.any(String))
  })
})

describe('Peace conferences', () => {
  it('lets a war leader request the general peace instead of a turn-bound proposal', async () => {
    const requestConference = vi.fn().mockResolvedValue({ conference: conference({ status: 'REQUESTED' }), treaty: null, withdrawals: [] })
    renderGame({ requestConference })
    await showPanel('diplomacy')
    const card = await screen.findByRole('article', { name: /^War: Ostran/ })
    expect(within(card).queryByRole('button', { name: 'Negotiate peace' })).not.toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Request peace conference' }))
    expect(requestConference).toHaveBeenCalledWith('g1', 'w1', 'GENERAL', expect.any(String))
  })

  it('offers a separate peace to a nation that does not lead its side', async () => {
    const requestConference = vi.fn().mockResolvedValue({ conference: conference({ kind: 'SEPARATE', status: 'REQUESTED' }), treaty: null, withdrawals: [] })
    renderGame({
      requestConference,
      getDiplomacy: vi.fn().mockResolvedValue(
        overview({
          wars: [
            war({
              defenderLeaderId: ALLY,
              youLead: false,
              participants: [
                { nationId: OSTRA, name: 'Ostran Confederacy', side: 'ATTACKER', joinReason: 'DECLARED', joinedRound: 1 },
                { nationId: ALLY, name: 'Helm', side: 'DEFENDER', joinReason: 'ATTACKED', joinedRound: 1 },
                { nationId: VARN, name: 'Kingdom of Varn', side: 'DEFENDER', joinReason: 'CALL_TO_ARMS', joinedRound: 2 },
              ],
            }),
          ],
        }),
      ),
    })
    await showPanel('diplomacy')
    const card = await screen.findByRole('article', { name: /^War: Ostran Confederacy against Helm/ })
    expect(within(card).getByText(/Kingdom of Varn \(answered a call to arms\)/)).toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Ask Ostran Confederacy for a separate peace' }))
    expect(requestConference).toHaveBeenCalledWith('g1', 'w1', 'SEPARATE', expect.any(String))
  })

  it('shows the pause, the revision and who must still approve, and approves that exact revision', async () => {
    const decideDraft = vi.fn().mockResolvedValue({ conference: conference(), treaty: null, withdrawals: [] })
    renderGame(
      { decideDraft, getDiplomacy: vi.fn().mockResolvedValue(overview({ conferences: [conference()] })) },
      { ...state, pausedConferenceId: 'c1', yourTurn: false },
    )
    await showPanel('diplomacy')
    expect(await screen.findByText(/Paused for a peace conference/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'End turn' })).not.toBeInTheDocument()
    const card = await screen.findByRole('article', { name: 'General peace: Ostran Confederacy and Kingdom of Varn' })
    expect(within(card).getByText('Revision 2')).toBeInTheDocument()
    expect(within(card).getByText(/Helm pays 5 gold to Ostran Confederacy/)).toBeInTheDocument()
    expect(within(card).getAllByText('not yet decided')).toHaveLength(2)
    expect(within(card).getByText('approved')).toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Approve revision 2' }))
    expect(decideDraft).toHaveBeenCalledWith('g1', 'c1', 2, 'approve', expect.any(String))
  })

  it('opens the paused conference in a blocking window over the map, which can be minimised and reopened', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(overview({ conferences: [conference()] })) }, { ...state, pausedConferenceId: 'c1' })
    const dialog = await screen.findByRole('dialog', { name: 'Peace conference' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(within(dialog).getByRole('article', { name: 'General peace: Ostran Confederacy and Kingdom of Varn' })).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Minimise' }))
    expect(screen.queryByRole('dialog', { name: 'Peace conference' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Open peace conference' }))
    expect(screen.getByRole('dialog', { name: 'Peace conference' })).toBeInTheDocument()
  })

  it('publishes a new revision built on the one shown', async () => {
    const submitDraft = vi.fn().mockResolvedValue({ conference: conference({ currentRevision: 3 }), treaty: null, withdrawals: [] })
    renderGame(
      { submitDraft, getDiplomacy: vi.fn().mockResolvedValue(overview({ conferences: [conference()] })) },
      { ...state, pausedConferenceId: 'c1' },
    )
    await showPanel('diplomacy')
    const card = await screen.findByRole('article', { name: 'General peace: Ostran Confederacy and Kingdom of Varn' })
    await userEvent.click(within(card).getByRole('button', { name: 'Revise the draft' }))
    const editor = within(card).getByRole('group', { name: 'Revise the draft' })
    await userEvent.click(within(editor).getAllByRole('button', { name: 'Remove' })[0])
    await userEvent.click(within(editor).getByRole('button', { name: 'Publish revision' }))
    expect(submitDraft).toHaveBeenCalledWith('g1', 'c1', 2, [], expect.any(String))
    expect(await screen.findByText('Revision 3 published. Everyone affected must approve it.')).toBeInTheDocument()
  })
})

describe('Attached battalions', () => {
  it('lends selected battalions to an ally and lists them as commanded by it', async () => {
    const attach = vi.fn().mockResolvedValue({ battalions: [] })
    renderGame({
      attach,
      getMilitary: vi.fn().mockResolvedValue(
        militaryFixture({
          battalions: [
            battalionFixture({ id: 'b1' }),
            battalionFixture({ id: 'b2', manpower: 650, commanderNationId: ALLY, eligibility: null }),
            battalionFixture({ id: 'h1', nationId: ALLY, manpower: 400, eligibility: 'READY', commanderNationId: VARN }),
          ],
        }),
      ),
    })
    await showPanel('diplomacy')
    const panel = await openForces('Elmsford')
    expect(await within(panel).findByText('Commanded by Helm')).toBeInTheDocument()
    expect(within(panel).getByText('Lent by Helm — you command it')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Lend to an ally' })).toBeDisabled()
    await userEvent.click(within(panel).getByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    await userEvent.click(within(panel).getByRole('button', { name: 'Lend to an ally' }))
    await userEvent.click(within(panel).getByRole('button', { name: 'Lend 1 battalion(s)' }))
    expect(attach).toHaveBeenCalledWith('g1', ['b1'], ALLY, expect.any(String))
  })
})

describe('Coalition battle report', () => {
  it('lists each nation with its own losses', () => {
    const base = battleFixture()
    const battle = {
      ...base,
      details: {
        ...base.details,
        nations: [
          { nationId: VARN, side: 'ATTACKER' as const, manpowerBefore: 1000, losses: 80, retreatProvinceKey: null, destroyed: false, modifiers: null },
          { nationId: ALLY, side: 'ATTACKER' as const, manpowerBefore: 400, losses: 40, retreatProvinceKey: null, destroyed: false, modifiers: null },
          { nationId: OSTRA, side: 'DEFENDER' as const, manpowerBefore: 1000, losses: 310, retreatProvinceKey: 'varnhold', destroyed: false, modifiers: null },
        ],
      },
    }
    const nations = new Map([
      [VARN, { name: 'Kingdom of Varn', color: '#3f6fb5' }],
      [OSTRA, { name: 'Ostran Confederacy', color: '#b5503a' }],
      [ALLY, { name: 'Helm', color: '#4f9d9a' }],
    ])
    render(<BattleReportView battle={battle} nations={nations} provinceNames={new Map([['varnhold', 'Varnhold']])} />)
    const table = screen.getByRole('table', { name: 'By nation' })
    const helm = within(table).getByRole('row', { name: /Attacker · Helm/ })
    expect(within(helm).getByText('40')).toBeInTheDocument()
    expect(within(table).getByText('Retreated to Varnhold')).toBeInTheDocument()
  })
})
