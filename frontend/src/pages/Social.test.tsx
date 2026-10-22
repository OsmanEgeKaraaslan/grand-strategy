import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { FriendsOverview, GameSummary, Invitation, PublicProfile, QuickMatch } from '../api/types'
import { fakeApi, renderPage } from '../test/render'
import { economyFixture, gameStateFixture, mapFixture, militaryFixture } from '../test/fixtures'
import { DashboardPage } from './DashboardPage'
import { GamePage } from './GamePage'
import { LobbyPage } from './LobbyPage'
import { ProfilePage } from './ProfilePage'
import { lobbyFixture } from '../test/fixtures'

const me = {
  id: 'p1',
  username: 'aldric',
  displayName: 'Aldric',
  emblem: 'crown' as const,
  usernameChosen: true,
  matchHistoryPublic: true,
  createdAt: '',
  updatedAt: '',
}

function profile(overrides: Partial<PublicProfile> = {}): PublicProfile {
  return {
    username: 'bryn',
    displayName: 'Bryn',
    emblem: 'tower',
    memberSince: '2026-10-01T00:00:00Z',
    you: false,
    relation: 'NONE',
    matchHistoryPublic: true,
    statistics: {
      completed: 3,
      wins: 1,
      losses: 1,
      draws: 1,
      winRateBps: 3333,
      favouriteFaction: { key: 'varn', name: 'Kingdom of Varn', played: 2, wins: 1 },
      factions: [
        { key: 'varn', name: 'Kingdom of Varn', played: 2, wins: 1 },
        { key: 'ostra', name: 'Ostran Confederacy', played: 1, wins: 0 },
      ],
    },
    recentMatches: [
      {
        gameId: null,
        gameName: 'Twin Shores',
        mapName: 'The Sundered Reach',
        finishedAt: '2026-10-20T10:00:00Z',
        finishedRound: 30,
        outcome: 'WIN',
        rank: 1,
        factionName: 'Kingdom of Varn',
      },
    ],
    ...overrides,
  }
}

const friendsWith = (overrides: Partial<FriendsOverview> = {}): FriendsOverview => ({
  friends: [{ username: 'bryn', displayName: 'Bryn', emblem: 'tower', since: '2026-10-10T00:00:00Z' }],
  incoming: [{ id: 'r1', username: 'cara', displayName: 'Cara', emblem: 'wheat', createdAt: '', status: 'PENDING' }],
  outgoing: [{ id: 'r2', username: 'dorn', displayName: 'Dorn', emblem: 'flag', createdAt: '', status: 'PENDING' }],
  blocked: [],
  ...overrides,
})

const lobbySummary = (overrides: Partial<GameSummary> = {}): GameSummary => ({
  id: 'lobby-1',
  name: 'Friends only',
  status: 'WAITING_FOR_PLAYERS',
  playerCount: 1,
  maxPlayers: 2,
  yourSeat: 0,
  youAreHost: true,
  createdAt: '',
  mapName: 'Grand Campaign',
  ...overrides,
})

function dashboard(overrides = {}) {
  return renderPage(<DashboardPage />, {
    api: fakeApi({ getMe: vi.fn().mockResolvedValue(me), listGames: vi.fn().mockResolvedValue([]), listMaps: vi.fn().mockResolvedValue([]), ...overrides }),
    extraRoutes: [
      { path: '/games/:gameId', element: <p>Lobby page</p> },
      { path: '/games/:gameId/play', element: <p>Game page</p> },
      { path: '/players/:username', element: <p>Profile page</p> },
    ],
  })
}

