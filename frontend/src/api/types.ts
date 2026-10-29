// Shapes of the Spring Boot REST API (docs/API_DESIGN.md). Hand-written for Phase 2; to be
// replaced by types generated from the backend's OpenAPI document once it is published.

/** The caller's own account (GET/PATCH /me): never credentials or email. */
export interface PlayerProfile {
  id: string
  /** Unique public handle (lowercase); older test fixtures may omit it. */
  username?: string
  displayName: string
  emblem?: Emblem
  /** False until the player picked their handle (migrated and new accounts get a derived one). */
  usernameChosen?: boolean
  matchHistoryPublic?: boolean
  createdAt: string
  updatedAt: string
  /** Legal acceptance (Phase 15A); older test fixtures may omit it. */
  terms?: TermsStatus
}

/** Which Terms of Service / Privacy Policy versions are current and which the player accepted. */
export interface TermsStatus {
  currentTermsVersion: string
  currentPrivacyVersion: string
  /** True only when exactly the current versions were accepted. */
  accepted: boolean
  acceptedTermsVersion: string | null
  acceptedPrivacyVersion: string | null
  acceptedAt: string | null
}

export interface DeletedAccount {
  deletedAt: string
}

/** The predefined profile emblems (no uploaded images). */
export type Emblem = 'crown' | 'shield' | 'swords' | 'tower' | 'flag' | 'scholar' | 'mountain' | 'wheat'

export interface ProfileChanges {
  displayName?: string
  username?: string
  emblem?: Emblem
  matchHistoryPublic?: boolean
}

/** Who a player is to the viewer. */
export type Relation = 'YOU' | 'NONE' | 'FRIEND' | 'REQUEST_SENT' | 'REQUEST_RECEIVED' | 'BLOCKED'

export interface PersonRef {
  username: string
  displayName: string
  emblem: Emblem
}

export interface FactionRecord {
  key: string
  name: string
  played: number
  wins: number
}

export interface RecentMatch {
  gameId: string | null
  gameName: string
  mapName: string
  finishedAt: string
  finishedRound: number
  outcome: 'WIN' | 'LOSS' | 'DRAW'
  rank: number
  factionName: string
}

export interface PublicProfile {
  username: string
  displayName: string
  emblem: Emblem
  memberSince: string
  you: boolean
  relation: Relation
  matchHistoryPublic: boolean
  statistics: {
    completed: number
    wins: number
    losses: number
    draws: number
    /** Wins per 10 000 completed matches, rounded down; null without completed matches. */
    winRateBps: number | null
    favouriteFaction: FactionRecord | null
    factions: FactionRecord[]
  }
  /** Newest first; null when the owner hides them from others. */
  recentMatches: RecentMatch[] | null
}

export interface PlayerCard extends PersonRef {
  relation: Relation
}

export interface FriendRequest extends PersonRef {
  id: string
  createdAt: string
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED'
}

export interface FriendsOverview {
  friends: (PersonRef & { since: string })[]
  incoming: FriendRequest[]
  outgoing: FriendRequest[]
  blocked: PlayerCard[]
}

export type NotificationKind =
  | 'FRIEND_REQUEST'
  | 'FRIEND_ACCEPTED'
  | 'GAME_INVITATION'
  | 'INVITATION_ACCEPTED'
  | 'INVITATION_DECLINED'
  | 'MATCH_FOUND'
  | 'GAME_STARTED'
  | 'GAME_FINISHED'

export interface NotificationItem {
  id: string
  kind: NotificationKind
  actor: PersonRef | null
  gameId: string | null
  gameName: string | null
  referenceId: string | null
  createdAt: string
  read: boolean
}

export interface NotificationsView {
  unread: number
  items: NotificationItem[]
}

export interface Invitation {
  id: string
  gameId: string
  gameName: string
  mapName: string
  playerCount: number
  maxPlayers: number
  /** Whether the lobby can still be joined right now (acceptance decides). */
  joinable: boolean
  inviter: PersonRef | null
  invitee: PersonRef | null
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'CANCELLED' | 'INVALIDATED'
  reason: string | null
  createdAt: string
}

export interface QuickMatchFaction {
  key: string
  name: string
  color: string
}

export interface QuickMatch {
  state: 'NONE' | 'SEARCHING' | 'MATCHED' | 'CANCELLED' | 'EXPIRED'
  ticketId: string | null
  searchingSince: string | null
  match: {
    gameId: string
    gameName: string
    mapName: string
    yourFaction: QuickMatchFaction | null
    opponent: (PersonRef & { faction: QuickMatchFaction | null }) | null
    opened: boolean
  } | null
}

