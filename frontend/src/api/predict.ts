/**
 * Instant feedback (docs/OPEN_QUESTIONS.md D-UX1, extended 2026-11-21): what a command will do to the
 * views the client already holds, computed only from those views. Nothing here uses information the
 * player cannot see (an opponent's research or treasury, a battle's outcome). The server stays the
 * only authority: a prediction is rolled back if the command is refused, and replaced by the server's
 * answer and the refetch that follows. Each function is pure and returns a new view.
 */
import type {
  AllianceKind,
  AllianceView,
  Army,
  Battalion,
  BattalionDetails,
  CallToArms,
  CityInstitutions,
  Conference,
  ConferenceKind,
  DiplomacyOverview,
  EconomyOverview,
  GameState,
  InstitutionType,
  MilitaryOverview,
  NationRelation,
  PeaceProposal,
  PeaceTerm,
  ProvinceFortification,
  ResearchOverview,
  ResearchTrack,
  SoldierReturn,
  UnitType,
  WarView,
  Workshop,
} from './types'

/** Ids of rows the server has not created yet; replaced by the real ids when it answers. */
export const PENDING_ID_PREFIX = 'pending-'

export function pendingId(seed: string): string {
  return `${PENDING_ID_PREFIX}${seed}`
}

export function isPendingId(id: string): boolean {
  return id.startsWith(PENDING_ID_PREFIX)
}

// --- treasury and population ---------------------------------------------------------------------

export function spendGold(economy: EconomyOverview, amountMg: number): EconomyOverview {
  return { ...economy, nation: { ...economy.nation, treasuryMg: economy.nation.treasuryMg - amountMg } }
}

export function receiveGold(economy: EconomyOverview, amountMg: number): EconomyOverview {
  return { ...economy, nation: { ...economy.nation, treasuryMg: economy.nation.treasuryMg + amountMg } }
}

export function changeCityPopulation(economy: EconomyOverview, cityId: string, delta: number): EconomyOverview {
  return {
    ...economy,
    cities: economy.cities.map((c) => (c.cityId === cityId ? { ...c, population: c.population + delta } : c)),
  }
}

export function changeStatePopulation(state: GameState, cityId: string, delta: number): GameState {
  return {
    ...state,
    provinces: state.provinces.map((p) =>
      p.city.id === cityId ? { ...p, city: { ...p.city, population: p.city.population + delta } } : p,
    ),
  }
}

// --- military --------------------------------------------------------------------------------------

/** The recruitment cost and civilians of a unit in a city, as the military view quotes them. */
export function recruitQuote(military: MilitaryOverview, cityId: string, unitType: UnitType) {
  const city = military.recruitment.find((c) => c.cityId === cityId)
  const option = city?.options.find((o) => o.unitType === unitType)
  return city && option ? { city, option } : null
}

export function recruit(
  military: MilitaryOverview,
  input: { cityId: string; unitType: UnitType; battalionId: string },
): MilitaryOverview {
  const quote = recruitQuote(military, input.cityId, input.unitType)
  if (!quote) {
    return military
  }
  const manpower = military.rules.battalionManpower
  const battalion: Battalion = {
    id: input.battalionId,
    nationId: military.nationId,
    unitType: input.unitType,
    manpower,
    maxManpower: manpower,
    provinceKey: quote.city.provinceKey,
    armyId: null,
    originCityId: input.cityId,
    recruitedRound: military.round,
    eligibility: 'RECRUITED_THIS_TURN',
    commanderNationId: null,
    upkeepMg: quote.option.upkeepMg,
  }
  const population = quote.city.population - quote.option.populationCost
  return {
    ...military,
    battalions: [...military.battalions, battalion],
    upkeep: { ...military.upkeep, battalions: military.upkeep.battalions + 1, soldiers: military.upkeep.soldiers + manpower },
    recruitment: military.recruitment.map((c) =>
      c.cityId !== input.cityId
        ? c
        : {
            ...c,
            population,
            options: c.options.map((o) => {
              const after = population - o.populationCost
              const tooFew = after < military.rules.minResidualPopulation
              return {
                ...o,
                populationAfter: after,
                allowed: o.allowed && !tooFew,
                blockers: tooFew && !o.blockers.includes('INSUFFICIENT_POPULATION') ? [...o.blockers, 'INSUFFICIENT_POPULATION'] : o.blockers,
              }
            }),
          },
    ),
  }
}

/** The server's battalion replaces the pending one. */
export function confirmRecruit(military: MilitaryOverview, pending: string, battalion: Battalion): MilitaryOverview {
  const withoutPending = military.battalions.filter((b) => b.id !== pending && b.id !== battalion.id)
  return { ...military, battalions: [...withoutPending, battalion] }
}

/** Replacements cost the full battalion's price pro rata, rounded up (BattalionDetails.reinforcement). */
export function reinforcementCost(details: BattalionDetails, soldiers: number): number {
  const { recruitCostMg, battalionManpower } = details.reinforcement
  return Math.ceil((recruitCostMg * soldiers) / battalionManpower)
}

