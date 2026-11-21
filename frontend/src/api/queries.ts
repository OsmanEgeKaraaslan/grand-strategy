import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useApi } from './ApiContext'
import * as predict from './predict'
import { pendingId } from './predict'
import type {
  AllianceKind,
  BattalionDetails,
  CityInstitutions,
  ConferenceKind,
  DiplomacyOverview,
  EconomyOverview,
  GameState,
  InstitutionType,
  Lobby,
  MilitaryOverview,
  PeaceTerm,
  ProvinceFortification,
  ResearchOverview,
  ResearchTrack,
  UnitType,
} from './types'

/** How often an open lobby is re-fetched so new participants appear (no WebSockets needed). */
export const LOBBY_POLL_INTERVAL_MS = 3000

export const queryKeys = {
  me: ['me'] as const,
  games: ['games'] as const,
  game: (gameId: string) => ['games', gameId] as const,
}

export function useMe() {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.me, queryFn: api.getMe })
}

export function useMyGames() {
  const api = useApi()
  return useQuery({ queryKey: queryKeys.games, queryFn: api.listGames })
}

export function useLobby(gameId: string) {
  const api = useApi()
  return useQuery({
    queryKey: queryKeys.game(gameId),
    queryFn: () => api.getGame(gameId),
    // Keep polling until the game starts: joins, nation choices and the start all happen remotely.
    refetchInterval: (query) =>
      query.state.data?.status === 'IN_PROGRESS' || query.state.data?.status === 'FINISHED' || query.state.data?.status === 'ABANDONED'
        ? false
        : LOBBY_POLL_INTERVAL_MS,
    retry: (failureCount, error) => !('status' in error && error.status === 404) && failureCount < 2,
  })
}

function useLobbyMutation<I = string>(action: (input: I) => Promise<Lobby>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    onSuccess: (lobby) => {
      queryClient.setQueryData(queryKeys.game(lobby.id), lobby)
      // The response is the fresh lobby; only the "my games" list needs refreshing.
      return queryClient.invalidateQueries({ queryKey: queryKeys.games, exact: true })
    },
  })
}

export function useCreateGame() {
  const api = useApi()
  return useLobbyMutation(api.createGame)
}

/** Published maps (immutable templates). */
export function useMaps() {
  const api = useApi()
  return useQuery({ queryKey: ['maps'] as const, queryFn: api.listMaps, staleTime: Infinity })
}

export function useJoinGame() {
  const api = useApi()
  return useLobbyMutation(api.joinGame)
}

export function useChooseNation(gameId: string) {
  const api = useApi()
  return useLobbyMutation((nationId: string) => api.chooseNation(gameId, nationId))
}

export function useStartGame(gameId: string) {
  const api = useApi()
  return useLobbyMutation<void>(() => api.startGame(gameId))
}

/** How often a running game is re-fetched (unchanged polls are cheap 304s via ETag). */
export const GAME_POLL_INTERVAL_MS = 3000

export function useGameMap(mapId: string | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['maps', mapId] as const,
    queryFn: () => api.getMap(mapId!),
    enabled: Boolean(mapId),
    // A map id names an immutable template version.
    staleTime: Infinity,
  })
}

export function useGameState(gameId: string) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'state'] as const,
    queryFn: () => api.getGameState(gameId),
    refetchInterval: GAME_POLL_INTERVAL_MS,
    retry: (failureCount, error) => !('status' in error && (error.status === 404 || error.status === 409)) && failureCount < 2,
  })
}

export function useEndTurn(gameId: string) {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ expectedTurnNumber, idempotencyKey }: { expectedTurnNumber: number; idempotencyKey: string }) =>
      api.endTurn(gameId, expectedTurnNumber, idempotencyKey),
    // Success or a stale-turn conflict: either way the authoritative state has moved on.
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['games', gameId, 'state'] }),
  })
}

/**
 * The caller's economy. Keyed by the game's state version, so it refetches exactly when the
 * polled game state changes (construction, end of turn, round end) — no separate polling.
 */