export type GameStatus = 'WAITING_FOR_PLAYERS' | 'READY' | 'IN_PROGRESS' | 'FINISHED'

export interface GameSummary {
  id: string
  name: string
  status: GameStatus
  playerCount: number
  maxPlayers: number
  yourSeat: number
  youAreHost: boolean
  createdAt: string
  /** Phase 13A: what the dashboard needs to resume a game (older fixtures may omit these). */
  mapName?: string
  yourNation?: { key: string; name: string; color: string } | null
  currentRound?: number | null
  yourTurn?: boolean
  paused?: boolean
  youAreEliminated?: boolean
  humanPlayers?: number
  computerPlayers?: number
  lastActivityAt?: string
}

export interface Participant {
  seat: number
  displayName: string
  host: boolean
  you: boolean
  nationId: string | null
  joinedAt: string
}

export interface Lobby {
  id: string
  name: string
  status: GameStatus
  mapId: string
  mapName: string
  maxPlayers: number
  playerCount: number
  availableSlots: number
  inviteCode: string
  yourSeat: number
  youAreHost: boolean
  participants: Participant[]
  nations: NationChoice[]
  /** True only for the host, once every player has a distinct nation. */
  canStart: boolean
  stateVersion: number
  createdAt: string
}

/** RFC 9457 problem body returned by the API for every error. */
export interface Problem {
  status: number
  code: string
  title?: string
  detail?: string
  requestId?: string
  errors?: { field: string; message: string }[]
}

// --- Phase 3: nations, map, game start, turns -------------------------------------------------

export interface NationChoice {
  id: string
  key: string
  name: string
  color: string
  provinceCount: number
  /** Seat of the player who chose it, or null while unchosen. */
  chosenBySeat: number | null
  chosenByYou: boolean
  /** Starting profile: POWER (balanced human nations), MAJOR, REGIONAL or MINOR. */
  tier: NationTier
  /** Only playable nations can be chosen; the others are controlled by the game. */
  playable: boolean
}

export type NationTier = 'POWER' | 'MAJOR' | 'REGIONAL' | 'MINOR'

/** A published map new games can be created on. */
export interface MapChoice {
  id: string
  key: string
  name: string
  maxPlayers: number
  nations: number
  playableNations: number
}

export type Terrain = 'PLAINS' | 'MOUNTAINS'
export type Crossing = 'NONE' | 'RIVER'

/** Static map template: geometry and topology, identical for every game on this map. */
export interface GameMap {
  id: string
  key: string
  version: number
  name: string
  viewBox: string
  provinces: MapProvince[]
  rivers: { a: string; b: string; svgPath: string }[]
  nations: {
    id: string
    key: string
    name: string
    color: string
    capitalProvinceKey: string
    startProvinceKeys: string[]
    tier?: NationTier
    playable?: boolean
  }[]
}

export interface MapProvince {
  key: string
  name: string
  terrain: Terrain
  cityName: string
  svgPath: string
  labelX: number
  /** City point inside the province (equal to the label anchor on older maps). */
  cityX?: number
  cityY?: number
  labelY: number
  neighbours: { provinceKey: string; crossing: Crossing }[]
}

/** Per-game state of a started game (members only). */
export interface GameState {
  id: string
  name: string
  status: GameStatus
  mapId: string
  round: number
  activeSeat: number
  turnNumber: number
  stateVersion: number
  yourSeat: number
  yourTurn: boolean
  /** Set while a peace conference pauses the whole game (ruleset v6): no turn passes, no normal command. */
  pausedConferenceId?: string | null
  /** Your nation has been eliminated: you can still watch, but give no more orders. */
  youAreEliminated?: boolean
  /** computer: a computer-controlled seat (Phase 11); it plays its turn automatically. */
  players: { seat: number; displayName: string; host: boolean; you: boolean; nationId: string; computer?: boolean }[]
  /**
   * control: HUMAN (a player), AI (a computer seat that plays its own turns, ruleset v7) or NPC (a passive
   * placeholder in older games, without a seat).
   */
  nations: { id: string; name: string; color: string; seat: number | null; control?: 'HUMAN' | 'AI' | 'NPC'; eliminatedRound?: number | null }[]
  provinces: ProvinceState[]
}

export interface ProvinceState {
  key: string
  /** Legal owner and military controller differ only while the province is occupied. */
  ownerNationId: string
  controllerNationId: string
  city: { id: string; name: string; population: number }
}

export interface TurnResult {
  round: number
  activeSeat: number
  turnNumber: number
  stateVersion: number
  roundCompleted: boolean
  replayed: boolean
  /** The game ended with this round (Phase 9). */
  finished: boolean
}

