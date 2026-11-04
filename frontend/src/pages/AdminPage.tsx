import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useApi } from '../api/ApiContext'
import { describeError } from '../api/client'
import { useMe } from '../api/queries'
import type { AdminGame, AdminPlayer } from '../api/types'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { Spinner } from '../components/Spinner'
import { StatusBadge } from '../components/StatusBadge'

const adminKeys = {
  players: (query: string) => ['admin', 'players', query] as const,
  games: ['admin', 'games'] as const,
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

/**
 * Moderation for administrators (Q-MOD1): find players, suspend or reinstate them, reset offensive
 * names, close stuck games. The server checks every request; this page only hides itself from others.
 */
export function AdminPage() {
  const me = useMe()
  return (
    <AppShell>
      <section className="hero">
        <h1>Administration</h1>
        <p className="muted">Every action here is recorded with your account.</p>
      </section>
      {me.isPending && <Spinner label="Loading your account…" />}
      {me.data && !me.data.admin && <Alert>This page is only for administrators.</Alert>}
      {me.data?.admin && (
        <div className="admin">
          <PlayersCard />
          <GamesCard />
        </div>
      )}
    </AppShell>
  )
}

function PlayersCard() {
  const api = useApi()
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const players = useQuery({ queryKey: adminKeys.players(query), queryFn: () => api.adminPlayers(query) })

  function handleSearch(event: FormEvent) {
    event.preventDefault()
    setQuery(input.trim())
  }

  return (
    <section className="card" aria-labelledby="admin-players-title">
      <h2 id="admin-players-title">Players</h2>
      <form className="admin__search" onSubmit={handleSearch} role="search">
        <label className="field">
          <span>Username, display name or player id</span>
          <input value={input} maxLength={64} onChange={(e) => setInput(e.target.value)} />
        </label>
        <button type="submit" className="button">
          Search
        </button>
      </form>
      {players.isPending && <Spinner label="Loading players…" />}
      {players.error && <Alert>{describeError(players.error)}</Alert>}
      {players.data && players.data.length === 0 && <p className="empty">No player matches.</p>}
      {players.data && players.data.length > 0 && (
        <ul className="admin__list">
          {players.data.map((player) => (
            <PlayerRow key={player.id} player={player} />
          ))}
        </ul>
      )}
    </section>
  )
}

function PlayerRow({ player }: { player: AdminPlayer }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [banning, setBanning] = useState(false)
  const [reason, setReason] = useState('')
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin'] })
  const ban = useMutation({
    mutationFn: () => api.banPlayer(player.id, reason.trim()),
    onSuccess: () => {
      setBanning(false)
      setReason('')
      return refresh()
    },
  })
  const unban = useMutation({ mutationFn: () => api.unbanPlayer(player.id), onSuccess: refresh })
  const resetName = useMutation({ mutationFn: () => api.resetPlayerName(player.id), onSuccess: refresh })
  const error = ban.error ?? unban.error ?? resetName.error
  const busy = ban.isPending || unban.isPending || resetName.isPending
  const label = `${player.displayName}${player.username ? ` (@${player.username})` : ''}`

  return (
    <li className="admin__row" aria-label={label}>
      <div className="admin__who">
        <strong>{player.displayName}</strong>
        {player.username && <span className="muted"> @{player.username}</span>}
        <div className="muted admin__meta">
          Joined {formatDate(player.createdAt)}
          {player.admin && ' · Administrator'}
          {player.deleted && ' · Deleted'}
        </div>
        {player.bannedAt && (
          <div className="admin__banned">
            Suspended {formatDate(player.bannedAt)}: {player.banReason}
          </div>
        )}
      </div>
      {!player.admin && !player.deleted && (
        <div className="admin__actions">
          {player.bannedAt ? (
            <button type="button" className="button" disabled={busy} onClick={() => unban.mutate()}>
              Unban
            </button>
          ) : (
            <button type="button" className="button button--danger" disabled={busy} onClick={() => setBanning(!banning)}>
              Ban…
            </button>
          )}
          <button type="button" className="button" disabled={busy} onClick={() => resetName.mutate()}>
            Reset name
          </button>
        </div>
      )}
      {banning && (
        <form
          className="admin__reason"
          onSubmit={(event) => {
            event.preventDefault()
            ban.mutate()
          }}
        >
          <label className="field">
            <span>Reason (the player sees it)</span>
            <input value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} autoFocus />
          </label>
          <p className="muted">
            Their lobbies close and the computer takes over their nations in running games. This cannot be undone by
            unbanning.
          </p>
          <button type="submit" className="button button--danger" disabled={reason.trim().length < 3 || busy}>
            {ban.isPending ? 'Suspending…' : `Suspend ${player.displayName}`}
          </button>
        </form>
      )}
      {error && <Alert>{describeError(error)}</Alert>}
    </li>
  )
}

function GamesCard() {
  const api = useApi()
  const games = useQuery({ queryKey: adminKeys.games, queryFn: api.adminGames })
  return (
    <section className="card" aria-labelledby="admin-games-title">
      <h2 id="admin-games-title">Open games</h2>
      <p className="muted">Lobbies and running games, least recently active first.</p>
      {games.isPending && <Spinner label="Loading games…" />}
      {games.error && <Alert>{describeError(games.error)}</Alert>}
      {games.data && games.data.length === 0 && <p className="empty">No open games.</p>}
      {games.data && games.data.length > 0 && (
        <ul className="admin__list">
          {games.data.map((game) => (
            <GameRow key={game.id} game={game} />
          ))}
        </ul>
      )}
    </section>
  )
}

function GameRow({ game }: { game: AdminGame }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [closing, setClosing] = useState(false)
  const [reason, setReason] = useState('')
  const abandon = useMutation({
    mutationFn: () => api.abandonGame(game.id, reason.trim()),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.games }),
  })

  return (
    <li className="admin__row" aria-label={game.name}>
      <div className="admin__who">
        <strong>{game.name}</strong> <StatusBadge status={game.status} />
        <div className="muted admin__meta">
          {game.humans.join(', ') || 'No human players'}
          {game.computerSeats > 0 && ` · ${game.computerSeats} computer nations`}
          {game.round !== null && ` · round ${game.round}`}
          {` · last activity ${formatDate(game.lastActionAt)}`}
        </div>
      </div>
      <div className="admin__actions">
        <button type="button" className="button button--danger" onClick={() => setClosing(!closing)}>
          Close game…
        </button>
      </div>
      {closing && (
        <form
          className="admin__reason"
          onSubmit={(event) => {
            event.preventDefault()
            abandon.mutate()
          }}
        >
          <label className="field">
            <span>Reason (kept in the moderation log)</span>
            <input value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} autoFocus />
          </label>
          <p className="muted">The game ends without a result. Its players can still look at it.</p>
          <button type="submit" className="button button--danger" disabled={reason.trim().length < 3 || abandon.isPending}>
            {abandon.isPending ? 'Closing…' : `Close ${game.name}`}
          </button>
        </form>
      )}
      {abandon.error && <Alert>{describeError(abandon.error)}</Alert>}
    </li>
  )
}
