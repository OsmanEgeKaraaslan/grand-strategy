import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { DiplomacyOverview, GameResult, PeaceProposal, WarView } from '../api/types'
import { fakeApi, openForces, renderPage, showPanel } from '../test/render'
import { OSTRA, VARN, economyFixture, gameStateFixture, mapFixture, militaryFixture } from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

const RULES = {
  version: 'v5',
  truceRounds: 10,
  proposalExpiryRounds: 3,
  maxPeaceTerms: 10,
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
}

const side = (total: number) => ({
  battles: total,
  sieges: 0,
  occupation: 0,
  total,
  battlesWon: total > 0 ? 1 : 0,
  siegesWon: 0,
  occupiedEnemyPopulation: 0,
  enemyPopulation: 20000,
})

function warFixture(overrides: Partial<WarView> = {}): WarView {
  return {
    id: 'w1',
    status: 'ACTIVE',
    attackerLeaderId: VARN,
    defenderLeaderId: OSTRA,
    participants: [
      { nationId: VARN, name: 'Kingdom of Varn', side: 'ATTACKER', joinReason: 'DECLARED', joinedRound: 2 },
      { nationId: OSTRA, name: 'Ostran Confederacy', side: 'DEFENDER', joinReason: 'ATTACKED', joinedRound: 2 },
    ],
    declaredRound: 2,
    endedRound: null,
    endReason: null,
    durationRounds: 3,
    score: { attacker: side(7), defender: side(0), net: 7, maxScore: 100 },
    yourSide: 'ATTACKER',
    youLead: true,
    ...overrides,
  }
}

function diplomacyFixture(status: 'PEACE' | 'WAR', overrides: Partial<DiplomacyOverview> = {}): DiplomacyOverview {
  const atWar = status === 'WAR'
  return {
    gameId: 'g1',
    available: true,
    rulesetVersion: 'v5',
    round: 4,
    yourTurn: true,
    yourNationId: VARN,
    rules: RULES,
    nations: [
      { nationId: VARN, name: 'Kingdom of Varn', color: '#3f6fb5', you: true, status: 'SELF', allianceKind: null, allianceId: null, warId: null, truceUntilRound: null, weMayEnter: true, theyMayEnter: true, warBlockers: [], allianceBlockers: [] },
      {
        nationId: OSTRA,
        name: 'Ostran Confederacy',
        color: '#b5503a',
        you: false,
        status,
        allianceKind: null,
        allianceId: null,
        warId: atWar ? 'w1' : null,
        truceUntilRound: null,
        weMayEnter: atWar,
        theyMayEnter: atWar,
        warBlockers: atWar ? ['ALREADY_AT_WAR'] : [],
        allianceBlockers: atWar ? ['AT_WAR'] : [],
      },
    ],
    wars: atWar ? [warFixture()] : [],
    alliances: [],
    proposals: [],
    treaties: [],
    tributes: [],
    events: atWar
      ? [{ id: 'ev1', round: 2, kind: 'WAR_DECLARED', actorNationId: VARN, targetNationId: OSTRA, warId: 'w1', details: {}, createdAt: '' }]
      : [],
    gifts: [],
    ...overrides,
  }
}

function proposalFixture(overrides: Partial<PeaceProposal> = {}): PeaceProposal {
  return {
    id: 'pp1',
    warId: 'w1',
    proposerNationId: OSTRA,
    recipientNationId: VARN,
    status: 'PENDING',
    terms: [
      { type: 'GOLD', fromNationId: OSTRA, toNationId: VARN, amountMg: 20000 },
      { type: 'TRIBUTE', fromNationId: OSTRA, toNationId: VARN, tributeRateBps: 1000, tributeRounds: 10 },
    ],
    createdRound: 4,
    createdTurn: 7,
    createdStateVersion: 30,
    respondedRound: null,
    counterOfId: null,
    expiresAfterRound: 7,
    incoming: true,
    violations: [],
    createdAt: '',
    ...overrides,
  }
}

const withMarrowfield = gameStateFixture({
  provinces: [
    ...gameStateFixture().provinces,
    { key: 'marrowfield', ownerNationId: OSTRA, controllerNationId: VARN, city: { id: 'city-marrow', name: 'Marrow', population: 5000 } },
  ],
})

