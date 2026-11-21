import { useState } from 'react'
import { describeError } from '../api/client'
import { useCheckPeace, useCounterPeace, useProposePeace } from '../api/queries'
import type { DiplomacyRules, GameState, PeaceProposal, PeaceTerm, TermType, WarView } from '../api/types'
import { VIOLATION_TEXT, describeTerm, isMajorSettlement, settlementSummary, type Names } from '../lib/diplomacy'
import { formatGold, parseGoldToMg } from '../lib/gold'
import { Alert } from './Alert'

const TERM_LABELS: Record<TermType, string> = {
  PROVINCE: 'Cede a province',
  GOLD: 'Pay reparations (gold, at once)',
  TRIBUTE: 'Pay tribute (share of income, several rounds)',
  POPULATION: 'Transfer people between cities',
}

interface PeaceNegotiationProps {
  gameId: string
  war: WarView
  yourNationId: string
  state: GameState
  names: Names
  /** Starting capitals can never be ceded. */
  capitalKeys: Set<string>
  rules: DiplomacyRules
  /** When answering a proposal: its terms are the starting point and the answer supersedes it. */
  counterOf?: PeaceProposal
  onDone: (message: string) => void
  onCancel: () => void
}

/**
 * Builds a peace offer from the four approved term types and sends it to the other war leader.
 * The server validates every term (ownership, treasury, population limits) when the offer is made
 * and again when it is accepted; the summary here only explains the offer.
 */
export function PeaceNegotiation({
  gameId,
  war,
  yourNationId,
  state,
  names,
  capitalKeys,
  rules,
  counterOf,
  onDone,
  onCancel,
}: PeaceNegotiationProps) {
  const other = war.attackerLeaderId === yourNationId ? war.defenderLeaderId : war.attackerLeaderId
  const [terms, setTerms] = useState<PeaceTerm[]>(() =>
    (counterOf?.terms ?? []).map((t) => ({ ...t, tributeRateBps: null, tributeRounds: null })),
  )
  const [confirming, setConfirming] = useState(false)
  const propose = useProposePeace(gameId)
  const counter = useCounterPeace(gameId)
  const check = useCheckPeace(gameId)
  // Sending is shown at once (D-UX1); a refusal rolls the proposal back and is reported.
  const sending = false
  const error = propose.error ?? counter.error
  const summary = settlementSummary(terms, names)

  function add(term: PeaceTerm) {
    setTerms((current) => [...current, term])
    setConfirming(false)
    check.reset()
  }

  function remove(index: number) {
    setTerms((current) => current.filter((_, i) => i !== index))
    setConfirming(false)
    check.reset()
  }

  function send() {
    if (isMajorSettlement(terms) && !confirming) {
      setConfirming(true)
      return
    }
    const idempotencyKey = crypto.randomUUID()
    if (counterOf) {
      counter.mutate({ proposalId: counterOf.id, terms, idempotencyKey })
    } else {
      propose.mutate({ warId: war.id, terms, idempotencyKey })
    }
    onDone(counterOf ? 'Counter-proposal sent.' : 'Peace proposal sent.')
  }

  return (
    <div className="peace" role="group" aria-label="Peace negotiation">
      <h3>{counterOf ? 'Counter-proposal' : 'Propose peace'} to {names.nation(other)}</h3>
      <p className="muted">
        War score guides the negotiation but never forces it: any terms both leaders agree to are valid. A treaty ends the
        war for every participant and starts a truce of {rules.truceRounds} rounds.
      </p>
      {terms.length === 0 ? (
        <p className="muted">No terms: a white peace. Add terms below.</p>
      ) : (
        <ol className="peace__terms">
          {terms.map((t, i) => (
            <li key={i}>
              {describeTerm({ ...t, tributeRateBps: rules.tributeRateBps, tributeRounds: rules.tributeRounds }, names)}{' '}
              <button type="button" className="link-button" onClick={() => remove(i)}>
                Remove
              </button>
            </li>
          ))}
        </ol>
      )}
      {terms.length < rules.maxPeaceTerms && (
        <TermBuilder
          you={yourNationId}
          givers={[other, yourNationId]}
          receiversOf={(giver) => [giver === yourNationId ? other : yourNationId]}
          state={state}
          names={names}
          capitalKeys={capitalKeys}
          rules={rules}
          hasTribute={terms.some((t) => t.type === 'TRIBUTE')}
          onAdd={add}
        />
      )}

      <h4>What each side gains and loses</h4>
      <SettlementSummary summary={summary} names={names} you={yourNationId} />

      <div className="peace__actions">
        <button
          type="button"
          className="button"
          disabled={check.isPending}
          onClick={() => check.mutate({ warId: war.id, terms })}
        >
          Check terms
        </button>
        <button type="button" className="button button--primary" disabled={sending} onClick={send}>
          {confirming ? 'Confirm and send' : counterOf ? 'Send counter-proposal' : 'Send proposal'}
        </button>
        <button type="button" className="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
      {confirming && (
        <p className="tag tag--warning" role="alert">
          This is a major settlement (territory, people, tribute or a large payment). Press “Confirm and send” to offer it.
        </p>
      )}
      {check.data &&
        (check.data.valid ? (
          <p className="muted">The server would accept these terms as they stand.</p>
        ) : (
          <Alert>
            {check.data.violations
              .map((v) => `${v.term < 0 ? 'Proposal' : `Term ${v.term + 1}`}: ${VIOLATION_TEXT[v.code] ?? v.code}`)
              .join(' ')}
          </Alert>
        ))}
      {check.error && <Alert>{describeError(check.error)}</Alert>}
      {error && <Alert>{describeError(error)}</Alert>}
    </div>
  )
}

