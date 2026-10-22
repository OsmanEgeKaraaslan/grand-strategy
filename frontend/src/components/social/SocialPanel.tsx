import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { describeError } from '../../api/client'
import { useMyGames } from '../../api/queries'
import {
  useAnswerFriendRequest,
  useAnswerInvitation,
  useBlockPlayer,
  useFriends,
  useInvitations,
  useInviteToGame,
  usePlayerSearch,
  useRemoveFriend,
  useSendFriendRequest,
  useUnblockPlayer,
} from '../../api/social'
import type { PersonRef, PlayerCard } from '../../api/types'
import { Alert } from '../Alert'
import { Icon } from '../icons'
import { ActiveGames } from './ActiveGames'
import { PersonChip } from './People'
import { QuickMatchCard } from './QuickMatchCard'

export type SocialTab = 'friends' | 'invitations' | 'games' | 'match'

/**
 * Friends, requests, game invitations, active games and Quick Match in one compact panel — on the
 * dashboard and over the map during a game. Opening or switching tabs never starts a search or
 * leaves the current game; only explicit buttons act.
 */
export function SocialPanel({ currentGameId, initialTab = 'friends' }: { currentGameId?: string; initialTab?: SocialTab }) {
  const [tab, setTab] = useState<SocialTab>(initialTab)
  const friends = useFriends()
  const invitations = useInvitations()
  const incomingRequests = friends.data?.incoming.length ?? 0
  const incomingInvitations = invitations.data?.incoming.length ?? 0
  const tabs: { key: SocialTab; label: string; badge: number }[] = [
    { key: 'friends', label: 'Friends', badge: incomingRequests },
    { key: 'invitations', label: 'Invitations', badge: incomingInvitations },
    { key: 'games', label: 'Games', badge: 0 },
    { key: 'match', label: 'Quick Match', badge: 0 },
  ]

  return (
    <section className="card social-panel" aria-labelledby="social-title">
      <h2 id="social-title">
        <Icon name="social" size={18} /> Social
      </h2>
      <div className="social-tabs" role="tablist" aria-label="Social sections">
        {tabs.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            id={`social-tab-${t.key}`}
            aria-selected={tab === t.key}
            aria-controls={`social-tabpanel-${t.key}`}
            className={`social-tabs__tab ${tab === t.key ? 'social-tabs__tab--active' : ''}`}
            onClick={() => setTab(t.key)}
          >
            {t.label}
            {t.badge > 0 && (
              <span className="social-tabs__badge" aria-label={`${t.badge} waiting`}>
                {t.badge}
              </span>
            )}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`social-tabpanel-${tab}`} aria-labelledby={`social-tab-${tab}`} className="social-panel__body">
        {tab === 'friends' && <FriendsTab />}
        {tab === 'invitations' && <InvitationsTab />}
        {tab === 'games' && <ActiveGames currentGameId={currentGameId} />}
        {tab === 'match' && <QuickMatchCard compact onInviteFriend={() => setTab('friends')} />}
      </div>
    </section>
  )
}

