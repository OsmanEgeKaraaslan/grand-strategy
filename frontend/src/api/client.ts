import type {
  AllianceKind,
  AllianceResult,
  DiplomacyEvent,
  DiplomacyOverview,
  GameResult,
  PeaceProposal,
  PeaceResult,
  PeaceTerm,
  ProposalResult,
  TermsCheck,
  Treaty,
  TributeView,
  WarResult,
  CityInstitutions,
  InstitutionResult,
  InstitutionType,
  ResearchOverview,
  ResearchTrack,
  UnlockResult,
  WorkshopUpgradeResult,
  FortressWorksResult,
  ProvinceFortification,
  SiegeOverview,
  WorkshopRepairResult,
  BattalionDetails,
  DisbandResult,
  ReinforceResult,
  ArmyResult,
  BattleReport,
  BattleSummary,
  BuildResult,
  CityEconomy,
  EconomyOverview,
  GameMap,
  GameState,
  GameSummary,
  Lobby,
  MilitaryOverview,
  MoveResult,
  PlayerProfile,
  Problem,
  RecruitResult,
  TurnResult,
  UnitType,
} from './types'

/** An error response from the API, carrying the stable problem `code`. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string
  readonly fieldErrors: { field: string; message: string }[]

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed (${problem.status})`)
    this.name = 'ApiError'
    this.status = problem.status
    this.code = problem.code
    this.requestId = problem.requestId
    this.fieldErrors = problem.errors ?? []
  }
}

export interface ApiClientOptions {
  baseUrl: string
  /** Supplies the current Supabase access token; null when signed out. */
  accessToken: () => Promise<string | null>
  /** Called when the API rejects the token (expired/revoked session). */
  onUnauthorized?: () => void
  fetch?: typeof fetch
}

/**
 * The single gateway to the Spring Boot API. Components never call fetch directly: they use
 * the query hooks in api/queries.ts, which use this client.
 */
