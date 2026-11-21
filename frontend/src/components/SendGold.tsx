import { useState } from 'react'
import { describeError } from '../api/client'
import { useSendGold } from '../api/queries'
import type { DiplomacyOverview } from '../api/types'
import type { Names } from '../lib/diplomacy'
import { formatGold, parseGoldToMg } from '../lib/gold'
import { Alert } from './Alert'

/**
 * Sends gold to another nation (Phase 10). The player picks a recipient and an amount, sees the
 * treasury after the gift and confirms; the server checks the treasury, the limit and the
 * diplomatic restriction again and moves exactly that amount.
 */
export function SendGold({
  gameId,
  overview,
  names,
  treasuryMg,
  yourTurn,
}: {
  gameId: string
  overview: DiplomacyOverview
  names: Names
  treasuryMg: number | undefined
  yourTurn: boolean
}) {
  const rules = overview.rules
  const [recipient, setRecipient] = useState('')
  const [amount, setAmount] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const send = useSendGold(gameId)
  if (!rules || rules.giftMaxMg == null) return null

  const candidates = overview.nations.filter((n) => !n.you && (rules.giftsToEnemies || n.status !== 'WAR'))
  const amountMg = parseGoldToMg(amount)
  const after = treasuryMg !== undefined && amountMg !== null ? treasuryMg - amountMg : null
  const problem =
    amountMg === null || amountMg <= 0
      ? 'Enter an amount in gold (up to three decimals).'
      : amountMg > rules.giftMaxMg
        ? `At most ${formatGold(rules.giftMaxMg)} gold per gift.`
        : after !== null && after < 0
          ? 'Your treasury does not hold that much gold.'
          : recipient === ''
            ? 'Choose a recipient.'
            : null

  function submit() {
    if (problem) return
    if (!confirming) {
      setConfirming(true)
      return
    }
    // Shown at once (D-UX1): only your own treasury changes; a refusal is rolled back and reported.
    send.mutate({ recipientNationId: recipient, amountMg: amountMg!, idempotencyKey: crypto.randomUUID() })
    setMessage(
      `Sent ${formatGold(amountMg!)} gold to ${names.nation(recipient)}.` +
        (after !== null ? ` Your treasury: ${formatGold(after)} gold.` : ''),
    )
    setAmount('')
    setConfirming(false)
  }

  return (
    <div className="send-gold" role="group" aria-label="Send gold">
      <h3>Send gold</h3>
      <p className="muted">
        A voluntary gift: it cannot be undone and the recipient does not have to accept it.
        {!rules.giftsToEnemies && ' Nations you are at war with cannot receive gold.'}
      </p>
      {yourTurn ? (
        <form
          className="peace__builder"
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
        >
          <label>
            Recipient
            <select
              value={recipient}
              onChange={(event) => {
                setRecipient(event.target.value)
                setConfirming(false)
              }}
            >
              <option value="">Choose…</option>
              {candidates.map((n) => (
                <option key={n.nationId} value={n.nationId}>
                  {n.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Gold
            <input
              inputMode="decimal"
              value={amount}
              placeholder="e.g. 10"
              onChange={(event) => {
                setAmount(event.target.value)
                setConfirming(false)
              }}
            />
          </label>
          <button
            type="submit"
            className={`button ${confirming ? 'button--primary' : ''}`}
            disabled={problem !== null}
          >
            {confirming ? 'Confirm gift' : 'Send gold'}
          </button>
        </form>
      ) : (
        <p className="muted">You can send gold on your turn.</p>
      )}
      {yourTurn && amountMg !== null && amountMg > 0 && treasuryMg !== undefined && (
        <p className="muted">
          Treasury: {formatGold(treasuryMg)} → {after !== null && after >= 0 ? formatGold(after) : '—'} gold
        </p>
      )}
      {yourTurn && problem && amount !== '' && <p className="muted">{problem}</p>}
      {confirming && (
        <p className="tag tag--warning" role="alert">
          Send {formatGold(amountMg ?? 0)} gold to {names.nation(recipient)}? Press “Confirm gift”.
        </p>
      )}
      {message && <p role="status">{message}</p>}
      {send.error && <Alert>{describeError(send.error)}</Alert>}
      {overview.gifts.length > 0 && (
        <ul className="battle-list">
          {overview.gifts.slice(0, 8).map((g) => (
            <li key={g.id}>
              Round {g.round}: {names.nation(g.senderNationId)} → {names.nation(g.recipientNationId)}, {formatGold(g.amountMg)}{' '}
              gold
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
