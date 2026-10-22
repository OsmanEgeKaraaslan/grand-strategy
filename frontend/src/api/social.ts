import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from './ApiContext'
import { queryKeys } from './queries'
import type { ProfileChanges, QuickMatch } from './types'

/**
 * Social and matchmaking server state (Phases 12–13). Polling is light and purpose-built: the
 * unread count is one small number, lists refresh only while mounted, and TanStack Query stops
 * every interval on unmount and while the tab is hidden (except the search heartbeat, see below).
 */
export const SOCIAL_POLL_MS = 20_000
/** A search is dropped by the server after 90 s without a heartbeat; this keeps it well alive. */
export const QUICK_MATCH_HEARTBEAT_MS = 10_000

export const socialKeys = {
  profile: (username: string) => ['profiles', username] as const,
  search: (query: string) => ['players', 'search', query] as const,
  friends: ['friends'] as const,
  notifications: ['notifications'] as const,
  unread: ['notifications', 'unread'] as const,
  invitations: ['invitations'] as const,
  sentInvitations: (gameId: string) => ['games', gameId, 'invitations'] as const,
  quickMatch: ['quick-match'] as const,
}

export function useUpdateProfile() {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (changes: ProfileChanges) => api.updateMe(changes),
    onSuccess: (profile) => {
      queryClient.setQueryData(queryKeys.me, profile)
      return queryClient.invalidateQueries({ queryKey: ['profiles'] })
    },
  })
}

export function useProfile(username: string | undefined) {
  const api = useApi()
  return useQuery({
    queryKey: socialKeys.profile(username ?? ''),
    queryFn: () => api.getProfile(username!),
    enabled: Boolean(username),
    retry: (count, error) => !('status' in error && error.status === 404) && count < 2,
  })
}

export function usePlayerSearch(query: string) {
  const api = useApi()
  const trimmed = query.trim()
  return useQuery({
    queryKey: socialKeys.search(trimmed.toLowerCase()),
    queryFn: () => api.searchPlayers(trimmed),
    enabled: trimmed.length > 0,
    staleTime: 10_000,
  })
}

export function useFriends() {
  const api = useApi()
  return useQuery({ queryKey: socialKeys.friends, queryFn: api.getFriends, refetchInterval: SOCIAL_POLL_MS })
}

/** Every friend-graph change refreshes the lists, search results and profiles that show relations. */
function useSocialMutation<I, O>(action: (input: I) => Promise<O>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: socialKeys.friends }),
        queryClient.invalidateQueries({ queryKey: ['players', 'search'] }),
        queryClient.invalidateQueries({ queryKey: ['profiles'] }),
        queryClient.invalidateQueries({ queryKey: socialKeys.notifications }),
      ]),
  })
}

export function useSendFriendRequest() {
  const api = useApi()
  return useSocialMutation((username: string) => api.sendFriendRequest(username))
}

export function useAnswerFriendRequest() {
  const api = useApi()
  return useSocialMutation(({ id, action }: { id: string; action: 'accept' | 'decline' | 'cancel' }) =>
    api.answerFriendRequest(id, action),
  )
}

export function useRemoveFriend() {
  const api = useApi()
  return useSocialMutation((username: string) => api.removeFriend(username))
}

export function useBlockPlayer() {
  const api = useApi()
  return useSocialMutation((username: string) => api.blockPlayer(username))
}

export function useUnblockPlayer() {
  const api = useApi()
  return useSocialMutation((username: string) => api.unblockPlayer(username))
}

/** The cheap poll behind the bell: a single number. */
export function useUnreadCount() {
  const api = useApi()
  return useQuery({ queryKey: socialKeys.unread, queryFn: api.getUnreadCount, refetchInterval: SOCIAL_POLL_MS })
}

export function useNotifications(enabled: boolean) {
  const api = useApi()
  return useQuery({ queryKey: socialKeys.notifications, queryFn: api.getNotifications, enabled, refetchInterval: SOCIAL_POLL_MS })
}

export function useMarkNotificationsRead() {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (ids?: string[]) => api.markNotificationsRead(ids),
    onSuccess: (view) => {
      queryClient.setQueryData(socialKeys.notifications, view)
      queryClient.setQueryData(socialKeys.unread, { unread: view.unread })
    },
  })
}

export function useInvitations() {
  const api = useApi()
  return useQuery({ queryKey: socialKeys.invitations, queryFn: api.getInvitations, refetchInterval: SOCIAL_POLL_MS })
}

export function useSentInvitations(gameId: string, enabled: boolean) {
  const api = useApi()
  return useQuery({
    queryKey: socialKeys.sentInvitations(gameId),
    queryFn: () => api.getSentInvitations(gameId),
    enabled,
    refetchInterval: SOCIAL_POLL_MS,
  })
}

export function useInviteToGame() {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ gameId, username }: { gameId: string; username: string }) => api.inviteToGame(gameId, username),
    onSettled: (_data, _error, { gameId }) => queryClient.invalidateQueries({ queryKey: socialKeys.sentInvitations(gameId) }),
  })
}

export function useAnswerInvitation() {
  const api = useApi()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'accept' | 'decline' | 'cancel' }) => api.answerInvitation(id, action),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: socialKeys.invitations }),
        queryClient.invalidateQueries({ queryKey: queryKeys.games, exact: true }),
      ]),
  })
}

/**
 * The Quick Match state. Loading it never starts a search. While SEARCHING, a heartbeat every
 * {@link QUICK_MATCH_HEARTBEAT_MS} keeps the search alive and lets the server pair it; it keeps
 * running in a hidden tab (browsers slow it to about once a minute, inside the server's timeout).
 */
export function useQuickMatch() {
  const api = useApi()
  const queryClient = useQueryClient()
  const status = useQuery({ queryKey: socialKeys.quickMatch, queryFn: api.getQuickMatch })
  useQuery({
    queryKey: [...socialKeys.quickMatch, 'heartbeat'] as const,
    queryFn: async () => {
      const view = await api.quickMatchHeartbeat()
      queryClient.setQueryData(socialKeys.quickMatch, view)
      if (view.state === 'MATCHED') void queryClient.invalidateQueries({ queryKey: queryKeys.games, exact: true })
      return view
    },
    enabled: status.data?.state === 'SEARCHING',
    refetchInterval: QUICK_MATCH_HEARTBEAT_MS,
    refetchIntervalInBackground: true,
    // The first heartbeat waits one interval: the join itself just looked for an opponent.
    initialData: status.data?.state === 'SEARCHING' ? status.data : undefined,
    initialDataUpdatedAt: () => Date.now(),
  })
  return status
}

function useQuickMatchAction(action: () => Promise<QuickMatch>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    onSuccess: (view) => {
      queryClient.setQueryData(socialKeys.quickMatch, view)
      if (view.state === 'MATCHED') return queryClient.invalidateQueries({ queryKey: queryKeys.games, exact: true })
    },
  })
}

export function useJoinQuickMatch() {
  const api = useApi()
  return useQuickMatchAction(api.joinQuickMatch)
}

export function useCancelQuickMatch() {
  const api = useApi()
  return useQuickMatchAction(api.cancelQuickMatch)
}

export function useAcknowledgeQuickMatch() {
  const api = useApi()
  return useQuickMatchAction(api.acknowledgeQuickMatch)
}