// --- Phase 4: economy ---------------------------------------------------------------------------
// All amounts are integer milligold (…Mg): 1 gold = 1000 mg. The server computes every value.

export type IncomeStatus = 'COLLECTIBLE' | 'OCCUPIED'
export type WorkshopStatus = 'UNDER_CONSTRUCTION' | 'ACTIVE' | 'DAMAGED' | 'UNDER_REPAIR'
export type ConstructionBlocker =
  | 'NOT_YOUR_TURN'
  | 'NOT_OWNER'
  | 'OCCUPIED'
  | 'BESIEGED'
  | 'TIER_LOCKED'
  | 'WORKSHOP_LIMIT_REACHED'
  | 'IN_ARREARS'
  | 'INSUFFICIENT_GOLD'

export interface Workshop {
  id: string
  tier: number
  status: WorkshopStatus
  orderedRound: number
  /** Round at whose end a project completes; null for starting workshops. */
  completesAfterRound: number | null
  /** First round whose income includes it; null for starting workshops. */
  operationalFromRound: number | null
  /** Round at whose end a running repair completes; null otherwise. */
  repairCompletesAfterRound: number | null
  /** The server's repair quote for a DAMAGED workshop; null otherwise. */
  repair: {
    costMg: number
    durationRounds: number
    completesAfterRound: number
    operationalFromRound: number
    allowed: boolean
    blockers: WorkshopRepairBlocker[]
  } | null
  /** Tier an upgrade in progress will reach; null when none runs. */
  upgradeTargetTier: number | null
  upgradeCompletesAfterRound: number | null
  /** The server's Tier 1 → 2 upgrade quote (v4+ games, ACTIVE Tier 1 workshops); null otherwise. */
  upgrade: {
    targetTier: number
    costMg: number
    durationRounds: number
    completesAfterRound: number
    operationalFromRound: number
    /** The city's production once the upgrade has completed. */
    potentialIncomeAfterMg: number
    allowed: boolean
    blockers: WorkshopUpgradeBlocker[]
  } | null
}

export type WorkshopUpgradeBlocker =
  | 'NOT_YOUR_TURN'
  | 'NOT_OWNER'
  | 'OCCUPIED'
  | 'BESIEGED'
  | 'TECHNOLOGY_REQUIRED'
  | 'NOT_ELIGIBLE'
  | 'NOT_AVAILABLE_IN_RULESET'
  | 'IN_ARREARS'
  | 'INSUFFICIENT_GOLD'


export type WorkshopRepairBlocker =
  | 'NOT_YOUR_TURN'
  | 'NOT_OWNER'
  | 'OCCUPIED'
  | 'BESIEGED'
  | 'NOT_DAMAGED'
  | 'NOT_AVAILABLE_IN_RULESET'
  | 'IN_ARREARS'
  | 'INSUFFICIENT_GOLD'

export interface WorkshopRepairResult {
  workshop: Workshop
  costMg: number
  treasuryMg: number
}

export interface CityEconomy {
  cityId: string
  provinceKey: string
  cityName: string
  population: number
  ownerNationId: string
  controllerNationId: string
  incomeStatus: IncomeStatus
  employment: { agricultureWorkers: number; tier1Workers: number; tier2Workers: number }
  /** What the city's workers produce, whether or not anyone collects it. */
  potentialIncome: { agricultureMg: number; tier1Mg: number; tier2Mg: number; totalMg: number }
  /** What the owner is credited at round end: 0 while occupied. */
  collectibleIncomeMg: number
  workshops: Workshop[]
  workshopCount: number
  construction: {
    tier: number
    costMg: number
    durationRounds: number
    completesAfterRound: number
    operationalFromRound: number
    allowed: boolean
    blockers: ConstructionBlocker[]
  }
  /** Next round's growth (v4+ games; null before). */
  growth: { eligible: boolean; rateBps: number; nextRoundGrowth: number; blockers: ('OCCUPIED' | 'BESIEGED')[] } | null
}

export interface EconomyOverview {
  gameId: string
  round: number
  yourTurn: boolean
  rules: {
    version: string
    agricultureMgPerWorker: number
    workshopCapacity: number
    maxWorkshopsPerCity: number
    workshopTiers: { tier: number; mgPerWorker: number; buildCostMg: number; buildDurationRounds: number; constructible: boolean }[]
  }
  nation: {
    nationId: string
    treasuryMg: number
    inArrears: boolean
    projectedGrossIncomeMg: number
    projectedUpkeepMg: number
    projectedNetMg: number
    lastRound: {
      round: number
      grossIncomeMg: number
      upkeepMg: number
      deficitMg: number
      treasuryAfterMg: number
      inArrears: boolean
    } | null
  }
  cities: CityEconomy[]
}