function FriendsTab() {
  const friends = useFriends()
  const answer = useAnswerFriendRequest()
  const data = friends.data
  return (
    <div className="friends">
      <PlayerSearch />
      {friends.error && <Alert>{describeError(friends.error)}</Alert>}
      {answer.error && <Alert>{describeError(answer.error)}</Alert>}

      {data && data.incoming.length > 0 && (
        <section aria-label="Incoming friend requests">
          <h3>Requests for you</h3>
          <ul className="people-list">
            {data.incoming.map((r) => (
              <li key={r.id}>
                <PersonChip username={r.username} displayName={r.displayName} emblem={r.emblem} />
                <span className="people-list__actions">
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`Accept friend request from ${r.displayName}`}
                    title="Accept"
                    disabled={answer.isPending}
                    onClick={() => answer.mutate({ id: r.id, action: 'accept' })}
                  >
                    <Icon name="check" />
                  </button>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`Decline friend request from ${r.displayName}`}
                    title="Decline"
                    disabled={answer.isPending}
                    onClick={() => answer.mutate({ id: r.id, action: 'decline' })}
                  >
                    <Icon name="close" />
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Your friends">
        <h3>Friends{data ? ` (${data.friends.length})` : ''}</h3>
        {data && data.friends.length === 0 && <p className="muted">No friends yet — find players by their username above.</p>}
        <ul className="people-list">
          {data?.friends.map((f) => <FriendRow key={f.username} friend={f} />)}
        </ul>
      </section>

      {data && data.outgoing.length > 0 && (
        <section aria-label="Sent friend requests">
          <h3>Sent requests</h3>
          <ul className="people-list">
            {data.outgoing.map((r) => (
              <li key={r.id}>
                <PersonChip username={r.username} displayName={r.displayName} emblem={r.emblem} />
                <span className="people-list__actions">
                  <span className="tag">Pending</span>
                  <button
                    type="button"
                    className="link-button"
                    disabled={answer.isPending}
                    onClick={() => answer.mutate({ id: r.id, action: 'cancel' })}
                    aria-label={`Cancel friend request to ${r.displayName}`}
                  >
                    Cancel
                  </button>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {data && data.blocked.length > 0 && <BlockedList blocked={data.blocked} />}
    </div>
  )
}

function PlayerSearch() {
  const [draft, setDraft] = useState('')
  const [query, setQuery] = useState('')
  const results = usePlayerSearch(query)
  const send = useSendFriendRequest()

  function submit(event: FormEvent) {
    event.preventDefault()
    setQuery(draft)
  }

  return (
    <div className="player-search">
      <form className="player-search__form" onSubmit={submit} role="search" aria-label="Find players">
        <label className="visually-hidden" htmlFor="player-search-input">
          Find players by username
        </label>
        <input
          id="player-search-input"
          placeholder="Find players by username"
          value={draft}
          maxLength={20}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => setDraft(event.target.value)}
        />
        <button type="submit" className="icon-button" aria-label="Search players" disabled={draft.trim() === ''}>
          <Icon name="search" />
        </button>
      </form>
      {results.error && <Alert>{describeError(results.error)}</Alert>}
      {send.error && <Alert>{describeError(send.error)}</Alert>}
      {query && results.data && (
        <ul className="people-list" aria-label="Search results">
          {results.data.length === 0 && <li className="muted">No player found. Usernames are matched exactly, or by their first three letters.</li>}
          {results.data.map((p) => (
            <li key={p.username}>
              <PersonChip username={p.username} displayName={p.displayName} emblem={p.emblem} />
              <span className="people-list__actions">
                <RelationAction player={p} pending={send.isPending} onAdd={() => send.mutate(p.username)} />
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function RelationAction({ player, pending, onAdd }: { player: PlayerCard; pending: boolean; onAdd: () => void }) {
  switch (player.relation) {
    case 'NONE':
      return (
        <button type="button" className="icon-button" aria-label={`Add ${player.displayName} as a friend`} title="Add friend" disabled={pending} onClick={onAdd}>
          <Icon name="userPlus" />
        </button>
      )
    case 'FRIEND':
      return <span className="tag tag--active">Friend</span>
    case 'REQUEST_SENT':
      return <span className="tag">Requested</span>
    case 'REQUEST_RECEIVED':
      return <span className="tag">Wants to be friends — answer above</span>
    case 'BLOCKED':
      return <span className="tag tag--warning">Blocked</span>
    case 'YOU':
      return <span className="tag tag--you">You</span>
  }
}

function FriendRow({ friend }: { friend: PersonRef }) {
  const [confirming, setConfirming] = useState<'remove' | 'block' | null>(null)
  const remove = useRemoveFriend()
  const block = useBlockPlayer()
  return (
    <li>
      <PersonChip username={friend.username} displayName={friend.displayName} emblem={friend.emblem} />
      <span className="people-list__actions">
        <InviteToGame friend={friend} />
        <button
          type="button"
          className="icon-button icon-button--danger"
          aria-label={`Remove ${friend.displayName} from friends`}
          title="Remove friend"
          onClick={() => setConfirming('remove')}
        >
          <Icon name="disband" />
        </button>
        <button
          type="button"
          className="icon-button icon-button--danger"
          aria-label={`Block ${friend.displayName}`}
          title="Block"
          onClick={() => setConfirming('block')}
        >
          <Icon name="ban" />
        </button>
      </span>
      {confirming && (
        <div className="confirm people-list__confirm" role="alertdialog" aria-label={confirming === 'remove' ? 'Remove friend' : 'Block player'}>
          <p>
            {confirming === 'remove'
              ? `Remove ${friend.displayName} from your friends?`
              : `Block ${friend.displayName}? You will no longer be friends, and neither of you can send the other requests or invitations.`}
          </p>
          <div className="confirm__actions">
            <button
              type="button"
              className="button button--danger button--small"
              onClick={() =>
                (confirming === 'remove' ? remove : block).mutate(friend.username, { onSuccess: () => setConfirming(null) })
              }
            >
              {confirming === 'remove' ? 'Remove' : 'Block'}
            </button>
            <button type="button" className="button button--ghost button--small" onClick={() => setConfirming(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {(remove.error ?? block.error) && <Alert>{describeError(remove.error ?? block.error)}</Alert>}
    </li>
  )
}

/** Invites a friend into one of the caller's own lobbies that still waits for players. */
function InviteToGame({ friend }: { friend: PersonRef }) {
  const games = useMyGames()
  const invite = useInviteToGame()
  const lobbies = (games.data ?? []).filter((g) => g.youAreHost && g.status === 'WAITING_FOR_PLAYERS')
  const [gameId, setGameId] = useState('')
  const chosen = lobbies.find((g) => g.id === gameId) ?? lobbies[0]

  if (lobbies.length === 0) {
    return (
      <button
        type="button"
        className="icon-button"
        aria-label={`Invite ${friend.displayName} to a game`}
        title="Create a private game first: you can invite friends into lobbies you host."
        disabled
      >
        <Icon name="mail" />
      </button>
    )
  }
  return (
    <span className="invite-to-game">
      {lobbies.length > 1 && (
        <select aria-label={`Game to invite ${friend.displayName} to`} value={chosen?.id} onChange={(e) => setGameId(e.target.value)}>
          {lobbies.map((g) => (
            <option key={g.id} value={g.id}>
              {g.name}
            </option>
          ))}
        </select>
      )}
      <button
        type="button"
        className="icon-button"
        aria-label={`Invite ${friend.displayName} to ${chosen.name}`}
        title={`Invite to ${chosen.name}`}
        disabled={invite.isPending || invite.isSuccess}
        onClick={() => invite.mutate({ gameId: chosen.id, username: friend.username })}
      >
        <Icon name={invite.isSuccess ? 'check' : 'mail'} />
      </button>
      {invite.isSuccess && <span className="muted invite-to-game__sent" role="status">Invited</span>}
      {invite.error && <span className="negative" role="alert">{describeError(invite.error)}</span>}
    </span>
  )
}

function BlockedList({ blocked }: { blocked: PlayerCard[] }) {
  const unblock = useUnblockPlayer()
  return (
    <details>
      <summary>Blocked players ({blocked.length})</summary>
      <ul className="people-list">
        {blocked.map((p) => (
          <li key={p.username}>
            <PersonChip username={p.username} displayName={p.displayName} emblem={p.emblem} />
            <button type="button" className="link-button" onClick={() => unblock.mutate(p.username)}>
              Unblock
            </button>
          </li>
        ))}
      </ul>
    </details>
  )
}

function InvitationsTab() {
  const invitations = useInvitations()
  const answer = useAnswerInvitation()
  const navigate = useNavigate()
  const incoming = invitations.data?.incoming ?? []
  return (
    <div className="invitations">
      {invitations.error && <Alert>{describeError(invitations.error)}</Alert>}
      {answer.error && <Alert>{describeError(answer.error)}</Alert>}
      {invitations.data && incoming.length === 0 && <p className="muted">No open invitations.</p>}
      <ul className="people-list">
        {incoming.map((i) => (
          <li key={i.id} className="invitation">
            <span className="invitation__text">
              <strong>{i.gameName}</strong>
              <span className="muted">
                {i.mapName} · {i.playerCount}/{i.maxPlayers} players
                {i.inviter ? ` · from ${i.inviter.displayName}` : ''}
              </span>
              {!i.joinable && <span className="tag tag--warning">Lobby full or started</span>}
            </span>
            <span className="people-list__actions">
              <button
                type="button"
                className="button button--primary button--small"
                disabled={answer.isPending || !i.joinable}
                onClick={() =>
                  answer.mutate({ id: i.id, action: 'accept' }, { onSuccess: (accepted) => navigate(`/games/${accepted.gameId}`) })
                }
                aria-label={`Join ${i.gameName}`}
              >
                Join
              </button>
              <button
                type="button"
                className="button button--ghost button--small"
                disabled={answer.isPending}
                onClick={() => answer.mutate({ id: i.id, action: 'decline' })}
                aria-label={`Decline the invitation to ${i.gameName}`}
              >
                Decline
              </button>
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
