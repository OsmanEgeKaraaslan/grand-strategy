import { describeError } from '../api/client'
import { useResearch, useUnlockDoctrine, useUnlockTechnology } from '../api/queries'
import type { Doctrine, MilitaryOverview, ResearchTrackView } from '../api/types'
import { formatGold } from '../lib/gold'
import { useState } from 'react'
import { INSTITUTION_LABELS, POOL_ICONS, POOL_LABELS, doctrineIcon, doctrineLabel, formatBps, formatPenalty } from '../lib/research'
import { UNIT_LABELS } from '../lib/units'
import { Alert } from './Alert'
import { Icon, type IconName } from './icons'

type Category = 'military' | 'civilian' | 'doctrines' | 'institutions' | 'effects'

const CATEGORIES: { key: Category; label: string; icon: IconName; hint: string }[] = [
  { key: 'military', label: 'Military technology', icon: 'military', hint: 'Military technology: combat damage per level' },
  { key: 'civilian', label: 'Civilian technology', icon: 'science', hint: 'Civilian technology: Tier 2 workshops and population growth' },
  { key: 'doctrines', label: 'Doctrines', icon: 'doctrine', hint: 'Doctrine: one specialisation for your army' },
  { key: 'institutions', label: 'Institutions', icon: 'academy', hint: 'Institutions: the buildings that produce research points' },
  { key: 'effects', label: 'Active effects', icon: 'check', hint: 'What your research changes in play right now' },
]

const INSTITUTION_ICONS: Record<string, IconName> = { MILITARY_ACADEMY: 'academy', UNIVERSITY: 'university', BARRACKS: 'barracks' }

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
  const [category, setCategory] = useState<Category>('military')
  if (research.error) return <Alert>{describeError(research.error)}</Alert>
  const data = research.data
  if (!data || !data.available) return null

  return (
    <section className="card research" aria-labelledby="research-title">
      <h2 id="research-title">Research</h2>
      <dl className="facts research__pools">
        {data.pools.map((p) => (
          <div key={p.pool} className="research__pool" title={`${POOL_LABELS[p.pool]} research points`}>
            <dt>
              <Icon name={POOL_ICONS[p.pool]} size={14} /> {POOL_LABELS[p.pool]}
            </dt>
            <dd>
              {p.points} points <span className="muted">(+{p.perRound} per round)</span>
            </dd>
          </div>
        ))}
      </dl>
      <div className="icon-tabs" role="tablist" aria-label="Research category">
        {CATEGORIES.map((c) => (
          <button
            key={c.key}
            type="button"
            role="tab"
            id={`research-tab-${c.key}`}
            aria-selected={category === c.key}
            aria-controls="research-body"
            aria-label={c.label}
            title={c.hint}
            className={`icon-tabs__tab ${category === c.key ? 'icon-tabs__tab--active' : ''}`}
            onClick={() => setCategory(c.key)}
          >
            <Icon name={c.icon} size={18} />
          </button>
        ))}
      </div>
      <div id="research-body" role="tabpanel" aria-labelledby={`research-tab-${category}`}>
      {category === 'military' && data.military && <Track gameId={gameId} track={data.military} title="Military technology" icon="military" />}
      {category === 'civilian' && data.civilian && <Track gameId={gameId} track={data.civilian} title="Civilian technology" icon="science" />}
      {category === 'doctrines' && (
        <Doctrines gameId={gameId} doctrines={data.doctrines} points={data.pools.find((p) => p.pool === 'DOCTRINE')!.points} />
      )}
      {category === 'effects' && military && <ActiveEffects military={military} growthBonusBps={data.effects?.growthBonusBps ?? 0} />}
      {category === 'institutions' && (
      <>
      <h3>Institutions</h3>
      {data.institutions.length === 0 ? (
        <p className="muted">No institutions yet. Build a Military Academy, University or Barracks in one of your cities.</p>
      ) : (
        <ul className="battle-list">
          {data.institutions.map((i) => (
            <li key={i.id}>
              <Icon name={INSTITUTION_ICONS[i.type] ?? 'academy'} size={14} /> {INSTITUTION_LABELS[i.type]} in {i.cityName}{' '}
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
      </>
      )}
      </div>
    </section>
  )
}

function Track({ gameId, track, title, icon }: { gameId: string; track: ResearchTrackView; title: string; icon: IconName }) {
  const unlock = useUnlockTechnology(gameId)
  return (
    <div className="research__track" role="group" aria-label={title}>
      <h3>
        <Icon name={icon} size={16} /> {title} · level {track.level}/{track.maxLevel}
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
            disabled={!track.next.allowed}
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
      {(unlock.isPending || unlock.isSuccess) && unlock.variables && (
        <Alert kind="success">Level {unlock.variables.targetLevel} researched.</Alert>
      )}
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
            <span title={doctrineLabel(d.key)}>
              <Icon name={doctrineIcon(d.key)} size={16} />
            </span>{' '}
            <strong>{doctrineLabel(d.key)}</strong> <span className="muted">· {doctrineEffect(d)}</span>
            {d.chosen ? (
              <span className="tag tag--active">Chosen</span>
            ) : (
              <button
                type="button"
                className="button"
                disabled={!d.allowed}
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