export function useEconomy(gameId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'economy', stateVersion] as const,
    queryFn: () => api.getEconomy(gameId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

/** All battalions, the caller's upkeep and recruitment options; refetched with the state version. */
export function useMilitary(gameId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'military', stateVersion] as const,
    queryFn: () => api.getMilitary(gameId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

/** A stored battle report never changes. */
export function useBattle(gameId: string, battleId: string | null) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'battles', battleId] as const,
    queryFn: () => api.getBattle(gameId, battleId!),
    enabled: battleId !== null,
    staleTime: Infinity,
  })
}

/** Reads and changes the cached views of one game (every version a view is cached under). */
export interface GameCache {
  /** The most recently fetched view under ['games', gameId, ...segments], if any. */
  read<T>(...segments: unknown[]): T | undefined
  /** The most recently fetched view under ['games', gameId, ...segments] that matches. */
  find<T>(segments: unknown[], match: (view: T) => boolean): T | undefined
  /** Changes every cached view under ['games', gameId, ...segments]. */
  patch<T>(segments: unknown[], change: (view: T) => T): void
}

function gameCache(queryClient: QueryClient, gameId: string): GameCache {
  const latest = <T,>(segments: unknown[], match: (view: T) => boolean) =>
    queryClient
      .getQueryCache()
      .findAll({ queryKey: ['games', gameId, ...segments] })
      .filter((q) => q.state.data !== undefined && match(q.state.data as T))
      .sort((a, b) => b.state.dataUpdatedAt - a.state.dataUpdatedAt)[0]?.state.data as T | undefined
  return {
    read<T>(...segments: unknown[]) {
      return latest<T>(segments, () => true)
    },
    find<T>(segments: unknown[], match: (view: T) => boolean) {
      return latest<T>(segments, match)
    },
    patch<T>(segments: unknown[], change: (view: T) => T) {
      queryClient.setQueriesData<T>({ queryKey: ['games', gameId, ...segments] }, (old) => (old === undefined ? old : change(old)))
    },
  }
}

interface Prediction<I, R> {
  /** Applies what the command will do, from cached views only; {@code seed} names pending rows. */
  predict?: (cache: GameCache, input: I, seed: string) => void
  /** Replaces the prediction with the server's answer as soon as the command returns. */
  confirm?: (cache: GameCache, result: R, input: I, seed: string) => void
}

/**
 * A game command with instant feedback (D-UX1, extended 2026-11-21): the prediction is drawn at once,
 * rolled back if the server refuses, replaced by the server's answer when it arrives, and the state
 * refetch that follows (it bumps the state version, which refreshes every view) has the last word.
 */
function useGameCommand<I extends { idempotencyKey: string }, R>(
  gameId: string,
  action: (input: I) => Promise<R>,
  prediction: Prediction<I, R> = {},
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    // Marks the command for the page's notice of refused commands (its panel may have closed meanwhile).
    meta: { instant: prediction.predict !== undefined },
    onMutate: async (input: I) => {
      if (!prediction.predict) {
        return undefined
      }
      await queryClient.cancelQueries({ queryKey: ['games', gameId] })
      const snapshot = queryClient.getQueriesData({ queryKey: ['games', gameId] })
      prediction.predict(gameCache(queryClient, gameId), input, pendingId(input.idempotencyKey))
      return { snapshot }
    },
    onError: (_error, _input, context) => context?.snapshot.forEach(([key, data]) => queryClient.setQueryData(key, data)),
    onSuccess: (result, input) => prediction.confirm?.(gameCache(queryClient, gameId), result, input, pendingId(input.idempotencyKey)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['games', gameId, 'state'] }),
  })
}

const MILITARY = ['military']
const ECONOMY = ['economy']
const RESEARCH = ['research']
const DIPLOMACY = ['diplomacy']
const STATE = ['state']

function setTreasuryEverywhere(cache: GameCache, treasuryMg: number) {
  cache.patch<EconomyOverview>(ECONOMY, (e) => predict.setTreasury(e, treasuryMg))
}

function spendEverywhere(cache: GameCache, amountMg: number | null | undefined) {
  if (amountMg) {
    cache.patch<EconomyOverview>(ECONOMY, (e) => predict.spendGold(e, amountMg))
  }
}