export function createApiClient({ baseUrl, accessToken, onUnauthorized, fetch: fetchImpl }: ApiClientOptions) {
  const doFetch = fetchImpl ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init))

  async function request<T>(
    method: string,
    path: string,
    body?: unknown,
    extraHeaders: Record<string, string> = {},
  ): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json', ...extraHeaders }
    const token = await accessToken()
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    let response: Response
    try {
      response = await doFetch(`${baseUrl}/api/v1${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new ApiError({
        status: 0,
        code: 'NETWORK_ERROR',
        detail: 'Cannot reach the game server. Check your connection and try again.',
      })
    }

    if (response.status === 401) onUnauthorized?.()
    if (!response.ok) throw new ApiError(await readProblem(response))
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  return {
    getMe: () => request<PlayerProfile>('GET', '/me'),
    updateMe: (displayName: string) => request<PlayerProfile>('PATCH', '/me', { displayName }),
    listGames: () => request<GameSummary[]>('GET', '/games'),
    getGame: (gameId: string) => request<Lobby>('GET', `/games/${encodeURIComponent(gameId)}`),
    createGame: (name: string) => request<Lobby>('POST', '/games', { name }),
    joinGame: (inviteCode: string) => request<Lobby>('POST', '/games/join', { inviteCode }),
    chooseNation: (gameId: string, nationId: string) =>
      request<Lobby>('PUT', `/games/${encodeURIComponent(gameId)}/players/me/nation`, { nationId }),
    startGame: (gameId: string) => request<Lobby>('POST', `/games/${encodeURIComponent(gameId)}/start`),
    getMap: (mapId: string) => request<GameMap>('GET', `/maps/${encodeURIComponent(mapId)}`),
    getGameState: (gameId: string) => request<GameState>('GET', `/games/${encodeURIComponent(gameId)}/state`),
    getEconomy: (gameId: string) => request<EconomyOverview>('GET', `/games/${encodeURIComponent(gameId)}/economy`),
    getCityEconomy: (gameId: string, cityId: string) =>
      request<CityEconomy>('GET', `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/economy`),
    /** Orders a workshop; the server decides price and timing. One key per click. */
    buildWorkshop: (gameId: string, cityId: string, tier: number, idempotencyKey: string) =>
      request<BuildResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/workshops`,
        { tier },
        { 'Idempotency-Key': idempotencyKey },
      ),
    getMilitary: (gameId: string) => request<MilitaryOverview>('GET', `/games/${encodeURIComponent(gameId)}/military`),
    getBattles: (gameId: string) => request<BattleSummary[]>('GET', `/games/${encodeURIComponent(gameId)}/battles`),
    getBattle: (gameId: string, battleId: string) =>
      request<BattleReport>('GET', `/games/${encodeURIComponent(gameId)}/battles/${encodeURIComponent(battleId)}`),
    /** Recruits a battalion; cost, civilians and strength are decided by the server. */
    recruit: (gameId: string, cityId: string, unitType: UnitType, idempotencyKey: string) =>
      request<RecruitResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/recruitments`,
        { unitType },
        { 'Idempotency-Key': idempotencyKey },
      ),
    /** Moves co-located battalions one province; entering hostile forces fights a battle. */
    move: (gameId: string, battalionIds: string[], toProvinceKey: string, idempotencyKey: string) =>
      request<MoveResult>('POST', `/games/${encodeURIComponent(gameId)}/moves`, { battalionIds, toProvinceKey }, {
        'Idempotency-Key': idempotencyKey,
      }),
    getResearch: (gameId: string) => request<ResearchOverview>('GET', `/games/${encodeURIComponent(gameId)}/research`),
    getCityInstitutions: (gameId: string, cityId: string) =>
      request<CityInstitutions>('GET', `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/institutions`),
    buildInstitution: (gameId: string, cityId: string, type: InstitutionType, idempotencyKey: string) =>
      request<InstitutionResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/institutions`,
        { type },
        { 'Idempotency-Key': idempotencyKey },
      ),
    /** Buys the next level of a track; the server checks points, prerequisites and the maximum. */
    unlockTechnology: (gameId: string, track: ResearchTrack, targetLevel: number, idempotencyKey: string) =>
      request<UnlockResult>('POST', `/games/${encodeURIComponent(gameId)}/research/technologies`, { track, targetLevel }, {
        'Idempotency-Key': idempotencyKey,
      }),
    unlockDoctrine: (gameId: string, doctrine: string, idempotencyKey: string) =>
      request<UnlockResult>('POST', `/games/${encodeURIComponent(gameId)}/research/doctrines`, { doctrine }, {
        'Idempotency-Key': idempotencyKey,
      }),
    upgradeWorkshop: (gameId: string, cityId: string, buildingId: string, idempotencyKey: string) =>
      request<WorkshopUpgradeResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/workshops/${encodeURIComponent(buildingId)}/upgrade`,
        {},
        { 'Idempotency-Key': idempotencyKey },
      ),
    getSieges: (gameId: string) => request<SiegeOverview>('GET', `/games/${encodeURIComponent(gameId)}/sieges`),
    getProvinceFortification: (gameId: string, provinceKey: string) =>
      request<ProvinceFortification>(
        'GET',
        `/games/${encodeURIComponent(gameId)}/provinces/${encodeURIComponent(provinceKey)}/fortification`,
      ),
    /** Builds the first level or raises the fortress by one; the server decides price and timing. */
    buildFortress: (gameId: string, cityId: string, targetLevel: number, idempotencyKey: string) =>
      request<FortressWorksResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/fortress`,
        { targetLevel },
        { 'Idempotency-Key': idempotencyKey },
      ),
    repairFortress: (gameId: string, cityId: string, idempotencyKey: string) =>
      request<FortressWorksResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/fortress/repair`,
        {},
        { 'Idempotency-Key': idempotencyKey },
      ),
    repairWorkshop: (gameId: string, cityId: string, buildingId: string, idempotencyKey: string) =>
      request<WorkshopRepairResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/cities/${encodeURIComponent(cityId)}/workshops/${encodeURIComponent(buildingId)}/repair`,
        {},
        { 'Idempotency-Key': idempotencyKey },
      ),
    getBattalion: (gameId: string, battalionId: string) =>
      request<BattalionDetails>('GET', `/games/${encodeURIComponent(gameId)}/battalions/${encodeURIComponent(battalionId)}`),
    /** Adds replacements from the local city; cost and civilians are decided by the server. */
    reinforce: (gameId: string, battalionId: string, soldiers: number, idempotencyKey: string) =>
      request<ReinforceResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/battalions/${encodeURIComponent(battalionId)}/reinforcements`,
        { soldiers },
        { 'Idempotency-Key': idempotencyKey },
      ),
    disband: (gameId: string, battalionId: string, idempotencyKey: string) =>
      request<DisbandResult>(
        'POST',
        `/games/${encodeURIComponent(gameId)}/battalions/${encodeURIComponent(battalionId)}/disband`,
        {},
        { 'Idempotency-Key': idempotencyKey },
      ),
    regroup: (gameId: string, name: string, battalionIds: string[], idempotencyKey: string) =>
      request<ArmyResult>('POST', `/games/${encodeURIComponent(gameId)}/armies`, { name, battalionIds }, {
        'Idempotency-Key': idempotencyKey,
      }),
    getDiplomacy: (gameId: string) => request<DiplomacyOverview>('GET', diplomacyPath(gameId)),
    getPeaceProposals: (gameId: string) => request<PeaceProposal[]>('GET', diplomacyPath(gameId, '/peace-proposals')),
    getTreaties: (gameId: string) => request<Treaty[]>('GET', diplomacyPath(gameId, '/treaties')),
    getTributes: (gameId: string) => request<TributeView[]>('GET', diplomacyPath(gameId, '/tributes')),
    getDiplomacyEvents: (gameId: string) => request<DiplomacyEvent[]>('GET', diplomacyPath(gameId, '/events')),
    /** Declares war; the server checks truces, alliances and duplicates and calls in the defender's allies. */
    declareWar: (gameId: string, targetNationId: string, idempotencyKey: string) =>
      request<WarResult>('POST', diplomacyPath(gameId, '/wars'), { targetNationId }, { 'Idempotency-Key': idempotencyKey }),
    proposeAlliance: (gameId: string, nationId: string, kind: AllianceKind, idempotencyKey: string) =>
      request<AllianceResult>('POST', diplomacyPath(gameId, '/alliances'), { nationId, kind }, {
        'Idempotency-Key': idempotencyKey,
      }),
    /** accept/reject a proposal addressed to you; terminate ends an alliance or withdraws your own proposal. */
    allianceAction: (
      gameId: string,
      allianceId: string,
      action: 'accept' | 'reject' | 'terminate',
      idempotencyKey: string,
    ) =>
      request<AllianceResult>('POST', diplomacyPath(gameId, `/alliances/${encodeURIComponent(allianceId)}/${action}`), {}, {
        'Idempotency-Key': idempotencyKey,
      }),
    /** Terms are intents; the server validates them now and again at acceptance. */
    proposePeace: (gameId: string, warId: string, terms: PeaceTerm[], idempotencyKey: string) =>
      request<ProposalResult>('POST', diplomacyPath(gameId, '/peace-proposals'), { warId, terms }, {
        'Idempotency-Key': idempotencyKey,
      }),
    /** Validation only: nothing changes on the server. */
    checkPeace: (gameId: string, warId: string, terms: PeaceTerm[]) =>
      request<TermsCheck>('POST', diplomacyPath(gameId, '/peace-proposals/check'), { warId, terms }),
    acceptPeace: (gameId: string, proposalId: string, idempotencyKey: string) =>
      request<PeaceResult>('POST', diplomacyPath(gameId, `/peace-proposals/${encodeURIComponent(proposalId)}/accept`), {}, {
        'Idempotency-Key': idempotencyKey,
      }),
    peaceAction: (gameId: string, proposalId: string, action: 'reject' | 'cancel', idempotencyKey: string) =>
      request<ProposalResult>(
        'POST',
        diplomacyPath(gameId, `/peace-proposals/${encodeURIComponent(proposalId)}/${action}`),
        {},
        { 'Idempotency-Key': idempotencyKey },
      ),
    counterPeace: (gameId: string, proposalId: string, terms: PeaceTerm[], idempotencyKey: string) =>
      request<ProposalResult>(
        'POST',
        diplomacyPath(gameId, `/peace-proposals/${encodeURIComponent(proposalId)}/counter`),
        { terms },
        { 'Idempotency-Key': idempotencyKey },
      ),
    getResult: (gameId: string) => request<GameResult>('GET', `/games/${encodeURIComponent(gameId)}/result`),
    /** The idempotency key identifies one click: retries of that click reuse it. */
    endTurn: (gameId: string, expectedTurnNumber: number, idempotencyKey: string) =>
      request<TurnResult>('POST', `/games/${encodeURIComponent(gameId)}/turn/end`, { expectedTurnNumber }, {
        'Idempotency-Key': idempotencyKey,
      }),
  }
}

export type ApiClient = ReturnType<typeof createApiClient>

function diplomacyPath(gameId: string, rest = ''): string {
  return `/games/${encodeURIComponent(gameId)}/diplomacy${rest}`
}

async function readProblem(response: Response): Promise<Problem> {
  try {
    const body = (await response.json()) as Partial<Problem>
    return { ...body, status: response.status, code: body.code ?? `HTTP_${response.status}` }
  } catch {
    return { status: response.status, code: `HTTP_${response.status}` }
  }
}

/** Player-facing text for an error from any API call. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'GAME_FULL':
        return 'This game is already full.'
      case 'INVITE_CODE_NOT_FOUND':
        return 'No game matches that invitation code. Check it and try again.'
      case 'GAME_NOT_FOUND':
        return 'This game does not exist or you are not one of its players.'
      case 'INSUFFICIENT_GOLD':
        return 'Your treasury cannot pay for this.'
      case 'WORKSHOP_LIMIT_REACHED':
        return 'This city already has the maximum number of workshops.'
      case 'PROVINCE_OCCUPIED':
        return 'This province is occupied; nothing can be built or recruited until it is liberated.'
      case 'NOT_OWNER':
        return 'Only the nation that owns this city can do that.'
      case 'INSUFFICIENT_POPULATION':
        return 'This city does not have enough civilians to raise a battalion.'
      case 'BATTALION_RECRUITED_THIS_TURN':
        return 'Battalions recruited this turn cannot move or attack until your next turn.'
      case 'BATTALION_ALREADY_MOVED':
        return 'A selected battalion has already moved this turn.'
      case 'BATTALIONS_NOT_COLOCATED':
        return 'All selected battalions must be in the same province.'
      case 'NOT_ADJACENT':
        return 'Battalions can only move to an adjacent province.'
      case 'NOT_YOUR_BATTALION':
        return 'You can only command your own battalions.'
      case 'INSTITUTION_EXISTS':
        return 'This city already has (or is building) an institution of this type.'
      case 'INSUFFICIENT_RESEARCH_POINTS':
        return 'Your nation does not have enough research points for this.'
      case 'STALE_TECHNOLOGY_LEVEL':
        return 'Your research changed in the meantime. The view has been refreshed.'
      case 'DOCTRINE_ALREADY_CHOSEN':
        return 'Your nation already follows this doctrine.'
      case 'DOCTRINE_TIER_FULL':
        return 'Your nation has already chosen its doctrine.'
      case 'DOCTRINE_NOT_FOUND':
        return 'This doctrine does not exist in this game.'
      case 'TECHNOLOGY_REQUIRED':
        return 'Tier 2 workshops need civilian technology you have not researched yet.'
      case 'WORKSHOP_NOT_UPGRADABLE':
        return 'Only an active Tier 1 workshop without another project can be upgraded.'
      case 'CITY_BESIEGED':
        return 'This city is under siege; nothing can be raised, built or repaired there until the siege ends.'
      case 'PROVINCE_BESIEGED':
        return 'Battalions cannot disband inside a province under siege.'
      case 'FORTRESS_PROJECT_IN_PROGRESS':
        return 'A fortress project is already under way in this city.'
      case 'FORTRESS_DAMAGED':
        return 'The fortress is damaged; repair it before raising it further.'
      case 'FORTRESS_NOT_DAMAGED':
      case 'BUILDING_NOT_DAMAGED':
        return 'Only damaged buildings can be repaired.'
      case 'MAX_LEVEL_REACHED':
        return 'This fortress is already at the highest level.'
      case 'STALE_FORTRESS_LEVEL':
        return 'The fortress changed in the meantime. The view has been refreshed.'
      case 'NOT_AVAILABLE_IN_RULESET':
        return 'This game was created before fortifications existed.'
      case 'BATTALION_REINFORCED_THIS_TURN':
        return 'Battalions reinforced this turn cannot move or attack until your next turn.'
      case 'BATTALION_AT_FULL_STRENGTH':
        return 'This battalion is already at full strength.'
      case 'REINFORCEMENT_EXCEEDS_MAXIMUM':
        return 'A battalion cannot exceed 1,000 soldiers.'
      case 'REINFORCEMENT_LIMIT_REACHED':
        return 'A battalion can receive at most 500 replacements per turn.'
      case 'INVALID_REINFORCEMENT_LOCATION':
        return 'Battalions can only be reinforced in a city your nation owns and controls.'
      case 'NOT_FRIENDLY_TERRITORY':
        return 'Battalions can only be disbanded in a province your nation controls.'
      case 'BATTALION_DISBANDED':
        return 'This battalion has already been disbanded.'
      case 'BATTALION_NOT_FOUND':
        return 'A selected battalion no longer exists. The view has been refreshed.'
      case 'IN_ARREARS':
        return 'Your nation is in arrears and cannot construct or recruit this round.'
      case 'TIER_LOCKED':
        return 'This workshop tier requires technology you do not have yet.'
      case 'NATION_TAKEN':
        return 'Another player has already chosen that nation.'
      case 'NATIONS_NOT_SELECTED':
        return 'Every player must choose a different nation first.'
      case 'NOT_HOST':
        return 'Only the host can start the game.'
      case 'GAME_ALREADY_STARTED':
        return 'The game has already started; nations are locked.'
      case 'NOT_ACTIVE_PLAYER':
        return 'It is not your turn.'
      case 'TURN_CHANGED':
        return 'The turn changed in the meantime. The game has been refreshed.'
      case 'NO_MILITARY_ACCESS':
        return 'Your battalions may not enter this province: you are not at war with its holder and have no military access.'
      case 'COALITION_BATTLE_UNSUPPORTED':
        return 'Battalions of several enemy nations hold this province; coalition battles are not supported yet.'
      case 'DIPLOMACY_NOT_AVAILABLE':
        return 'This game was created before diplomacy existed: every nation is permanently at war.'
      case 'CANNOT_TARGET_SELF':
        return 'Your nation cannot do that with itself.'
      case 'NATION_NOT_FOUND':
        return 'That nation is not part of this game.'
      case 'ALREADY_AT_WAR':
        return 'You are already at war with this nation.'
      case 'ALLIED_NATION':
        return 'You are allied with this nation; end the alliance before declaring war.'
      case 'FIGHTING_TOGETHER':
        return 'This nation fights on your side of a war; you cannot declare war on it.'
      case 'TRUCE_IN_EFFECT':
        return 'A truce from your last peace treaty with this nation is still in effect.'
      case 'AT_WAR':
        return 'You are at war with this nation; make peace before proposing an alliance.'
      case 'ALREADY_ALLIED':
        return 'You are already allied with this nation.'
      case 'ALLIANCE_ALREADY_PROPOSED':
        return 'An alliance proposal between you and this nation is already waiting for an answer.'
      case 'ALLIANCE_CONFLICT':
        return 'This alliance would bind you to a nation at war with one of your allies.'
      case 'ALLIANCE_NOT_PENDING':
      case 'PROPOSAL_NOT_PENDING':
        return 'This proposal is no longer pending. The view has been refreshed.'
      case 'ALLIANCE_NOT_ACTIVE':
        return 'This alliance is no longer active.'
      case 'ALLIES_AT_WAR_TOGETHER':
        return 'You and your ally fight on the same side of a war; make peace before ending the alliance.'
      case 'NOT_ALLIANCE_RECIPIENT':
      case 'NOT_PROPOSAL_RECIPIENT':
        return 'Only the nation that received this proposal can answer it.'
      case 'NOT_ALLIANCE_PROPOSER':
      case 'NOT_PROPOSAL_PROPOSER':
        return 'Only the nation that made this proposal can withdraw it.'
      case 'NOT_WAR_LEADER':
        return 'Only the two leaders of a war negotiate its peace.'
      case 'WAR_NOT_FOUND':
      case 'PEACE_PROPOSAL_NOT_FOUND':
      case 'ALLIANCE_NOT_FOUND':
        return 'This no longer exists. The view has been refreshed.'
      case 'WAR_NOT_ACTIVE':
        return 'This war has already ended.'
      case 'PEACE_TERMS_INVALID':
        return error.message
      case 'PEACE_TERMS_NO_LONGER_VALID':
        return 'The situation has changed and this treaty can no longer be settled as it stands. Reject it or make a counter-proposal.'
      case 'INVALID_TERM':
        return 'A peace term is incomplete.'
      case 'GAME_FINISHED':
        return 'The game has finished; its final state can be viewed but not changed.'
      case 'GAME_NOT_FINISHED':
        return 'The game has not finished yet.'
      case 'DISPLAY_NAME_TAKEN':
        return 'That display name is already taken.'
      case 'UNAUTHENTICATED':
        return 'Your session has expired. Please sign in again.'
      case 'VALIDATION_FAILED':
        return error.fieldErrors.map((e) => `${e.field}: ${e.message}`).join('; ') || error.message
      default:
        return error.requestId ? `${error.message} (reference ${error.requestId})` : error.message
    }
  }
  return error instanceof Error ? error.message : 'Something went wrong.'
}
