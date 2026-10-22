import type { ReactNode } from 'react'
import { useResearch } from '../api/queries'
import type { EconomyOverview, MilitaryOverview } from '../api/types'
import { formatGold, formatGoldDelta } from '../lib/gold'
import { Icon, type IconName } from './icons'

const POOL_LABELS: Record<string, string> = { MILITARY: 'Military', CIVILIAN: 'Civilian', DOCTRINE: 'Doctrine' }

interface ResourceBarProps {
  gameId: string
  stateVersion: number
  economy: EconomyOverview | undefined
  military: MilitaryOverview | undefined
  /** People living in the viewer's own cities (from the game state). */
  population: number | undefined
}

/**
 * The national resources in the top bar: treasury, net change per round, population, army size
 * and upkeep, research points. Compact icon + value pairs with the long form in each title.
 * Read-only; every number comes from the server's views.
 */
export function ResourceBar({ gameId, stateVersion, economy, military, population }: ResourceBarProps) {
  const research = useResearch(gameId, stateVersion)
  const net = economy?.nation.projectedNetMg
  return (
    <section className="resource-bar" aria-label="Resources">
      <dl className="resource-bar__items">
        <Resource icon="gold" label="Gold" hint="Gold in the treasury">
          {economy ? formatGold(economy.nation.treasuryMg) : '—'}
        </Resource>
        <Resource
          icon="trend"
          label="Per round"
          hint={
            economy
              ? `Next round: income ${formatGoldDelta(economy.nation.projectedGrossIncomeMg)}, upkeep −${formatGold(economy.nation.projectedUpkeepMg)}`
              : 'Projected income minus upkeep at the end of this round'
          }
          tone={net === undefined ? undefined : net < 0 ? 'negative' : 'positive'}
        >
          {net === undefined ? '—' : formatGoldDelta(net)}
        </Resource>
        <Resource icon="population" label="Population" hint="People in your cities">
          {population === undefined ? '—' : population.toLocaleString('en-US')}
        </Resource>
        <Resource icon="army" label="Army" hint="Your battalions, soldiers and their upkeep per round">
          {military
            ? `${military.upkeep.battalions} bn · ${military.upkeep.soldiers.toLocaleString('en-US')} men · −${formatGold(military.upkeep.totalMg)}`
            : '—'}
        </Resource>
        {research.data?.available &&
          research.data.pools.map((p) => (
            <Resource
              key={p.pool}
              icon="research"
              label={`${POOL_LABELS[p.pool] ?? p.pool} research`}
              hint={`${POOL_LABELS[p.pool] ?? p.pool} research points${p.perRound > 0 ? `, +${p.perRound} per round` : ''}`}
            >
              {p.points}
              {p.perRound > 0 ? ` (+${p.perRound})` : ''}
            </Resource>
          ))}
        {economy?.nation.inArrears && (
          <Resource icon="warning" label="Status" hint="Unpaid upkeep: no construction until a round ends without a deficit" tone="warning">
            In arrears
          </Resource>
        )}
      </dl>
    </section>
  )
}

function Resource({
  icon,
  label,
  hint,
  tone,
  children,
}: {
  icon: IconName
  label: string
  hint: string
  tone?: 'negative' | 'positive' | 'warning'
  children: ReactNode
}) {
  return (
    <div className={`resource-bar__item ${tone ? `resource-bar__item--${tone}` : ''}`} title={hint}>
      <Icon name={icon} size={15} />
      <dt className="visually-hidden">{label}</dt>
      <dd className={tone === 'negative' ? 'negative' : tone === 'positive' ? 'positive' : undefined}>{children}</dd>
    </div>
  )
}