export interface BuildResult {
  workshop: Workshop
  treasuryMg: number
  city: CityEconomy
}

// --- Phase 5: military --------------------------------------------------------------------------
// Strengths, costs, eligibility and battle results all come from the server; the client only
// submits intents (unit type, battalion ids, destination).

export type UnitType = 'INFANTRY' | 'CAVALRY' | 'ARTILLERY'
export type Eligibility = 'READY' | 'RECRUITED_THIS_TURN' | 'ALREADY_MOVED' | 'REINFORCED_THIS_TURN'
export type RecruitBlocker =
  | 'NOT_YOUR_TURN'
  | 'NOT_OWNER'
  | 'OCCUPIED'
  | 'BESIEGED'
  | 'IN_ARREARS'
  | 'INSUFFICIENT_POPULATION'
  | 'INSUFFICIENT_GOLD'

export interface Battalion {
  id: string
  nationId: string
  unitType: UnitType
  manpower: number
  maxManpower: number
  provinceKey: string
  armyId: string | null
  originCityId: string
  recruitedRound: number
  /** For battalions you command (your own, or an ally's attached to you); null otherwise. */
  eligibility: Eligibility | null
  /** The ally commanding this battalion while it is attached (Phase 10), or null. */
  commanderNationId?: string | null
  upkeepMg: number
}

export interface Army {
  id: string
  nationId: string
  name: string
  provinceKey: string | null
  battalionIds: string[]
}

export interface RecruitOption {
  unitType: UnitType
  costMg: number
  upkeepMg: number
  populationCost: number
  populationAfter: number
  allowed: boolean
  blockers: RecruitBlocker[]
}

export interface CityRecruitment {
  cityId: string
  provinceKey: string
  cityName: string
  population: number
  options: RecruitOption[]
}

export interface BattleSummary {
  id: string
  round: number
  turnNumber: number
  provinceKey: string
  attackerNationId: string
  defenderNationId: string
  winner: 'ATTACKER' | 'DEFENDER'
  attackerLosses: number
  defenderLosses: number
  createdAt: string
}

export interface MilitaryOverview {
  gameId: string
  round: number
  turnNumber: number
  yourTurn: boolean
  nationId: string
  rules: {
    version: string
    battalionManpower: number
    minResidualPopulation: number
    units: { unitType: UnitType; recruitCostMg: number; upkeepMg: number }[]
  }
  battalions: Battalion[]
  armies: Army[]
  upkeep: { totalMg: number; byUnitTypeMg: Record<UnitType, number>; battalions: number; soldiers: number }
  recruitment: CityRecruitment[]
  recentBattles: BattleSummary[]
  modifiers: {
    militaryLevel: number
    doctrines: string[]
    damageBonusBps: Partial<Record<UnitType, number>>
    baseRiverAttackMultiplier: number
    riverAttackMultiplier: number
    baseMountainAttackMultiplier: number
    mountainAttackMultiplier: number
    upkeepReductionBps: number
    siegeArtilleryBonusBps: number
  }
}

export interface SideTechnology {
  militaryLevel: number
  doctrines: string[]
  damageBonusBps: Partial<Record<UnitType, number>>
}

export type BattleFate = 'SURVIVED' | 'RETREATED' | 'DESTROYED'

export interface BattleSide {
  protection: number
  artilleryEffectiveness: number
  flankBonus: number
  terrainMultiplier: number
  /** Damage this side dealt in the phase. */
  damage: number
  /** Men this side lost in the phase, by unit type. */
  losses: Partial<Record<UnitType, number>>
  manpowerAfter: number
}

export interface BattleReport {
  id: string
  round: number
  turnNumber: number
  provinceKey: string
  attackerNationId: string
  defenderNationId: string
  winner: 'ATTACKER' | 'DEFENDER'
  rulesetVersion: string
  createdAt: string
  details: {
    fromProvinceKey: string
    provinceKey: string
    river: boolean
    mountains: boolean
    attackers: BattleParticipant[]
    defenders: BattleParticipant[]
    phases: { number: number; attacker: BattleSide; defender: BattleSide }[]
    attackerInitial: number
    defenderInitial: number
    attackerLosses: number
    defenderLosses: number
    broken: 'ATTACKER' | 'DEFENDER' | null
    winner: 'ATTACKER' | 'DEFENDER'
    retreatProvinceKey: string | null
    defendersDestroyed: boolean
    /** Whether each side reached the break threshold; null in reports stored before Phase 6. */
    attackerBroke: boolean | null
    defenderBroke: boolean | null
    /** Each side's technology and doctrine at the time (null before Phase 8). */
    attackerModifiers?: SideTechnology | null
    defenderModifiers?: SideTechnology | null
    /** Coalition battles (Phase 10): one entry per nation, each with its own modifiers and losses. */
    nations?: BattleNation[]
  }
}

