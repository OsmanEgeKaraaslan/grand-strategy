import { useState } from 'react'
import type { DiplomacyOverview, GameState } from '../api/types'
import type { Names } from '../lib/diplomacy'
import { NationActions, RelationTags } from './DiplomacyPanel'

const JOIN: Record<string, string> = {
  DECLARED: 'declared it',
  ATTACKED: 'was attacked',
  DEFENSIVE_RESPONSE: 'defends an ally',
  CALL_TO_ARMS: 'answered a call to arms',
}

/**
 * One nation's diplomatic picture, opened from the map (a province's legal owner): how it stands
 * with the viewer, its alliances and wars, and what the viewer may do with it now. All of it is the
 * public diplomacy overview; actions are validated by the server like everywhere else.
 */
export function NationDiplomacy({
  gameId,
  nationId,
  overview,
  state,
  names,
  onShowAll,
}: {
  gameId: string
  nationId: string
  overview: DiplomacyOverview
  state: GameState
  names: Names
  /** Opens the full diplomacy window (every nation, wars, conferences). */
  onShowAll: () => void
}) {
  const [message, setMessage] = useState<string | null>(null)
  const relation = overview.nations.find((n) => n.nationId === nationId)
  const nation = state.nations.find((n) => n.id === nationId)
  if (!relation || !nation) return <p className="muted">This nation is not part of the game.</p>

  const alliances = overview.alliances.filter(
    (a) => a.status === 'ACTIVE' && (a.nationA === nationId || a.nationB === nationId),
  )
  const wars = overview.wars.filter(
    (w) => w.status === 'ACTIVE' && w.participants.some((p) => p.nationId === nationId && p.leftRound == null),
  )
  const finished = state.status === 'FINISHED'

  return (
    <section className="card nation-diplomacy" aria-labelledby="nation-diplomacy-title">
      <h2 id="nation-diplomacy-title">
        <span className="swatch" style={{ background: nation.color }} aria-hidden="true" /> {nation.name}
      </h2>
      {message && (
        <p className="muted" role="status">
          {message}
        </p>
      )}
      {relation.you ? (
        <p className="muted">This is your nation.</p>
      ) : (
        <>
          <p>
            <RelationTags relation={relation} />
          </p>
          <dl className="facts facts--compact">
            <dt>Your battalions</dt>
            <dd>{relation.weMayEnter ? 'may enter its territory' : 'may not enter its territory'}</dd>
            <dt>Its battalions</dt>
            <dd>{relation.theyMayEnter ? 'may enter yours' : 'may not enter yours'}</dd>
            {nation.eliminatedRound != null && (
              <>
                <dt>Status</dt>
                <dd>Eliminated in round {nation.eliminatedRound}</dd>
              </>
            )}
          </dl>
        </>
      )}

      <h3>Alliances</h3>
      {alliances.length === 0 ? (
        <p className="muted">No alliances.</p>
      ) : (
        <ul className="battle-list">
          {alliances.map((a) => {
            const partner = a.nationA === nationId ? a.nationB : a.nationA
            return (
              <li key={a.id}>
                {a.kind === 'NORMAL' ? 'Normal alliance' : 'Defensive pact'} with <strong>{names.nation(partner)}</strong>
                <span className="muted"> · since round {a.acceptedRound}</span>
              </li>
            )
          })}
        </ul>
      )}

      <h3>Wars</h3>
      {wars.length === 0 ? (
        <p className="muted">At peace with everyone.</p>
      ) : (
        <ul className="battle-list">
          {wars.map((w) => {
            const own = w.participants.find((p) => p.nationId === nationId)!
            const enemies = w.participants.filter((p) => p.side !== own.side && p.leftRound == null)
            return (
              <li key={w.id}>
                {own.side === 'ATTACKER' ? 'Attacking' : 'Defending against'}{' '}
                <strong>{enemies.map((p) => p.name).join(', ')}</strong>
                <span className="muted">
                  {' '}
                  · since round {w.declaredRound} · {JOIN[own.joinReason] ?? own.joinReason} · war score{' '}
                  {w.score.net > 0 ? `+${w.score.net}` : w.score.net}
                </span>
              </li>
            )
          })}
        </ul>
      )}

      {!relation.you && !finished && (
        <>
          <h3>Actions</h3>
          {!overview.yourTurn ? (
            <p className="muted">You can act on your turn.</p>
          ) : relation.status === 'WAR' ? (
            <p className="muted">You are at war: peace is negotiated in the war&apos;s card in the diplomacy window.</p>
          ) : null}
          <NationActions
            gameId={gameId}
            relation={relation}
            overview={overview}
            names={names}
            yourTurn={overview.yourTurn && !finished}
            onMessage={setMessage}
          />
        </>
      )}
      <p>
        <button type="button" className="link-button" onClick={onShowAll}>
          Open the full diplomacy window
        </button>
      </p>
    </section>
  )
}