function renderGame(overrides = {}, state = gameStateFixture()) {
  const api = fakeApi({
    getMe: vi.fn().mockResolvedValue(me),
    getGameState: vi.fn().mockResolvedValue(state),
    getMap: vi.fn().mockResolvedValue(mapFixture),
    getEconomy: vi.fn().mockResolvedValue(economyFixture()),
    getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
    getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('PEACE')),
    ...overrides,
  })
  return renderPage(<GamePage />, { route: '/games/g1/play', path: '/games/:gameId/play', api })
}

async function diplomacyPanel() {
  return screen.findByRole('region', { name: 'Diplomacy' })
}

describe('Diplomacy panel', () => {
  it('shows every nation at peace and explains access', async () => {
    renderGame()
    await showPanel('diplomacy')

    const panel = await diplomacyPanel()
    expect(within(panel).getByText('At peace')).toBeInTheDocument()
    expect(within(panel).getByText(/your battalions may not enter its territory/)).toBeInTheDocument()
    expect(within(panel).getByText('No war is being fought.')).toBeInTheDocument()
  })

  it('declares war only after an explicit confirmation', async () => {
    const declareWar = vi.fn().mockResolvedValue({ war: warFixture(), joinedDefenders: [] })
    renderGame({ declareWar })
    await showPanel('diplomacy')

    const panel = await diplomacyPanel()
    await userEvent.click(within(panel).getByRole('button', { name: 'Declare war' }))
    expect(declareWar).not.toHaveBeenCalled()
    await userEvent.click(within(panel).getByRole('button', { name: 'Confirm declaration' }))

    expect(declareWar).toHaveBeenCalledWith('g1', OSTRA, expect.stringMatching(/^[0-9a-f-]{36}$/))
    expect(await within(panel).findByText(/War declared on Ostran Confederacy/)).toBeInTheDocument()
  })

  it('proposes an alliance of the chosen kind', async () => {
    const proposeAlliance = vi.fn().mockResolvedValue({ alliance: {}, withdrawals: [] })
    renderGame({ proposeAlliance })
    await showPanel('diplomacy')

    const panel = await diplomacyPanel()
    await userEvent.selectOptions(within(panel).getByRole('combobox', { name: /Alliance type/ }), 'DEFENSIVE')
    await userEvent.click(within(panel).getByRole('button', { name: 'Propose alliance' }))

    expect(proposeAlliance).toHaveBeenCalledWith('g1', OSTRA, 'DEFENSIVE', expect.any(String))
  })

  it('shows the war with its server-computed score, duration and history', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')) })
    await showPanel('diplomacy')

    const war = await screen.findByRole('article', { name: /War: Kingdom of Varn against Ostran Confederacy/ })
    expect(within(war).getByText(/Declared in round 2 · 3 round\(s\)/)).toBeInTheDocument()
    expect(within(war).getByRole('meter', { name: 'War score' })).toHaveAttribute('aria-valuenow', '7')
    expect(within(war).getByText('+7')).toBeInTheDocument()
    const panel = await diplomacyPanel()
    expect(within(panel).getByText(/Kingdom of Varn declared war on Ostran Confederacy/)).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Declare war' })).not.toBeInTheDocument()
  })

  it('does not pretend allies fight when they only joined legally', async () => {
    const war = warFixture({
      participants: [
        ...warFixture().participants,
        { nationId: 'nation-third', name: 'Third Realm', side: 'DEFENDER', joinReason: 'DEFENSIVE_RESPONSE', joinedRound: 2 },
      ],
    })
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR', { wars: [war] })) })
    await showPanel('diplomacy')

    const card = await screen.findByRole('article', { name: /War:/ })
    expect(within(card).getByText(/Third Realm \(joined to defend an ally\)/)).toBeInTheDocument()
    expect(within(card).getByText(/battles with several nations on one side arrive in a later phase/)).toBeInTheDocument()
  })

  it('explains that older games have no diplomacy', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('PEACE', { available: false, rules: null })) })
    await showPanel('diplomacy')

    expect(await within(await diplomacyPanel()).findByText(/every nation is permanently at war/)).toBeInTheDocument()
  })
})

