import type {
  Battalion,
  BattalionDetails,
  BattleReport,
  CityEconomy,
  EconomyOverview,
  GameMap,
  GameState,
  Lobby,
  MilitaryOverview,
  RecruitOption,
} from '../api/types'

export const VARN = 'nation-varn'
export const OSTRA = 'nation-ostra'

export function lobbyFixture(overrides: Partial<Lobby> = {}): Lobby {
  return {
    id: 'g1',
    name: 'Twin Shores',
    status: 'WAITING_FOR_PLAYERS',
    mapId: 'map-1',
    mapName: 'The Sundered Reach',
    maxPlayers: 2,
    playerCount: 1,
    availableSlots: 1,
    inviteCode: 'ABCD-EFGH-JKLM',
    yourSeat: 0,
    youAreHost: true,
    participants: [
      { seat: 0, displayName: 'Aldric', host: true, you: true, nationId: null, joinedAt: '2026-10-17T12:00:00Z' },
    ],
    nations: [
      { id: VARN, key: 'varn', name: 'Kingdom of Varn', color: '#3f6fb5', provinceCount: 6, chosenBySeat: null, chosenByYou: false, tier: 'POWER', playable: true },
      { id: OSTRA, key: 'ostra', name: 'Ostran Confederacy', color: '#b5503a', provinceCount: 6, chosenBySeat: null, chosenByYou: false, tier: 'POWER', playable: true },
    ],
    canStart: false,
    stateVersion: 1,
    createdAt: '2026-10-17T12:00:00Z',
    ...overrides,
  }
}

/** Host (Aldric, Varn, seat 0) and guest (Bryn, Ostra, seat 1), both nations chosen. */
export function readyLobbyFixture(overrides: Partial<Lobby> = {}): Lobby {
  const base = lobbyFixture()
  return lobbyFixture({
    status: 'READY',
    playerCount: 2,
    availableSlots: 0,
    participants: [
      { ...base.participants[0], nationId: VARN },
      { seat: 1, displayName: 'Bryn', host: false, you: false, nationId: OSTRA, joinedAt: '2026-10-17T12:05:00Z' },
    ],
    nations: [
      { ...base.nations[0], chosenBySeat: 0, chosenByYou: true },
      { ...base.nations[1], chosenBySeat: 1, chosenByYou: false },
    ],
    canStart: true,
    ...overrides,
  })
}

export const mapFixture: GameMap = {
  id: 'map-1',
  key: 'sundered-reach',
  version: 1,
  name: 'The Sundered Reach',
  viewBox: '0 0 1000 640',
  provinces: [
    {
      key: 'varnhold',
      name: 'Varnhold',
      terrain: 'PLAINS',
      cityName: 'Varn',
      svgPath: 'M0 0 L100 0 L100 100 L0 100 Z',
      labelX: 50,
      labelY: 50,
      neighbours: [{ provinceKey: 'elmsford', crossing: 'NONE' }],
    },
    {
      key: 'elmsford',
      name: 'Elmsford',
      terrain: 'PLAINS',
      cityName: 'Elmsford',
      svgPath: 'M100 0 L200 0 L200 100 L100 100 Z',
      labelX: 150,
      labelY: 50,
      neighbours: [
        { provinceKey: 'varnhold', crossing: 'NONE' },
        { provinceKey: 'sablewater', crossing: 'RIVER' },
      ],
    },
    {
      key: 'sablewater',
      name: 'Sablewater',
      terrain: 'MOUNTAINS',
      cityName: 'Sable',
      svgPath: 'M200 0 L300 0 L300 100 L200 100 Z',
      labelX: 250,
      labelY: 50,
      neighbours: [{ provinceKey: 'elmsford', crossing: 'RIVER' }],
    },
  ],
  rivers: [{ a: 'elmsford', b: 'sablewater', svgPath: 'M200 0 L200 100' }],
  nations: [
    { id: VARN, key: 'varn', name: 'Kingdom of Varn', color: '#3f6fb5', capitalProvinceKey: 'varnhold', startProvinceKeys: ['varnhold', 'elmsford'] },
    { id: OSTRA, key: 'ostra', name: 'Ostran Confederacy', color: '#b5503a', capitalProvinceKey: 'sablewater', startProvinceKeys: ['sablewater'] },
  ],
}

export function gameStateFixture(overrides: Partial<GameState> = {}): GameState {
  return {
    id: 'g1',
    name: 'Twin Shores',
    status: 'IN_PROGRESS',
    mapId: 'map-1',
    round: 1,
    activeSeat: 0,
    turnNumber: 1,
    stateVersion: 3,
    yourSeat: 0,
    yourTurn: true,
    players: [
      { seat: 0, displayName: 'Aldric', host: true, you: true, nationId: VARN },
      { seat: 1, displayName: 'Bryn', host: false, you: false, nationId: OSTRA },
    ],
    nations: [
      { id: VARN, name: 'Kingdom of Varn', color: '#3f6fb5', seat: 0 },
      { id: OSTRA, name: 'Ostran Confederacy', color: '#b5503a', seat: 1 },
    ],
    provinces: [
      { key: 'varnhold', ownerNationId: VARN, controllerNationId: VARN, city: { id: 'city-varnhold', name: 'Varn', population: 12000 } },
      { key: 'elmsford', ownerNationId: VARN, controllerNationId: VARN, city: { id: 'city-elmsford', name: 'Elmsford', population: 8000 } },
      { key: 'sablewater', ownerNationId: OSTRA, controllerNationId: OSTRA, city: { id: 'city-sablewater', name: 'Sable', population: 8000 } },
    ],
    ...overrides,
  }
}

