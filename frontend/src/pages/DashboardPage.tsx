import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { describeError } from '../api/client'
import { useCreateGame, useJoinGame, useMaps, useMe, useMyGames } from '../api/queries'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { Spinner } from '../components/Spinner'
import { StatusBadge } from '../components/StatusBadge'

export function DashboardPage() {
  const me = useMe()
  return (
    <AppShell>
      <section className="hero">
        <h1>{me.data ? `Welcome, ${me.data.displayName}` : 'Welcome'}</h1>
        <p className="muted">Gather a rival, then claim the Reach.</p>
        {me.error && <Alert>{describeError(me.error)}</Alert>}
      </section>
      <div className="dashboard-grid">
        <MyGames />
        <div className="stack">
          <CreateGameCard />
          <JoinGameCard />
        </div>
      </div>
    </AppShell>
  )
}

function MyGames() {
  const games = useMyGames()

  return (
    <section className="card" aria-labelledby="my-games-title">
      <h2 id="my-games-title">Your games</h2>
      {games.isPending && <Spinner label="Loading your games…" />}
      {games.error && <Alert>{describeError(games.error)}</Alert>}
      {games.data && games.data.length === 0 && (
        <p className="empty">You are not in any game yet. Create one or join with an invitation code.</p>
      )}
      {games.data && games.data.length > 0 && (
        <ul className="game-list">
          {games.data.map((game) => (
            <li key={game.id}>
              <Link
                to={game.status === 'IN_PROGRESS' || game.status === 'FINISHED' ? `/games/${game.id}/play` : `/games/${game.id}`}
                className="game-list__item"
              >
                <span className="game-list__name">{game.name}</span>
                <span className="game-list__meta">
                  {game.playerCount}/{game.maxPlayers} players · {game.youAreHost ? 'Host' : `Seat ${game.yourSeat + 1}`}
                </span>
                <StatusBadge status={game.status} />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function CreateGameCard() {
  const navigate = useNavigate()
  const createGame = useCreateGame()
  const maps = useMaps()
  const [name, setName] = useState('')
  const [mapKey, setMapKey] = useState('')
  const [players, setPlayers] = useState(2)
  // Until a map is picked, show the Grand Campaign (the server's default) or the first published map.
  const selectedMap =
    maps.data?.find((m) => m.key === mapKey) ?? maps.data?.find((m) => m.key === 'grand-campaign') ?? maps.data?.[0]
  const maxPlayers = selectedMap?.maxPlayers ?? 2

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    createGame.mutate(
      { name: name.trim() || 'New campaign', maxPlayers: Math.min(players, maxPlayers), mapKey: selectedMap?.key },
      { onSuccess: (lobby) => navigate(`/games/${lobby.id}`) },
    )
  }

  return (
    <section className="card" aria-labelledby="create-title">
      <h2 id="create-title">Create a game</h2>
      <form className="form" onSubmit={handleSubmit}>
        <label className="field">
          <span>Game name</span>
          <input
            placeholder="New campaign"
            maxLength={40}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        {maps.data && maps.data.length > 0 && (
          <label className="field">
            <span>Map</span>
            <select value={selectedMap?.key ?? ''} onChange={(event) => setMapKey(event.target.value)}>
              {maps.data.map((m) => (
                <option key={m.key} value={m.key}>
                  {m.name} — {m.nations} nations, up to {m.maxPlayers} players
                </option>
              ))}
            </select>
          </label>
        )}
        <label className="field">
          <span>Human players</span>
          <select value={Math.min(players, maxPlayers)} onChange={(event) => setPlayers(Number(event.target.value))}>
            {Array.from({ length: maxPlayers - 1 }, (_, i) => i + 2).map((n) => (
              <option key={n} value={n}>
                {n} players
              </option>
            ))}
          </select>
        </label>
        {selectedMap && selectedMap.nations > maxPlayers && (
          <p className="muted">Nations nobody chooses are controlled by the game (passive until AI arrives).</p>
        )}
        {createGame.error && <Alert>{describeError(createGame.error)}</Alert>}
        <button type="submit" className="button button--primary" disabled={createGame.isPending}>
          {createGame.isPending ? 'Creating…' : 'Create game'}
        </button>
      </form>
    </section>
  )
}

function JoinGameCard() {
  const navigate = useNavigate()
  const joinGame = useJoinGame()
  const [code, setCode] = useState('')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    joinGame.mutate(code.trim(), { onSuccess: (lobby) => navigate(`/games/${lobby.id}`) })
  }

  return (
    <section className="card" aria-labelledby="join-title">
      <h2 id="join-title">Join a game</h2>
      <form className="form" onSubmit={handleSubmit}>
        <label className="field">
          <span>Invitation code</span>
          <input
            className="input--code"
            placeholder="ABCD-EFGH-JKLM"
            autoComplete="off"
            spellCheck={false}
            value={code}
            onChange={(event) => setCode(event.target.value)}
          />
        </label>
        {joinGame.error && <Alert>{describeError(joinGame.error)}</Alert>}
        <button type="submit" className="button" disabled={joinGame.isPending || code.trim() === ''}>
          {joinGame.isPending ? 'Joining…' : 'Join game'}
        </button>
      </form>
    </section>
  )
}