describe('Movement and war', () => {
  it('closes neutral territory and calls a peaceful neighbour’s battalions neutral', async () => {
    renderGame()
    await showPanel('diplomacy')

    const province = await openForces('Elmsford')
    await userEvent.click(await within(province).findByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    const sable = within(province).getByRole('button', { name: 'Move to Sablewater' })
    expect(sable).toBeDisabled()
    expect(within(province).getByText('Closed — not at war, no military access')).toBeInTheDocument()
  })

  it('offers the attack once the nations are at war', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')) })
    await showPanel('diplomacy')

    await screen.findByRole('article', { name: /War:/ })
    const province = await openForces('Elmsford')
    await userEvent.click(await within(province).findByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    expect(within(province).getByRole('button', { name: 'Attack Sablewater' })).toBeEnabled()
  })
})

describe('Diplomatic map perspective', () => {
  it('shows another nation’s relations without changing whose orders are given', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')) })
    const svg = await screen.findByRole('group', { name: /^Map of/ })
    await userEvent.click(await screen.findByRole('button', { name: 'Diplomatic' }))
    expect(screen.getByRole('status', { name: 'Diplomatic perspective' })).toHaveTextContent('Relations of your nation')

    // A click on an Ostran province shows the map from Ostra, and still opens the province.
    await userEvent.click(within(svg).getByRole('button', { name: /^Sablewater,/ }))
    const bar = screen.getByRole('status', { name: 'Diplomatic perspective' })
    expect(bar).toHaveTextContent('Relations of Ostran Confederacy')
    expect(within(svg).getByRole('button', { name: /^Sablewater,/ })).toHaveAttribute('data-standing', 'self')
    expect(within(svg).getByRole('button', { name: /^Varnhold,/ })).toHaveAttribute('data-standing', 'war')
    expect(screen.getByRole('region', { name: 'Sablewater' })).toBeInTheDocument()

    // Selecting your army keeps the perspective, and orders still come from your own nation.
    await userEvent.click(within(svg).getByRole('button', { name: /^Select your army in Elmsford/ }))
    expect(screen.getByRole('region', { name: 'Army in Elmsford' })).toBeInTheDocument()
    expect(bar).toHaveTextContent('Relations of Ostran Confederacy')
    expect(within(svg).getByRole('button', { name: /^Sablewater.*attack target/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'End turn' })).toBeInTheDocument()

    await userEvent.click(within(bar).getByRole('button', { name: 'Reset to my nation' }))
    expect(bar).toHaveTextContent('Relations of your nation')
    expect(within(svg).getByRole('button', { name: /^Varnhold,/ })).toHaveAttribute('data-standing', 'self')
  })
})