function changePopulationEverywhere(cache: GameCache, cityId: string, delta: number) {
  cache.patch<EconomyOverview>(ECONOMY, (e) => predict.changeCityPopulation(e, cityId, delta))
  cache.patch<GameState>(STATE, (s) => predict.changeStatePopulation(s, cityId, delta))
}

/** The fortification view of a city, whichever province key it is cached under. */
function patchFortificationOfCity(cache: GameCache, cityId: string, change: (f: ProvinceFortification) => ProvinceFortification) {
  cache.patch<ProvinceFortification>(['fortification'], (f) => (f.cityId === cityId ? change(f) : f))
}

/** Withdrawn battalions and returned control, which only the server's answer knows exactly. */
function applyConsequences(cache: GameCache, withdrawals: { battalionId: string; toProvinceKey: string }[], controlReturned: string[] = []) {
  if (withdrawals.length > 0) {
    cache.patch<MilitaryOverview>(MILITARY, (m) => predict.withdraw(m, withdrawals))
  }
  if (controlReturned.length > 0) {
    cache.patch<GameState>(STATE, (s) => ({
      ...s,
      provinces: s.provinces.map((p) => (controlReturned.includes(p.key) ? { ...p, controllerNationId: p.ownerNationId } : p)),
    }))
  }
}

/** Agreed peace terms as both sides see them: the caller's gold, ceded provinces and moved people. */
function applyTerms(cache: GameCache, terms: PeaceTerm[], yourNationId: string) {
  const gold = predict.goldChangeOf(terms, yourNationId)
  if (gold !== 0) {
    cache.patch<EconomyOverview>(ECONOMY, (e) => predict.receiveGold(e, gold))
  }
  cache.patch<GameState>(STATE, (s) => predict.applyTermsToState(s, terms))
}

/**
 * Recruiting is shown at once: the battalion (with a pending id), the gold and the city's civilians;
 * the server's battalion replaces it when the command answers (D-UX1).
 */
export function useRecruit(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, unitType, idempotencyKey }: { cityId: string; unitType: UnitType; idempotencyKey: string }) =>
      api.recruit(gameId, cityId, unitType, idempotencyKey),
    {
      predict: (cache, { cityId, unitType }, seed) => {
        const military = cache.read<MilitaryOverview>(...MILITARY)
        const quote = military ? predict.recruitQuote(military, cityId, unitType) : null
        if (!quote) return
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.recruit(m, { cityId, unitType, battalionId: seed }))
        spendEverywhere(cache, quote.option.costMg)
        changePopulationEverywhere(cache, cityId, -quote.option.populationCost)
      },
      confirm: (cache, result, _input, seed) => {
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.confirmRecruit(m, seed, result.battalion))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

/**
 * Moves are shown at once (D-UX1). A plain move — into a province without foreign battalions, where
 * the rules cannot start a battle — is drawn at its target before the server answers, and rolled back
 * if the server refuses it. A move that may fight waits for the server, whose result (the battle
 * included) is applied as soon as the command answers. The server stays the only authority: the
 * state refetch that follows always replaces the prediction.
 */
export function useMove(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ battalionIds, toProvinceKey, idempotencyKey }: { battalionIds: string[]; toProvinceKey: string; idempotencyKey: string }) =>
      api.move(gameId, battalionIds, toProvinceKey, idempotencyKey),
    {
      predict: (cache, { battalionIds, toProvinceKey }) => {
        cache.patch<MilitaryOverview>(MILITARY, (military) => {
          const foreignInTarget = military.battalions.some(
            (b) => b.provinceKey === toProvinceKey && b.nationId !== military.nationId && b.commanderNationId !== military.nationId,
          )
          if (foreignInTarget) {
            return military
          }
          return {
            ...military,
            battalions: military.battalions.map((b) =>
              battalionIds.includes(b.id) ? { ...b, provinceKey: toProvinceKey, eligibility: 'ALREADY_MOVED' } : b,
            ),
          }
        })
      },
      confirm: (cache, result) => cache.patch<MilitaryOverview>(MILITARY, (m) => predict.withBattalions(m, result.battalions)),
    },
  )
}

