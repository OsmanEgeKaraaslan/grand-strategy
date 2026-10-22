import { Link, useNavigate } from 'react-router'
import { describeError } from '../../api/client'
import { useAcknowledgeQuickMatch, useCancelQuickMatch, useJoinQuickMatch, useQuickMatch } from '../../api/social'
import type { QuickMatchFaction } from '../../api/types'
import { Alert } from '../Alert'
import { Icon } from '../icons'
import { Spinner } from '../Spinner'
import { EmblemBadge } from './People'

/**
 * Unranked Quick Match: two humans, every other Grand Campaign nation played by the computer.
 * Loading this card never starts a search; only the button does. No waiting time is promised —
 * the beta has few players — so while searching it offers the alternatives instead.
 */
export function QuickMatchCard({ onInviteFriend, compact = false }: { onInviteFriend?: () => void; compact?: boolean }) {
  const status = useQuickMatch()
  const join = useJoinQuickMatch()
  const cancel = useCancelQuickMatch()
  const acknowledge = useAcknowledgeQuickMatch()
  const navigate = useNavigate()
  const view = status.data
  const busy = join.isPending || cancel.isPending

  function play(gameId: string) {
    acknowledge.mutate(undefined, { onSettled: () => navigate(`/games/${gameId}/play`) })
  }

  return (
    <section className={`card quick-match ${compact ? 'quick-match--compact' : ''}`} aria-labelledby="quick-match-title">
      <h2 id="quick-match-title">
        <Icon name="military" size={18} /> Quick Match
      </h2>
      <p className="muted quick-match__lede">Unranked · you and one other player on the Grand Campaign, eight nations played by the computer.</p>
      {status.isPending && <Spinner label="Checking the queue…" />}
      {status.error && <Alert>{describeError(status.error)}</Alert>}

      {view && (view.state === 'NONE' || view.state === 'CANCELLED' || view.state === 'EXPIRED') && (
        <div className="quick-match__body">
          {view.state === 'CANCELLED' && <p className="muted" role="status">Search cancelled.</p>}
          {view.state === 'EXPIRED' && (
            <p className="muted" role="status">
              Your last search stopped because this page was closed for a while. Search again whenever you like.
            </p>
          )}
          <button type="button" className="button button--primary" onClick={() => join.mutate()} disabled={busy}>
            {join.isPending ? 'Looking for an opponent…' : 'Find a match'}
          </button>
        </div>
      )}

      {view?.state === 'SEARCHING' && (
        <div className="quick-match__body" role="status" aria-live="polite">
          <p className="quick-match__searching">
            <span className="pulse" aria-hidden="true" /> Searching for an opponent…
          </p>
          <p className="muted">
            There is no fixed waiting time: a match starts as soon as another player searches. Keep this page open, or meanwhile:
          </p>
          <ul className="quick-match__alternatives">
            {onInviteFriend && (
              <li>
                <button type="button" className="link-button" onClick={onInviteFriend}>
                  Invite a friend
                </button>
              </li>
            )}
            <li>Create a private game and share its invitation code</li>
            <li>Create a game for yourself — every nation you do not choose is played by the computer</li>
          </ul>
          <button type="button" className="button" onClick={() => cancel.mutate()} disabled={busy}>
            {cancel.isPending ? 'Cancelling…' : 'Cancel search'}
          </button>
        </div>
      )}

      {view?.state === 'MATCHED' && view.match && (
        <div className="quick-match__body quick-match__found" role="status" aria-live="polite">
          <p className="quick-match__headline">
            <Icon name="check" size={16} /> Match found
          </p>
          <dl className="facts facts--compact">
            <dt>Campaign</dt>
            <dd>
              {view.match.gameName} · {view.match.mapName}
            </dd>
            <dt>Your faction</dt>
            <dd>
              <Faction faction={view.match.yourFaction} />
            </dd>
            <dt>Opponent</dt>
            <dd>
              {view.match.opponent ? (
                <>
                  <Link to={`/players/${encodeURIComponent(view.match.opponent.username)}`} className="person person--inline">
                    <EmblemBadge emblem={view.match.opponent.emblem} size={12} />
                    {view.match.opponent.displayName}
                  </Link>{' '}
                  · <Faction faction={view.match.opponent.faction} />
                </>
              ) : (
                '—'
              )}
            </dd>
          </dl>
          <button type="button" className="button button--primary" onClick={() => play(view.match!.gameId)}>
            Play now
          </button>
        </div>
      )}

      {join.error && <Alert>{describeError(join.error)}</Alert>}
      {cancel.error && <Alert>{describeError(cancel.error)}</Alert>}
    </section>
  )
}

function Faction({ faction }: { faction: QuickMatchFaction | null | undefined }) {
  if (!faction) return <span className="muted">—</span>
  return (
    <span className="nation-name">
      <span className="swatch" style={{ background: faction.color }} aria-hidden="true" />
      {faction.name}
    </span>
  )
}