export function cityEconomyFixture(overrides: Partial<CityEconomy> = {}): CityEconomy {
  return {
    cityId: 'city-varnhold',
    provinceKey: 'varnhold',
    cityName: 'Varn',
    population: 12000,
    ownerNationId: VARN,
    controllerNationId: VARN,
    incomeStatus: 'COLLECTIBLE',
    employment: { agricultureWorkers: 8000, tier1Workers: 4000, tier2Workers: 0 },
    potentialIncome: { agricultureMg: 8000, tier1Mg: 12000, tier2Mg: 0, totalMg: 20000 },
    collectibleIncomeMg: 20000,
    workshops: [
      { id: 'w1', tier: 1, status: 'ACTIVE', orderedRound: 0, completesAfterRound: null, operationalFromRound: null, repairCompletesAfterRound: null, repair: null, upgradeTargetTier: null, upgradeCompletesAfterRound: null, upgrade: null },
    ],
    workshopCount: 1,
    construction: {
      tier: 1,
      costMg: 40000,
      durationRounds: 1,
      completesAfterRound: 1,
      operationalFromRound: 2,
      allowed: true,
      blockers: [],
    },
    growth: { eligible: true, rateBps: 50, nextRoundGrowth: 60, blockers: [] },
    ...overrides,
  }
}

export function economyFixture(overrides: Partial<EconomyOverview['nation']> = {}, cities?: CityEconomy[]): EconomyOverview {
  return {
    gameId: 'g1',
    round: 1,
    yourTurn: true,
    rules: {
      version: 'v1',
      agricultureMgPerWorker: 1,
      workshopCapacity: 4000,
      maxWorkshopsPerCity: 6,
      workshopTiers: [
        { tier: 1, mgPerWorker: 3, buildCostMg: 40000, buildDurationRounds: 1, constructible: true },
        { tier: 2, mgPerWorker: 5, buildCostMg: 100000, buildDurationRounds: 1, constructible: false },
      ],
    },
    nation: {
      nationId: VARN,
      treasuryMg: 50000,
      inArrears: false,
      projectedGrossIncomeMg: 50000,
      projectedUpkeepMg: 0,
      projectedNetMg: 50000,
      lastRound: null,
      ...overrides,
    },
    cities: cities ?? [
      cityEconomyFixture(),
      cityEconomyFixture({
        cityId: 'city-elmsford',
        provinceKey: 'elmsford',
        cityName: 'Elmsford',
        population: 8000,
        employment: { agricultureWorkers: 8000, tier1Workers: 0, tier2Workers: 0 },
        potentialIncome: { agricultureMg: 8000, tier1Mg: 0, tier2Mg: 0, totalMg: 8000 },
        collectibleIncomeMg: 8000,
        workshops: [],
        workshopCount: 0,
      }),
      cityEconomyFixture({
        cityId: 'city-sablewater',
        provinceKey: 'sablewater',
        cityName: 'Sable',
        population: 8000,
        ownerNationId: OSTRA,
        controllerNationId: OSTRA,
        employment: { agricultureWorkers: 8000, tier1Workers: 0, tier2Workers: 0 },
        potentialIncome: { agricultureMg: 8000, tier1Mg: 0, tier2Mg: 0, totalMg: 8000 },
        collectibleIncomeMg: 8000,
        workshops: [],
        workshopCount: 0,
        construction: { ...cityEconomyFixture().construction, allowed: false, blockers: ['NOT_OWNER'] },
      }),
    ],
  }
}

export function battalionFixture(overrides: Partial<Battalion> = {}): Battalion {
  return {
    id: 'b1',
    nationId: VARN,
    unitType: 'INFANTRY',
    manpower: 1000,
    maxManpower: 1000,
    provinceKey: 'elmsford',
    armyId: null,
    originCityId: 'city-varnhold',
    recruitedRound: 0,
    eligibility: 'READY',
    upkeepMg: 500,
    ...overrides,
  }
}

function recruitOption(unitType: RecruitOption['unitType'], costMg: number, upkeepMg: number, population: number): RecruitOption {
  return { unitType, costMg, upkeepMg, populationCost: 1000, populationAfter: population - 1000, allowed: true, blockers: [] }
}