describe('Peace negotiation', () => {
  it('builds terms, summarizes gains and losses and confirms a major settlement before sending', async () => {
    const proposePeace = vi.fn().mockResolvedValue({ proposal: proposalFixture({ incoming: false }) })
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')), proposePeace }, withMarrowfield)
    await showPanel('diplomacy')

    await userEvent.click(await screen.findByRole('button', { name: 'Negotiate peace' }))
    const negotiation = screen.getByRole('group', { name: 'Peace negotiation' })
    expect(within(negotiation).getByText(/a white peace/)).toBeInTheDocument()

    // Ostra cedes Marrowfield (Sablewater, its capital, is never offered).
    const province = within(negotiation).getByRole('combobox', { name: 'Province' })
    expect(within(province).queryByRole('option', { name: /Sablewater/ })).not.toBeInTheDocument()
    await userEvent.selectOptions(province, 'marrowfield')
    await userEvent.click(within(negotiation).getByRole('button', { name: 'Add term' }))

    await userEvent.selectOptions(within(negotiation).getByRole('combobox', { name: 'Term' }), 'GOLD')
    await userEvent.type(within(negotiation).getByRole('textbox', { name: /Gold/ }), '12.5')
    await userEvent.click(within(negotiation).getByRole('button', { name: 'Add term' }))

    await userEvent.selectOptions(within(negotiation).getByRole('combobox', { name: 'Term' }), 'TRIBUTE')
    await userEvent.click(within(negotiation).getByRole('button', { name: 'Add term' }))

    expect(within(negotiation).getByText(/Ostran Confederacy cedes marrowfield to Kingdom of Varn/)).toBeInTheDocument()
    expect(within(negotiation).getByText(/pays 10% of its gross income to Kingdom of Varn every round for 10 rounds/)).toBeInTheDocument()
    expect(within(negotiation).getByText(/Gains: Province marrowfield, 12.5 gold, Tribute income/)).toBeInTheDocument()
    expect(within(negotiation).getByText(/Gives up: Province marrowfield, 12.5 gold, Tribute from its income/)).toBeInTheDocument()

    await userEvent.click(within(negotiation).getByRole('button', { name: 'Send proposal' }))
    expect(proposePeace).not.toHaveBeenCalled()
    expect(within(negotiation).getByRole('alert')).toHaveTextContent(/major settlement/)
    await userEvent.click(within(negotiation).getByRole('button', { name: 'Confirm and send' }))

    expect(proposePeace).toHaveBeenCalledWith(
      'g1',
      'w1',
      [
        { type: 'PROVINCE', fromNationId: OSTRA, toNationId: VARN, provinceKey: 'marrowfield' },
        { type: 'GOLD', fromNationId: OSTRA, toNationId: VARN, amountMg: 12500 },
        { type: 'TRIBUTE', fromNationId: OSTRA, toNationId: VARN },
      ],
      expect.any(String),
    )
    expect(await screen.findByText('Peace proposal sent.')).toBeInTheDocument()
  }, 20_000)

  it('transfers people between named cities and lets the server check the terms', async () => {
    const checkPeace = vi.fn().mockResolvedValue({ valid: false, violations: [{ term: 0, code: 'SHARE_EXCEEDED' }] })
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')), checkPeace }, withMarrowfield)
    await showPanel('diplomacy')

    await userEvent.click(await screen.findByRole('button', { name: 'Negotiate peace' }))
    const negotiation = screen.getByRole('group', { name: 'Peace negotiation' })
    await userEvent.selectOptions(within(negotiation).getByRole('combobox', { name: 'Term' }), 'POPULATION')
    await userEvent.selectOptions(within(negotiation).getByRole('combobox', { name: 'From city' }), 'city-marrow')
    await userEvent.selectOptions(within(negotiation).getByRole('combobox', { name: 'To city' }), 'city-elmsford')
    await userEvent.click(within(negotiation).getByRole('button', { name: 'Add term' }))
    expect(within(negotiation).getByText(/1,000 people move from Marrow \(Ostran Confederacy\) to Elmsford/)).toBeInTheDocument()

    await userEvent.click(within(negotiation).getByRole('button', { name: 'Check terms' }))
    expect(checkPeace).toHaveBeenCalledWith('g1', 'w1', [
      { type: 'POPULATION', fromNationId: OSTRA, toNationId: VARN, sourceCityId: 'city-marrow', destinationCityId: 'city-elmsford', people: 1000 },
    ])
    expect(await within(negotiation).findByText(/Term 1: Too large a share of the source city would leave/)).toBeInTheDocument()
  })

  it('accepts an incoming major settlement only after confirming it', async () => {
    const acceptPeace = vi.fn().mockResolvedValue({ treaty: { truceUntilRound: 14 }, war: warFixture(), withdrawals: [{}, {}], controlReturned: [] })
    renderGame({
      getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR', { proposals: [proposalFixture()] })),
      acceptPeace,
    })
    await showPanel('diplomacy')

    const proposal = await screen.findByRole('article', { name: 'Incoming peace proposal' })
    expect(within(proposal).getByText(/Ostran Confederacy pays 20 gold to Kingdom of Varn at once/)).toBeInTheDocument()
    expect(within(proposal).getByText(/Kingdom of Varn \(you\)/)).toBeInTheDocument()
    await userEvent.click(within(proposal).getByRole('button', { name: 'Accept' }))
    expect(acceptPeace).not.toHaveBeenCalled()
    await userEvent.click(within(proposal).getByRole('button', { name: 'Confirm: accept these terms' }))

    expect(acceptPeace).toHaveBeenCalledWith('g1', 'pp1', expect.any(String))
    expect(await screen.findByText(/Peace signed. Truce until round 14. 2 battalion\(s\) withdrew./)).toBeInTheDocument()
  })

  it('rejects, or counters starting from the received terms', async () => {
    const peaceAction = vi.fn().mockResolvedValue({ proposal: proposalFixture({ status: 'REJECTED' }) })
    const counterPeace = vi.fn().mockResolvedValue({ proposal: proposalFixture({ incoming: false }) })
    renderGame({
      getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR', { proposals: [proposalFixture({ terms: [] })] })),
      peaceAction,
      counterPeace,
    })
    await showPanel('diplomacy')

    const proposal = await screen.findByRole('article', { name: 'Incoming peace proposal' })
    expect(within(proposal).getByText(/White peace/)).toBeInTheDocument()
    await userEvent.click(within(proposal).getByRole('button', { name: 'Reject' }))
    expect(peaceAction).toHaveBeenCalledWith('g1', 'pp1', 'reject', expect.any(String))

    await userEvent.click(within(proposal).getByRole('button', { name: 'Counter-proposal' }))
    const negotiation = screen.getByRole('group', { name: 'Peace negotiation' })
    await userEvent.click(within(negotiation).getByRole('button', { name: 'Send counter-proposal' }))
    expect(counterPeace).toHaveBeenCalledWith('g1', 'pp1', [], expect.any(String))
  })

  it('warns when a pending proposal can no longer be settled', async () => {
    renderGame({
      getDiplomacy: vi.fn().mockResolvedValue(
        diplomacyFixture('WAR', { proposals: [proposalFixture({ violations: ['term 1: INSUFFICIENT_GOLD'] })] }),
      ),
    })
    await showPanel('diplomacy')

    const proposal = await screen.findByRole('article', { name: 'Incoming peace proposal' })
    expect(within(proposal).getByRole('alert')).toHaveTextContent(/can no longer be settled/)
  })
})