export interface BattleNation {
  nationId: string
  side: 'ATTACKER' | 'DEFENDER'
  manpowerBefore: number
  losses: number
  retreatProvinceKey: string | null
  destroyed: boolean
  modifiers: SideTechnology | null
}

export interface BattleParticipant {
  battalionId: string
  unitType: UnitType
  manpowerBefore: number
  losses: number
  manpowerAfter: number
  fate: BattleFate
  /** The battalion's nation (coalition battles; absent in older reports). */
  nationId?: string | null
}

export interface RecruitResult {
  battalion: Battalion
  treasuryMg: number
  cityPopulation: number
}

export interface MoveResult {
  outcome: 'MOVED' | 'ATTACK_WON' | 'ATTACK_REPELLED'
  toProvinceKey: string
  battalions: Battalion[]
  battle: BattleReport | null
}

export interface ArmyResult {
  army: Army
}

// --- Phase 6: reinforcement and disbanding -------------------------------------------------------

export type ReinforceBlocker =
  | 'NOT_YOUR_TURN'
  | 'AT_FULL_STRENGTH'
  | 'EXCEEDS_MAXIMUM_MANPOWER'
  | 'TURN_LIMIT_REACHED'
  | 'NOT_OWNED_CITY'
  | 'OCCUPIED'
  | 'BESIEGED'
  | 'IN_ARREARS'
  | 'INSUFFICIENT_POPULATION'
  | 'INSUFFICIENT_GOLD'

export type DisbandBlocker = 'NOT_YOUR_TURN' | 'NOT_FRIENDLY_TERRITORY' | 'BESIEGED'

export interface SoldierReturn {
  cityId: string
  cityName: string
  provinceKey: string
  soldiers: number
  /** The origin city is no longer yours: these soldiers settle in the local city instead. */
  fallback: boolean
}

/** One of your battalions in detail, with the server's reinforcement quote and disband preview. */
export interface BattalionDetails {
  battalion: Battalion
  cohorts: { originCityId: string; cityName: string; provinceKey: string; soldiers: number }[]
  reinforcement: {
    cityId: string
    cityName: string
    cityPopulation: number
    missing: number
    turnAllowance: number
    maxSoldiers: number
    /** Price of a full battalion; replacements cost it pro rata, rounded up. */
    recruitCostMg: number
    battalionManpower: number
    costOfMaxMg: number
    upkeepAfterMaxMg: number
    allowed: boolean
    blockers: ReinforceBlocker[]
  }
  disband: {
    allowed: boolean
    blockers: DisbandBlocker[]
    soldiers: number
    returns: SoldierReturn[]
    upkeepSavedMg: number
  }
}

export interface ReinforceResult {
  battalion: Battalion
  soldiersAdded: number
  costMg: number
  treasuryMg: number
  cityId: string
  cityPopulation: number
}

export interface DisbandResult {
  battalionId: string
  unitType: UnitType
  soldiersReturned: number
  returns: SoldierReturn[]
  upkeepSavedMg: number
}

// --- Phase 7: fortifications and sieges ----------------------------------------------------------

export type FortressBlocker =
  | 'NOT_YOUR_TURN'
  | 'NOT_OWNER'
  | 'OCCUPIED'
  | 'BESIEGED'
  | 'PROJECT_IN_PROGRESS'
  | 'FORTRESS_DAMAGED'
  | 'NOT_DAMAGED'
  | 'MAX_LEVEL_REACHED'
  | 'NOT_AVAILABLE_IN_RULESET'
  | 'IN_ARREARS'
  | 'INSUFFICIENT_GOLD'

export interface Fortification {
  cityId: string
  provinceKey: string
  cityName: string
  /** 0 only while the first level is under construction. */
  level: number
  /** What a siege must overcome: the level while ACTIVE, 0 while DAMAGED. */
  effectiveLevel: number
  status: 'ACTIVE' | 'DAMAGED'
  integrity: number
  maxIntegrity: number
  project: { kind: 'UPGRADE' | 'REPAIR'; targetLevel: number | null; completesAfterRound: number; operationalFromRound: number } | null
}

