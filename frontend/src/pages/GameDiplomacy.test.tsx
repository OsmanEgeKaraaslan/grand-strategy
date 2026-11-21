import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { DiplomacyOverview, GameResult } from '../api/types'
import { fakeApi, openForces, renderPage, showPanel } from '../test/render'
import {
  DIPLOMACY_RULES as RULES,
  OSTRA,
  VARN,
  diplomacyFixture,
  economyFixture,
  gameStateFixture,
  mapFixture,
  militaryFixture,
  proposalFixture,
  warFixture,
} from '../test/fixtures'
import { GamePage } from './GamePage'

const me = { id: 'p1', displayName: 'Aldric', createdAt: '', updatedAt: '' }

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
    const kinds = within(panel).getByRole('radiogroup', { name: /Alliance type/ })
    expect(within(kinds).getByRole('radio', { name: /Normal alliance/ })).toHaveAttribute('aria-checked', 'true')
    await userEvent.click(within(kinds).getByRole('radio', { name: /Defensive pact/ }))
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
    expect(within(panel).queryByRole('button', { name: 'Declare war' })).not.toBeInTheDocument()
    // The event log lives in the History window, not among the live matters.
    expect(within(panel).queryByText(/Kingdom of Varn declared war on Ostran Confederacy/)).not.toBeInTheDocument()
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
    const map = screen.getByRole('group', { name: /^Map of/ })
    // Closed: neither a move nor an attack target on the map.
    expect(within(map).getByRole('button', { name: /^Sablewater/ }).getAttribute('aria-label')).not.toMatch(/target/)
    expect(within(map).getByRole('button', { name: /^Varnhold.*move target/ })).toBeInTheDocument()
  })

  it('offers the attack once the nations are at war', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')) })
    await showPanel('diplomacy')

    await screen.findByRole('article', { name: /War:/ })
    const province = await openForces('Elmsford')
    await userEvent.click(await within(province).findByRole('checkbox', { name: 'Select Infantry, 1,000 of 1,000 men' }))
    expect(within(screen.getByRole('group', { name: /^Map of/ })).getByRole('button', { name: /^Sablewater.*attack target/ })).toBeInTheDocument()
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

  it('rejects a proposal at once', async () => {
    const peaceAction = vi.fn().mockResolvedValue({ proposal: proposalFixture({ status: 'REJECTED' }) })
    renderGame({
      getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR', { proposals: [proposalFixture({ terms: [] })] })),
      peaceAction,
    })
    await showPanel('diplomacy')

    const proposal = await screen.findByRole('article', { name: 'Incoming peace proposal' })
    expect(within(proposal).getByText(/White peace/)).toBeInTheDocument()
    await userEvent.click(within(proposal).getByRole('button', { name: 'Reject' }))
    expect(peaceAction).toHaveBeenCalledWith('g1', 'pp1', 'reject', expect.any(String))
    // Shown before the server's refetch (D-UX1): the rejected proposal is no longer open to answer.
    expect(screen.queryByRole('button', { name: 'Counter-proposal' })).not.toBeInTheDocument()
  })

  it('counters starting from the received terms', async () => {
    const counterPeace = vi.fn().mockResolvedValue({ proposal: proposalFixture({ incoming: false }) })
    renderGame({
      getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR', { proposals: [proposalFixture({ terms: [] })] })),
      counterPeace,
    })
    await showPanel('diplomacy')

    const proposal = await screen.findByRole('article', { name: 'Incoming peace proposal' })
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

describe('Diplomacy from the map (playtest feedback)', () => {
  it('opens the legal owner’s diplomacy from the province window and keeps the province selected', async () => {
    const helm = 'nation-helm'
    const overview = diplomacyFixture('PEACE', {
      alliances: [
        { id: 'al1', kind: 'NORMAL', status: 'ACTIVE', nationA: OSTRA, nationB: helm, proposerNationId: OSTRA, proposedRound: 1, acceptedRound: 2, endedRound: null, expiresAfterRound: 3, endReason: null, grantsMilitaryAccess: true, canAccept: false, canEnd: false, endBlockers: [] },
      ] as DiplomacyOverview['alliances'],
    })
    const state = gameStateFixture({
      nations: [...gameStateFixture().nations, { id: helm, key: 'helm', name: 'Helm', color: '#999999', seat: null, eliminatedRound: null } as never],
      // Sablewater is owned by Ostra but held (occupied) by Varn: the owner, not the occupier, opens.
      provinces: gameStateFixture().provinces.map((p) => (p.key === 'sablewater' ? { ...p, controllerNationId: VARN } : p)),
    })
    const proposeAlliance = vi.fn().mockResolvedValue({ alliance: {}, withdrawals: [] })
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(overview), proposeAlliance }, state)

    const map = await screen.findByRole('group', { name: /^Map of/ })
    await userEvent.click(await within(map).findByRole('button', { name: /^Sablewater,/ }))
    const province = screen.getByRole('region', { name: 'Sablewater' })
    await userEvent.click(await within(province).findByRole('button', { name: 'Diplomacy with Ostran Confederacy' }))

    const nation = await screen.findByRole('region', { name: 'Ostran Confederacy' })
    expect(within(nation).getByText('At peace')).toBeInTheDocument()
    expect(within(nation).getByText(/Normal alliance with/)).toHaveTextContent('Helm')
    expect(within(nation).getByText('At peace with everyone.')).toBeInTheDocument()
    await userEvent.click(within(nation).getByRole('button', { name: 'Propose alliance' }))
    expect(proposeAlliance).toHaveBeenCalledWith('g1', OSTRA, 'NORMAL', expect.any(String))
    // The province stays selected and its window open.
    expect(screen.getByRole('region', { name: 'Sablewater' })).toBeInTheDocument()

    await userEvent.click(within(nation).getByRole('button', { name: 'Open the full diplomacy window' }))
    expect(await diplomacyPanel()).toBeInTheDocument()
  })

  it('lists a nation’s current wars with its side and the war score', async () => {
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('WAR')) })
    const province = await (async () => {
      const map = await screen.findByRole('group', { name: /^Map of/ })
      await userEvent.click(await within(map).findByRole('button', { name: /^Sablewater,/ }))
      return screen.getByRole('region', { name: 'Sablewater' })
    })()
    await userEvent.click(await within(province).findByRole('button', { name: 'Diplomacy with Ostran Confederacy' }))
    const nation = await screen.findByRole('region', { name: 'Ostran Confederacy' })
    expect(within(nation).getByText(/Defending against/)).toHaveTextContent('Kingdom of Varn')
    expect(within(nation).getByText(/war score \+7/)).toBeInTheDocument()
    expect(within(nation).queryByRole('button', { name: 'Declare war' })).not.toBeInTheDocument()
  })
})

