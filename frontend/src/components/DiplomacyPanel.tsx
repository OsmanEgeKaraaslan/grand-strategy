import { useState } from 'react'
import { describeError } from '../api/client'
import { useAcceptPeace, useAllianceAction, useDeclareWar, usePeaceAction, useProposeAlliance } from '../api/queries'
import type {
  AllianceKind,
  AllianceView,
  DiplomacyOverview,
  GameState,
  NationRelation,
  PeaceProposal,
  SideScore,
  WarView,
} from '../api/types'
import { BLOCKER_TEXT, describeEvent, describeTerm, isMajorSettlement, settlementSummary, type Names } from '../lib/diplomacy'
import { formatGold } from '../lib/gold'
import { Alert } from './Alert'
import { PeaceNegotiation, SettlementSummary } from './PeaceNegotiation'

const STATUS_LABELS: Record<string, string> = { WAR: 'At war', ALLIED: 'Allied', PEACE: 'At peace' }
const JOIN_LABELS: Record<string, string> = {
  DECLARED: 'declared the war',
  ATTACKED: 'was attacked',
  DEFENSIVE_RESPONSE: 'joined to defend an ally',
}

interface DiplomacyPanelProps {
  gameId: string
  overview: DiplomacyOverview
  state: GameState
  names: Names
  capitalKeys: Set<string>
}

/**
 * Relations with every nation, wars and their score, alliances, peace proposals, treaties, tribute
 * and the public history. Buttons only express intents; the server decides what is allowed (the
 * blockers shown come from it) and computes every number.
 */
