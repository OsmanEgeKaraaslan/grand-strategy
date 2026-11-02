import type { ReactNode } from 'react'
import { useResearch } from '../api/queries'
import type { EconomyOverview, MilitaryOverview } from '../api/types'
import { formatGold, formatGoldDelta } from '../lib/gold'
import { POOL_ICONS } from '../lib/research'
import { Icon, type IconName } from './icons'

const POOL_LABELS: Record<string, string> = { MILITARY: 'Military', CIVILIAN: 'Civilian', DOCTRINE: 'Doctrine' }

interface ResourceBarProps {
  gameId: string
  stateVersion: number
  economy: EconomyOverview | undefined
  military: MilitaryOverview | undefined
  /** People living in the viewer's own cities (from the game state). */
  population: number | undefined
  /** Projected change at the next round end (growth − siege attrition; see GamePage). */
  populationChange?: { net: number; growth: number; attrition: number }
}

/**
 * The national resources in the top bar: treasury and its net change per round, population and its
 * projected change per round, army size, research points (upkeep lives in the economy window). Compact icon + value pairs with the long form in each title.
 * Read-only; every number comes from the server's views.
 */
export function ResourceBar({ gameId, stateVersion, economy, military, population, populationChange }: ResourceBarProps) {
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
        <Resource
          icon="trend"
          label="Population per round"
          hint={
            populationChange
              ? `Next round end: growth ${signed(populationChange.growth)}, siege losses −${populationChange.attrition.toLocaleString('en-US')}. Recruitment, battles and disbanding change population at once and are not included.`
              : 'Projected population change at the end of this round'
          }
          tone={populationChange === undefined || populationChange.net === 0 ? undefined : populationChange.net < 0 ? 'negative' : 'positive'}
        >
          {populationChange === undefined ? '—' : signed(populationChange.net)}
        </Resource>
        <Resource icon="army" label="Army" hint="Your battalions and soldiers (their upkeep is in the economy window)">
          {military ? `${military.upkeep.battalions} bn · ${military.upkeep.soldiers.toLocaleString('en-US')} men` : '—'}
        </Resource>
        {research.data?.available &&
          research.data.pools.map((p) => (
            <Resource
              key={p.pool}
              icon={POOL_ICONS[p.pool as keyof typeof POOL_ICONS] ?? 'research'}
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

function signed(value: number): string {
  return `${value > 0 ? '+' : value < 0 ? '−' : '±'}${Math.abs(value).toLocaleString('en-US')}`
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