describe('History window (playtest feedback)', () => {
  it('shows one category at a time from an icon bar: ended wars, then battles', async () => {
    const ended = warFixture({ id: 'w0', status: 'ENDED', endedRound: 6, endReason: 'PEACE' })
    const getBattles = vi.fn().mockResolvedValue([
      { id: 'bt1', round: 3, turnNumber: 5, provinceKey: 'sablewater', attackerNationId: VARN, defenderNationId: OSTRA, winner: 'ATTACKER', attackerLosses: 120, defenderLosses: 300, createdAt: '' },
    ])
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(diplomacyFixture('PEACE', { wars: [ended] })), getBattles })

    await showPanel('diplomacy')
    // Ended wars are not among the live matters any more.
    expect(within(await diplomacyPanel()).queryByText(/rounds 2–6/)).not.toBeInTheDocument()

    await showPanel('history')
    const history = await screen.findByRole('region', { name: 'History' })
    const tabs = within(history).getByRole('tablist', { name: 'History category' })
    expect(within(tabs).getByRole('tab', { name: 'Past wars' })).toHaveAttribute('aria-selected', 'true')
    expect(within(tabs).getByRole('tab', { name: 'Battles' })).toHaveAttribute('title', expect.any(String))
    expect(within(history).getByText(/rounds 2–6/)).toBeInTheDocument()
    expect(getBattles).not.toHaveBeenCalled()

    await userEvent.click(within(tabs).getByRole('tab', { name: 'Battles' }))
    expect(await within(history).findByText(/Kingdom of Varn won at Sablewater/)).toBeInTheDocument()
    expect(within(history).queryByText(/rounds 2–6/)).not.toBeInTheDocument()
  })
})