/** The caller's research; refetched with the state version, so round-end points appear without polling it. */
export function useResearch(gameId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'research', stateVersion] as const,
    queryFn: () => api.getResearch(gameId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

export function useCityInstitutions(gameId: string, cityId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'institutions', cityId, stateVersion] as const,
    queryFn: () => api.getCityInstitutions(gameId, cityId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

export function useBuildInstitution(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, type, idempotencyKey }: { cityId: string; type: InstitutionType; idempotencyKey: string }) =>
      api.buildInstitution(gameId, cityId, type, idempotencyKey),
    {
      predict: (cache, { cityId, type }, seed) => {
        const institutions = cache.read<CityInstitutions>('institutions', cityId)
        if (!institutions) return
        cache.patch<CityInstitutions>(['institutions', cityId], (c) => predict.buildInstitution(c, type, seed))
        spendEverywhere(cache, predict.institutionCost(institutions, type))
      },
      confirm: (cache, result, { cityId, type }) => {
        cache.patch<CityInstitutions>(['institutions', cityId], (c) => ({
          ...c,
          options: c.options.map((o) => (o.type === type ? { ...o, existing: result.institution } : o)),
        }))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

export function useUnlockTechnology(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ track, targetLevel, idempotencyKey }: { track: ResearchTrack; targetLevel: number; idempotencyKey: string }) =>
      api.unlockTechnology(gameId, track, targetLevel, idempotencyKey),
    {
      predict: (cache, { track, targetLevel }) =>
        cache.patch<ResearchOverview>(RESEARCH, (r) => predict.unlockTechnology(r, track, targetLevel)),
      confirm: (cache, result) => cache.patch<ResearchOverview>(RESEARCH, () => result.research),
    },
  )
}

export function useUnlockDoctrine(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ doctrine, idempotencyKey }: { doctrine: string; idempotencyKey: string }) =>
      api.unlockDoctrine(gameId, doctrine, idempotencyKey),
    {
      predict: (cache, { doctrine }) => cache.patch<ResearchOverview>(RESEARCH, (r) => predict.unlockDoctrine(r, doctrine)),
      confirm: (cache, result) => cache.patch<ResearchOverview>(RESEARCH, () => result.research),
    },
  )
}