export function reinforce(military: MilitaryOverview, battalionId: string, soldiers: number): MilitaryOverview {
  return {
    ...military,
    battalions: military.battalions.map((b) =>
      b.id === battalionId ? { ...b, manpower: b.manpower + soldiers, eligibility: 'REINFORCED_THIS_TURN' } : b,
    ),
    upkeep: { ...military.upkeep, soldiers: military.upkeep.soldiers + soldiers },
  }
}

export function reinforceDetails(details: BattalionDetails, soldiers: number): BattalionDetails {
  const r = details.reinforcement
  const city = r.cityId
  const cohorts = details.cohorts.some((c) => c.originCityId === city)
    ? details.cohorts.map((c) => (c.originCityId === city ? { ...c, soldiers: c.soldiers + soldiers } : c))
    : [...details.cohorts, { originCityId: city, cityName: r.cityName, provinceKey: details.battalion.provinceKey, soldiers }]
  const maxSoldiers = Math.max(0, r.maxSoldiers - soldiers)
  return {
    ...details,
    battalion: { ...details.battalion, manpower: details.battalion.manpower + soldiers, eligibility: 'REINFORCED_THIS_TURN' },
    cohorts,
    reinforcement: {
      ...r,
      cityPopulation: r.cityPopulation - soldiers,
      missing: r.missing - soldiers,
      turnAllowance: r.turnAllowance - soldiers,
      maxSoldiers,
      allowed: r.allowed && maxSoldiers > 0,
    },
    disband: { ...details.disband, soldiers: details.disband.soldiers + soldiers },
  }
}

export function disband(military: MilitaryOverview, battalionId: string): MilitaryOverview {
  const battalion = military.battalions.find((b) => b.id === battalionId)
  if (!battalion) {
    return military
  }
  return {
    ...withoutEmptyArmies(
      {
        ...military,
        battalions: military.battalions.filter((b) => b.id !== battalionId),
        armies: military.armies.map((a) => ({ ...a, battalionIds: a.battalionIds.filter((id) => id !== battalionId) })),
      },
      battalion.armyId ? [battalion.armyId] : [],
    ),
    upkeep: {
      ...military.upkeep,
      battalions: military.upkeep.battalions - 1,
      soldiers: military.upkeep.soldiers - battalion.manpower,
    },
  }
}

/** Disbanded soldiers go home as the battalion's detail view previewed it. */
export function returnSoldiers<T>(view: T, returns: SoldierReturn[], change: (view: T, cityId: string, delta: number) => T): T {
  return returns.reduce((v, r) => change(v, r.cityId, r.soldiers), view)
}

export function formArmy(
  military: MilitaryOverview,
  input: { armyId: string; name: string; battalionIds: string[] },
): MilitaryOverview {
  const members = military.battalions.filter((b) => input.battalionIds.includes(b.id))
  if (members.length === 0) {
    return military
  }
  const previous = [...new Set(members.map((b) => b.armyId).filter((id): id is string => id !== null))]
  const army: Army = {
    id: input.armyId,
    nationId: military.nationId,
    name: input.name.trim(),
    provinceKey: members[0].provinceKey,
    battalionIds: members.map((b) => b.id).sort(),
  }
  return withoutEmptyArmies(
    {
      ...military,
      battalions: military.battalions.map((b) => (input.battalionIds.includes(b.id) ? { ...b, armyId: input.armyId } : b)),
      armies: [
        ...military.armies.map((a) => ({ ...a, battalionIds: a.battalionIds.filter((id) => !input.battalionIds.includes(id)) })),
        army,
      ],
    },
    previous,
  )
}

/** The server's army replaces the pending one. */
export function confirmArmy(military: MilitaryOverview, pending: string, army: Army): MilitaryOverview {
  return {
    ...military,
    armies: [...military.armies.filter((a) => a.id !== pending && a.id !== army.id), army],
    battalions: military.battalions.map((b) => (b.armyId === pending ? { ...b, armyId: army.id } : b)),
  }
}

export function attach(military: MilitaryOverview, battalionIds: string[], commanderNationId: string): MilitaryOverview {
  const previous = military.battalions
    .filter((b) => battalionIds.includes(b.id) && b.armyId !== null)
    .map((b) => b.armyId as string)
  return withoutEmptyArmies(
    {
      ...military,
      battalions: military.battalions.map((b) =>
        battalionIds.includes(b.id) ? { ...b, commanderNationId, armyId: null, eligibility: null } : b,
      ),
      armies: military.armies.map((a) => ({ ...a, battalionIds: a.battalionIds.filter((id) => !battalionIds.includes(id)) })),
    },
    previous,
  )
}

/** Released battalions return to their owner's command; their eligibility arrives with the answer. */
export function release(military: MilitaryOverview, battalionIds: string[]): MilitaryOverview {
  return {
    ...military,
    battalions: military.battalions.map((b) => (battalionIds.includes(b.id) ? { ...b, commanderNationId: null } : b)),
  }
}