describe('Profiles (12A)', () => {
  it('shows a public profile with its record, favourite faction and recent matches', async () => {
    const sendFriendRequest = vi.fn().mockResolvedValue({ id: 'r9', status: 'PENDING' })
    renderPage(<ProfilePage />, {
      route: '/players/bryn',
      path: '/players/:username',
      api: fakeApi({ getMe: vi.fn().mockResolvedValue(me), getProfile: vi.fn().mockResolvedValue(profile()), sendFriendRequest }),
    })

    expect(await screen.findByRole('heading', { name: 'Bryn' })).toBeInTheDocument()
    expect(screen.getByText(/@bryn · member since/)).toBeInTheDocument()
    const record = screen.getByRole('region', { name: 'Record' })
    expect(within(record).getByText('33.33%')).toBeInTheDocument()
    expect(within(record).getAllByText('Kingdom of Varn').length).toBeGreaterThan(0)
    expect(within(record).getByText('Win')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Profile settings' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Add friend' }))
    expect(sendFriendRequest).toHaveBeenCalledWith('bryn')
  })

  it('says when another player keeps their history private, and handles unknown players', async () => {
    renderPage(<ProfilePage />, {
      route: '/players/bryn',
      path: '/players/:username',
      api: fakeApi({ getMe: vi.fn().mockResolvedValue(me), getProfile: vi.fn().mockResolvedValue(profile({ recentMatches: null })) }),
    })
    expect(await screen.findByText('Bryn keeps their match history private.')).toBeInTheDocument()
  })

  it('reports a missing player', async () => {
    renderPage(<ProfilePage />, {
      route: '/players/nobody',
      path: '/players/:username',
      api: fakeApi({
        getMe: vi.fn().mockResolvedValue(me),
        getProfile: vi.fn().mockRejectedValue(new ApiError({ status: 404, code: 'PLAYER_NOT_FOUND' })),
      }),
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('No player has that username.')
  })

  it('validates and saves the owner’s settings', async () => {
    const updateMe = vi.fn().mockResolvedValue({ ...me, username: 'aldric_bold', emblem: 'tower' })
    renderPage(<ProfilePage />, {
      route: '/players/aldric',
      path: '/players/:username',
      api: fakeApi({
        getMe: vi.fn().mockResolvedValue(me),
        getProfile: vi.fn().mockResolvedValue(profile({ username: 'aldric', displayName: 'Aldric', you: true, relation: 'YOU' })),
        updateMe,
      }),
    })
    const settings = await screen.findByRole('region', { name: 'Profile settings' })
    const username = within(settings).getByRole('textbox', { name: /Username/ })
    const save = within(settings).getByRole('button', { name: 'Save changes' })
    expect(save).toBeDisabled()

    await userEvent.clear(username)
    await userEvent.type(username, '9lives')
    expect(username).toHaveAttribute('aria-invalid', 'true')
    expect(within(settings).getByText(/starting with a letter/)).toBeInTheDocument()
    expect(save).toBeDisabled()

    await userEvent.clear(username)
    await userEvent.type(username, 'Aldric_Bold')
    await userEvent.click(within(settings).getByRole('radio', { name: 'Tower' }))
    await userEvent.click(save)
    expect(updateMe).toHaveBeenCalledWith({ username: 'aldric_bold', displayName: undefined, emblem: 'tower', matchHistoryPublic: undefined })
  })

  it('shows a taken username as the server reports it', async () => {
    renderPage(<ProfilePage />, {
      route: '/players/aldric',
      path: '/players/:username',
      api: fakeApi({
        getMe: vi.fn().mockResolvedValue(me),
        getProfile: vi.fn().mockResolvedValue(profile({ username: 'aldric', displayName: 'Aldric', you: true, relation: 'YOU' })),
        updateMe: vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'USERNAME_TAKEN' })),
      }),
    })
    const settings = await screen.findByRole('region', { name: 'Profile settings' })
    const username = within(settings).getByRole('textbox', { name: /Username/ })
    await userEvent.clear(username)
    await userEvent.type(username, 'bryn')
    await userEvent.click(within(settings).getByRole('button', { name: 'Save changes' }))
    expect(await within(settings).findByRole('alert')).toHaveTextContent('That username is already taken.')
  })
})