export function useUpgradeWorkshop(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, buildingId, idempotencyKey }: { cityId: string; buildingId: string; idempotencyKey: string }) =>
      api.upgradeWorkshop(gameId, cityId, buildingId, idempotencyKey),
    {
      predict: (cache, { cityId, buildingId }) =>
        cache.patch<EconomyOverview>(ECONOMY, (e) => predict.upgradeWorkshop(e, cityId, buildingId)),
      confirm: (cache, result, { cityId }) => {
        cache.patch<EconomyOverview>(ECONOMY, (e) => predict.replaceWorkshop(e, cityId, result.workshop))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

/** Fortresses and sieges of the game; refetched whenever the state version changes (e.g. a round ends). */
export function useSieges(gameId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'sieges', stateVersion] as const,
    queryFn: () => api.getSieges(gameId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

export function useProvinceFortification(gameId: string, provinceKey: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'fortification', provinceKey, stateVersion] as const,
    queryFn: () => api.getProvinceFortification(gameId, provinceKey),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

export function useBuildFortress(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, targetLevel, idempotencyKey }: { cityId: string; targetLevel: number; idempotencyKey: string }) =>
      api.buildFortress(gameId, cityId, targetLevel, idempotencyKey),
    {
      predict: (cache, { cityId }) => {
        const fort = cache.find<ProvinceFortification>(['fortification'], (f) => f.cityId === cityId)
        if (!fort?.upgrade) return
        patchFortificationOfCity(cache, cityId, (f) => predict.orderFortressWorks(f, 'UPGRADE'))
        spendEverywhere(cache, fort.upgrade.costMg)
      },
      confirm: (cache, result, { cityId }) => {
        patchFortificationOfCity(cache, cityId, (f) => ({ ...f, fortification: result.fortification }))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

export function useRepairFortress(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, idempotencyKey }: { cityId: string; idempotencyKey: string }) => api.repairFortress(gameId, cityId, idempotencyKey),
    {
      predict: (cache, { cityId }) => {
        const fort = cache.find<ProvinceFortification>(['fortification'], (f) => f.cityId === cityId)
        if (!fort?.repair) return
        patchFortificationOfCity(cache, cityId, (f) => predict.orderFortressWorks(f, 'REPAIR'))
        spendEverywhere(cache, fort.repair.costMg)
      },
      confirm: (cache, result, { cityId }) => {
        patchFortificationOfCity(cache, cityId, (f) => ({ ...f, fortification: result.fortification }))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

export function useRepairWorkshop(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, buildingId, idempotencyKey }: { cityId: string; buildingId: string; idempotencyKey: string }) =>
      api.repairWorkshop(gameId, cityId, buildingId, idempotencyKey),
    {
      predict: (cache, { cityId, buildingId }) =>
        cache.patch<EconomyOverview>(ECONOMY, (e) => predict.repairWorkshop(e, cityId, buildingId)),
      confirm: (cache, result, { cityId }) => {
        cache.patch<EconomyOverview>(ECONOMY, (e) => predict.replaceWorkshop(e, cityId, result.workshop))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

/** One own battalion with the server's reinforcement quote; refetched with the state version. */
export function useBattalion(gameId: string, battalionId: string | null, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'battalions', battalionId, stateVersion] as const,
    queryFn: () => api.getBattalion(gameId, battalionId!),
    enabled: battalionId !== null && stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

export function useReinforce(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ battalionId, soldiers, idempotencyKey }: { battalionId: string; soldiers: number; idempotencyKey: string }) =>
      api.reinforce(gameId, battalionId, soldiers, idempotencyKey),
    {
      predict: (cache, { battalionId, soldiers }) => {
        const details = cache.read<BattalionDetails>('battalions', battalionId)
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.reinforce(m, battalionId, soldiers))
        if (!details) return
        cache.patch<BattalionDetails>(['battalions', battalionId], (d) => predict.reinforceDetails(d, soldiers))
        spendEverywhere(cache, predict.reinforcementCost(details, soldiers))
        changePopulationEverywhere(cache, details.reinforcement.cityId, -soldiers)
      },
      confirm: (cache, result, { battalionId }) => {
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.withBattalions(m, [result.battalion]))
        cache.patch<BattalionDetails>(['battalions', battalionId], (d) => ({ ...d, battalion: result.battalion }))
        setTreasuryEverywhere(cache, result.treasuryMg)
      },
    },
  )
}

export function useDisband(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ battalionId, idempotencyKey }: { battalionId: string; idempotencyKey: string }) => api.disband(gameId, battalionId, idempotencyKey),
    {
      predict: (cache, { battalionId }) => {
        const details = cache.read<BattalionDetails>('battalions', battalionId)
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.disband(m, battalionId))
        if (!details) return
        details.disband.returns.forEach((r) => changePopulationEverywhere(cache, r.cityId, r.soldiers))
      },
    },
  )
}

export function useRegroup(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ name, battalionIds, idempotencyKey }: { name: string; battalionIds: string[]; idempotencyKey: string }) =>
      api.regroup(gameId, name, battalionIds, idempotencyKey),
    {
      predict: (cache, { name, battalionIds }, seed) =>
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.formArmy(m, { armyId: seed, name, battalionIds })),
      confirm: (cache, result, _input, seed) => cache.patch<MilitaryOverview>(MILITARY, (m) => predict.confirmArmy(m, seed, result.army)),
    },
  )
}

export function useBuildWorkshop(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ cityId, idempotencyKey }: { cityId: string; idempotencyKey: string }) => api.buildWorkshop(gameId, cityId, 1, idempotencyKey),
    {
      predict: (cache, { cityId }, seed) => cache.patch<EconomyOverview>(ECONOMY, (e) => predict.buildWorkshop(e, cityId, seed)),
      confirm: (cache, result) =>
        cache.patch<EconomyOverview>(ECONOMY, (e) =>
          predict.setTreasury({ ...e, cities: e.cities.map((c) => (c.cityId === result.city.cityId ? result.city : c)) }, result.treasuryMg),
        ),
    },
  )
}

