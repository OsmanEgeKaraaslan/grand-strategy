import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from './ApiContext'
import type { AllianceKind, ConferenceKind, InstitutionType, Lobby, PeaceTerm, ResearchTrack, UnitType } from './types'

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
      query.state.data?.status === 'IN_PROGRESS' || query.state.data?.status === 'FINISHED' ? false : LOBBY_POLL_INTERVAL_MS,
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

/** Military commands bump the state version: refreshing the state refreshes economy and forces. */
function useGameCommand<I, R>(gameId: string, action: (input: I) => Promise<R>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['games', gameId, 'state'] }),
  })
}

export function useRecruit(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ cityId, unitType, idempotencyKey }: {
    cityId: string
    unitType: UnitType
    idempotencyKey: string
  }) => api.recruit(gameId, cityId, unitType, idempotencyKey))
}

export function useMove(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ battalionIds, toProvinceKey, idempotencyKey }: {
    battalionIds: string[]
    toProvinceKey: string
    idempotencyKey: string
  }) => api.move(gameId, battalionIds, toProvinceKey, idempotencyKey))
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
  return useGameCommand(gameId, ({ cityId, type, idempotencyKey }: {
    cityId: string
    type: InstitutionType
    idempotencyKey: string
  }) => api.buildInstitution(gameId, cityId, type, idempotencyKey))
}

export function useUnlockTechnology(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ track, targetLevel, idempotencyKey }: {
    track: ResearchTrack
    targetLevel: number
    idempotencyKey: string
  }) => api.unlockTechnology(gameId, track, targetLevel, idempotencyKey))
}

export function useUnlockDoctrine(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ doctrine, idempotencyKey }: { doctrine: string; idempotencyKey: string }) =>
    api.unlockDoctrine(gameId, doctrine, idempotencyKey),
  )
}

export function useUpgradeWorkshop(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ cityId, buildingId, idempotencyKey }: {
    cityId: string
    buildingId: string
    idempotencyKey: string
  }) => api.upgradeWorkshop(gameId, cityId, buildingId, idempotencyKey))
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
  return useGameCommand(gameId, ({ cityId, targetLevel, idempotencyKey }: {
    cityId: string
    targetLevel: number
    idempotencyKey: string
  }) => api.buildFortress(gameId, cityId, targetLevel, idempotencyKey))
}

export function useRepairFortress(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ cityId, idempotencyKey }: { cityId: string; idempotencyKey: string }) =>
    api.repairFortress(gameId, cityId, idempotencyKey),
  )
}

export function useRepairWorkshop(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ cityId, buildingId, idempotencyKey }: {
    cityId: string
    buildingId: string
    idempotencyKey: string
  }) => api.repairWorkshop(gameId, cityId, buildingId, idempotencyKey))
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
  return useGameCommand(gameId, ({ battalionId, soldiers, idempotencyKey }: {
    battalionId: string
    soldiers: number
    idempotencyKey: string
  }) => api.reinforce(gameId, battalionId, soldiers, idempotencyKey))
}

export function useDisband(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ battalionId, idempotencyKey }: { battalionId: string; idempotencyKey: string }) =>
    api.disband(gameId, battalionId, idempotencyKey),
  )
}

export function useRegroup(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ name, battalionIds, idempotencyKey }: {
    name: string
    battalionIds: string[]
    idempotencyKey: string
  }) => api.regroup(gameId, name, battalionIds, idempotencyKey))
}

export function useBuildWorkshop(gameId: string) {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ cityId, idempotencyKey }: { cityId: string; idempotencyKey: string }) =>
      api.buildWorkshop(gameId, cityId, 1, idempotencyKey),
    // The order bumped the state version: refreshing the state also refreshes the economy.
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['games', gameId, 'state'] }),
  })
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
  )
}

export function useProposeAlliance(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ nationId, kind, idempotencyKey }: {
    nationId: string
    kind: AllianceKind
    idempotencyKey: string
  }) => api.proposeAlliance(gameId, nationId, kind, idempotencyKey))
}