describe('Friends (12B)', () => {
  it('finds players by username and sends a friend request', async () => {
    const searchPlayers = vi.fn().mockResolvedValue([
      { username: 'bryn', displayName: 'Bryn', emblem: 'tower', relation: 'NONE' },
      { username: 'brynhild', displayName: 'Brynhild', emblem: 'flag', relation: 'FRIEND' },
    ])
    const sendFriendRequest = vi.fn().mockResolvedValue({ id: 'r1', status: 'PENDING' })
    dashboard({ searchPlayers, sendFriendRequest })

    const social = await screen.findByRole('region', { name: 'Social' })
    await userEvent.type(within(social).getByRole('textbox', { name: 'Find players by username' }), 'bryn{Enter}')
    const results = await within(social).findByRole('list', { name: 'Search results' })
    expect(searchPlayers).toHaveBeenCalledWith('bryn')
    expect(within(results).getByText('Friend')).toBeInTheDocument()
    await userEvent.click(within(results).getByRole('button', { name: 'Add Bryn as a friend' }))
    expect(sendFriendRequest).toHaveBeenCalledWith('bryn')
  })

  it('accepts, declines and cancels requests and removes a friend after confirming', async () => {
    const answerFriendRequest = vi.fn().mockResolvedValue({ status: 'ACCEPTED' })
    const removeFriend = vi.fn().mockResolvedValue(undefined)
    dashboard({ getFriends: vi.fn().mockResolvedValue(friendsWith()), answerFriendRequest, removeFriend })

    const social = await screen.findByRole('region', { name: 'Social' })
    expect(await within(social).findByRole('tab', { name: /Friends.*1 waiting/ })).toBeInTheDocument()
    await userEvent.click(within(social).getByRole('button', { name: 'Accept friend request from Cara' }))
    expect(answerFriendRequest).toHaveBeenCalledWith('r1', 'accept')
    await userEvent.click(within(social).getByRole('button', { name: 'Decline friend request from Cara' }))
    expect(answerFriendRequest).toHaveBeenCalledWith('r1', 'decline')
    await userEvent.click(within(social).getByRole('button', { name: 'Cancel friend request to Dorn' }))
    expect(answerFriendRequest).toHaveBeenCalledWith('r2', 'cancel')

    await userEvent.click(within(social).getByRole('button', { name: 'Remove Bryn from friends' }))
    const confirm = within(social).getByRole('alertdialog', { name: 'Remove friend' })
    expect(removeFriend).not.toHaveBeenCalled()
    await userEvent.click(within(confirm).getByRole('button', { name: 'Remove' }))
    expect(removeFriend).toHaveBeenCalledWith('bryn')
  })

  it('invites a friend into a lobby the player hosts', async () => {
    const inviteToGame = vi.fn().mockResolvedValue({ id: 'i1', status: 'PENDING' })
    dashboard({
      getFriends: vi.fn().mockResolvedValue(friendsWith({ incoming: [], outgoing: [] })),
      listGames: vi.fn().mockResolvedValue([lobbySummary()]),
      inviteToGame,
    })
    const social = await screen.findByRole('region', { name: 'Social' })
    await userEvent.click(await within(social).findByRole('button', { name: 'Invite Bryn to Friends only' }))
    expect(inviteToGame).toHaveBeenCalledWith('lobby-1', 'bryn')
    expect(await within(social).findByText('Invited')).toBeInTheDocument()
  })

  it('cannot invite without a hosted lobby and explains why', async () => {
    dashboard({ getFriends: vi.fn().mockResolvedValue(friendsWith({ incoming: [], outgoing: [] })) })
    const social = await screen.findByRole('region', { name: 'Social' })
    const button = await within(social).findByRole('button', { name: 'Invite Bryn to a game' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringMatching(/Create a private game first/))
  })
})

describe('Invitations and active games (13A)', () => {
  const invitation: Invitation = {
    id: 'inv-1',
    gameId: 'lobby-9',
    gameName: 'Bryn’s campaign',
    mapName: 'Grand Campaign',
    playerCount: 1,
    maxPlayers: 2,
    joinable: true,
    inviter: { username: 'bryn', displayName: 'Bryn', emblem: 'tower' },
    invitee: { username: 'aldric', displayName: 'Aldric', emblem: 'crown' },
    status: 'PENDING',
    reason: null,
    createdAt: '',
  }

  it('joins an invited lobby from the invitations tab', async () => {
    const answerInvitation = vi.fn().mockResolvedValue({ ...invitation, status: 'ACCEPTED' })
    dashboard({ getInvitations: vi.fn().mockResolvedValue({ incoming: [invitation] }), answerInvitation })

    const social = await screen.findByRole('region', { name: 'Social' })
    await userEvent.click(await within(social).findByRole('tab', { name: /Invitations.*1 waiting/ }))
    expect(within(social).getByText(/from Bryn/)).toBeInTheDocument()
    await userEvent.click(within(social).getByRole('button', { name: 'Join Bryn’s campaign' }))
    expect(answerInvitation).toHaveBeenCalledWith('inv-1', 'accept')
    expect(await screen.findByText('Lobby page')).toBeInTheDocument()
  })

  it('shows a full lobby and lets the player decline', async () => {
    const answerInvitation = vi.fn().mockResolvedValue({ ...invitation, status: 'DECLINED' })
    dashboard({ getInvitations: vi.fn().mockResolvedValue({ incoming: [{ ...invitation, joinable: false }] }), answerInvitation })
    const social = await screen.findByRole('region', { name: 'Social' })
    await userEvent.click(await within(social).findByRole('tab', { name: /Invitations/ }))
    expect(within(social).getByRole('button', { name: 'Join Bryn’s campaign' })).toBeDisabled()
    await userEvent.click(within(social).getByRole('button', { name: 'Decline the invitation to Bryn’s campaign' }))
    expect(answerInvitation).toHaveBeenCalledWith('inv-1', 'decline')
  })

  it('lists active games with faction, round and turn, and resumes one', async () => {
    dashboard({
      listGames: vi.fn().mockResolvedValue([
        lobbySummary({
          id: 'g1',
          name: 'Continental',
          status: 'IN_PROGRESS',
          yourNation: { key: 'castavar', name: 'Kingdom of Castavar', color: '#c0892f' },
          currentRound: 4,
          yourTurn: true,
          humanPlayers: 2,
          computerPlayers: 8,
        }),
        lobbySummary({ id: 'old', name: 'Ancient war', status: 'FINISHED' }),
      ]),
    })
    const games = await screen.findByRole('region', { name: 'My active games' })
    const row = await within(games).findByRole('link', { name: /Continental/ })
    expect(row).toHaveAttribute('href', '/games/g1/play')
    expect(within(row).getByText('Your turn')).toBeInTheDocument()
    expect(within(row).getByText(/Kingdom of Castavar/)).toBeInTheDocument()
    expect(within(row).getByText(/Round 4/)).toBeInTheDocument()
    expect(within(row).getByText(/8 computer/)).toBeInTheDocument()
    expect(within(games).getByText('Finished games (1)')).toBeInTheDocument()
    await userEvent.click(row)
    expect(await screen.findByText('Game page')).toBeInTheDocument()
  })

  it('lets the host invite friends from the lobby', async () => {
    const inviteToGame = vi.fn().mockResolvedValue({ id: 'i1', status: 'PENDING' })
    renderPage(<LobbyPage />, {
      route: '/games/g1',
      path: '/games/:gameId',
      api: fakeApi({
        getMe: vi.fn().mockResolvedValue(me),
        getGame: vi.fn().mockResolvedValue(lobbyFixture()),
        getFriends: vi.fn().mockResolvedValue(friendsWith({ incoming: [], outgoing: [] })),
        inviteToGame,
      }),
    })
    const card = await screen.findByRole('region', { name: 'Invite friends' })
    await userEvent.click(await within(card).findByRole('button', { name: 'Invite Bryn' }))
    expect(inviteToGame).toHaveBeenCalledWith('g1', 'bryn')
  })
})

describe('Quick Match (13B)', () => {
  const searching: QuickMatch = { state: 'SEARCHING', ticketId: 't1', searchingSince: '2026-10-22T12:00:00Z', match: null }
  const matched: QuickMatch = {
    state: 'MATCHED',
    ticketId: 't1',
    searchingSince: '2026-10-22T12:00:00Z',
    match: {
      gameId: 'qm-1',
      gameName: 'Quick Match',
      mapName: 'Grand Campaign',
      yourFaction: { key: 'castavar', name: 'Kingdom of Castavar', color: '#c0892f' },
      opponent: { username: 'bryn', displayName: 'Bryn', emblem: 'tower', faction: { key: 'vostrava', name: 'Tsardom of Vostrava', color: '#a8452f' } },
      opened: false,
    },
  }

  it('never searches on its own, searches on request and can be cancelled', async () => {
    const joinQuickMatch = vi.fn().mockResolvedValue(searching)
    const cancelQuickMatch = vi.fn().mockResolvedValue({ ...searching, state: 'CANCELLED' })
    dashboard({ joinQuickMatch, cancelQuickMatch })

    const card = await screen.findByRole('region', { name: 'Quick Match' })
    await userEvent.click(await within(card).findByRole('button', { name: 'Find a match' }))
    expect(joinQuickMatch).toHaveBeenCalledTimes(1)
    expect(await within(card).findByText(/Searching for an opponent/)).toBeInTheDocument()
    expect(within(card).getByText(/no fixed waiting time/)).toBeInTheDocument()
    expect(within(card).queryByRole('progressbar')).not.toBeInTheDocument()

    await userEvent.click(within(card).getByRole('button', { name: 'Cancel search' }))
    expect(cancelQuickMatch).toHaveBeenCalled()
    expect(await within(card).findByText('Search cancelled.')).toBeInTheDocument()
  })

  it('shows the match with both factions and opens the game', async () => {
    const acknowledgeQuickMatch = vi.fn().mockResolvedValue({ ...matched, state: 'NONE' })
    dashboard({ getQuickMatch: vi.fn().mockResolvedValue(matched), acknowledgeQuickMatch })

    const card = await screen.findByRole('region', { name: 'Quick Match' })
    expect(await within(card).findByText('Match found')).toBeInTheDocument()
    expect(within(card).getByText('Kingdom of Castavar')).toBeInTheDocument()
    expect(within(card).getByText('Tsardom of Vostrava')).toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Play now' }))
    expect(acknowledgeQuickMatch).toHaveBeenCalled()
    expect(await screen.findByText('Game page')).toBeInTheDocument()
  })

  it('explains an expired search and a refused one', async () => {
    dashboard({
      getQuickMatch: vi.fn().mockResolvedValue({ ...searching, state: 'EXPIRED' }),
      joinQuickMatch: vi.fn().mockRejectedValue(new ApiError({ status: 409, code: 'TOO_MANY_ACTIVE_GAMES', detail: 'Finish some of your 5 open games before searching for another.' })),
    })
    const card = await screen.findByRole('region', { name: 'Quick Match' })
    expect(await within(card).findByText(/last search stopped/)).toBeInTheDocument()
    await userEvent.click(within(card).getByRole('button', { name: 'Find a match' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent('Finish some of your 5 open games')
  })
})

describe('Notifications', () => {
  it('shows the unread count, lists a found match and marks everything read; Escape closes it', async () => {
    const markNotificationsRead = vi.fn().mockResolvedValue({ unread: 0, items: [] })
    dashboard({
      getUnreadCount: vi.fn().mockResolvedValue({ unread: 2 }),
      getNotifications: vi.fn().mockResolvedValue({
        unread: 2,
        items: [
          { id: 'n1', kind: 'MATCH_FOUND', actor: { username: 'bryn', displayName: 'Bryn', emblem: 'tower' }, gameId: 'qm-1', gameName: 'Quick Match', referenceId: null, createdAt: '2026-10-22T12:00:00Z', read: false },
          { id: 'n2', kind: 'FRIEND_REQUEST', actor: { username: 'cara', displayName: 'Cara', emblem: 'wheat' }, gameId: null, gameName: null, referenceId: 'r1', createdAt: '2026-10-22T11:00:00Z', read: false },
        ],
      }),
      markNotificationsRead,
    })
    const bell = await screen.findByRole('button', { name: 'Social notifications (2 unread)' })
    await userEvent.click(bell)
    const menu = screen.getByRole('region', { name: 'Notifications' })
    expect(await within(menu).findByRole('link', { name: 'Quick Match found against Bryn' })).toHaveAttribute('href', '/games/qm-1/play')
    expect(within(menu).getByRole('button', { name: 'Cara sent you a friend request' })).toBeInTheDocument()
    await userEvent.click(within(menu).getByRole('button', { name: 'Mark all read' }))
    expect(markNotificationsRead).toHaveBeenCalled()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Notifications' })).not.toBeInTheDocument())
  })
})

describe('In-game social overlay', () => {
  it('opens over the map without losing the army selection or the game', async () => {
    const getGameState = vi.fn().mockResolvedValue(gameStateFixture())
    renderPage(<GamePage />, {
      route: '/games/g1/play',
      path: '/games/:gameId/play',
      api: fakeApi({
        getMe: vi.fn().mockResolvedValue(me),
        getGameState,
        getMap: vi.fn().mockResolvedValue(mapFixture),
        getEconomy: vi.fn().mockResolvedValue(economyFixture()),
        getMilitary: vi.fn().mockResolvedValue(militaryFixture()),
        getUnreadCount: vi.fn().mockResolvedValue({ unread: 1 }),
        getFriends: vi.fn().mockResolvedValue(friendsWith()),
      }),
    })
    const svg = await screen.findByRole('group', { name: /^Map of/ })
    await userEvent.click(await within(svg).findByRole('button', { name: /^Select your army in Elmsford/ }))
    expect(screen.getByRole('region', { name: 'Army in Elmsford' })).toBeInTheDocument()

    const toolbar = screen.getByRole('navigation', { name: 'Game panels' })
    await userEvent.click(within(toolbar).getByRole('button', { name: 'Show social (1 waiting)' }))
    const social = await screen.findByRole('region', { name: 'Social' })
    expect(within(social).getByRole('tab', { name: /Friends/ })).toHaveAttribute('aria-selected', 'true')
    await userEvent.click(within(social).getByRole('tab', { name: 'Games' }))
    expect(within(social).getByRole('region', { name: 'My active games' })).toBeInTheDocument()

    // The map, the army window and the selection are untouched; closing the panel returns to them.
    expect(screen.getByRole('region', { name: 'Army in Elmsford' })).toBeInTheDocument()
    expect(screen.getByText(/2 battalions selected in Elmsford/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Close window' }))
    expect(screen.queryByRole('region', { name: 'Social' })).not.toBeInTheDocument()
    expect(screen.getByText(/2 battalions selected in Elmsford/)).toBeInTheDocument()
  })
})