// --- Phase 9: diplomacy and match completion ----------------------------------------------------

/** The caller's diplomatic picture; refetched with the state version like every other game view. */
/** Every battle of the game (newest first, as the server orders them); for the History window. */
export function useBattles(gameId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'battles', 'all', stateVersion] as const,
    queryFn: () => api.getBattles(gameId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

/** The public diplomatic event log (the server keeps the latest 200); for the History window. */
export function useDiplomacyEvents(gameId: string, stateVersion: number | undefined, enabled = true) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'diplomacy-events', stateVersion] as const,
    queryFn: () => api.getDiplomacyEvents(gameId),
    enabled: enabled && stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

export function useDiplomacy(gameId: string, stateVersion: number | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'diplomacy', stateVersion] as const,
    queryFn: () => api.getDiplomacy(gameId),
    enabled: stateVersion !== undefined,
    placeholderData: keepPreviousData,
  })
}

/** A stored result never changes once the game has finished. */
export function useGameResult(gameId: string, finished: boolean) {
  const api = useApi()
  return useQuery({
    queryKey: ['games', gameId, 'result'] as const,
    queryFn: () => api.getResult(gameId),
    enabled: finished,
    staleTime: Infinity,
  })
}

export function useDeclareWar(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ targetNationId, idempotencyKey, invite }: { targetNationId: string; idempotencyKey: string; invite?: string[] }) =>
      invite && invite.length > 0
        ? api.declareWar(gameId, targetNationId, idempotencyKey, invite)
        : api.declareWar(gameId, targetNationId, idempotencyKey),
    {
      predict: (cache, { targetNationId }, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) =>
          predict.declareWar(d, {
            targetNationId,
            warId: seed,
            targetName: d.nations.find((n) => n.nationId === targetNationId)?.name ?? '',
          }),
        ),
      // Allies that joined the defender arrive with the server's answer and the refetch that follows.
      confirm: (cache, result, _input, seed) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceWar(d, result.war, seed)),
    },
  )
}

export function useProposeAlliance(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ nationId, kind, idempotencyKey }: { nationId: string; kind: AllianceKind; idempotencyKey: string }) =>
      api.proposeAlliance(gameId, nationId, kind, idempotencyKey),
    {
      predict: (cache, { nationId, kind }, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.proposeAlliance(d, { nationId, kind, allianceId: seed })),
      confirm: (cache, result, _input, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceAlliance(d, result.alliance, seed)),
    },
  )
}

export function useAllianceAction(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ allianceId, action, idempotencyKey }: { allianceId: string; action: 'accept' | 'reject' | 'terminate'; idempotencyKey: string }) =>
      api.allianceAction(gameId, allianceId, action, idempotencyKey),
    {
      predict: (cache, { allianceId, action }) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.allianceAction(d, allianceId, action)),
      confirm: (cache, result) => {
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceAlliance(d, result.alliance))
        applyConsequences(cache, result.withdrawals)
      },
    },
  )
}

export function useProposePeace(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ warId, terms, idempotencyKey }: { warId: string; terms: PeaceTerm[]; idempotencyKey: string }) =>
      api.proposePeace(gameId, warId, terms, idempotencyKey),
    {
      predict: (cache, { warId, terms }, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) =>
          predict.proposePeace(d, { warId, terms, proposalId: seed, now: new Date().toISOString() }),
        ),
      confirm: (cache, result, _input, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceProposal(d, result.proposal, seed)),
    },
  )
}

export function useCounterPeace(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ proposalId, terms, idempotencyKey }: { proposalId: string; terms: PeaceTerm[]; idempotencyKey: string }) =>
      api.counterPeace(gameId, proposalId, terms, idempotencyKey),
    {
      predict: (cache, { proposalId, terms }, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => {
          const original = d.proposals.find((p) => p.id === proposalId)
          return original
            ? predict.proposePeace(d, { warId: original.warId, terms, proposalId: seed, counterOfId: proposalId, now: new Date().toISOString() })
            : d
        }),
      confirm: (cache, result, _input, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceProposal(d, result.proposal, seed)),
    },
  )
}

