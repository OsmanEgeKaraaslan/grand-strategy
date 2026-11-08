import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { describeError } from '../api/client'
import { useCreateGame, useJoinGame, useMaps, useMe } from '../api/queries'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { Dialog } from '../components/Dialog'
import { ActiveGames } from '../components/social/ActiveGames'
import { QuickMatchCard } from '../components/social/QuickMatchCard'
import { SocialPanel, type SocialTab } from '../components/social/SocialPanel'

/**
 * Two columns: the left one is about playing (start a game, then your games), the right one about
 * people (friends and invitations). Creating or joining a private game opens a dialog, so the page
 * itself stays a short list of choices.
 */
export function DashboardPage() {
  const me = useMe()
  const [socialKey, setSocialKey] = useState(0)
  const [socialTab, setSocialTab] = useState<SocialTab>('friends')
  const [dialog, setDialog] = useState<'create' | 'join' | null>(null)
  const social = useRef<HTMLDivElement>(null)

  function openSocial(tab: SocialTab = 'friends') {
    setSocialTab(tab)
    setSocialKey((k) => k + 1)
    social.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  return (
    <AppShell onOpenSocial={() => openSocial('friends')}>
      <div className="dashboard">
        <div className="dashboard__main">
          <section className="hero">
            <h1>{me.data ? `Welcome, ${me.data.displayName}` : 'Welcome'}</h1>
            {me.error && <Alert>{describeError(me.error)}</Alert>}
            {me.data && me.data.usernameChosen === false && (
              <p className="onboarding-note">
                Your username is <strong>@{me.data.username}</strong>. Friends find you by it —{' '}
                <Link to="/profile">choose your own</Link>.
              </p>
            )}
          </section>

          <section className="dashboard__start" aria-label="Start a game">
            <QuickMatchCard onInviteFriend={() => openSocial('friends')} />
            <section className="card private-game" aria-labelledby="private-title">
              <h2 id="private-title">Private game</h2>
              <p className="muted">Play with friends: pick the map and the number of players, then share the invitation code.</p>
              <div className="private-game__actions">
                <button type="button" className="button button--primary" onClick={() => setDialog('create')}>
                  Create a game
                </button>
                <button type="button" className="button" onClick={() => setDialog('join')}>
                  Join with a code
                </button>
              </div>
            </section>
          </section>

          <ActiveGames />
        </div>

        <aside ref={social} className="dashboard__aside" aria-label="Friends and invitations">
          <SocialPanel key={socialKey} initialTab={socialTab} sections={['friends', 'invitations']} />
        </aside>
      </div>

      {dialog === 'create' && (
        <Dialog
          title="Create a private game"
          description="You can invite friends from the lobby or share its code."
          onClose={() => setDialog(null)}
        >
          <CreateGameForm />
        </Dialog>
      )}
      {dialog === 'join' && (
        <Dialog title="Join a game" description="Enter the invitation code a friend sent you." onClose={() => setDialog(null)}>
          <JoinGameForm />
        </Dialog>
      )}
    </AppShell>
  )
}

function CreateGameForm() {
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
    <form className="form" onSubmit={handleSubmit}>
      <label className="field">
        <span>Game name</span>
        <input placeholder="New campaign" maxLength={40} value={name} onChange={(event) => setName(event.target.value)} />
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
        <p className="muted form__note">
          Nations nobody chooses are played by the computer: they build, recruit, research, go to war and negotiate by the
          same rules as you.
        </p>
      )}
      {createGame.error && <Alert>{describeError(createGame.error)}</Alert>}
      <button type="submit" className="button button--primary" disabled={createGame.isPending}>
        {createGame.isPending ? 'Creating…' : 'Create game'}
      </button>
    </form>
  )
}

function JoinGameForm() {
  const navigate = useNavigate()
  const joinGame = useJoinGame()
  const [code, setCode] = useState('')

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    joinGame.mutate(code.trim(), { onSuccess: (lobby) => navigate(`/games/${lobby.id}`) })
  }

  return (
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
      <button type="submit" className="button button--primary" disabled={joinGame.isPending || code.trim() === ''}>
        {joinGame.isPending ? 'Joining…' : 'Join game'}
      </button>
    </form>
  )
}