export function withBattalions(military: MilitaryOverview, changed: Battalion[]): MilitaryOverview {
  const byId = new Map(changed.map((b) => [b.id, b]))
  const kept = military.battalions.map((b) => byId.get(b.id) ?? b)
  const added = changed.filter((b) => !military.battalions.some((existing) => existing.id === b.id))
  return { ...military, battalions: [...kept, ...added] }
}

/** Battalions that withdrew because access ended (an alliance or a war ended). */
export function withdraw(military: MilitaryOverview, moves: { battalionId: string; toProvinceKey: string }[]): MilitaryOverview {
  const to = new Map(moves.map((m) => [m.battalionId, m.toProvinceKey]))
  return {
    ...military,
    battalions: military.battalions.map((b) => (to.has(b.id) ? { ...b, provinceKey: to.get(b.id)! } : b)),
  }
}

function withoutEmptyArmies(military: MilitaryOverview, candidates: string[]): MilitaryOverview {
  const empty = new Set(candidates.filter((id) => !military.battalions.some((b) => b.armyId === id)))
  return empty.size === 0 ? military : { ...military, armies: military.armies.filter((a) => !empty.has(a.id)) }
}

// --- economy ---------------------------------------------------------------------------------------

export function buildWorkshop(economy: EconomyOverview, cityId: string, workshopId: string): EconomyOverview {
  const city = economy.cities.find((c) => c.cityId === cityId)
  if (!city) {
    return economy
  }
  const c = city.construction
  const workshop: Workshop = {
    id: workshopId,
    tier: c.tier,
    status: 'UNDER_CONSTRUCTION',
    orderedRound: economy.round,
    completesAfterRound: c.completesAfterRound,
    operationalFromRound: c.operationalFromRound,
    repairCompletesAfterRound: null,
    repair: null,
    upgradeTargetTier: null,
    upgradeCompletesAfterRound: null,
    upgrade: null,
  }
  const count = city.workshopCount + 1
  const full = count >= economy.rules.maxWorkshopsPerCity
  return spendGold(
    {
      ...economy,
      cities: economy.cities.map((x) =>
        x.cityId !== cityId
          ? x
          : {
              ...x,
              workshops: [...x.workshops, workshop],
              workshopCount: count,
              construction: full
                ? { ...c, allowed: false, blockers: c.blockers.includes('WORKSHOP_LIMIT_REACHED') ? c.blockers : [...c.blockers, 'WORKSHOP_LIMIT_REACHED'] }
                : c,
            },
      ),
    },
    c.costMg,
  )
}

export function upgradeWorkshop(economy: EconomyOverview, cityId: string, workshopId: string): EconomyOverview {
  const workshop = economy.cities.find((c) => c.cityId === cityId)?.workshops.find((w) => w.id === workshopId)
  const upgrade = workshop?.upgrade
  if (!workshop || !upgrade) {
    return economy
  }
  return spendGold(
    replaceWorkshop(economy, cityId, {
      ...workshop,
      upgradeTargetTier: upgrade.targetTier,
      upgradeCompletesAfterRound: upgrade.completesAfterRound,
      upgrade: null,
    }),
    upgrade.costMg,
  )
}

export function repairWorkshop(economy: EconomyOverview, cityId: string, workshopId: string): EconomyOverview {
  const workshop = economy.cities.find((c) => c.cityId === cityId)?.workshops.find((w) => w.id === workshopId)
  const repair = workshop?.repair
  if (!workshop || !repair) {
    return economy
  }
  return spendGold(
    replaceWorkshop(economy, cityId, {
      ...workshop,
      status: 'UNDER_REPAIR',
      repairCompletesAfterRound: repair.completesAfterRound,
      repair: null,
    }),
    repair.costMg,
  )
}

export function replaceWorkshop(economy: EconomyOverview, cityId: string, workshop: Workshop, replacing = workshop.id): EconomyOverview {
  return {
    ...economy,
    cities: economy.cities.map((c) =>
      c.cityId !== cityId ? c : { ...c, workshops: c.workshops.map((w) => (w.id === replacing ? workshop : w)) },
    ),
  }
}

export function setTreasury(economy: EconomyOverview, treasuryMg: number): EconomyOverview {
  return { ...economy, nation: { ...economy.nation, treasuryMg } }
}

// --- research --------------------------------------------------------------------------------------

export function institutionCost(cityInstitutions: CityInstitutions, type: InstitutionType): number | null {
  return cityInstitutions.options.find((o) => o.type === type)?.costMg ?? null
}

export function buildInstitution(
  cityInstitutions: CityInstitutions,
  type: InstitutionType,
  institutionId: string,
): CityInstitutions {
  return {
    ...cityInstitutions,
    options: cityInstitutions.options.map((o) =>
      o.type !== type
        ? o
        : {
            ...o,
            allowed: false,
            blockers: o.blockers.includes('ALREADY_EXISTS') ? o.blockers : [...o.blockers, 'ALREADY_EXISTS'],
            existing: {
              id: institutionId,
              cityId: cityInstitutions.cityId,
              cityName: cityInstitutions.cityName,
              provinceKey: cityInstitutions.provinceKey,
              type,
              pool: o.pool,
              status: 'UNDER_CONSTRUCTION',
              completesAfterRound: o.completesAfterRound,
              operationalFromRound: o.operationalFromRound,
              pointsPerRound: o.pointsPerRound,
              upkeepMg: o.upkeepMg,
              producing: false,
            },
          },
    ),
  }
}