export interface SiegeSummary {
  id: string
  provinceKey: string
  cityName: string
  cityId: string
  besiegerNationId: string
  defenderNationId: string
  status: 'ACTIVE' | 'SUCCEEDED' | 'LIFTED'
  startedRound: number
  endedRound: number | null
  endReason: 'CAPTURED' | 'BESIEGERS_GONE' | null
  fortressLevel: number
  requiredProgress: number
  progress: number
  /** Active sieges only: progress the besiegers present now would add at the next round end. */
  ratePerRound: number | null
  artilleryManpower: number | null
  /** The besieger's Siege Engineering bonus on artillery progress, in basis points. */
  artilleryBonusBps: number | null
  estimatedRoundsLeft: number | null
  nextAttrition: number | null
  populationLost: number
  artillerySiege: boolean | null
  damagedBuildingId: string | null
}

export interface SiegeOverview {
  gameId: string
  round: number
  fortificationsAvailable: boolean
  fortifications: Fortification[]
  activeSieges: SiegeSummary[]
  recentSieges: SiegeSummary[]
}

export interface FortressWorksOption {
  targetLevel: number
  costMg: number
  durationRounds: number
  completesAfterRound: number
  operationalFromRound: number
  allowed: boolean
  blockers: FortressBlocker[]
}

export interface ProvinceFortification {
  provinceKey: string
  cityId: string
  cityName: string
  population: number
  ownerNationId: string
  controllerNationId: string
  fortificationsAvailable: boolean
  fortification: Fortification | null
  activeSiege: SiegeSummary | null
  upgrade: FortressWorksOption | null
  repair: FortressWorksOption | null
}

export interface FortressWorksResult {
  fortification: Fortification
  costMg: number
  treasuryMg: number
}

// --- Phase 8: research, institutions, doctrines ---------------------------------------------------

export type ResearchPool = 'MILITARY' | 'CIVILIAN' | 'DOCTRINE'
export type InstitutionType = 'MILITARY_ACADEMY' | 'UNIVERSITY' | 'BARRACKS'
export type ResearchTrack = 'MILITARY' | 'CIVILIAN'

export interface Institution {
  id: string
  cityId: string
  cityName: string
  provinceKey: string
  type: InstitutionType
  pool: ResearchPool
  status: 'UNDER_CONSTRUCTION' | 'ACTIVE'
  completesAfterRound: number
  operationalFromRound: number
  pointsPerRound: number
  upkeepMg: number
  /** Adds points at the next round end (ACTIVE, city owned and controlled). */
  producing: boolean
}

export interface ResearchQuote {
  targetLevel: number
  costPoints: number
  allowed: boolean
  blockers: string[]
}

export interface ResearchTrackView {
  track: ResearchTrack
  level: number
  maxLevel: number
  levels: {
    level: number
    costPoints: number
    unlocked: boolean
    damageBonusBps: Partial<Record<UnitType, number>>
    unlocksWorkshopTier: number | null
    growthBonusBps: number
  }[]
  next: ResearchQuote | null
}

export interface Doctrine {
  key: string
  tier: number
  costPoints: number
  riverPenaltyReductionBps: number
  mountainPenaltyReductionBps: number
  siegeArtilleryBonusBps: number
  militaryUpkeepReductionBps: number
  chosen: boolean
  allowed: boolean
  blockers: string[]
}

export interface ResearchOverview {
  gameId: string
  round: number
  yourTurn: boolean
  /** False for games created before research existed (ruleset v1–v3). */
  available: boolean
  nationId: string
  pools: { pool: ResearchPool; points: number; perRound: number; lastRound: number | null }[]
  military: ResearchTrackView | null
  civilian: ResearchTrackView | null
  doctrines: Doctrine[]
  institutions: Institution[]
  effects: {
    militaryLevel: number
    civilianLevel: number
    damageBonusBps: Partial<Record<UnitType, number>>
    riverPenaltyReductionBps: number
    mountainPenaltyReductionBps: number
    siegeArtilleryBonusBps: number
    militaryUpkeepReductionBps: number
    growthBonusBps: number
    highestUnlockedWorkshopTier: number
    doctrines: string[]
  } | null
}

export interface InstitutionOption {
  type: InstitutionType
  pool: ResearchPool
  costMg: number
  durationRounds: number
  completesAfterRound: number
  operationalFromRound: number
  pointsPerRound: number
  upkeepMg: number
  allowed: boolean
  blockers: string[]
  existing: Institution | null
}

export interface CityInstitutions {
  cityId: string
  cityName: string
  provinceKey: string
  available: boolean
  options: InstitutionOption[]
}

export interface InstitutionResult {
  institution: Institution
  costMg: number
  treasuryMg: number
}

