import { useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router'
import { describeError } from '../api/client'
import { useMe } from '../api/queries'
import { useBlockPlayer, useProfile, useSendFriendRequest, useUpdateProfile } from '../api/social'
import type { Emblem, PlayerProfile, PublicProfile } from '../api/types'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { EmblemBadge } from '../components/social/People'
import { EMBLEM_LABELS, EMBLEMS } from '../lib/emblems'
import { Spinner } from '../components/Spinner'

const USERNAME_PATTERN = /^[a-z][a-z0-9_]{2,19}$/

/** /players/:username — anyone's public profile; your own also shows its settings. */
export function ProfilePage() {
  const { username = '' } = useParams()
  const profile = useProfile(username)
  const me = useMe()
  return (
    <AppShell>
      <Link to="/" className="back-link">
        ← Back to dashboard
      </Link>
      {profile.isPending && <Spinner label="Opening the profile…" />}
      {profile.error && <Alert>{describeError(profile.error)}</Alert>}
      {profile.data && (
        <div className="profile">
          <ProfileHeader profile={profile.data} />
          <div className="dashboard-grid">
            <Statistics profile={profile.data} />
            {profile.data.you && me.data && <ProfileSettings me={me.data} />}
          </div>
        </div>
      )}
    </AppShell>
  )
}

/** /profile — your own profile. */
export function MyProfileRedirect() {
  const me = useMe()
  if (me.data?.username) {
    return <Navigate to={`/players/${encodeURIComponent(me.data.username)}`} replace />
  }
  return (
    <AppShell>
      {me.isPending && <Spinner label="Opening your profile…" />}
      {me.error && <Alert>{describeError(me.error)}</Alert>}
    </AppShell>
  )
}

function ProfileHeader({ profile }: { profile: PublicProfile }) {
  const request = useSendFriendRequest()
  const block = useBlockPlayer()
  return (
    <section className="profile__header" aria-label="Profile">
      <EmblemBadge emblem={profile.emblem} size={34} />
      <div className="profile__names">
        <h1>{profile.displayName}</h1>
        <p className="muted">
          @{profile.username} · member since {new Date(profile.memberSince).toLocaleDateString()}
        </p>
      </div>
      <div className="profile__actions">
        {profile.relation === 'NONE' && (
          <button type="button" className="button button--primary" disabled={request.isPending || request.isSuccess} onClick={() => request.mutate(profile.username)}>
            {request.isSuccess ? 'Request sent' : 'Add friend'}
          </button>
        )}
        {profile.relation === 'FRIEND' && <span className="tag tag--active">Friend</span>}
        {profile.relation === 'REQUEST_SENT' && <span className="tag">Friend request sent</span>}
        {profile.relation === 'REQUEST_RECEIVED' && <span className="tag">Sent you a friend request</span>}
        {profile.relation === 'BLOCKED' && <span className="tag tag--warning">Blocked</span>}
        {!profile.you && profile.relation !== 'BLOCKED' && (
          <button type="button" className="button button--ghost button--small" disabled={block.isPending} onClick={() => block.mutate(profile.username)}>
            Block
          </button>
        )}
      </div>
      {(request.error ?? block.error) && <Alert>{describeError(request.error ?? block.error)}</Alert>}
    </section>
  )
}

/** Win rate from basis points without floating point: 3333 → "33.33%". */
function percent(bps: number): string {
  const whole = Math.floor(bps / 100)
  const fraction = String(bps % 100).padStart(2, '0')
  return fraction === '00' ? `${whole}%` : `${whole}.${fraction}%`
}

function Statistics({ profile }: { profile: PublicProfile }) {
  const s = profile.statistics
  return (
    <section className="card" aria-labelledby="stats-title">
      <h2 id="stats-title">Record</h2>
      <dl className="profile-stats">
        <div>
          <dt>Completed</dt>
          <dd>{s.completed}</dd>
        </div>
        <div>
          <dt>Wins</dt>
          <dd>{s.wins}</dd>
        </div>
        <div>
          <dt>Losses</dt>
          <dd>{s.losses}</dd>
        </div>
        <div>
          <dt>Draws</dt>
          <dd>{s.draws}</dd>
        </div>
        <div>
          <dt>Win rate</dt>
          <dd>{s.winRateBps === null ? '—' : percent(s.winRateBps)}</dd>
        </div>
        <div>
          <dt>Favourite faction</dt>
          <dd>{s.favouriteFaction ? s.favouriteFaction.name : '—'}</dd>
        </div>
      </dl>
      <p className="muted">Only finished games with a stored result count; games in progress never do.</p>
      {s.factions.length > 0 && (
        <table className="data-table" aria-label="Matches per faction">
          <thead>
            <tr>
              <th scope="col">Faction</th>
              <th scope="col">Played</th>
              <th scope="col">Won</th>
            </tr>
          </thead>
          <tbody>
            {s.factions.map((f) => (
              <tr key={f.key}>
                <td>{f.name}</td>
                <td>{f.played}</td>
                <td>{f.wins}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h3>Recent matches</h3>
      {profile.recentMatches === null ? (
        <p className="muted">{profile.displayName} keeps their match history private.</p>
      ) : profile.recentMatches.length === 0 ? (
        <p className="muted">No completed matches yet.</p>
      ) : (
        <ul className="match-history">
          {profile.recentMatches.map((m, i) => (
            <li key={`${m.finishedAt}-${i}`}>
              <span className={`tag outcome outcome--${m.outcome.toLowerCase()}`}>{m.outcome === 'WIN' ? 'Win' : m.outcome === 'LOSS' ? 'Loss' : 'Draw'}</span>
              {m.gameId ? <Link to={`/games/${m.gameId}/play`}>{m.gameName}</Link> : <span>{m.gameName}</span>}
              <span className="muted">
                {m.factionName} · {m.mapName} · round {m.finishedRound} · rank {m.rank} · {new Date(m.finishedAt).toLocaleDateString()}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ProfileSettings({ me }: { me: PlayerProfile }) {
  const update = useUpdateProfile()
  const navigate = useNavigate()
  const [username, setUsername] = useState(me.username ?? '')
  const [displayName, setDisplayName] = useState(me.displayName)
  const [emblem, setEmblem] = useState<Emblem>(me.emblem ?? 'crown')
  const [historyPublic, setHistoryPublic] = useState(me.matchHistoryPublic ?? true)
  const normalized = username.trim().toLowerCase()
  const usernameProblem =
    normalized.length === 0 ? null : USERNAME_PATTERN.test(normalized) ? null : '3–20 lowercase letters, digits or _, starting with a letter.'

  function submit(event: FormEvent) {
    event.preventDefault()
    if (usernameProblem) return
    update.mutate(
      {
        username: normalized !== me.username ? normalized : undefined,
        displayName: displayName.trim() !== me.displayName ? displayName.trim() : undefined,
        emblem: emblem !== me.emblem ? emblem : undefined,
        matchHistoryPublic: historyPublic !== me.matchHistoryPublic ? historyPublic : undefined,
      },
      { onSuccess: (profile) => profile.username && navigate(`/players/${encodeURIComponent(profile.username)}`, { replace: true }) },
    )
  }

  const unchanged =
    normalized === me.username && displayName.trim() === me.displayName && emblem === me.emblem && historyPublic === me.matchHistoryPublic

  return (
    <section className="card" aria-labelledby="settings-title">
      <h2 id="settings-title">Profile settings</h2>
      <form className="form" onSubmit={submit} noValidate>
        <label className="field">
          <span>Username</span>
          <input
            value={username}
            maxLength={20}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={usernameProblem !== null}
            aria-describedby="username-help"
            onChange={(event) => setUsername(event.target.value)}
          />
          <small id="username-help" className={usernameProblem ? 'negative' : 'muted'}>
            {usernameProblem ?? 'Your unique handle; friends find you by it.'}
          </small>
        </label>
        <label className="field">
          <span>Display name</span>
          <input value={displayName} maxLength={24} onChange={(event) => setDisplayName(event.target.value)} />
        </label>
        <fieldset className="emblem-picker">
          <legend>Emblem</legend>
          {EMBLEMS.map((key) => (
            <label key={key} className={`emblem-picker__option ${emblem === key ? 'emblem-picker__option--active' : ''}`} title={EMBLEM_LABELS[key]}>
              <input type="radio" name="emblem" value={key} checked={emblem === key} onChange={() => setEmblem(key)} className="visually-hidden" />
              <EmblemBadge emblem={key} size={18} />
              <span className="visually-hidden">{EMBLEM_LABELS[key]}</span>
            </label>
          ))}
        </fieldset>
        <label className="checkbox-field">
          <input type="checkbox" checked={historyPublic} onChange={(event) => setHistoryPublic(event.target.checked)} />
          Show my recent matches to other players
        </label>
        {update.error && <Alert>{describeError(update.error)}</Alert>}
        {update.isSuccess && (
          <p className="positive" role="status">
            Saved.
          </p>
        )}
        <button type="submit" className="button button--primary" disabled={unchanged || usernameProblem !== null || update.isPending}>
          {update.isPending ? 'Saving…' : 'Save changes'}
        </button>
      </form>
    </section>
  )
}