export function SettlementSummary({
  summary,
  names,
  you,
}: {
  summary: Map<string, { gains: string[]; losses: string[] }>
  names: Names
  you: string
}) {
  if (summary.size === 0) return <p className="muted">Nothing changes hands.</p>
  return (
    <dl className="facts peace__summary">
      {[...summary.entries()].map(([nationId, s]) => (
        <div key={nationId}>
          <dt>{nationId === you ? `${names.nation(nationId)} (you)` : names.nation(nationId)}</dt>
          <dd>
            {s.gains.length > 0 && <span>Gains: {s.gains.join(', ')}. </span>}
            {s.losses.length > 0 && <span>Gives up: {s.losses.join(', ')}.</span>}
          </dd>
        </div>
      ))}
    </dl>
  )
}

/**
 * One term at a time. `givers` lists the nations that may give something; `receiversOf`
 * the nations of the other side that may receive it (two leaders in a Phase 9 negotiation, the
 * coalitions in a Phase 10 peace conference).
 */
export function TermBuilder({
  you,
  givers,
  receiversOf,
  state,
  names,
  capitalKeys,
  rules,
  hasTribute,
  onAdd,
}: {
  you: string
  givers: string[]
  receiversOf: (giver: string) => string[]
  state: GameState
  names: Names
  capitalKeys: Set<string>
  rules: DiplomacyRules
  hasTribute: boolean
  onAdd: (term: PeaceTerm) => void
}) {
  const [type, setType] = useState<TermType>('PROVINCE')
  const [giver, setGiver] = useState<string>(givers[0])
  const receivers = receiversOf(giver)
  const [chosenReceiver, setChosenReceiver] = useState<string>('')
  const [province, setProvince] = useState('')
  const [gold, setGold] = useState('')
  const [source, setSource] = useState('')
  const [destination, setDestination] = useState('')
  const [people, setPeople] = useState(String(rules.populationMinPeople))
  const receiver = receivers.includes(chosenReceiver) ? chosenReceiver : receivers[0]
  const giverProvinces = state.provinces.filter((p) => p.ownerNationId === giver)
  const receiverProvinces = state.provinces.filter((p) => p.ownerNationId === receiver)
  const amountMg = parseGoldToMg(gold)
  const peopleCount = /^\d+$/.test(people) ? Number(people) : null

  function build(): PeaceTerm | null {
    const base = { fromNationId: giver, toNationId: receiver }
    switch (type) {
      case 'PROVINCE':
        return province ? { ...base, type, provinceKey: province } : null
      case 'GOLD':
        return amountMg !== null && amountMg > 0 ? { ...base, type, amountMg } : null
      case 'TRIBUTE':
        return hasTribute ? null : { ...base, type }
      case 'POPULATION':
        return source && destination && peopleCount !== null
          ? { ...base, type, sourceCityId: source, destinationCityId: destination, people: peopleCount }
          : null
    }
  }

  const term = build()

  return (
    <form
      className="peace__builder"
      onSubmit={(event) => {
        event.preventDefault()
        if (term) onAdd(term)
      }}
    >
      <label>
        Term
        <select value={type} onChange={(e) => setType(e.target.value as TermType)}>
          {(Object.keys(TERM_LABELS) as TermType[]).map((t) => (
            <option key={t} value={t}>
              {TERM_LABELS[t]}
            </option>
          ))}
        </select>
      </label>
      <label>
        Given by
        <select value={giver} onChange={(e) => setGiver(e.target.value)}>
          {givers.map((g) => (
            <option key={g} value={g}>
              {names.nation(g)}
              {g === you ? ' (you)' : ''}
            </option>
          ))}
        </select>
      </label>
      {receivers.length > 1 && (
        <label>
          Given to
          <select value={receiver} onChange={(e) => setChosenReceiver(e.target.value)}>
            {receivers.map((r) => (
              <option key={r} value={r}>
                {names.nation(r)}
                {r === you ? ' (you)' : ''}
              </option>
            ))}
          </select>
        </label>
      )}
      {type === 'PROVINCE' && (
        <label>
          Province
          <select value={province} onChange={(e) => setProvince(e.target.value)}>
            <option value="">Choose…</option>
            {giverProvinces
              .filter((p) => !capitalKeys.has(p.key))
              .map((p) => (
                <option key={p.key} value={p.key}>
                  {names.province(p.key)}
                  {p.controllerNationId !== p.ownerNationId ? ' (occupied)' : ''}
                </option>
              ))}
          </select>
        </label>
      )}
      {type === 'GOLD' && (
        <label>
          Gold (up to {formatGold(rules.reparationsMaxMg)})
          <input inputMode="decimal" value={gold} onChange={(e) => setGold(e.target.value)} placeholder="e.g. 25" />
        </label>
      )}
      {type === 'TRIBUTE' && (
        <p className="muted">
          {rules.tributeRateBps / 100}% of the payer’s gross income, once per round for {rules.tributeRounds} rounds, never
          more than its treasury holds.{hasTribute ? ' This treaty already has a tribute.' : ''}
        </p>
      )}
      {type === 'POPULATION' && (
        <>
          <label>
            From city
            <select value={source} onChange={(e) => setSource(e.target.value)}>
              <option value="">Choose…</option>
              {giverProvinces.map((p) => (
                <option key={p.city.id} value={p.city.id}>
                  {p.city.name} ({p.city.population.toLocaleString('en-US')})
                </option>
              ))}
            </select>
          </label>
          <label>
            To city
            <select value={destination} onChange={(e) => setDestination(e.target.value)}>
              <option value="">Choose…</option>
              {receiverProvinces.map((p) => (
                <option key={p.city.id} value={p.city.id}>
                  {p.city.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            People ({rules.populationMinPeople.toLocaleString('en-US')}–{rules.populationMaxPeople.toLocaleString('en-US')})
            <input inputMode="numeric" value={people} onChange={(e) => setPeople(e.target.value)} />
          </label>
        </>
      )}
      <button type="submit" className="button" disabled={term === null}>
        Add term
      </button>
    </form>
  )
}