export function unlockTechnology(research: ResearchOverview, track: ResearchTrack, targetLevel: number): ResearchOverview {
  const key = track === 'MILITARY' ? 'military' : 'civilian'
  const view = research[key]
  const level = view?.levels.find((l) => l.level === targetLevel)
  if (!view || !level) {
    return research
  }
  const pools = research.pools.map((p) => (p.pool === track ? { ...p, points: p.points - level.costPoints } : p))
  const points = pools.find((p) => p.pool === track)?.points ?? 0
  const following = view.levels.find((l) => l.level === targetLevel + 1)
  const next = following
    ? {
        targetLevel: following.level,
        costPoints: following.costPoints,
        allowed: research.yourTurn && points >= following.costPoints,
        blockers: [
          ...(research.yourTurn ? [] : ['NOT_YOUR_TURN']),
          ...(points >= following.costPoints ? [] : ['INSUFFICIENT_POINTS']),
        ],
      }
    : null
  return {
    ...research,
    pools,
    [key]: {
      ...view,
      level: targetLevel,
      levels: view.levels.map((l) => (l.level <= targetLevel ? { ...l, unlocked: true } : l)),
      next,
    },
  }
}

export function unlockDoctrine(research: ResearchOverview, key: string): ResearchOverview {
  const doctrine = research.doctrines.find((d) => d.key === key)
  if (!doctrine) {
    return research
  }
  const pools = research.pools.map((p) => (p.pool === 'DOCTRINE' ? { ...p, points: p.points - doctrine.costPoints } : p))
  return {
    ...research,
    pools,
    doctrines: research.doctrines.map((d) => {
      if (d.key === key) {
        return { ...d, chosen: true, allowed: false, blockers: ['ALREADY_CHOSEN'] }
      }
      // PROVISIONAL rule (docs/RESEARCH.md): one doctrine per tier.
      return d.tier === doctrine.tier && !d.chosen ? { ...d, allowed: false, blockers: [...new Set([...d.blockers, 'TIER_FULL'])] } : d
    }),
  }
}

// --- fortresses ------------------------------------------------------------------------------------

export function orderFortressWorks(fort: ProvinceFortification, kind: 'UPGRADE' | 'REPAIR'): ProvinceFortification {
  const option = kind === 'UPGRADE' ? fort.upgrade : fort.repair
  if (!option) {
    return fort
  }
  const project = {
    kind,
    targetLevel: kind === 'UPGRADE' ? option.targetLevel : null,
    completesAfterRound: option.completesAfterRound,
    operationalFromRound: option.operationalFromRound,
  }
  const fortification = fort.fortification
    ? { ...fort.fortification, project }
    : {
        cityId: fort.cityId,
        provinceKey: fort.provinceKey,
        cityName: fort.cityName,
        level: 0,
        effectiveLevel: 0,
        status: 'ACTIVE' as const,
        integrity: 0,
        maxIntegrity: 0,
        project,
      }
  const busy = <T extends { allowed: boolean; blockers: string[] }>(o: T | null): T | null =>
    o ? { ...o, allowed: false, blockers: o.blockers.includes('PROJECT_IN_PROGRESS') ? o.blockers : [...o.blockers, 'PROJECT_IN_PROGRESS'] } : o
  return {
    ...fort,
    fortification,
    upgrade: busy(fort.upgrade) as ProvinceFortification['upgrade'],
    repair: busy(fort.repair) as ProvinceFortification['repair'],
  }
}

// --- diplomacy -------------------------------------------------------------------------------------

function relation(diplomacy: DiplomacyOverview, nationId: string, change: (r: NationRelation) => NationRelation): DiplomacyOverview {
  return { ...diplomacy, nations: diplomacy.nations.map((n) => (n.nationId === nationId ? change(n) : n)) }
}

function addBlocker(blockers: string[], blocker: string): string[] {
  return blockers.includes(blocker) ? blockers : [...blockers, blocker]
}

export function proposeAlliance(
  diplomacy: DiplomacyOverview,
  input: { nationId: string; kind: AllianceKind; allianceId: string },
): DiplomacyOverview {
  const you = diplomacy.yourNationId
  const rules = diplomacy.rules
  const alliance: AllianceView = {
    id: input.allianceId,
    kind: input.kind,
    nationA: you,
    nationB: input.nationId,
    proposerNationId: you,
    status: 'PROPOSED',
    proposedRound: diplomacy.round,
    acceptedRound: null,
    endedRound: null,
    endReason: null,
    expiresAfterRound: diplomacy.round + (rules?.proposalExpiryRounds ?? 0),
    grantsMilitaryAccess: input.kind === 'NORMAL' ? Boolean(rules?.normalAllianceGrantsAccess) : Boolean(rules?.defensiveAllianceGrantsAccess),
    canAccept: false,
    canEnd: true,
    endBlockers: [],
  }
  return relation({ ...diplomacy, alliances: [...diplomacy.alliances, alliance] }, input.nationId, (r) => ({
    ...r,
    allianceBlockers: addBlocker(r.allianceBlockers, 'ALREADY_PROPOSED'),
  }))
}

