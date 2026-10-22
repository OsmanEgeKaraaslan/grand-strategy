import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { describeError } from '../api/client'
import { useCreateGame, useJoinGame, useMaps, useMe } from '../api/queries'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { ActiveGames } from '../components/social/ActiveGames'
import { QuickMatchCard } from '../components/social/QuickMatchCard'
import { SocialPanel, type SocialTab } from '../components/social/SocialPanel'

export function DashboardPage() {
  const me = useMe()
  const [socialKey, setSocialKey] = useState(0)
  const [socialTab, setSocialTab] = useState<SocialTab>('friends')
  const social = useRef<HTMLDivElement>(null)

  function openSocial(tab: SocialTab = 'friends') {
    setSocialTab(tab)
    setSocialKey((k) => k + 1)
    social.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <AppShell onOpenSocial={() => openSocial('friends')}>
      <section className="hero">
        <h1>{me.data ? `Welcome, ${me.data.displayName}` : 'Welcome'}</h1>
        <p className="muted">Find a rival, gather your friends, then claim the Reach.</p>
        {me.error && <Alert>{describeError(me.error)}</Alert>}
        {me.data && me.data.usernameChosen === false && (
          <p className="onboarding-note">
            Your username is <strong>@{me.data.username}</strong>. Friends find you by it —{' '}
            <Link to="/profile">choose your own</Link>.
          </p>
        )}
      </section>
      <div className="dashboard-actions">
        <QuickMatchCard onInviteFriend={() => openSocial('friends')} />
        <CreateGameCard />
        <JoinGameCard />
      </div>
      <div className="dashboard-grid">
        <ActiveGames />
        <div ref={social} className="stack">
          <SocialPanel key={socialKey} initialTab={socialTab} />
        </div>
      </div>
    </AppShell>
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
      <h2 id="create-title">Create a private game</h2>
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
          <p className="muted">Nations nobody chooses are played by the computer: they build, recruit, research, go to war and negotiate by the same rules as you.</p>
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
