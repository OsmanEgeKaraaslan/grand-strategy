import { useState } from 'react'
import { describeError } from '../api/client'
import { useBattalion, useDisband, useReinforce } from '../api/queries'
import type { DisbandResult } from '../api/types'
import { DISBAND_BLOCKER_TEXT, REINFORCE_BLOCKER_TEXT } from '../lib/blockers'
import { formatGold } from '../lib/gold'
import { UNIT_LABELS } from '../lib/units'
import { Alert } from './Alert'

/** Replacement cost as the server computes it (recruitment cost pro rata, rounded up) — display only. */
function replacementCost(recruitCostMg: number, battalionManpower: number, soldiers: number): number {
  return Math.ceil((recruitCostMg * soldiers) / battalionManpower)
}

/**
 * Reinforcement or disbanding (mode) of one of your battalions, opened from the army window. Every
 * limit, price and destination is the server's quote; the server re-checks the command when it arrives.
 */
export function BattalionManagement({
  gameId,
  battalionId,
  stateVersion,
  mode,
  onDisbanded,
}: {
  gameId: string
  battalionId: string
  stateVersion: number
  mode: 'reinforce' | 'disband'
  onDisbanded: (result: DisbandResult) => void
}) {
  const details = useBattalion(gameId, battalionId, stateVersion)
  const reinforce = useReinforce(gameId)
  const disband = useDisband(gameId)
  // Raw text of the amount field; null until the player edits it (then the quote maximum applies).
  const [amountText, setAmountText] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  if (details.error) return <Alert>{describeError(details.error)}</Alert>
  if (!details.data) return <p className="placeholder">Loading battalion…</p>

  const { battalion, cohorts, reinforcement: quote, disband: preview } = details.data
  const text = amountText ?? String(quote.maxSoldiers)
  const parsed = /^\d+$/.test(text) ? Number(text) : 0
  const validAmount = parsed >= 1 && parsed <= quote.maxSoldiers
  const soldiers = validAmount ? parsed : 0
  const cost = replacementCost(quote.recruitCostMg, quote.battalionManpower, soldiers)

  function handleReinforce() {
    setMessage(null)
    reinforce.mutate(
      { battalionId, soldiers, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (result) => {
          setAmountText(null)
          setMessage(`Added ${result.soldiersAdded.toLocaleString('en-US')} soldiers for ${formatGold(result.costMg)} gold.`)
        },
      },
    )
  }

  function handleDisband() {
    disband.mutate(
      { battalionId, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (result) => {
          setConfirming(false)
          onDisbanded(result)
        },
      },
    )
  }

  return (
    <div className="battalion-management">
      <h4>
        {UNIT_LABELS[battalion.unitType]} battalion · {battalion.manpower.toLocaleString('en-US')} /{' '}
        {battalion.maxManpower.toLocaleString('en-US')}
      </h4>
      <p className="muted">Soldiers from: {cohorts.map((c) => `${c.cityName} ${c.soldiers.toLocaleString('en-US')}`).join(', ')}</p>

      {mode === 'reinforce' && (
        <section className="reinforce" aria-label="Reinforce">
          <h5>Reinforce from {quote.cityName}</h5>
          <dl className="facts facts--compact">
            <dt>Missing</dt>
            <dd>{quote.missing.toLocaleString('en-US')}</dd>
            <dt>Available now</dt>
            <dd>{quote.maxSoldiers.toLocaleString('en-US')} (at most 500 per turn)</dd>
          </dl>
          {quote.allowed ? (
            <>
              <label className="reinforce__amount">
                Soldiers to add
                <input
                  type="number"
                  min={1}
                  max={quote.maxSoldiers}
                  value={text}
                  onChange={(event) => setAmountText(event.target.value)}
                />
              </label>
              {!validAmount && <p className="muted">Enter a number from 1 to {quote.maxSoldiers.toLocaleString('en-US')}.</p>}
              <dl className="facts facts--compact">
                <dt>Cost</dt>
                <dd>{formatGold(cost)} gold</dd>
                <dt>Civilians</dt>
                <dd>
                  −{soldiers.toLocaleString('en-US')} ({quote.cityName}: {quote.cityPopulation.toLocaleString('en-US')} →{' '}
                  {(quote.cityPopulation - soldiers).toLocaleString('en-US')})
                </dd>
              </dl>
              <p className="muted">A reinforced battalion still defends but cannot move or attack this turn.</p>
            </>
          ) : (
            <ul className="blockers">
              {quote.blockers.map((b) => (
                <li key={b}>{REINFORCE_BLOCKER_TEXT[b]}</li>
              ))}
            </ul>
          )}
          <button
            type="button"
            className="button button--primary button--small"
            disabled={!quote.allowed || !validAmount || reinforce.isPending}
            onClick={handleReinforce}
          >
            {reinforce.isPending ? 'Reinforcing…' : `Reinforce with ${soldiers.toLocaleString('en-US')}`}
          </button>
          {message && <p role="status">{message}</p>}
          {reinforce.error && <Alert>{describeError(reinforce.error)}</Alert>}
        </section>
      )}

      {mode === 'disband' && (
        <section className="disband" aria-label="Disband">
          <h5>Disband</h5>
          {!preview.allowed && (
            <ul className="blockers">
              {preview.blockers.map((b) => (
                <li key={b}>{DISBAND_BLOCKER_TEXT[b]}</li>
              ))}
            </ul>
          )}
          {!confirming ? (
            <button type="button" className="button button--danger button--small" disabled={!preview.allowed} onClick={() => setConfirming(true)}>
              Disband…
            </button>
          ) : (
            <div className="confirm" role="alertdialog" aria-labelledby={`disband-${battalionId}`}>
              <p id={`disband-${battalionId}`}>
                Disband this battalion? {preview.soldiers.toLocaleString('en-US')} surviving soldiers return to civilian life:
              </p>
              <ul>
                {preview.returns.map((r) => (
                  <li key={r.cityId}>
                    {r.cityName}: +{r.soldiers.toLocaleString('en-US')}
                    {r.fallback && <span className="tag">origin lost — settles here</span>}
                  </li>
                ))}
              </ul>
              <p className="muted">Upkeep saved: {formatGold(preview.upkeepSavedMg)} gold per round. This cannot be undone.</p>
              <div className="confirm__actions">
                <button type="button" className="button button--danger button--small" disabled={disband.isPending} onClick={handleDisband}>
                  {disband.isPending ? 'Disbanding…' : 'Confirm disband'}
                </button>
                <button type="button" className="button button--ghost button--small" onClick={() => setConfirming(false)}>
                  Cancel
                </button>
              </div>
            </div>
          )}
          {disband.error && <Alert>{describeError(disband.error)}</Alert>}
        </section>
      )}
    </div>
  )
}