export function allianceAction(
  diplomacy: DiplomacyOverview,
  allianceId: string,
  action: 'accept' | 'reject' | 'terminate',
): DiplomacyOverview {
  const alliance = diplomacy.alliances.find((a) => a.id === allianceId)
  if (!alliance) {
    return diplomacy
  }
  const you = diplomacy.yourNationId
  const other = alliance.nationA === you ? alliance.nationB : alliance.nationA
  const round = diplomacy.round
  let changed: AllianceView
  if (action === 'accept') {
    changed = { ...alliance, status: 'ACTIVE', acceptedRound: round, canAccept: false, canEnd: true }
  }
  else if (action === 'reject') {
    changed = { ...alliance, status: 'REJECTED', endedRound: round, endReason: 'REJECTED', canAccept: false, canEnd: false }
  }
  else if (alliance.status === 'PROPOSED') {
    changed = { ...alliance, status: 'CANCELLED', endedRound: round, endReason: 'WITHDRAWN', canAccept: false, canEnd: false }
  }
  else {
    changed = { ...alliance, status: 'ENDED', endedRound: round, endReason: 'TERMINATED', canAccept: false, canEnd: false }
  }
  const next = { ...diplomacy, alliances: diplomacy.alliances.map((a) => (a.id === allianceId ? changed : a)) }
  if (changed.status === 'ACTIVE') {
    return relation(next, other, (r) => ({
      ...r,
      status: 'ALLIED',
      allianceKind: alliance.kind,
      allianceId,
      weMayEnter: alliance.grantsMilitaryAccess || r.weMayEnter,
      theyMayEnter: alliance.grantsMilitaryAccess || r.theyMayEnter,
    }))
  }
  if (changed.status === 'ENDED') {
    // Access ends at once; the battalions that must withdraw arrive with the server's answer.
    return relation(next, other, (r) => ({
      ...r,
      status: r.status === 'ALLIED' ? 'PEACE' : r.status,
      allianceKind: null,
      allianceId: null,
      weMayEnter: r.status === 'WAR' ? r.weMayEnter : false,
      theyMayEnter: r.status === 'WAR' ? r.theyMayEnter : false,
    }))
  }
  return relation(next, other, (r) => ({ ...r, allianceBlockers: r.allianceBlockers.filter((b) => b !== 'ALREADY_PROPOSED') }))
}

export function replaceAlliance(diplomacy: DiplomacyOverview, alliance: AllianceView, replacing = alliance.id): DiplomacyOverview {
  const known = diplomacy.alliances.some((a) => a.id === replacing)
  return {
    ...diplomacy,
    alliances: known
      ? diplomacy.alliances.map((a) => (a.id === replacing ? alliance : a))
      : [...diplomacy.alliances.filter((a) => a.id !== alliance.id), alliance],
  }
}

export function declareWar(
  diplomacy: DiplomacyOverview,
  input: { targetNationId: string; warId: string; targetName: string },
): DiplomacyOverview {
  const you = diplomacy.yourNationId
  const round = diplomacy.round
  const yourName = diplomacy.nations.find((n) => n.nationId === you)?.name ?? ''
  const emptyScore = { battles: 0, sieges: 0, occupation: 0, total: 0, battlesWon: 0, siegesWon: 0, occupiedEnemyPopulation: 0, enemyPopulation: 0 }
  const war: WarView = {
    id: input.warId,
    status: 'ACTIVE',
    attackerLeaderId: you,
    defenderLeaderId: input.targetNationId,
    participants: [
      { nationId: you, name: yourName, side: 'ATTACKER', joinReason: 'DECLARED', joinedRound: round },
      { nationId: input.targetNationId, name: input.targetName, side: 'DEFENDER', joinReason: 'ATTACKED', joinedRound: round },
    ],
    declaredRound: round,
    endedRound: null,
    endReason: null,
    durationRounds: 0,
    score: { attacker: emptyScore, defender: emptyScore, net: 0, maxScore: diplomacy.rules?.maxScore ?? 0 },
    yourSide: 'ATTACKER',
    youLead: true,
  }
  const pair = (a: AllianceView) =>
    (a.nationA === you && a.nationB === input.targetNationId) || (a.nationB === you && a.nationA === input.targetNationId)
  // Peaceful ties between the two end with the war (pending alliance proposals, a running tribute).
  return relation(
    {
      ...diplomacy,
      wars: [...diplomacy.wars, war],
      alliances: diplomacy.alliances.map((a) =>
        a.status === 'PROPOSED' && pair(a) ? { ...a, status: 'CANCELLED', endedRound: round, endReason: 'WAR', canAccept: false, canEnd: false } : a,
      ),
      tributes: diplomacy.tributes.map((t) =>
        t.status === 'ACTIVE' &&
        ((t.payerNationId === you && t.receiverNationId === input.targetNationId) ||
          (t.receiverNationId === you && t.payerNationId === input.targetNationId))
          ? { ...t, status: 'CANCELLED', endReason: 'WAR_RENEWED', endedRound: round }
          : t,
      ),
    },
    input.targetNationId,
    (r) => ({
      ...r,
      status: 'WAR',
      warId: input.warId,
      weMayEnter: true,
      theyMayEnter: true,
      warBlockers: addBlocker(r.warBlockers, 'ALREADY_AT_WAR'),
      allianceBlockers: addBlocker(r.allianceBlockers, 'AT_WAR'),
    }),
  )
}