/**
 * Accepting peace ends the war at once, with the agreed terms as both sides see them (gold, ceded
 * provinces, moved people); withdrawn battalions and returned control arrive with the server's answer.
 */
export function useAcceptPeace(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ proposalId, idempotencyKey }: { proposalId: string; idempotencyKey: string }) => api.acceptPeace(gameId, proposalId, idempotencyKey),
    {
      predict: (cache, { proposalId }) => {
        const diplomacy = cache.read<DiplomacyOverview>(...DIPLOMACY)
        const proposal = diplomacy?.proposals.find((p) => p.id === proposalId)
        if (!diplomacy || !proposal) return
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.acceptPeace(d, proposalId))
        applyTerms(cache, proposal.terms, diplomacy.yourNationId)
      },
      confirm: (cache, result) => {
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => ({
          ...predict.replaceWar(d, result.war),
          treaties: [result.treaty, ...d.treaties.filter((t) => t.id !== result.treaty.id)],
        }))
        applyConsequences(cache, result.withdrawals, result.controlReturned)
      },
    },
  )
}

export function usePeaceAction(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ proposalId, action, idempotencyKey }: { proposalId: string; action: 'reject' | 'cancel'; idempotencyKey: string }) =>
      api.peaceAction(gameId, proposalId, action, idempotencyKey),
    {
      predict: (cache, { proposalId, action }) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.peaceAction(d, proposalId, action)),
      confirm: (cache, result) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceProposal(d, result.proposal)),
    },
  )
}

/** Asks the server whether terms would be accepted now; changes nothing. */
export function useCheckPeace(gameId: string) {
  const api = useApi()
  return useMutation({
    mutationFn: ({ warId, terms }: { warId: string; terms: PeaceTerm[] }) => api.checkPeace(gameId, warId, terms),
  })
}

export function useSendGold(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ recipientNationId, amountMg, idempotencyKey }: { recipientNationId: string; amountMg: number; idempotencyKey: string }) =>
      api.sendGold(gameId, recipientNationId, amountMg, idempotencyKey),
    {
      predict: (cache, { recipientNationId, amountMg }, seed) => {
        spendEverywhere(cache, amountMg)
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) =>
          predict.sendGift(d, { giftId: seed, recipientNationId, amountMg, now: new Date().toISOString() }),
        )
      },
      confirm: (cache, result, _input, seed) => {
        setTreasuryEverywhere(cache, result.treasuryMg)
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => ({
          ...d,
          gifts: [result.gift, ...d.gifts.filter((g) => g.id !== seed && g.id !== result.gift.id)],
        }))
      },
    },
  )
}

// --- Phase 10: coalitions, calls to arms and peace conferences ----------------------------------

export function useAttach(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ battalionIds, commanderNationId, idempotencyKey }: { battalionIds: string[]; commanderNationId: string; idempotencyKey: string }) =>
      api.attach(gameId, battalionIds, commanderNationId, idempotencyKey),
    {
      predict: (cache, { battalionIds, commanderNationId }) =>
        cache.patch<MilitaryOverview>(MILITARY, (m) => predict.attach(m, battalionIds, commanderNationId)),
      confirm: (cache, result) => cache.patch<MilitaryOverview>(MILITARY, (m) => predict.withBattalions(m, result.battalions)),
    },
  )
}

export function useRelease(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ battalionIds, idempotencyKey }: { battalionIds: string[]; idempotencyKey: string }) => api.release(gameId, battalionIds, idempotencyKey),
    {
      predict: (cache, { battalionIds }) => cache.patch<MilitaryOverview>(MILITARY, (m) => predict.release(m, battalionIds)),
      confirm: (cache, result) => cache.patch<MilitaryOverview>(MILITARY, (m) => predict.withBattalions(m, result.battalions)),
    },
  )
}

export function useCallToArms(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ warId, allyNationId, idempotencyKey }: { warId: string; allyNationId: string; idempotencyKey: string }) =>
      api.callToArms(gameId, warId, allyNationId, idempotencyKey),
    {
      predict: (cache, { warId, allyNationId }, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.callToArms(d, { callId: seed, warId, allyNationId })),
      confirm: (cache, result, _input, seed) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceCall(d, result.call, seed)),
    },
  )
}