describe('Offensive calls to arms (ruleset v8)', () => {
  const HELM = 'nation-helm'
  const KESH = 'nation-kesh'
  function v8Overview(extra: Partial<DiplomacyOverview> = {}) {
    const base = diplomacyFixture('PEACE')
    const ally = (id: string, name: string, kind: 'NORMAL' | 'DEFENSIVE') => ({
      ...base.nations[1], nationId: id, name, status: 'ALLIED' as const, allianceKind: kind, warBlockers: ['ALLIED_NATION'], allianceBlockers: ['ALREADY_ALLIED'],
    })
    return diplomacyFixture('PEACE', {
      rules: { ...RULES, features: { coalitionWarfare: true, attachments: true, callsToArms: true, peaceConferences: true, separatePeace: true, capitalsCedable: true, elimination: true, offensiveCallsToArms: true, truceAllowsCallsToArms: true } },
      nations: [...base.nations, ally(HELM, 'Helm', 'NORMAL'), ally(KESH, 'Kesh', 'DEFENSIVE')],
      ...extra,
    } as Partial<DiplomacyOverview>)
  }

  it('lets the declarer invite only its normal allies, or nobody', async () => {
    const declareWar = vi.fn().mockResolvedValue({ war: warFixture(), joinedDefenders: [], invitedAllies: [HELM] })
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(v8Overview()), declareWar })
    await showPanel('diplomacy')
    const panel = await diplomacyPanel()
    const ostra = within(panel).getByText('Ostran Confederacy').closest('li')!
    await userEvent.click(within(ostra).getByRole('button', { name: 'Declare war' }))

    const invitations = within(ostra).getByRole('group', { name: 'Invite allies to join the attack' })
    expect(within(invitations).getByRole('checkbox', { name: /Helm/ })).not.toBeChecked()
    // A defensive-pact partner is no offensive ally.
    expect(within(invitations).queryByRole('checkbox', { name: /Kesh/ })).not.toBeInTheDocument()
    expect(within(invitations).getByText(/Nobody invited/)).toBeInTheDocument()
    await userEvent.click(within(invitations).getByRole('checkbox', { name: /Helm/ }))
    expect(within(invitations).getByText(/Invited: Helm/)).toBeInTheDocument()
    await userEvent.click(within(ostra).getByRole('button', { name: 'Confirm declaration' }))

    expect(declareWar).toHaveBeenCalledWith('g1', OSTRA, expect.any(String), [HELM])
    expect(await within(panel).findByText(/Invited to join you: Helm/)).toBeInTheDocument()
  })

  it('declares without invitations when none is chosen', async () => {
    const declareWar = vi.fn().mockResolvedValue({ war: warFixture(), joinedDefenders: [], invitedAllies: [] })
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(v8Overview()), declareWar })
    await showPanel('diplomacy')
    const ostra = within(await diplomacyPanel()).getByText('Ostran Confederacy').closest('li')!
    await userEvent.click(within(ostra).getByRole('button', { name: 'Declare war' }))
    await userEvent.click(within(ostra).getByRole('button', { name: 'Confirm declaration' }))
    expect(declareWar).toHaveBeenCalledWith('g1', OSTRA, expect.any(String))
  })

  it('tells an invited ally that declining keeps the alliance', async () => {
    const overview = diplomacyFixture('PEACE', {
      calls: [{ id: 'c1', warId: 'w9', callerNationId: OSTRA, allyNationId: VARN, status: 'PENDING', createdRound: 4, answeredRound: null, canAnswer: true, side: 'ATTACKER' }],
    } as Partial<DiplomacyOverview>)
    renderGame({ getDiplomacy: vi.fn().mockResolvedValue(overview) })
    await showPanel('diplomacy')
    const panel = await diplomacyPanel()
    expect(within(panel).getByText(/Ostran Confederacy invites you to join its attack/)).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('button', { name: 'Decline' }))
    expect(within(panel).getByText(/Declining keeps your alliance with Ostran Confederacy/)).toBeInTheDocument()
  })
})