export function replaceWar(diplomacy: DiplomacyOverview, war: WarView, replacing = war.id): DiplomacyOverview {
  return {
    ...diplomacy,
    wars: [...diplomacy.wars.filter((w) => w.id !== replacing && w.id !== war.id), war],
    nations: diplomacy.nations.map((n) => (n.warId === replacing ? { ...n, warId: war.id } : n)),
  }
}

function otherLeader(war: WarView, nationId: string): string {
  return war.attackerLeaderId === nationId ? war.defenderLeaderId : war.attackerLeaderId
}

export function proposePeace(
  diplomacy: DiplomacyOverview,
  input: { warId: string; terms: PeaceTerm[]; proposalId: string; counterOfId?: string | null; now: string },
): DiplomacyOverview {
  const war = diplomacy.wars.find((w) => w.id === input.warId)
  if (!war) {
    return diplomacy
  }
  const you = diplomacy.yourNationId
  const round = diplomacy.round
  const proposal: PeaceProposal = {
    id: input.proposalId,
    warId: input.warId,
    proposerNationId: you,
    recipientNationId: otherLeader(war, you),
    status: 'PENDING',
    terms: input.terms,
    createdRound: round,
    createdTurn: 0,
    createdStateVersion: 0,
    respondedRound: null,
    counterOfId: input.counterOfId ?? null,
    expiresAfterRound: round + (diplomacy.rules?.proposalExpiryRounds ?? 0),
    incoming: false,
    violations: [],
    createdAt: input.now,
  }
  return {
    ...diplomacy,
    proposals: [
      proposal,
      ...diplomacy.proposals.map((p) =>
        p.status === 'PENDING' && p.warId === input.warId && (p.proposerNationId === you || p.id === input.counterOfId)
          ? { ...p, status: 'SUPERSEDED' as const, respondedRound: round }
          : p,
      ),
    ],
  }
}

export function peaceAction(diplomacy: DiplomacyOverview, proposalId: string, action: 'reject' | 'cancel'): DiplomacyOverview {
  return {
    ...diplomacy,
    proposals: diplomacy.proposals.map((p) =>
      p.id === proposalId && p.status === 'PENDING'
        ? { ...p, status: action === 'reject' ? 'REJECTED' : 'CANCELLED', respondedRound: diplomacy.round }
        : p,
    ),
  }
}

export function replaceProposal(diplomacy: DiplomacyOverview, proposal: PeaceProposal, replacing = proposal.id): DiplomacyOverview {
  const known = diplomacy.proposals.some((p) => p.id === replacing)
  return {
    ...diplomacy,
    proposals: known
      ? diplomacy.proposals.map((p) => (p.id === replacing ? proposal : p))
      : [proposal, ...diplomacy.proposals.filter((p) => p.id !== proposal.id)],
  }
}

/**
 * A war ends by treaty: everyone who still fought it is at peace with the other side and in truce for
 * the ruleset's truce rounds. Withdrawals and returned control arrive with the server's answer.
 */
export function endWarByTreaty(diplomacy: DiplomacyOverview, warId: string): DiplomacyOverview {
  const war = diplomacy.wars.find((w) => w.id === warId)
  if (!war) {
    return diplomacy
  }
  const round = diplomacy.round
  const truceUntil = round + (diplomacy.rules?.truceRounds ?? 0)
  const fighting = war.participants.filter((p) => p.leftRound == null)
  const yourSide = fighting.find((p) => p.nationId === diplomacy.yourNationId)?.side
  const enemies = new Set(fighting.filter((p) => yourSide && p.side !== yourSide).map((p) => p.nationId))
  return {
    ...diplomacy,
    wars: diplomacy.wars.map((w) => (w.id === warId ? { ...w, status: 'ENDED', endedRound: round, endReason: 'PEACE_TREATY' } : w)),
    nations: diplomacy.nations.map((n) =>
      enemies.has(n.nationId) && n.warId === warId
        ? {
            ...n,
            status: 'PEACE',
            warId: null,
            truceUntilRound: truceUntil,
            weMayEnter: false,
            theyMayEnter: false,
            warBlockers: addBlocker(n.warBlockers.filter((b) => b !== 'ALREADY_AT_WAR'), 'TRUCE'),
          }
        : n,
    ),
  }
}