export interface UnlockResult {
  kind: 'TECHNOLOGY' | 'DOCTRINE'
  subject: string
  level: number | null
  costPoints: number
  research: ResearchOverview
}

export interface WorkshopUpgradeResult {
  workshop: Workshop
  costMg: number
  treasuryMg: number
}

// --- Phase 9: diplomacy, war, peace and match completion ------------------------------------------
// Wars, alliances, treaties, tributes and events are public; proposals are only ever returned to
// their two nations. War score, settlement results and tribute amounts are always the server's.

export type AllianceKind = 'NORMAL' | 'DEFENSIVE'
export type RelationStatus = 'SELF' | 'WAR' | 'ALLIED' | 'PEACE'
export type WarSide = 'ATTACKER' | 'DEFENDER'
export type ProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED' | 'SUPERSEDED'
export type TermType = 'PROVINCE' | 'GOLD' | 'TRIBUTE' | 'POPULATION'

export interface DiplomacyRules {
  version: string
  truceRounds: number
  proposalExpiryRounds: number
  maxPeaceTerms: number
  tributeRateBps: number
  tributeRounds: number
  populationMinPeople: number
  populationMaxPeople: number
  populationMaxShareBps: number
  populationMinResidual: number
  reparationsMaxMg: number
  maxScore: number
  victoryMaxRounds: number
  victoryProvincePoints: number
  victoryPointsPerThousandPopulation: number
  normalAllianceGrantsAccess: boolean
  defensiveAllianceGrantsAccess: boolean
  coBelligerentAccess: boolean
  /** Largest gift, or null when the ruleset has no gifts. */
  giftMaxMg?: number | null
  giftsToEnemies?: boolean
  features?: DiplomacyFeatures
  victoryPointsPerHundredGold?: number
  domination?: boolean
}

export interface DiplomacyFeatures {
  coalitionWarfare: boolean
  attachments: boolean
  callsToArms: boolean
  peaceConferences: boolean
  separatePeace: boolean
  capitalsCedable: boolean
  elimination: boolean
}

export interface NationRelation {
  nationId: string
  name: string
  color: string
  you: boolean
  status: RelationStatus
  allianceKind: AllianceKind | null
  allianceId: string | null
  warId: string | null
  truceUntilRound: number | null
  weMayEnter: boolean
  theyMayEnter: boolean
  warBlockers: string[]
  allianceBlockers: string[]
}

export interface SideScore {
  battles: number
  sieges: number
  occupation: number
  total: number
  battlesWon: number
  siegesWon: number
  occupiedEnemyPopulation: number
  enemyPopulation: number
}

export interface WarView {
  id: string
  status: 'ACTIVE' | 'ENDED'
  attackerLeaderId: string
  defenderLeaderId: string
  /** Everyone who fought; leftRound is set for nations that left by separate peace or elimination. */
  participants: {
    nationId: string
    name: string
    side: WarSide
    joinReason: string
    joinedRound: number
    leftRound?: number | null
    leftReason?: string | null
  }[]
  declaredRound: number
  endedRound: number | null
  endReason: string | null
  durationRounds: number
  score: { attacker: SideScore; defender: SideScore; net: number; maxScore: number }
  yourSide: WarSide | null
  youLead: boolean
}

export interface AllianceView {
  id: string
  kind: AllianceKind
  nationA: string
  nationB: string
  proposerNationId: string
  status: 'PROPOSED' | 'ACTIVE' | 'REJECTED' | 'CANCELLED' | 'EXPIRED' | 'ENDED'
  proposedRound: number
  acceptedRound: number | null
  endedRound: number | null
  endReason: string | null
  expiresAfterRound: number
  grantsMilitaryAccess: boolean
  canAccept: boolean
  canEnd: boolean
  endBlockers: string[]
}

export interface PeaceTerm {
  type: TermType
  fromNationId: string
  toNationId: string
  provinceKey?: string | null
  amountMg?: number | null
  sourceCityId?: string | null
  destinationCityId?: string | null
  people?: number | null
  /** Shown for tribute terms; set by the ruleset, never by the client. */
  tributeRateBps?: number | null
  tributeRounds?: number | null
}

export interface PeaceProposal {
  id: string
  warId: string
  proposerNationId: string
  recipientNationId: string
  status: ProposalStatus
  terms: PeaceTerm[]
  createdRound: number
  createdTurn: number
  createdStateVersion: number
  respondedRound: number | null
  counterOfId: string | null
  expiresAfterRound: number
  incoming: boolean
  /** What would stop this proposal from being settled against the current state. */
  violations: string[]
  createdAt: string
}