export function useAllianceAction(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ allianceId, action, idempotencyKey }: {
    allianceId: string
    action: 'accept' | 'reject' | 'terminate'
    idempotencyKey: string
  }) => api.allianceAction(gameId, allianceId, action, idempotencyKey))
}

export function useProposePeace(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ warId, terms, idempotencyKey }: {
    warId: string
    terms: PeaceTerm[]
    idempotencyKey: string
  }) => api.proposePeace(gameId, warId, terms, idempotencyKey))
}

export function useCounterPeace(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ proposalId, terms, idempotencyKey }: {
    proposalId: string
    terms: PeaceTerm[]
    idempotencyKey: string
  }) => api.counterPeace(gameId, proposalId, terms, idempotencyKey))
}

export function useAcceptPeace(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ proposalId, idempotencyKey }: { proposalId: string; idempotencyKey: string }) =>
    api.acceptPeace(gameId, proposalId, idempotencyKey),
  )
}

export function usePeaceAction(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ proposalId, action, idempotencyKey }: {
    proposalId: string
    action: 'reject' | 'cancel'
    idempotencyKey: string
  }) => api.peaceAction(gameId, proposalId, action, idempotencyKey))
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
  return useGameCommand(gameId, ({ recipientNationId, amountMg, idempotencyKey }: {
    recipientNationId: string
    amountMg: number
    idempotencyKey: string
  }) => api.sendGold(gameId, recipientNationId, amountMg, idempotencyKey))
}

// --- Phase 10: coalitions, calls to arms and peace conferences ----------------------------------

export function useAttach(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ battalionIds, commanderNationId, idempotencyKey }: {
    battalionIds: string[]
    commanderNationId: string
    idempotencyKey: string
  }) => api.attach(gameId, battalionIds, commanderNationId, idempotencyKey))
}

export function useRelease(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ battalionIds, idempotencyKey }: { battalionIds: string[]; idempotencyKey: string }) =>
    api.release(gameId, battalionIds, idempotencyKey),
  )
}

export function useCallToArms(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ warId, allyNationId, idempotencyKey }: {
    warId: string
    allyNationId: string
    idempotencyKey: string
  }) => api.callToArms(gameId, warId, allyNationId, idempotencyKey))
}

export function useAnswerCall(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ callId, accept, idempotencyKey }: { callId: string; accept: boolean; idempotencyKey: string }) =>
    api.answerCall(gameId, callId, accept, idempotencyKey),
  )
}

export function useRequestConference(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ warId, kind, idempotencyKey }: { warId: string; kind: ConferenceKind; idempotencyKey: string }) =>
    api.requestConference(gameId, warId, kind, idempotencyKey),
  )
}

export function useAnswerConference(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ conferenceId, accept, idempotencyKey }: {
    conferenceId: string
    accept: boolean
    idempotencyKey: string
  }) => api.answerConference(gameId, conferenceId, accept, idempotencyKey))
}

export function useCancelConference(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ conferenceId, idempotencyKey }: { conferenceId: string; idempotencyKey: string }) =>
    api.cancelConference(gameId, conferenceId, idempotencyKey),
  )
}

export function useSubmitDraft(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ conferenceId, expectedRevision, terms, idempotencyKey }: {
    conferenceId: string
    expectedRevision: number
    terms: PeaceTerm[]
    idempotencyKey: string
  }) => api.submitDraft(gameId, conferenceId, expectedRevision, terms, idempotencyKey))
}

export function useDecideDraft(gameId: string) {
  const api = useApi()
  return useGameCommand(gameId, ({ conferenceId, revision, action, idempotencyKey }: {
    conferenceId: string
    revision: number
    action: 'approve' | 'reject'
    idempotencyKey: string
  }) => api.decideDraft(gameId, conferenceId, revision, action, idempotencyKey))
}