describe('Game completion', () => {
  const result: GameResult = {
    gameId: 'g1',
    gameName: 'Twin Shores',
    finishedRound: 30,
    reason: 'ROUND_LIMIT',
    outcome: 'WINNER',
    winnerNationId: VARN,
    winnerName: 'Kingdom of Varn',
    rulesetVersion: 'v5',
    finishedAt: '',
    standings: [
      { rank: 1, nationId: VARN, nationName: 'Kingdom of Varn', color: '#3f6fb5', playerName: 'Aldric', you: true, score: 186, ownedProvinces: 7, ownedPopulation: 46000, treasuryMg: 120000 },
      { rank: 2, nationId: OSTRA, nationName: 'Ostran Confederacy', color: '#b5503a', playerName: 'Bryn', you: false, score: 151, ownedProvinces: 5, ownedPopulation: 51999, treasuryMg: 80000 },
    ],
    statistics: { roundsPlayed: 30, warsDeclared: 1, peaceTreaties: 1, tributePaidMg: 41500 },
  }

  it('shows the winner, standings and statistics and keeps the map read-only', async () => {
    const getResult = vi.fn().mockResolvedValue(result)
    renderGame(
      { getResult, getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('PEACE', { yourTurn: false })) },
      gameStateFixture({ status: 'FINISHED', round: 30, yourTurn: false }),
    )
    await showPanel('diplomacy')

    const screenResult = await screen.findByRole('region', { name: 'Kingdom of Varn wins' })
    expect(within(screenResult).getByText(/Finished after round 30/)).toBeInTheDocument()
    const rows = within(screenResult).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('Kingdom of Varn')
    expect(rows[1]).toHaveTextContent('186')
    expect(rows[1]).toHaveTextContent('46,000')
    expect(rows[2]).toHaveTextContent('Ostran Confederacy')
    expect(within(screenResult).getByText('41.5')).toBeInTheDocument()
    expect(within(screenResult).getByRole('link', { name: 'Return to dashboard' })).toHaveAttribute('href', '/')
    expect(screen.getByText(/Game over — final state after round 30/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'End turn' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Declare war' })).not.toBeInTheDocument()
    expect(screen.getByRole('group', { name: /^Map of/ })).toBeInTheDocument()

    // The result can be put aside to look at the final map, and brought back from the top bar.
    await userEvent.click(screen.getByRole('button', { name: 'Hide the result to look at the map' }))
    expect(screen.queryByRole('region', { name: 'Kingdom of Varn wins' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show the result' }))
    expect(screen.getByRole('region', { name: 'Kingdom of Varn wins' })).toBeInTheDocument()
  })

  it('announces a draw', async () => {
    renderGame(
      { getResult: vi.fn().mockResolvedValue({ ...result, outcome: 'DRAW', winnerNationId: null, winnerName: null }) },
      gameStateFixture({ status: 'FINISHED', round: 30, yourTurn: false }),
    )
    await showPanel('diplomacy')

    expect(await screen.findByRole('heading', { name: 'The game ended in a draw' })).toBeInTheDocument()
  })
})