export interface Treaty {
  id: string
  warId: string
  proposalId: string
  signedRound: number
  signedTurn: number
  terms: PeaceTerm[]
  truceUntilRound: number
}

export interface TributeView {
  id: string
  treatyId: string
  payerNationId: string
  receiverNationId: string
  rateBps: number
  roundsTotal: number
  roundsCharged: number
  firstRound: number
  totalPaidMg: number
  status: 'ACTIVE' | 'COMPLETED' | 'CANCELLED'
  endReason: string | null
  endedRound: number | null
  payments: { round: number; grossIncomeMg: number; dueMg: number; paidMg: number }[]
}

export interface DiplomacyEvent {
  id: string
  round: number
  kind: string
  actorNationId: string | null
  targetNationId: string | null
  warId: string | null
  details: Record<string, unknown>
  createdAt: string
}

export interface DiplomacyOverview {
  gameId: string
  /** False for games created before diplomacy existed: every nation is permanently at war there. */
  available: boolean
  rulesetVersion: string
  round: number
  yourTurn: boolean
  yourNationId: string
  rules: DiplomacyRules | null
  nations: NationRelation[]
  wars: WarView[]
  alliances: AllianceView[]
  proposals: PeaceProposal[]
  treaties: Treaty[]
  tributes: TributeView[]
  events: DiplomacyEvent[]
  /** Gold gifts, newest first (public history). */
  gifts: Gift[]
  /** Defensive calls to arms (ruleset v6). */
  calls?: CallToArms[]
  /** Peace conferences (ruleset v6). */
  conferences?: Conference[]
}

export type CallStatus = 'PENDING' | 'HONORED' | 'DECLINED' | 'CANCELLED'

export interface CallToArms {
  id: string
  warId: string
  callerNationId: string
  allyNationId: string
  status: CallStatus
  createdRound: number
  answeredRound: number | null
  canAnswer: boolean
}

export type ConferenceKind = 'GENERAL' | 'SEPARATE'
export type ConferenceStatus = 'REQUESTED' | 'ACTIVE' | 'CONCLUDED' | 'REJECTED' | 'CANCELLED'

export interface Conference {
  id: string
  warId: string
  kind: ConferenceKind
  requesterNationId: string
  counterpartNationId: string
  status: ConferenceStatus
  requestedRound: number
  currentRevision: number
  /** The current revision's terms. */
  terms: PeaceTerm[]
  /** Every nation whose approval the current revision needs; decision null = not yet decided. */
  approvals: { nationId: string; name: string; npc: boolean; decision: 'APPROVED' | 'REJECTED' | null }[]
  revisions: { revision: number; authorNationId: string; termCount: number; createdAt: string }[]
  /** Why the current revision could not be settled now (empty = it could). */
  violations: string[]
  youArePrincipal: boolean
  canAnswer: boolean
  canCancel: boolean
  canRevise: boolean
  youMustDecide: boolean
}

export interface CallResult {
  call: CallToArms
}

export interface ConferenceResult {
  conference: Conference
  /** Set when this command settled the treaty. */
  treaty: Treaty | null
  withdrawals: Withdrawal[]
}

export interface AttachmentResult {
  battalions: Battalion[]
}

export interface Gift {
  id: string
  senderNationId: string
  recipientNationId: string
  amountMg: number
  round: number
  createdAt: string
}

export interface GiftResult {
  gift: Gift
  /** The sender's treasury after the gift. */
  treasuryMg: number
}

export interface Withdrawal {
  battalionId: string
  nationId: string
  fromProvinceKey: string
  toProvinceKey: string
}

export interface WarResult {
  war: WarView
  joinedDefenders: string[]
}

export interface AllianceResult {
  alliance: AllianceView
  withdrawals: Withdrawal[]
}

export interface ProposalResult {
  proposal: PeaceProposal
}

export interface PeaceResult {
  treaty: Treaty
  war: WarView
  withdrawals: Withdrawal[]
  controlReturned: string[]
}

export interface TermsCheck {
  valid: boolean
  violations: { term: number; code: string }[]
}

export interface GameResult {
  gameId: string
  gameName: string
  finishedRound: number
  reason: string
  outcome: 'WINNER' | 'DRAW'
  winnerNationId: string | null
  winnerName: string | null
  rulesetVersion: string
  finishedAt: string
  standings: {
    rank: number
    nationId: string
    nationName: string
    color: string
    playerName: string
    you: boolean
    score: number
    ownedProvinces: number
    ownedPopulation: number
    treasuryMg: number
  }[]
  statistics: Record<string, number>
}