export function acceptPeace(diplomacy: DiplomacyOverview, proposalId: string): DiplomacyOverview {
  const proposal = diplomacy.proposals.find((p) => p.id === proposalId)
  if (!proposal) {
    return diplomacy
  }
  const round = diplomacy.round
  return endWarByTreaty(
    {
      ...diplomacy,
      proposals: diplomacy.proposals.map((p) =>
        p.id === proposalId
          ? { ...p, status: 'ACCEPTED' as const, respondedRound: round }
          : p.status === 'PENDING' && p.warId === proposal.warId
            ? { ...p, status: 'SUPERSEDED' as const, respondedRound: round }
            : p,
      ),
    },
    proposal.warId,
  )
}

/** What the agreed terms do to the caller's own treasury (reparations), as both sides can see them. */
export function goldChangeOf(terms: PeaceTerm[], nationId: string): number {
  return terms.reduce((sum, t) => {
    if (t.type !== 'GOLD' || !t.amountMg) {
      return sum
    }
    if (t.fromNationId === nationId) return sum - t.amountMg
    if (t.toNationId === nationId) return sum + t.amountMg
    return sum
  }, 0)
}

/** Ceded provinces change owner and controller; transferred people move between the named cities. */
export function applyTermsToState(state: GameState, terms: PeaceTerm[]): GameState {
  return terms.reduce((s, t) => {
    if (t.type === 'PROVINCE' && t.provinceKey) {
      return {
        ...s,
        provinces: s.provinces.map((p) => (p.key === t.provinceKey ? { ...p, ownerNationId: t.toNationId, controllerNationId: t.toNationId } : p)),
      }
    }
    if (t.type === 'POPULATION' && t.sourceCityId && t.destinationCityId && t.people) {
      return changeStatePopulation(changeStatePopulation(s, t.sourceCityId, -t.people), t.destinationCityId, t.people)
    }
    return s
  }, state)
}

export function sendGift(
  diplomacy: DiplomacyOverview,
  input: { giftId: string; recipientNationId: string; amountMg: number; now: string },
): DiplomacyOverview {
  return {
    ...diplomacy,
    gifts: [
      {
        id: input.giftId,
        senderNationId: diplomacy.yourNationId,
        recipientNationId: input.recipientNationId,
        amountMg: input.amountMg,
        round: diplomacy.round,
        createdAt: input.now,
      },
      ...diplomacy.gifts,
    ],
  }
}

export function callToArms(
  diplomacy: DiplomacyOverview,
  input: { callId: string; warId: string; allyNationId: string },
): DiplomacyOverview {
  const call: CallToArms = {
    id: input.callId,
    warId: input.warId,
    callerNationId: diplomacy.yourNationId,
    allyNationId: input.allyNationId,
    status: 'PENDING',
    createdRound: diplomacy.round,
    answeredRound: null,
    canAnswer: false,
    side: 'DEFENDER',
  }
  return { ...diplomacy, calls: [...(diplomacy.calls ?? []), call] }
}

/**
 * Answering a call: honouring it joins the war on the call's side (the enemies of that side become the
 * caller's enemies at once); declining a defensive call ends the alliance, whose withdrawals arrive
 * with the server's answer.
 */
export function answerCall(diplomacy: DiplomacyOverview, callId: string, accept: boolean): DiplomacyOverview {
  const call = (diplomacy.calls ?? []).find((c) => c.id === callId)
  if (!call) {
    return diplomacy
  }
  const round = diplomacy.round
  const you = diplomacy.yourNationId
  const next: DiplomacyOverview = {
    ...diplomacy,
    calls: (diplomacy.calls ?? []).map((c) =>
      c.id === callId ? { ...c, status: accept ? 'HONORED' : 'DECLINED', answeredRound: round, canAnswer: false } : c,
    ),
  }
  const war = diplomacy.wars.find((w) => w.id === call.warId)
  if (accept && war) {
    const side = call.side ?? 'DEFENDER'
    const yourName = diplomacy.nations.find((n) => n.nationId === you)?.name ?? ''
    const enemies = new Set(war.participants.filter((p) => p.leftRound == null && p.side !== side).map((p) => p.nationId))
    return {
      ...next,
      wars: next.wars.map((w) =>
        w.id === war.id
          ? { ...w, participants: [...w.participants, { nationId: you, name: yourName, side, joinReason: 'CALL_TO_ARMS', joinedRound: round }], yourSide: side }
          : w,
      ),
      nations: next.nations.map((n) =>
        enemies.has(n.nationId) ? { ...n, status: 'WAR', warId: war.id, weMayEnter: true, theyMayEnter: true } : n,
      ),
    }
  }
  if (!accept && (call.side ?? 'DEFENDER') === 'DEFENDER') {
    const alliance = diplomacy.alliances.find((a) => a.status === 'ACTIVE' && [a.nationA, a.nationB].includes(call.callerNationId) && [a.nationA, a.nationB].includes(you))
    return alliance ? allianceAction(next, alliance.id, 'terminate') : next
  }
  return next
}

