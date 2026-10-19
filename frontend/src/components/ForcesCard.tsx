import { Fragment } from 'react'
import type { MilitaryOverview, UnitType } from '../api/types'
import { formatGold } from '../lib/gold'
import { UNIT_LABELS } from '../lib/units'
import type { NationStyle } from './ProvinceMap'

/** The player's army totals and upkeep, plus the most recent battles of the game. */
export function ForcesCard({
  military,
  nations,
  provinceNames,
  onShowBattle,
}: {
  military: MilitaryOverview
  nations: Map<string, NationStyle>
  provinceNames: Map<string, string>
  onShowBattle: (battleId: string) => void
}) {
  const { upkeep } = military
  return (
    <section className="card forces" aria-labelledby="forces-title">
      <h2 id="forces-title">Your forces</h2>
      <dl className="facts">
        <dt>Battalions</dt>
        <dd>{upkeep.battalions}</dd>
        <dt>Soldiers</dt>
        <dd>{upkeep.soldiers.toLocaleString('en-US')}</dd>
        <dt>Upkeep per round</dt>
        <dd>{upkeep.totalMg === 0 ? '0' : `−${formatGold(upkeep.totalMg)}`} gold</dd>
        {(Object.keys(upkeep.byUnitTypeMg) as UnitType[])
          .filter((type) => upkeep.byUnitTypeMg[type] > 0)
          .map((type) => (
            <Fragment key={type}>
              <dt>{UNIT_LABELS[type]}</dt>
              <dd>−{formatGold(upkeep.byUnitTypeMg[type])}</dd>
            </Fragment>
          ))}
      </dl>
      <p className="muted">Damaged battalions cost upkeep in proportion to their remaining soldiers.</p>
      <h3>Recent battles</h3>
      {military.recentBattles.length === 0 ? (
        <p className="muted">No battles yet.</p>
      ) : (
        <ul className="battle-list">
          {military.recentBattles.map((battle) => {
            const winnerId = battle.winner === 'ATTACKER' ? battle.attackerNationId : battle.defenderNationId
            return (
              <li key={battle.id}>
                <button type="button" className="link-button" onClick={() => onShowBattle(battle.id)}>
                  Battle of {provinceNames.get(battle.provinceKey) ?? battle.provinceKey}
                </button>{' '}
                <span className="muted">
                  round {battle.round} · {nations.get(winnerId)?.name ?? 'unknown'} won · losses{' '}
                  {battle.attackerLosses.toLocaleString('en-US')} / {battle.defenderLosses.toLocaleString('en-US')}
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
