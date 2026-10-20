import { useState, type ReactNode } from 'react'
import { describeError } from '../api/client'
import {
  useAcceptPeace,
  useAllianceAction,
  useAnswerCall,
  useCallToArms,
  useDeclareWar,
  usePeaceAction,
  useProposeAlliance,
  useRequestConference,
} from '../api/queries'
import type {
  AllianceKind,
  AllianceView,
  CallToArms,
  ConferenceKind,
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
import { ConferenceCard } from './PeaceConference'
import { PeaceNegotiation, SettlementSummary } from './PeaceNegotiation'
import { SendGold } from './SendGold'

const STATUS_LABELS: Record<string, string> = { WAR: 'At war', ALLIED: 'Allied', PEACE: 'At peace' }
const JOIN_LABELS: Record<string, string> = {
  DECLARED: 'declared the war',
  ATTACKED: 'was attacked',
  DEFENSIVE_RESPONSE: 'joined to defend an ally',
  CALL_TO_ARMS: 'answered a call to arms',
}

const LEFT_LABELS: Record<string, string> = {
  SEPARATE_PEACE: 'left by separate peace',
  ELIMINATED: 'eliminated',
}

interface DiplomacyPanelProps {
  gameId: string
  overview: DiplomacyOverview
  state: GameState
  names: Names
  capitalKeys: Set<string>
  /** The caller's treasury (from the economy view), for the gift preview. */
  treasuryMg?: number
}

/**
 * Relations with every nation, wars and their score, alliances, peace proposals, treaties, tribute
 * and the public history. Buttons only express intents; the server decides what is allowed (the
 * blockers shown come from it) and computes every number.
 */
export function DiplomacyPanel({ gameId, overview, state, names, capitalKeys, treasuryMg }: DiplomacyPanelProps) {
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
  const features = rules.features
  const conferencesMode = Boolean(features?.peaceConferences)
  const conferences = overview.conferences ?? []
  const openConferences = conferences.filter((c) => c.status === 'REQUESTED' || c.status === 'ACTIVE')
  const pastConferences = conferences.filter((c) => c.status !== 'REQUESTED' && c.status !== 'ACTIVE')
  const calls = overview.calls ?? []
  const paused = Boolean(state.pausedConferenceId)
  const allies = overview.nations.filter((n) => n.status === 'ALLIED').map((n) => n.nationId)
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

      <SendGold gameId={gameId} overview={overview} names={names} treasuryMg={treasuryMg} yourTurn={overview.yourTurn && !finished} />

      <h3>Wars</h3>
      {activeWars.length === 0 && <p className="muted">No war is being fought.</p>}
      {activeWars.map((w) => (
        <WarCard
          key={w.id}
          war={w}
          names={names}
          canNegotiate={!conferencesMode && w.youLead && overview.yourTurn && !finished}
          onNegotiate={() => setNegotiating({ war: w })}
          coalitions={Boolean(features?.coalitionWarfare)}
        >
          {conferencesMode && !finished && (
            <WarActions
              gameId={gameId}
              war={w}
              you={you}
              names={names}
              yourTurn={overview.yourTurn}
              paused={paused}
              separatePeace={Boolean(features?.separatePeace)}
              callsToArms={Boolean(features?.callsToArms)}
              allies={allies}
              calls={calls}
              alreadyRequested={openConferences.some((c) => c.warId === w.id && c.requesterNationId === you)}
              onMessage={setMessage}
            />
          )}
        </WarCard>
      ))}

      {calls.length > 0 && (
        <CallsToArms gameId={gameId} calls={calls} names={names} you={you} yourTurn={overview.yourTurn && !finished} onMessage={setMessage} />
      )}

      {conferencesMode && (
        <>
          <h3>Peace conferences</h3>
          {openConferences.length === 0 && (
            <p className="muted">
              No conference is open. A war leader may request the general peace; another nation fighting a war may ask the
              opposing leader for a separate peace.
            </p>
          )}
          {openConferences.map((c) => (
            <ConferenceCard
              key={c.id}
              gameId={gameId}
              conference={c}
              war={overview.wars.find((w) => w.id === c.warId)}
              yourNationId={you}
              state={state}
              names={names}
              capitalKeys={capitalKeys}
              rules={rules}
              onMessage={setMessage}
            />
          ))}
          {pastConferences.length > 0 && (
            <details>
              <summary>Earlier conferences ({pastConferences.length})</summary>
              <ul className="battle-list">
                {pastConferences.map((c) => (
                  <li key={c.id}>
                    {c.kind === 'GENERAL' ? 'General peace' : 'Separate peace'}: {names.nation(c.requesterNationId)} and{' '}
                    {names.nation(c.counterpartNationId)} · <strong>{c.status.toLowerCase()}</strong>
                    {c.currentRevision > 0 ? ` after ${c.currentRevision} revision(s)` : ''}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
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

      {!conferencesMode && <h3>Peace proposals</h3>}
      {!conferencesMode && pending.length === 0 && <p className="muted">No proposal is waiting for an answer.</p>}
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
  coalitions = false,
  children,
}: {
  war: WarView
  names: Names
  canNegotiate: boolean
  onNegotiate: () => void
  coalitions?: boolean
  children?: ReactNode
}) {
  const describe = (p: WarView['participants'][number]) =>
    `${p.name} (${JOIN_LABELS[p.joinReason] ?? p.joinReason}${
      p.leftRound != null ? `; ${LEFT_LABELS[p.leftReason ?? ''] ?? 'left'} in round ${p.leftRound}` : ''
    })`
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
        Attackers: {attackers.map(describe).join(', ')} · Defenders: {defenders.map(describe).join(', ')}
      </p>
      {!coalitions && defenders.some((p) => p.joinReason === 'DEFENSIVE_RESPONSE') && (
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
      {children}
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

/**
 * Phase 10 actions on one war: the attacked leader calls allies to arms (on its turn); a leader asks
 * for the general peace and any other nation fighting asks for a separate peace (at any time, as long
 * as no conference is in session).
 */
function WarActions({
  gameId,
  war,
  you,
  names,
  yourTurn,
  paused,
  separatePeace,
  callsToArms,
  allies,
  calls,
  alreadyRequested,
  onMessage,
}: {
  gameId: string
  war: WarView
  you: string
  names: Names
  yourTurn: boolean
  paused: boolean
  separatePeace: boolean
  callsToArms: boolean
  allies: string[]
  calls: CallToArms[]
  alreadyRequested: boolean
  onMessage: (text: string) => void
}) {
  const request = useRequestConference(gameId)
  const call = useCallToArms(gameId)
  const fighting = new Set(war.participants.filter((p) => p.leftRound == null).map((p) => p.nationId))
  const callable = allies.filter(
    (a) => !fighting.has(a) && !calls.some((c) => c.status === 'PENDING' && c.warId === war.id && c.allyNationId === a),
  )
  const [ally, setAlly] = useState('')
  const chosen = callable.includes(ally) ? ally : (callable[0] ?? '')
  const youFight = fighting.has(you)
  const kind: ConferenceKind | null = !youFight ? null : war.youLead ? 'GENERAL' : separatePeace ? 'SEPARATE' : null
  const counterpart = war.yourSide === 'ATTACKER' ? war.defenderLeaderId : war.attackerLeaderId

  return (
    <div className="diplomacy__actions">
      {kind && !paused && !alreadyRequested && (
        <button
          type="button"
          className="button button--primary"
          disabled={request.isPending}
          onClick={() =>
            request.mutate(
              { warId: war.id, kind, idempotencyKey: crypto.randomUUID() },
              {
                onSuccess: (r) =>
                  onMessage(
                    r.conference.status === 'ACTIVE'
                      ? 'The peace conference is open. Normal play is paused.'
                      : `${names.nation(counterpart)} has been asked to a peace conference.`,
                  ),
              },
            )
          }
        >
          {kind === 'GENERAL' ? 'Request peace conference' : `Ask ${names.nation(counterpart)} for a separate peace`}
        </button>
      )}
      {callsToArms && war.defenderLeaderId === you && yourTurn && !paused && callable.length > 0 && (
        <span className="diplomacy__alliance">
          <select aria-label="Ally to call to arms" value={chosen} onChange={(e) => setAlly(e.target.value)}>
            {callable.map((a) => (
              <option key={a} value={a}>
                {names.nation(a)}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="button"
            disabled={call.isPending || !chosen}
            onClick={() =>
              call.mutate(
                { warId: war.id, allyNationId: chosen, idempotencyKey: crypto.randomUUID() },
                { onSuccess: () => onMessage(`${names.nation(chosen)} has been called to arms. It answers on its turn.`) },
              )
            }
          >
            Call to arms
          </button>
        </span>
      )}
      {request.error && <Alert>{describeError(request.error)}</Alert>}
      {call.error && <Alert>{describeError(call.error)}</Alert>}
    </div>
  )
}

/** Calls to arms: answer those addressed to you (on your turn); see the others. */
function CallsToArms({
  gameId,
  calls,
  names,
  you,
  yourTurn,
  onMessage,
}: {
  gameId: string
  calls: CallToArms[]
  names: Names
  you: string
  yourTurn: boolean
  onMessage: (text: string) => void
}) {
  const answer = useAnswerCall(gameId)
  const [declining, setDeclining] = useState<string | null>(null)
  const pending = calls.filter((c) => c.status === 'PENDING')
  const answered = calls.filter((c) => c.status !== 'PENDING')
  return (
    <>
      <h3>Calls to arms</h3>
      {pending.length === 0 && <p className="muted">No call to arms is waiting for an answer.</p>}
      <ul className="battle-list">
        {pending.map((c) => (
          <li key={c.id}>
            {names.nation(c.callerNationId)} calls {c.allyNationId === you ? 'you' : names.nation(c.allyNationId)} to arms
            (round {c.createdRound}).
            {c.canAnswer && yourTurn && (
              <span className="diplomacy__actions">
                {declining === c.id ? (
                  <>
                    <span>Declining ends your alliance with {names.nation(c.callerNationId)}.</span>
                    <button
                      type="button"
                      className="button button--danger"
                      disabled={answer.isPending}
                      onClick={() =>
                        answer.mutate(
                          { callId: c.id, accept: false, idempotencyKey: crypto.randomUUID() },
                          {
                            onSuccess: () => {
                              setDeclining(null)
                              onMessage(`You declined. Your alliance with ${names.nation(c.callerNationId)} has ended.`)
                            },
                          },
                        )
                      }
                    >
                      Confirm: decline
                    </button>
                    <button type="button" className="button" onClick={() => setDeclining(null)}>
                      Back
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      type="button"
                      className="button button--primary"
                      disabled={answer.isPending}
                      onClick={() =>
                        answer.mutate(
                          { callId: c.id, accept: true, idempotencyKey: crypto.randomUUID() },
                          { onSuccess: () => onMessage(`You joined the war on the side of ${names.nation(c.callerNationId)}.`) },
                        )
                      }
                    >
                      Honour — join the war
                    </button>
                    <button type="button" className="button" onClick={() => setDeclining(c.id)}>
                      Decline
                    </button>
                  </>
                )}
              </span>
            )}
          </li>
        ))}
        {answered.slice(0, 5).map((c) => (
          <li key={c.id} className="muted">
            Round {c.createdRound}: {names.nation(c.callerNationId)} called {names.nation(c.allyNationId)} ·{' '}
            {c.status.toLowerCase()}
          </li>
        ))}
      </ul>
      {answer.error && <Alert>{describeError(answer.error)}</Alert>}
    </>
  )
}