export function useAnswerCall(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ callId, accept, idempotencyKey }: { callId: string; accept: boolean; idempotencyKey: string }) =>
      api.answerCall(gameId, callId, accept, idempotencyKey),
    {
      predict: (cache, { callId, accept }) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.answerCall(d, callId, accept)),
      confirm: (cache, result) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceCall(d, result.call)),
    },
  )
}

export function useRequestConference(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ warId, kind, idempotencyKey }: { warId: string; kind: ConferenceKind; idempotencyKey: string }) =>
      api.requestConference(gameId, warId, kind, idempotencyKey),
    {
      predict: (cache, { warId, kind }, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.requestConference(d, { conferenceId: seed, warId, kind })),
      confirm: (cache, result, _input, seed) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceConference(d, result.conference, seed)),
    },
  )
}

export function useAnswerConference(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ conferenceId, accept, idempotencyKey }: { conferenceId: string; accept: boolean; idempotencyKey: string }) =>
      api.answerConference(gameId, conferenceId, accept, idempotencyKey),
    {
      predict: (cache, { conferenceId, accept }) => {
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.answerConference(d, conferenceId, accept))
        if (accept) cache.patch<GameState>(STATE, (s) => ({ ...s, pausedConferenceId: conferenceId }))
      },
      confirm: (cache, result) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceConference(d, result.conference)),
    },
  )
}

export function useCancelConference(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ conferenceId, idempotencyKey }: { conferenceId: string; idempotencyKey: string }) =>
      api.cancelConference(gameId, conferenceId, idempotencyKey),
    {
      predict: (cache, { conferenceId }) => {
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.cancelConference(d, conferenceId))
        cache.patch<GameState>(STATE, (s) => (s.pausedConferenceId === conferenceId ? { ...s, pausedConferenceId: null } : s))
      },
      confirm: (cache, result) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceConference(d, result.conference)),
    },
  )
}

export function useSubmitDraft(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ conferenceId, expectedRevision, terms, idempotencyKey }: {
      conferenceId: string
      expectedRevision: number
      terms: PeaceTerm[]
      idempotencyKey: string
    }) => api.submitDraft(gameId, conferenceId, expectedRevision, terms, idempotencyKey),
    {
      predict: (cache, { conferenceId, terms }) =>
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.submitDraft(d, conferenceId, terms, new Date().toISOString())),
      confirm: (cache, result) => cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.replaceConference(d, result.conference)),
    },
  )
}

/**
 * A decision on a draft is shown at once. The last approval settles the treaty: the general peace ends
 * the war and its terms are shown at once; withdrawals and returned control arrive with the answer.
 */
export function useDecideDraft(gameId: string) {
  const api = useApi()
  return useGameCommand(
    gameId,
    ({ conferenceId, revision, action, idempotencyKey }: {
      conferenceId: string
      revision: number
      action: 'approve' | 'reject'
      idempotencyKey: string
    }) => api.decideDraft(gameId, conferenceId, revision, action, idempotencyKey),
    {
      predict: (cache, { conferenceId, action }) => {
        const diplomacy = cache.read<DiplomacyOverview>(...DIPLOMACY)
        const conference = diplomacy?.conferences?.find((c) => c.id === conferenceId)
        if (!diplomacy || !conference) return
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => predict.decideDraft(d, conferenceId, action === 'approve'))
        if (action === 'approve' && conference.kind === 'GENERAL' && predict.settlesWith(conference, diplomacy.yourNationId)) {
          applyTerms(cache, conference.terms, diplomacy.yourNationId)
        }
      },
      confirm: (cache, result) => {
        cache.patch<DiplomacyOverview>(DIPLOMACY, (d) => {
          const next = predict.replaceConference(d, result.conference)
          return result.treaty ? { ...next, treaties: [result.treaty, ...next.treaties.filter((t) => t.id !== result.treaty!.id)] } : next
        })
        applyConsequences(cache, result.withdrawals)
      },
    },
  )
}