export function DiplomacyPanel({ gameId, overview, state, names, capitalKeys }: DiplomacyPanelProps) {
  const [negotiating, setNegotiating] = useState<{ war: WarView; counterOf?: PeaceProposal } | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  if (!overview.available || !overview.rules) {
    return (
      <section className="card" aria-labelledby="diplomacy-title">
        <h2 id="diplomacy-title">Diplomacy</h2>
        <p className="muted">This game was created before diplomacy existed: every nation is permanently at war.</p>
      </section>
    )
  }
  const rules = overview.rules
  const you = overview.yourNationId
  const finished = state.status === 'FINISHED'
  const activeWars = overview.wars.filter((w) => w.status === 'ACTIVE')
  const endedWars = overview.wars.filter((w) => w.status === 'ENDED')
  const pending = overview.proposals.filter((p) => p.status === 'PENDING')
  const past = overview.proposals.filter((p) => p.status !== 'PENDING').slice(0, 5)

  return (
    <section className="card diplomacy" aria-labelledby="diplomacy-title">
      <h2 id="diplomacy-title">Diplomacy</h2>
      {message && <p className="muted" role="status">{message}</p>}

      <h3>Nations</h3>
      <ul className="diplomacy__nations">
        {overview.nations
          .filter((n) => !n.you)
          .map((n) => (
            <NationRow
              key={n.nationId}
              gameId={gameId}
              relation={n}
              yourTurn={overview.yourTurn && !finished}
              round={overview.round}
              onMessage={setMessage}
            />
          ))}
      </ul>

      <h3>Wars</h3>
      {activeWars.length === 0 && <p className="muted">No war is being fought.</p>}
      {activeWars.map((w) => (
        <WarCard
          key={w.id}
          war={w}
          names={names}
          canNegotiate={w.youLead && overview.yourTurn && !finished}
          onNegotiate={() => setNegotiating({ war: w })}
        />
      ))}
      {negotiating && (
        <PeaceNegotiation
          key={negotiating.counterOf?.id ?? negotiating.war.id}
          gameId={gameId}
          war={negotiating.war}
          yourNationId={you}
          state={state}
          names={names}
          capitalKeys={capitalKeys}
          rules={rules}
          counterOf={negotiating.counterOf}
          onDone={(text) => {
            setNegotiating(null)
            setMessage(text)
          }}
          onCancel={() => setNegotiating(null)}
        />
      )}

      <h3>Peace proposals</h3>
      {pending.length === 0 && <p className="muted">No proposal is waiting for an answer.</p>}
      {pending.map((p) => (
        <ProposalCard
          key={p.id}
          gameId={gameId}
          proposal={p}
          names={names}
          you={you}
          yourTurn={overview.yourTurn && !finished}
          onCounter={() => {
            const war = overview.wars.find((w) => w.id === p.warId)
            if (war) setNegotiating({ war, counterOf: p })
          }}
          onMessage={setMessage}
        />
      ))}
      {past.length > 0 && (
        <details>
          <summary>Earlier proposals</summary>
          <ul className="battle-list">
            {past.map((p) => (
              <li key={p.id}>
                Round {p.createdRound}: {p.incoming ? `from ${names.nation(p.proposerNationId)}` : `to ${names.nation(p.recipientNationId)}`}{' '}
                · {p.terms.length} term(s) · <strong>{p.status.toLowerCase()}</strong>
              </li>
            ))}
          </ul>
        </details>
      )}

      <Alliances gameId={gameId} alliances={overview.alliances} names={names} yourTurn={overview.yourTurn && !finished} onMessage={setMessage} />

      {overview.treaties.length > 0 && (
        <>
          <h3>Treaties</h3>
          <ul className="battle-list">
            {overview.treaties.map((t) => (
              <li key={t.id}>
                Signed in round {t.signedRound} · truce until round {t.truceUntilRound}
                {t.terms.length === 0 ? ' · white peace' : ''}
                <ul>
                  {t.terms.map((term, i) => (
                    <li key={i}>{describeTerm(term, names)}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </>
      )}

      {overview.tributes.length > 0 && (
        <>
          <h3>Tribute</h3>
          <ul className="battle-list">
            {overview.tributes.map((t) => (
              <li key={t.id}>
                {names.nation(t.payerNationId)} → {names.nation(t.receiverNationId)}: {t.rateBps / 100}% of gross income ·{' '}
                {t.roundsCharged}/{t.roundsTotal} rounds · {formatGold(t.totalPaidMg)} gold paid ·{' '}
                <strong>{t.status.toLowerCase()}</strong>
                {t.endReason === 'WAR_RENEWED' ? ' (war renewed)' : ''}
              </li>
            ))}
          </ul>
        </>
      )}

      {endedWars.length > 0 && (
        <details>
          <summary>Ended wars ({endedWars.length})</summary>
          {endedWars.map((w) => (
            <WarCard key={w.id} war={w} names={names} canNegotiate={false} onNegotiate={() => undefined} />
          ))}
        </details>
      )}

      <h3>History</h3>
      {overview.events.length === 0 ? (
        <p className="muted">Nothing has happened yet.</p>
      ) : (
        <ul className="battle-list diplomacy__events">
          {overview.events.slice(0, 12).map((e) => (
            <li key={e.id}>
              <span className="muted">Round {e.round}:</span> {describeEvent(e, names)}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function NationRow({
  gameId,
  relation: n,
  yourTurn,
  round,
  onMessage,
}: {
  gameId: string
  relation: NationRelation
  yourTurn: boolean
  round: number
  onMessage: (text: string) => void
}) {
  const [confirmWar, setConfirmWar] = useState(false)
  const [kind, setKind] = useState<AllianceKind>('NORMAL')
  const declare = useDeclareWar(gameId)
  const propose = useProposeAlliance(gameId)

  return (
    <li className="diplomacy__nation">
      <span className="nation-name">
        <span className="swatch" style={{ background: n.color }} aria-hidden="true" />
        {n.name}
      </span>{' '}
      <span className={`tag ${n.status === 'WAR' ? 'tag--warning' : ''}`}>{STATUS_LABELS[n.status] ?? n.status}</span>
      {n.allianceKind && <span className="tag">{n.allianceKind === 'NORMAL' ? 'Normal alliance' : 'Defensive pact'}</span>}
      {n.truceUntilRound !== null && <span className="tag">Truce until round {n.truceUntilRound}</span>}
      <span className="muted">
        {' '}
        · your battalions {n.weMayEnter ? 'may' : 'may not'} enter its territory
      </span>
      {yourTurn && n.status !== 'WAR' && (
        <div className="diplomacy__actions">
          {n.warBlockers.length === 0 ? (
            confirmWar ? (
              <>
                <span>Declare war on {n.name}? Its allies may join the defence.</span>
                <button
                  type="button"
                  className="button button--danger"
                  disabled={declare.isPending}
                  onClick={() =>
                    declare.mutate(
                      { targetNationId: n.nationId, idempotencyKey: crypto.randomUUID() },
                      {
                        onSuccess: (r) => {
                          setConfirmWar(false)
                          onMessage(
                            `War declared on ${n.name} in round ${round}.` +
                              (r.joinedDefenders.length > 0 ? ` ${r.joinedDefenders.length} ally(ies) joined the defence.` : ''),
                          )
                        },
                      },
                    )
                  }
                >
                  Confirm declaration
                </button>
                <button type="button" className="button" onClick={() => setConfirmWar(false)}>
                  Keep the peace
                </button>
              </>
            ) : (
              <button type="button" className="button button--danger" onClick={() => setConfirmWar(true)}>
                Declare war
              </button>
            )
          ) : (
            <span className="muted">War: {n.warBlockers.map((b) => BLOCKER_TEXT[b] ?? b).join(' ')}</span>
          )}
          {n.allianceBlockers.length === 0 ? (
            <span className="diplomacy__alliance">
              <select aria-label={`Alliance type with ${n.name}`} value={kind} onChange={(e) => setKind(e.target.value as AllianceKind)}>
                <option value="NORMAL">Normal alliance (military access)</option>
                <option value="DEFENSIVE">Defensive pact</option>
              </select>
              <button
                type="button"
                className="button"
                disabled={propose.isPending}
                onClick={() =>
                  propose.mutate(
                    { nationId: n.nationId, kind, idempotencyKey: crypto.randomUUID() },
                    { onSuccess: () => onMessage(`Alliance proposed to ${n.name}. It answers on its turn.`) },
                  )
                }
              >
                Propose alliance
              </button>
            </span>
          ) : (
            n.status !== 'ALLIED' && (
              <span className="muted">Alliance: {n.allianceBlockers.map((b) => BLOCKER_TEXT[b] ?? b).join(' ')}</span>
            )
          )}
        </div>
      )}
      {declare.error && <Alert>{describeError(declare.error)}</Alert>}
      {propose.error && <Alert>{describeError(propose.error)}</Alert>}
    </li>
  )
}

function WarCard({
  war,
  names,
  canNegotiate,
  onNegotiate,
}: {
  war: WarView
  names: Names
  canNegotiate: boolean
  onNegotiate: () => void
}) {
  const attackers = war.participants.filter((p) => p.side === 'ATTACKER')
  const defenders = war.participants.filter((p) => p.side === 'DEFENDER')
  const max = war.score.maxScore
  const net = war.score.net
  return (
    <article className="war" aria-label={`War: ${names.nation(war.attackerLeaderId)} against ${names.nation(war.defenderLeaderId)}`}>
      <h4>
        {names.nation(war.attackerLeaderId)} against {names.nation(war.defenderLeaderId)}
      </h4>
      <p className="muted">
        Declared in round {war.declaredRound} · {war.durationRounds} round(s)
        {war.status === 'ENDED' ? ` · ended in round ${war.endedRound} by peace treaty` : ''}
        {war.yourSide ? ` · you are the ${war.yourSide === 'ATTACKER' ? 'attacker' : 'defender'}` : ''}
      </p>
      <p className="muted">
        Attackers: {attackers.map((p) => `${p.name} (${JOIN_LABELS[p.joinReason] ?? p.joinReason})`).join(', ')} · Defenders:{' '}
        {defenders.map((p) => `${p.name} (${JOIN_LABELS[p.joinReason] ?? p.joinReason})`).join(', ')}
      </p>
      {defenders.some((p) => p.joinReason === 'DEFENSIVE_RESPONSE') && (
        <p className="muted">
          Allies who joined are legally at war; battles with several nations on one side arrive in a later phase.
        </p>
      )}
      <div className="war__score" role="meter" aria-valuemin={-max} aria-valuemax={max} aria-valuenow={net} aria-label="War score">
        <ScoreSide title={names.nation(war.attackerLeaderId)} score={war.score.attacker} />
        <ScoreSide title={names.nation(war.defenderLeaderId)} score={war.score.defender} />
      </div>
      <p>
        War score: <strong>{net > 0 ? `+${net}` : net}</strong>{' '}
        <span className="muted">
          ({net === 0 ? 'even' : `favours ${names.nation(net > 0 ? war.attackerLeaderId : war.defenderLeaderId)}`}; it guides
          the negotiation but never forces a peace)
        </span>
      </p>
      {canNegotiate && (
        <button type="button" className="button button--primary" onClick={onNegotiate}>
          Negotiate peace
        </button>
      )}
    </article>
  )
}

function ScoreSide({ title, score }: { title: string; score: SideScore }) {
  return (
    <dl className="facts war__side">
      <dt>{title}</dt>
      <dd>
        <strong>{score.total}</strong> points · battles {score.battles} ({score.battlesWon} won) · sieges {score.sieges} (
        {score.siegesWon} won) · occupation {score.occupation} ({score.occupiedEnemyPopulation.toLocaleString('en-US')} of{' '}
        {score.enemyPopulation.toLocaleString('en-US')} enemy people)
      </dd>
    </dl>
  )
}

function ProposalCard({
  gameId,
  proposal: p,
  names,
  you,
  yourTurn,
  onCounter,
  onMessage,
}: {
  gameId: string
  proposal: PeaceProposal
  names: Names
  you: string
  yourTurn: boolean
  onCounter: () => void
  onMessage: (text: string) => void
}) {
  const [confirming, setConfirming] = useState(false)
  const accept = useAcceptPeace(gameId)
  const action = usePeaceAction(gameId)
  const major = isMajorSettlement(p.terms)

  function handleAccept() {
    if (major && !confirming) {
      setConfirming(true)
      return
    }
    accept.mutate(
      { proposalId: p.id, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (r) =>
          onMessage(
            `Peace signed. Truce until round ${r.treaty.truceUntilRound}.` +
              (r.withdrawals.length > 0 ? ` ${r.withdrawals.length} battalion(s) withdrew.` : ''),
          ),
      },
    )
  }

  return (
    <article className="proposal" aria-label={p.incoming ? 'Incoming peace proposal' : 'Your peace proposal'}>
      <h4>
        {p.incoming ? `From ${names.nation(p.proposerNationId)}` : `To ${names.nation(p.recipientNationId)}`}{' '}
        <span className="muted">· round {p.createdRound} · expires after round {p.expiresAfterRound}</span>
      </h4>
      {p.terms.length === 0 ? (
        <p>White peace: the war ends, nothing changes hands.</p>
      ) : (
        <ol>
          {p.terms.map((t, i) => (
            <li key={i}>{describeTerm(t, names)}</li>
          ))}
        </ol>
      )}
      <SettlementSummary summary={settlementSummary(p.terms, names)} names={names} you={you} />
      {p.violations.length > 0 && (
        <Alert>This proposal can no longer be settled as it stands ({p.violations.join('; ')}).</Alert>
      )}
      {yourTurn && p.incoming && (
        <div className="peace__actions">
          <button type="button" className="button button--primary" disabled={accept.isPending} onClick={handleAccept}>
            {confirming ? 'Confirm: accept these terms' : 'Accept'}
          </button>
          <button
            type="button"
            className="button"
            disabled={action.isPending}
            onClick={() =>
              action.mutate(
                { proposalId: p.id, action: 'reject', idempotencyKey: crypto.randomUUID() },
                { onSuccess: () => onMessage('Proposal rejected. The war goes on.') },
              )
            }
          >
            Reject
          </button>
          <button type="button" className="button" onClick={onCounter}>
            Counter-proposal
          </button>
        </div>
      )}
      {confirming && (
        <p className="tag tag--warning" role="alert">
          Territory, people, tribute or a large payment will change hands at once and cannot be undone.
        </p>
      )}
      {yourTurn && !p.incoming && (
        <button
          type="button"
          className="button"
          disabled={action.isPending}
          onClick={() =>
            action.mutate(
              { proposalId: p.id, action: 'cancel', idempotencyKey: crypto.randomUUID() },
              { onSuccess: () => onMessage('Proposal withdrawn.') },
            )
          }
        >
          Withdraw proposal
        </button>
      )}
      {!yourTurn && p.incoming && <p className="muted">You can answer on your turn.</p>}
      {accept.error && <Alert>{describeError(accept.error)}</Alert>}
      {action.error && <Alert>{describeError(action.error)}</Alert>}
    </article>
  )
}

function Alliances({
  gameId,
  alliances,
  names,
  yourTurn,
  onMessage,
}: {
  gameId: string
  alliances: AllianceView[]
  names: Names
  yourTurn: boolean
  onMessage: (text: string) => void
}) {
  const action = useAllianceAction(gameId)
  const open = alliances.filter((a) => a.status === 'ACTIVE' || a.status === 'PROPOSED')
  if (open.length === 0) return null

  function run(a: AllianceView, act: 'accept' | 'reject' | 'terminate', text: string) {
    action.mutate(
      { allianceId: a.id, action: act, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (r) =>
          onMessage(text + (r.withdrawals.length > 0 ? ` ${r.withdrawals.length} battalion(s) withdrew.` : '')),
      },
    )
  }

  return (
    <>
      <h3>Alliances</h3>
      <ul className="battle-list">
        {open.map((a) => (
          <li key={a.id}>
            {a.kind === 'NORMAL' ? 'Normal alliance' : 'Defensive pact'}: {names.nation(a.nationA)} and {names.nation(a.nationB)} ·{' '}
            {a.status === 'PROPOSED'
              ? `proposed by ${names.nation(a.proposerNationId)}, expires after round ${a.expiresAfterRound}`
              : `since round ${a.acceptedRound}`}{' '}
            · {a.grantsMilitaryAccess ? 'grants military access' : 'no military access'}
            {yourTurn && a.canAccept && (
              <>
                {' '}
                <button type="button" className="button" disabled={action.isPending} onClick={() => run(a, 'accept', 'Alliance formed.')}>
                  Accept
                </button>{' '}
                <button type="button" className="button" disabled={action.isPending} onClick={() => run(a, 'reject', 'Alliance rejected.')}>
                  Reject
                </button>
              </>
            )}
            {yourTurn && a.canEnd && (
              <>
                {' '}
                <button
                  type="button"
                  className="button"
                  disabled={action.isPending}
                  onClick={() => run(a, 'terminate', a.status === 'PROPOSED' ? 'Proposal withdrawn.' : 'Alliance ended.')}
                >
                  {a.status === 'PROPOSED' ? 'Withdraw' : 'End alliance'}
                </button>
              </>
            )}
            {a.endBlockers.length > 0 && (
              <span className="muted"> · {a.endBlockers.map((b) => BLOCKER_TEXT[b] ?? b).join(' ')}</span>
            )}
          </li>
        ))}
      </ul>
      {action.error && <Alert>{describeError(action.error)}</Alert>}
    </>
  )
}
