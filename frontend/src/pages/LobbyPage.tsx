import { useState } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { describeError } from '../api/client'
import { useChooseNation, useLobby, useStartGame } from '../api/queries'
import type { Lobby } from '../api/types'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { Spinner } from '../components/Spinner'
import { StatusBadge } from '../components/StatusBadge'

export function LobbyPage() {
  const { gameId = '' } = useParams()
  const lobby = useLobby(gameId)

  if (lobby.data?.status === 'IN_PROGRESS' || lobby.data?.status === 'FINISHED') {
    return <Navigate to={`/games/${gameId}/play`} replace />
  }

  return (
    <AppShell>
      <Link to="/" className="back-link">
        ← Back to dashboard
      </Link>
      {lobby.isPending && <Spinner label="Opening the war room…" />}
      {lobby.error && <Alert>{describeError(lobby.error)}</Alert>}
      {lobby.data && <LobbyDetails lobby={lobby.data} />}
    </AppShell>
  )
}

function LobbyDetails({ lobby }: { lobby: Lobby }) {
  const seats = Array.from({ length: lobby.maxPlayers }, (_, seat) => lobby.participants.find((p) => p.seat === seat))
  const nationsById = new Map(lobby.nations.map((nation) => [nation.id, nation]))

  return (
    <div className="lobby">
      <section className="lobby__header">
        <div>
          <h1>{lobby.name}</h1>
          <p className="muted">
            {lobby.mapName} · {lobby.playerCount} of {lobby.maxPlayers} players ·{' '}
            {lobby.availableSlots === 0
              ? 'all slots filled'
              : `${lobby.availableSlots} open slot${lobby.availableSlots === 1 ? '' : 's'}`}
          </p>
        </div>
        <StatusBadge status={lobby.status} />
      </section>

      <div className="dashboard-grid">
        <div className="stack">
          <section className="card" aria-labelledby="participants-title">
            <h2 id="participants-title">Players</h2>
            <ol className="seats">
              {seats.map((participant, seat) => {
                const nation = participant?.nationId ? nationsById.get(participant.nationId) : undefined
                return (
                  <li key={seat} className={`seat ${participant ? 'seat--taken' : 'seat--open'}`}>
                    <span className="seat__number">{seat + 1}</span>
                    {participant ? (
                      <span className="seat__name">
                        {participant.displayName}
                        {participant.host && <span className="tag">Host</span>}
                        {participant.you && <span className="tag tag--you">You</span>}
                        <span className="seat__nation">
                          {nation ? (
                            <>
                              <span className="swatch" style={{ background: nation.color }} aria-hidden="true" />
                              {nation.name}
                            </>
                          ) : (
                            <span className="muted">No nation chosen</span>
                          )}
                        </span>
                      </span>
                    ) : (
                      <span className="seat__name muted">Open slot — waiting for a player</span>
                    )}
                  </li>
                )
              })}
            </ol>
          </section>
          <NationPicker lobby={lobby} />
        </div>

        <div className="stack">
          <InviteCard code={lobby.inviteCode} />
          <StartCard lobby={lobby} />
        </div>
      </div>
    </div>
  )
}

const TIER_LABELS: Record<string, string> = {
  POWER: 'balanced power',
  MAJOR: 'major realm',
  REGIONAL: 'regional power',
  MINOR: 'small state',
}

function NationPicker({ lobby }: { lobby: Lobby }) {
  const chooseNation = useChooseNation(lobby.id)
  const seatNames = new Map(lobby.participants.map((p) => [p.seat, p.displayName]))

  return (
    <section className="card" aria-labelledby="nations-title">
      <h2 id="nations-title">Choose your nation</h2>
      <p className="muted">
        Each playable nation can be led by one player. You can change your choice until the game starts; the other
        nations are played by the computer.
      </p>
      {chooseNation.error && <Alert>{describeError(chooseNation.error)}</Alert>}
      <ul className="nation-list">
        {lobby.nations.map((nation) => {
          const takenByOther = nation.chosenBySeat !== null && !nation.chosenByYou
          const playable = nation.playable !== false
          return (
            <li key={nation.id}>
              <button
                type="button"
                className={`nation-card ${nation.chosenByYou ? 'nation-card--mine' : ''}`}
                disabled={!playable || takenByOther || chooseNation.isPending}
                aria-pressed={nation.chosenByYou}
                onClick={() => chooseNation.mutate(nation.id)}
              >
                <span className="swatch swatch--large" style={{ background: nation.color }} aria-hidden="true" />
                <span className="nation-card__body">
                  <span className="nation-card__name">{nation.name}</span>
                  <span className="muted">
                    {nation.provinceCount} provinces · {TIER_LABELS[nation.tier] ?? nation.tier} ·{' '}
                    {!playable
                      ? 'played by the computer'
                      : nation.chosenByYou
                      ? 'your nation'
                      : takenByOther
                        ? `chosen by ${seatNames.get(nation.chosenBySeat!) ?? 'another player'}`
                        : 'available'}
                  </span>
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function StartCard({ lobby }: { lobby: Lobby }) {
  const startGame = useStartGame(lobby.id)
  const everyoneChose = lobby.participants.every((p) => p.nationId !== null)

  let hint: string
  if (lobby.status === 'WAITING_FOR_PLAYERS') {
    hint = 'Share the invitation code. This page refreshes automatically when someone joins.'
  } else if (!everyoneChose) {
    hint = 'All players are here. Every player must choose a different nation.'
  } else if (lobby.youAreHost) {
    hint = 'Everyone is ready. Start the game when you are.'
  } else {
    hint = 'Everyone is ready. Waiting for the host to start the game…'
  }

  return (
    <section className="card" aria-labelledby="start-title">
      <h2 id="start-title">Status</h2>
      <Alert kind={lobby.canStart ? 'success' : 'info'}>{hint}</Alert>
      {startGame.error && <Alert>{describeError(startGame.error)}</Alert>}
      {lobby.youAreHost && (
        <button
          type="button"
          className="button button--primary start-button"
          disabled={!lobby.canStart || startGame.isPending}
          onClick={() => startGame.mutate()}
        >
          {startGame.isPending ? 'Starting…' : 'Start game'}
        </button>
      )}
    </section>
  )
}

function InviteCard({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  return (
    <section className="card" aria-labelledby="invite-title">
      <h2 id="invite-title">Invitation code</h2>
      <p className="muted">Only players in this game can see it. Send it to the rival you want to face.</p>
      <div className="invite">
        <code className="invite__code">{code}</code>
        <button type="button" className="button button--ghost" onClick={copy}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </section>
  )
}