export function replaceCall(diplomacy: DiplomacyOverview, call: CallToArms, replacing = call.id): DiplomacyOverview {
  const calls = diplomacy.calls ?? []
  return {
    ...diplomacy,
    calls: calls.some((c) => c.id === replacing) ? calls.map((c) => (c.id === replacing ? call : c)) : [...calls.filter((c) => c.id !== call.id), call],
  }
}

export function requestConference(
  diplomacy: DiplomacyOverview,
  input: { conferenceId: string; warId: string; kind: ConferenceKind },
): DiplomacyOverview {
  const war = diplomacy.wars.find((w) => w.id === input.warId)
  if (!war) {
    return diplomacy
  }
  const you = diplomacy.yourNationId
  const yourSide = war.participants.find((p) => p.nationId === you && p.leftRound == null)?.side
  const counterpart = yourSide === 'ATTACKER' ? war.defenderLeaderId : war.attackerLeaderId
  const conference: Conference = {
    id: input.conferenceId,
    warId: input.warId,
    kind: input.kind,
    requesterNationId: you,
    counterpartNationId: counterpart,
    status: 'REQUESTED',
    requestedRound: diplomacy.round,
    currentRevision: 0,
    terms: [],
    approvals: [],
    revisions: [],
    violations: [],
    youArePrincipal: true,
    canAnswer: false,
    canCancel: true,
    canRevise: false,
    youMustDecide: false,
  }
  return { ...diplomacy, conferences: [conference, ...(diplomacy.conferences ?? [])] }
}

function changeConference(diplomacy: DiplomacyOverview, conferenceId: string, change: (c: Conference) => Conference): DiplomacyOverview {
  return { ...diplomacy, conferences: (diplomacy.conferences ?? []).map((c) => (c.id === conferenceId ? change(c) : c)) }
}

export function answerConference(diplomacy: DiplomacyOverview, conferenceId: string, accept: boolean): DiplomacyOverview {
  return changeConference(diplomacy, conferenceId, (c) => ({
    ...c,
    status: accept ? 'ACTIVE' : 'REJECTED',
    canAnswer: false,
    canCancel: accept,
    canRevise: accept && c.youArePrincipal,
  }))
}

export function cancelConference(diplomacy: DiplomacyOverview, conferenceId: string): DiplomacyOverview {
  return changeConference(diplomacy, conferenceId, (c) => ({ ...c, status: 'CANCELLED', canAnswer: false, canCancel: false, canRevise: false, youMustDecide: false }))
}

/** A new revision: its approvals start over (who must approve is the server's answer). */
export function submitDraft(diplomacy: DiplomacyOverview, conferenceId: string, terms: PeaceTerm[], now: string): DiplomacyOverview {
  return changeConference(diplomacy, conferenceId, (c) => ({
    ...c,
    currentRevision: c.currentRevision + 1,
    terms,
    approvals: c.approvals.map((a) => ({ ...a, decision: null })),
    revisions: [...c.revisions, { revision: c.currentRevision + 1, authorNationId: diplomacy.yourNationId, termCount: terms.length, createdAt: now }],
    violations: [],
  }))
}

/** Whether the caller's approval is the last one the current revision needs (it then settles). */
export function settlesWith(conference: Conference, nationId: string): boolean {
  return (
    conference.approvals.length > 0 &&
    conference.approvals.every((a) => a.nationId === nationId || a.decision === 'APPROVED') &&
    conference.approvals.some((a) => a.nationId === nationId)
  )
}

export function decideDraft(diplomacy: DiplomacyOverview, conferenceId: string, approve: boolean): DiplomacyOverview {
  const conference = (diplomacy.conferences ?? []).find((c) => c.id === conferenceId)
  if (!conference) {
    return diplomacy
  }
  const you = diplomacy.yourNationId
  const decided = changeConference(diplomacy, conferenceId, (c) => ({
    ...c,
    approvals: c.approvals.map((a) => (a.nationId === you ? { ...a, decision: approve ? 'APPROVED' : 'REJECTED' } : a)),
    youMustDecide: false,
  }))
  if (!approve || !settlesWith(conference, you)) {
    return decided
  }
  // The last approval settles the treaty: the general peace ends the war for everyone.
  const concluded = changeConference(decided, conferenceId, (c) => ({ ...c, status: 'CONCLUDED', canCancel: false, canRevise: false }))
  return conference.kind === 'GENERAL' ? endWarByTreaty(concluded, conference.warId) : concluded
}

export function replaceConference(diplomacy: DiplomacyOverview, conference: Conference, replacing = conference.id): DiplomacyOverview {
  const conferences = diplomacy.conferences ?? []
  return {
    ...diplomacy,
    conferences: conferences.some((c) => c.id === replacing)
      ? conferences.map((c) => (c.id === replacing ? conference : c))
      : [conference, ...conferences.filter((c) => c.id !== conference.id)],
  }
}