/** Varn (you) has two infantry in Elmsford; Ostra has one infantry in Sablewater. */
export function militaryFixture(overrides: Partial<MilitaryOverview> = {}): MilitaryOverview {
  return {
    gameId: 'g1',
    round: 1,
    turnNumber: 1,
    yourTurn: true,
    nationId: VARN,
    rules: {
      version: 'v2',
      battalionManpower: 1000,
      minResidualPopulation: 1000,
      units: [
        { unitType: 'INFANTRY', recruitCostMg: 10000, upkeepMg: 500 },
        { unitType: 'CAVALRY', recruitCostMg: 25000, upkeepMg: 1500 },
        { unitType: 'ARTILLERY', recruitCostMg: 40000, upkeepMg: 2000 },
      ],
    },
    battalions: [
      battalionFixture({ id: 'b1' }),
      battalionFixture({ id: 'b2', manpower: 650, upkeepMg: 325 }),
      battalionFixture({ id: 'e1', nationId: OSTRA, provinceKey: 'sablewater', eligibility: null, originCityId: 'city-sablewater' }),
    ],
    armies: [],
    upkeep: { totalMg: 825, byUnitTypeMg: { INFANTRY: 825, CAVALRY: 0, ARTILLERY: 0 }, battalions: 2, soldiers: 1650 },
    recruitment: ['varnhold', 'elmsford'].map((key) => {
      const population = key === 'varnhold' ? 12000 : 8000
      return {
        cityId: `city-${key}`,
        provinceKey: key,
        cityName: key === 'varnhold' ? 'Varn' : 'Elmsford',
        population,
        options: [
          recruitOption('INFANTRY', 10000, 500, population),
          recruitOption('CAVALRY', 25000, 1500, population),
          recruitOption('ARTILLERY', 40000, 2000, population),
        ],
      }
    }),
    recentBattles: [],
    modifiers: {
      militaryLevel: 1,
      doctrines: [],
      damageBonusBps: {},
      baseRiverAttackMultiplier: 0.8,
      riverAttackMultiplier: 0.8,
      baseMountainAttackMultiplier: 0.65,
      mountainAttackMultiplier: 0.65,
      upkeepReductionBps: 0,
      siegeArtilleryBonusBps: 0,
    },
    ...overrides,
  }
}

export function battleFixture(overrides: Partial<BattleReport> = {}): BattleReport {
  return {
    id: 'battle-1',
    round: 1,
    turnNumber: 1,
    provinceKey: 'sablewater',
    attackerNationId: VARN,
    defenderNationId: OSTRA,
    winner: 'ATTACKER',
    rulesetVersion: 'v2',
    createdAt: '2026-10-18T12:00:00Z',
    details: {
      fromProvinceKey: 'elmsford',
      provinceKey: 'sablewater',
      river: true,
      mountains: true,
      attackers: [
        { battalionId: 'b1', unitType: 'INFANTRY', manpowerBefore: 1000, losses: 120, manpowerAfter: 880, fate: 'SURVIVED' },
      ],
      defenders: [
        { battalionId: 'e1', unitType: 'INFANTRY', manpowerBefore: 1000, losses: 310, manpowerAfter: 690, fate: 'RETREATED' },
      ],
      phases: [
        {
          number: 1,
          attacker: { protection: 1, artilleryEffectiveness: 1, flankBonus: 0, terrainMultiplier: 0.52, damage: 1.04, losses: { INFANTRY: 40 }, manpowerAfter: 960 },
          defender: { protection: 1, artilleryEffectiveness: 1, flankBonus: 0, terrainMultiplier: 1, damage: 1, losses: { INFANTRY: 34 }, manpowerAfter: 966 },
        },
      ],
      attackerInitial: 1000,
      defenderInitial: 1000,
      attackerLosses: 120,
      defenderLosses: 310,
      broken: 'DEFENDER',
      winner: 'ATTACKER',
      retreatProvinceKey: 'varnhold',
      defendersDestroyed: false,
      attackerBroke: false,
      defenderBroke: true,
    },
    ...overrides,
  }
}

/** Battalion b2 (650 men, raised in Varn, standing in Elmsford). */
export function battalionDetailsFixture(overrides: Partial<BattalionDetails> = {}): BattalionDetails {
  return {
    battalion: battalionFixture({ id: 'b2', manpower: 650, upkeepMg: 325 }),
    cohorts: [{ originCityId: 'city-varnhold', cityName: 'Varn', provinceKey: 'varnhold', soldiers: 650 }],
    reinforcement: {
      cityId: 'city-elmsford',
      cityName: 'Elmsford',
      cityPopulation: 8000,
      missing: 350,
      turnAllowance: 500,
      maxSoldiers: 350,
      recruitCostMg: 10000,
      battalionManpower: 1000,
      costOfMaxMg: 3500,
      upkeepAfterMaxMg: 500,
      allowed: true,
      blockers: [],
    },
    disband: {
      allowed: true,
      blockers: [],
      soldiers: 650,
      returns: [{ cityId: 'city-varnhold', cityName: 'Varn', provinceKey: 'varnhold', soldiers: 650, fallback: false }],
      upkeepSavedMg: 325,
    },
    ...overrides,
  }
}
