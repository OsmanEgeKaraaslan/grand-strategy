import type { EconomyOverview } from '../api/types'
import { formatGold, formatGoldDelta } from '../lib/gold'
import { Alert } from './Alert'

/** The player's national treasury and next round's projection, straight from the server. */
export function TreasuryPanel({ economy }: { economy: EconomyOverview }) {
  const { nation } = economy
  return (
    <section className="card treasury" aria-labelledby="treasury-title">
      <h2 id="treasury-title">Treasury</h2>
      <p className="treasury__gold">
        <span className="treasury__coin" aria-hidden="true">◉</span>
        <span>{formatGold(nation.treasuryMg)} gold</span>
      </p>
      <dl className="facts">
        <dt>Income next round</dt>
        <dd>{formatGoldDelta(nation.projectedGrossIncomeMg)}</dd>
        <dt>Upkeep</dt>
        <dd>{nation.projectedUpkeepMg === 0 ? '0' : `−${formatGold(nation.projectedUpkeepMg)}`}</dd>
        <dt>Net change</dt>
        <dd className={nation.projectedNetMg < 0 ? 'negative' : 'positive'}>{formatGoldDelta(nation.projectedNetMg)}</dd>
      </dl>
      {nation.lastRound && (
        <p className="muted treasury__last">
          Round {nation.lastRound.round}: {formatGoldDelta(nation.lastRound.grossIncomeMg)} income
          {nation.lastRound.upkeepMg > 0 && `, −${formatGold(nation.lastRound.upkeepMg)} upkeep`}
          {nation.lastRound.deficitMg > 0 && `, ${formatGold(nation.lastRound.deficitMg)} unpaid`}
        </p>
      )}
      {nation.inArrears && (
        <Alert>Your nation is in arrears: no construction until a round ends without a deficit.</Alert>
      )}
    </section>
  )
}
