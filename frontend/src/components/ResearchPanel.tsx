import { describeError } from '../api/client'
import { useResearch, useUnlockDoctrine, useUnlockTechnology } from '../api/queries'
import type { Doctrine, MilitaryOverview, ResearchTrackView } from '../api/types'
import { formatGold } from '../lib/gold'
import { INSTITUTION_LABELS, POOL_LABELS, doctrineLabel, formatBps, formatPenalty } from '../lib/research'
import { UNIT_LABELS } from '../lib/units'
import { Alert } from './Alert'

const BLOCKER_TEXT: Record<string, string> = {
  NOT_YOUR_TURN: 'You can research on your turn.',
  MAX_LEVEL_REACHED: 'Highest level reached.',
  INSUFFICIENT_POINTS: 'Not enough research points yet.',
  ALREADY_CHOSEN: 'Already chosen.',
  TIER_FULL: 'Your nation has already chosen its doctrine.',
  UNKNOWN_DOCTRINE: 'Unknown doctrine.',
}

/**
 * The player's national research: three point pools, the military and civilian technology tracks,
 * doctrines and institutions. Every number, cost and effect is the server's.
 */
export function ResearchPanel({
  gameId,
  stateVersion,
  military,
}: {
  gameId: string
  stateVersion: number
  military: MilitaryOverview | undefined
}) {
  const research = useResearch(gameId, stateVersion)
  if (research.error) return <Alert>{describeError(research.error)}</Alert>
  const data = research.data
  if (!data || !data.available) return null

  return (
    <section className="card research" aria-labelledby="research-title">
      <h2 id="research-title">Research</h2>
      <dl className="facts research__pools">
        {data.pools.map((p) => (
          <div key={p.pool} className="research__pool">
            <dt>{POOL_LABELS[p.pool]}</dt>
            <dd>
              {p.points} points <span className="muted">(+{p.perRound} per round)</span>
            </dd>
          </div>
        ))}
      </dl>
      {data.military && <Track gameId={gameId} track={data.military} title="Military technology" />}
      {data.civilian && <Track gameId={gameId} track={data.civilian} title="Civilian technology" />}
      <Doctrines gameId={gameId} doctrines={data.doctrines} points={data.pools.find((p) => p.pool === 'DOCTRINE')!.points} />
      {military && <ActiveEffects military={military} growthBonusBps={data.effects?.growthBonusBps ?? 0} />}
      <h3>Institutions</h3>
      {data.institutions.length === 0 ? (
        <p className="muted">No institutions yet. Build a Military Academy, University or Barracks in one of your cities.</p>
      ) : (
        <ul className="battle-list">
          {data.institutions.map((i) => (
            <li key={i.id}>
              {INSTITUTION_LABELS[i.type]} in {i.cityName}{' '}
              <span className="muted">
                {i.status === 'ACTIVE'
                  ? i.producing
                    ? `· +${i.pointsPerRound} ${POOL_LABELS[i.pool].toLowerCase()} per round`
                    : '· produces nothing while the city is occupied'
                  : `· ready at the end of round ${i.completesAfterRound}, produces from round ${i.operationalFromRound}`}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function Track({ gameId, track, title }: { gameId: string; track: ResearchTrackView; title: string }) {
  const unlock = useUnlockTechnology(gameId)
  return (
    <div className="research__track" role="group" aria-label={title}>
      <h3>
        {title} · level {track.level}/{track.maxLevel}
      </h3>
      <ul className="research__levels">
        {track.levels.map((l) => (
          <li key={l.level} className={l.unlocked ? 'research__level--unlocked' : undefined}>
            Level {l.level}: {levelEffect(l)}
            {!l.unlocked && <span className="muted"> · {l.costPoints} points</span>}
          </li>
        ))}
      </ul>
      {track.next ? (
        <>
          <button
            type="button"
            className="button"
            disabled={!track.next.allowed || unlock.isPending}
            onClick={() =>
              unlock.mutate({ track: track.track, targetLevel: track.next!.targetLevel, idempotencyKey: crypto.randomUUID() })
            }
          >
            Research level {track.next.targetLevel} · {track.next.costPoints} points
          </button>
          {!track.next.allowed && (
            <ul className="blockers">
              {track.next.blockers.map((b) => (
                <li key={b}>{BLOCKER_TEXT[b] ?? b}</li>
              ))}
            </ul>
          )}
        </>
      ) : (
        <p className="muted">Fully researched.</p>
      )}
      {unlock.isSuccess && <Alert kind="success">Level {unlock.data.level} researched.</Alert>}
      {unlock.error && <Alert>{describeError(unlock.error)}</Alert>}
    </div>
  )
}

function levelEffect(l: ResearchTrackView['levels'][number]): string {
  const damage = Object.entries(l.damageBonusBps)
  if (damage.length > 0) {
    const values = new Set(damage.map(([, v]) => v))
    return values.size === 1
      ? `+${formatBps(damage[0][1]!)} damage for all units`
      : damage.map(([type, v]) => `${UNIT_LABELS[type as keyof typeof UNIT_LABELS]} +${formatBps(v!)}`).join(', ')
  }
  if (l.unlocksWorkshopTier) return `unlocks Tier ${l.unlocksWorkshopTier} workshops (upgrade from Tier 1)`
  if (l.growthBonusBps > 0) return `+${formatBps(l.growthBonusBps)} population growth per round`
  return l.level === 1 ? 'starting level' : 'no effect'
}

function Doctrines({ gameId, doctrines, points }: { gameId: string; doctrines: Doctrine[]; points: number }) {
  const unlock = useUnlockDoctrine(gameId)
  const chosen = doctrines.filter((d) => d.chosen)
  return (
    <div className="research__doctrines" role="group" aria-label="Doctrines">
      <h3>Doctrine {chosen.length > 0 ? `· ${chosen.map((d) => doctrineLabel(d.key)).join(', ')}` : ''}</h3>
      <p className="muted">Choose one specialization with doctrine points ({points} available).</p>
      <ul className="research__doctrine-list">
        {doctrines.map((d) => (
          <li key={d.key} className={d.chosen ? 'research__doctrine--chosen' : undefined}>
            <strong>{doctrineLabel(d.key)}</strong> <span className="muted">· {doctrineEffect(d)}</span>
            {d.chosen ? (
              <span className="tag tag--active">Chosen</span>
            ) : (
              <button
                type="button"
                className="button"
                disabled={!d.allowed || unlock.isPending}
                onClick={() => unlock.mutate({ doctrine: d.key, idempotencyKey: crypto.randomUUID() })}
              >
                Choose · {d.costPoints} points
              </button>
            )}
            {!d.chosen && !d.allowed && (
              <span className="muted"> {d.blockers.map((b) => BLOCKER_TEXT[b] ?? b).join(' ')}</span>
            )}
          </li>
        ))}
      </ul>
      {unlock.error && <Alert>{describeError(unlock.error)}</Alert>}
    </div>
  )
}

function doctrineEffect(d: Doctrine): string {
  const parts: string[] = []
  if (d.mountainPenaltyReductionBps) parts.push(`mountain attack penalty reduced by ${formatBps(d.mountainPenaltyReductionBps)}`)
  if (d.riverPenaltyReductionBps) parts.push(`river crossing penalty reduced by ${formatBps(d.riverPenaltyReductionBps)}`)
  if (d.siegeArtilleryBonusBps) parts.push(`artillery siege progress +${formatBps(d.siegeArtilleryBonusBps)}`)
  if (d.militaryUpkeepReductionBps) parts.push(`battalion upkeep −${formatBps(d.militaryUpkeepReductionBps)}`)
  return parts.join('; ')
}

function ActiveEffects({ military, growthBonusBps }: { military: MilitaryOverview; growthBonusBps: number }) {
  const m = military.modifiers
  const damage = Object.values(m.damageBonusBps)
  return (
    <div className="research__effects" role="group" aria-label="Active effects">
      <h3>In effect now</h3>
      <dl className="facts">
        <dt>Combat damage</dt>
        <dd>{damage.length > 0 ? `+${formatBps(Math.max(...(damage as number[])))}` : 'base'}</dd>
        <dt>Attacking across rivers</dt>
        <dd>
          {formatPenalty(m.riverAttackMultiplier)}
          {m.riverAttackMultiplier !== m.baseRiverAttackMultiplier && (
            <span className="muted"> (base {formatPenalty(m.baseRiverAttackMultiplier)})</span>
          )}
        </dd>
        <dt>Attacking into mountains</dt>
        <dd>
          {formatPenalty(m.mountainAttackMultiplier)}
          {m.mountainAttackMultiplier !== m.baseMountainAttackMultiplier && (
            <span className="muted"> (base {formatPenalty(m.baseMountainAttackMultiplier)})</span>
          )}
        </dd>
        <dt>Artillery siege progress</dt>
        <dd>{m.siegeArtilleryBonusBps ? `+${formatBps(m.siegeArtilleryBonusBps)}` : 'base'}</dd>
        <dt>Battalion upkeep</dt>
        <dd>
          {m.upkeepReductionBps ? `−${formatBps(m.upkeepReductionBps)}` : 'base'}{' '}
          <span className="muted">({formatGold(military.upkeep.totalMg)} gold per round)</span>
        </dd>
        <dt>Population growth bonus</dt>
        <dd>{growthBonusBps ? `+${formatBps(growthBonusBps)}` : 'none'}</dd>
      </dl>
    </div>
  )
}
