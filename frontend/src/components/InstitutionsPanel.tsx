import { describeError } from '../api/client'
import { useBuildInstitution, useCityInstitutions } from '../api/queries'
import { formatGold } from '../lib/gold'
import { INSTITUTION_LABELS, POOL_LABELS } from '../lib/research'
import { Alert } from './Alert'

const BLOCKER_TEXT: Record<string, string> = {
  NOT_YOUR_TURN: 'You can build on your turn.',
  NOT_OWNER: 'Only the owning nation can build here.',
  OCCUPIED: 'Occupied by another nation.',
  BESIEGED: 'The city is under siege.',
  ALREADY_EXISTS: 'Already built or under construction.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

/** Research institutions of one of the player's cities, with the server's prices and timings. */
export function InstitutionsPanel({ gameId, cityId, stateVersion }: { gameId: string; cityId: string; stateVersion: number }) {
  const query = useCityInstitutions(gameId, cityId, stateVersion)
  const build = useBuildInstitution(gameId)
  if (query.error) return <Alert>{describeError(query.error)}</Alert>
  if (!query.data || !query.data.available) return null
  return (
    <section className="institutions" aria-label="Institutions">
      <h3>Institutions</h3>
      <ul className="institution-list">
        {query.data.options.map((o) => (
          <li key={o.type} className="institution">
            <strong>{INSTITUTION_LABELS[o.type]}</strong>{' '}
            <span className="muted">
              +{o.pointsPerRound} {POOL_LABELS[o.pool].toLowerCase()} points per round · upkeep {formatGold(o.upkeepMg)} gold
            </span>
            {o.existing ? (
              <p className="muted">
                {o.existing.status === 'ACTIVE'
                  ? 'Active.'
                  : `Under construction: ready at the end of round ${o.existing.completesAfterRound}, produces from round ${o.existing.operationalFromRound}.`}
              </p>
            ) : (
              <>
                <button
                  type="button"
                  className="button"
                  disabled={!o.allowed || build.isPending}
                  onClick={() => build.mutate({ cityId, type: o.type, idempotencyKey: crypto.randomUUID() })}
                >
                  Build {INSTITUTION_LABELS[o.type]} · {formatGold(o.costMg)} gold
                </button>
                <p className="muted">
                  {o.durationRounds} rounds: ready at the end of round {o.completesAfterRound}, produces from round{' '}
                  {o.operationalFromRound}.
                </p>
                {!o.allowed && (
                  <ul className="blockers">
                    {o.blockers.map((b) => (
                      <li key={b}>{BLOCKER_TEXT[b] ?? b}</li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </li>
        ))}
      </ul>
      {build.error && <Alert>{describeError(build.error)}</Alert>}
      {build.isSuccess && <Alert kind="success">Construction started.</Alert>}
    </section>
  )
}
