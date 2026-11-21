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
import { BLOCKER_TEXT, describeTerm, isMajorSettlement, settlementSummary, type Names } from '../lib/diplomacy'
import { formatGold } from '../lib/gold'
import { Alert } from './Alert'
import { ConferenceCard } from './PeaceConference'
import { PeaceNegotiation, SettlementSummary } from './PeaceNegotiation'
import { Icon } from './icons'
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
 * Live diplomacy: wars and their score, calls to arms, open conferences and proposals, alliances,
 * running tribute, gifts, and every nation's relation (also reachable from the map: a province's
 * owner name opens NationDiplomacy). Ended wars, past conferences, treaties and the event log are in
 * HistoryPanel. Buttons only express intents; the server decides what is allowed (the
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
  const features = rules.features
  const conferencesMode = Boolean(features?.peaceConferences)
  const conferences = overview.conferences ?? []
  const openConferences = conferences.filter((c) => c.status === 'REQUESTED' || c.status === 'ACTIVE')
  const calls = overview.calls ?? []
  const paused = Boolean(state.pausedConferenceId)
  const allies = overview.nations.filter((n) => n.status === 'ALLIED').map((n) => n.nationId)
  const pending = overview.proposals.filter((p) => p.status === 'PENDING')
  const activeTributes = overview.tributes.filter((t) => t.status === 'ACTIVE')

  return (
    <section className="card diplomacy" aria-labelledby="diplomacy-title">
      <h2 id="diplomacy-title">Diplomacy</h2>
      {message && <p className="muted" role="status">{message}</p>}

      <p className="muted diplomacy__tip">
        Tip: click a province, then its owner&apos;s name, to deal with that nation from the map. Ended wars, past
        conferences and treaties are in the History window.
      </p>
      <details className="diplomacy__all-nations">
        <summary>All nations ({overview.nations.filter((n) => !n.you).length})</summary>
        <ul className="diplomacy__nations">
          {overview.nations
            .filter((n) => !n.you)
            .map((n) => (
              <NationRow
                key={n.nationId}
                gameId={gameId}
                relation={n}
                overview={overview}
                names={names}
                yourTurn={overview.yourTurn && !finished}
                onMessage={setMessage}
              />
            ))}
        </ul>
      </details>

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

      {calls.some((c) => c.status === 'PENDING') && (
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

      <Alliances gameId={gameId} alliances={overview.alliances} names={names} yourTurn={overview.yourTurn && !finished} onMessage={setMessage} />

      {activeTributes.length > 0 && (
        <>
          <h3>Tribute</h3>
          <ul className="battle-list">
            {activeTributes.map((t) => (
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

    </section>
  )
}

function NationRow({
  gameId,
  relation: n,
  overview,
  names,
  yourTurn,
  onMessage,
}: {
  gameId: string
  relation: NationRelation
  overview: DiplomacyOverview
  names: Names
  yourTurn: boolean
  onMessage: (text: string) => void
}) {
  return (
    <li className="diplomacy__nation">
      <span className="nation-name">
        <span className="swatch" style={{ background: n.color }} aria-hidden="true" />
        {n.name}
      </span>{' '}
      <RelationTags relation={n} />
      <span className="muted">
        {' '}
        · your battalions {n.weMayEnter ? 'may' : 'may not'} enter its territory
      </span>
      <NationActions gameId={gameId} relation={n} overview={overview} names={names} yourTurn={yourTurn} onMessage={onMessage} />
    </li>
  )
}

/** Status, alliance and truce of the viewer's relation with one nation. */
export function RelationTags({ relation: n }: { relation: NationRelation }) {
  return (
    <>
      <span className={`tag ${n.status === 'WAR' ? 'tag--warning' : ''}`}>{STATUS_LABELS[n.status] ?? n.status}</span>
      {n.allianceKind && <span className="tag">{n.allianceKind === 'NORMAL' ? 'Normal alliance' : 'Defensive pact'}</span>}
      {n.truceUntilRound !== null && <span className="tag">Truce until round {n.truceUntilRound}</span>}
    </>
  )
}

const ALLIANCE_KINDS: { kind: AllianceKind; label: string; hint: string }[] = [
  { kind: 'NORMAL', label: 'Normal alliance', hint: 'Normal alliance: mutual military access; allies may be called to arms and invited to offensive wars' },
  { kind: 'DEFENSIVE', label: 'Defensive pact', hint: 'Defensive pact: the partner may be called to defend you; no military access' },
]

/**
 * What the viewer can do with one nation on their turn: declare war (optionally inviting full
 * allies) and propose an alliance. Every blocker shown is the server's.
 */
export function NationActions({
  gameId,
  relation: n,
  overview,
  names,
  yourTurn,
  onMessage,
}: {
  gameId: string
  relation: NationRelation
  overview: DiplomacyOverview
  names: Names
  yourTurn: boolean
  onMessage: (text: string) => void
}) {
  const [confirmWar, setConfirmWar] = useState(false)
  const [kind, setKind] = useState<AllianceKind>('NORMAL')
  const propose = useProposeAlliance(gameId)
  if (!yourTurn || n.status === 'WAR') return null

  return (
    <div className="diplomacy__actions">
      {n.warBlockers.length === 0 ? (
        confirmWar ? (
          <DeclareWar
            gameId={gameId}
            target={n}
            overview={overview}
            names={names}
            onCancel={() => setConfirmWar(false)}
            onDeclared={(text) => {
              setConfirmWar(false)
              onMessage(text)
            }}
          />
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
          <span className="segmented" role="radiogroup" aria-label={`Alliance type with ${n.name}`}>
            {ALLIANCE_KINDS.map((k) => (
              <button
                key={k.kind}
                type="button"
                role="radio"
                aria-checked={kind === k.kind}
                className={`segmented__option ${kind === k.kind ? 'segmented__option--active' : ''}`}
                title={k.hint}
                onClick={() => setKind(k.kind)}
              >
                <Icon name={k.kind === 'NORMAL' ? 'diplomacy' : 'fortress'} size={14} /> {k.label}
              </button>
            ))}
          </span>
          <button
            type="button"
            className="button"
            onClick={() => {
              propose.mutate({ nationId: n.nationId, kind, idempotencyKey: crypto.randomUUID() })
              onMessage(`Alliance proposed to ${n.name}. It answers on its turn.`)
            }}
          >
            Propose alliance
          </button>
        </span>
      ) : (
        n.status !== 'ALLIED' && <span className="muted">Alliance: {n.allianceBlockers.map((b) => BLOCKER_TEXT[b] ?? b).join(' ')}</span>
      )}
      {propose.error && <Alert>{describeError(propose.error)}</Alert>}
    </div>
  )
}

/**
 * The declaration with its confirmation. From ruleset v8 the declarer may invite any of its normal
 * (full) allies to join the attack — never defensive-pact partners — or nobody; each invited ally
 * answers on its own turn.
 */
function DeclareWar({
  gameId,
  target,
  overview,
  names,
  onCancel,
  onDeclared,
}: {
  gameId: string
  target: NationRelation
  overview: DiplomacyOverview
  names: Names
  onCancel: () => void
  onDeclared: (text: string) => void
}) {
  const declare = useDeclareWar(gameId)
  const [invited, setInvited] = useState<string[]>([])
  const offensive = Boolean(overview.rules?.features?.offensiveCallsToArms)
  const eligible = offensive
    ? overview.nations.filter((n) => !n.you && n.nationId !== target.nationId && n.status === 'ALLIED' && n.allianceKind === 'NORMAL')
    : []
  // Names from the diplomacy view itself (covers every nation of the map).
  const nationName = (id: string) => overview.nations.find((n) => n.nationId === id)?.name ?? names.nation(id)
  const toggle = (id: string) => setInvited((current) => (current.includes(id) ? current.filter((x) => x !== id) : [...current, id]))
  return (
    <div className="declare-war" role="group" aria-label={`Declare war on ${target.name}`}>
      <span>Declare war on {target.name}? Its allies may join the defence.</span>
      {offensive && (
        <fieldset className="declare-war__allies">
          <legend>Invite allies to join the attack</legend>
          {eligible.length === 0 ? (
            <p className="muted">You have no normal alliance to call on (defensive pacts only cover defence).</p>
          ) : (
            <>
              {eligible.map((n) => (
                <label key={n.nationId} className="checkbox-field">
                  <input type="checkbox" checked={invited.includes(n.nationId)} onChange={() => toggle(n.nationId)} />
                  <span className="swatch" style={{ background: n.color }} aria-hidden="true" />
                  {n.name}
                </label>
              ))}
              <p className="muted" aria-live="polite">
                {invited.length === 0
                  ? 'Nobody invited: you attack alone (allies of the target may still defend it).'
                  : `Invited: ${invited.map(nationName).join(', ')}. Each decides on its own turn; declining does not end your alliance.`}
              </p>
            </>
          )}
        </fieldset>
      )}
      <button
        type="button"
        className="button button--danger"
        onClick={() => {
          // The invited allies are the player's own choice, so the message names them at once.
          const declared =
            `War declared on ${target.name}.` + (invited.length > 0 ? ` Invited to join you: ${invited.map(nationName).join(', ')}.` : '')
          declare.mutate(
            { targetNationId: target.nationId, idempotencyKey: crypto.randomUUID(), invite: invited },
            {
              // Who joined the defence is the server's answer: the message grows when it arrives.
              onSuccess: (r) => {
                if (r.joinedDefenders.length > 0) {
                  onDeclared(`${declared} ${r.joinedDefenders.length} ally(ies) joined the defence.`)
                }
              },
            },
          )
          onDeclared(declared)
        }}
      >
        Confirm declaration
      </button>
      <button type="button" className="button" onClick={onCancel}>
        Keep the peace
      </button>
      {declare.error && <Alert>{describeError(declare.error)}</Alert>}
    </div>
  )
}

export function WarCard({
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
      <WarScore war={war} names={names} />
      <ScoreTable war={war} names={names} />
      <p className="war__note muted">
        {net === 0 ? 'The war is even.' : `The score favours ${names.nation(net > 0 ? war.attackerLeaderId : war.defenderLeaderId)}.`} It
        guides the negotiation but never forces a peace.
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

/**
 * Tug-of-war bar: the fill grows from the centre towards the side the score favours, scaled to the
 * ruleset's maximum. Green when it favours your side, red when it favours the enemy.
 */
function WarScore({ war, names }: { war: WarView; names: Names }) {
  const max = war.score.maxScore
  const net = war.score.net
  const share = max > 0 ? Math.min(Math.abs(net), max) / max : 0
  const favoured = net === 0 ? null : net > 0 ? 'ATTACKER' : 'DEFENDER'
  const tone = favoured === null || !war.yourSide ? 'neutral' : favoured === war.yourSide ? 'good' : 'bad'
  return (
    <div className="war__score" role="meter" aria-valuemin={-max} aria-valuemax={max} aria-valuenow={net} aria-label="War score">
      <div className="war-meter__labels" aria-hidden="true">
        <span>{names.nation(war.attackerLeaderId)}</span>
        <strong className={`war-meter__value war-meter__value--${tone}`}>{net > 0 ? `+${net}` : net}</strong>
        <span>{names.nation(war.defenderLeaderId)}</span>
      </div>
      <div className="war-meter__track" aria-hidden="true">
        <span
          className={`war-meter__fill war-meter__fill--${tone}`}
          style={net >= 0 ? { right: '50%', width: `${share * 50}%` } : { left: '50%', width: `${share * 50}%` }}
        />
      </div>
    </div>
  )
}

function ScoreTable({ war, names }: { war: WarView; names: Names }) {
  const a: SideScore = war.score.attacker
  const d: SideScore = war.score.defender
  const rows: [string, (s: SideScore) => string][] = [
    ['Battles', (s) => `${s.battles} (${s.battlesWon} won)`],
    ['Sieges', (s) => `${s.sieges} (${s.siegesWon} won)`],
    ['Occupation', (s) => `${s.occupation} · ${s.occupiedEnemyPopulation.toLocaleString('en-US')} of ${s.enemyPopulation.toLocaleString('en-US')} people`],
  ]
  return (
    <table className="war-table">
      <thead>
        <tr>
          <th scope="col">
            <span className="visually-hidden">Score</span>
          </th>
          <th scope="col">{names.nation(war.attackerLeaderId)}</th>
          <th scope="col">{names.nation(war.defenderLeaderId)}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(([label, value]) => (
          <tr key={label}>
            <th scope="row">{label}</th>
            <td>{value(a)}</td>
            <td>{value(d)}</td>
          </tr>
        ))}
        <tr className="war-table__total">
          <th scope="row">Points</th>
          <td>{a.total}</td>
          <td>{d.total}</td>
        </tr>
      </tbody>
    </table>
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
    onMessage('Peace signed.')
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
          <button type="button" className="button button--primary" onClick={handleAccept}>
            {confirming ? 'Confirm: accept these terms' : 'Accept'}
          </button>
          <button
            type="button"
            className="button"
            onClick={() => {
              action.mutate({ proposalId: p.id, action: 'reject', idempotencyKey: crypto.randomUUID() })
              onMessage('Proposal rejected. The war goes on.')
            }}
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
          onClick={() => {
            action.mutate({ proposalId: p.id, action: 'cancel', idempotencyKey: crypto.randomUUID() })
            onMessage('Proposal withdrawn.')
          }}
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
        onSuccess: (r) => {
          if (r.withdrawals.length > 0) onMessage(`${text} ${r.withdrawals.length} battalion(s) withdrew.`)
        },
      },
    )
    onMessage(text)
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
                <button type="button" className="button" onClick={() => run(a, 'accept', 'Alliance formed.')}>
                  Accept
                </button>{' '}
                <button type="button" className="button" onClick={() => run(a, 'reject', 'Alliance rejected.')}>
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
  const youFight = fighting.has(you)
  const kind: ConferenceKind | null = !youFight ? null : war.youLead ? 'GENERAL' : separatePeace ? 'SEPARATE' : null
  const counterpart = war.yourSide === 'ATTACKER' ? war.defenderLeaderId : war.attackerLeaderId

  return (
    <div className="diplomacy__actions">
      {kind && !paused && !alreadyRequested && (
        <button
          type="button"
          className="button button--primary"
          onClick={() => {
            request.mutate(
              { warId: war.id, kind, idempotencyKey: crypto.randomUUID() },
              {
                onSuccess: (r) => {
                  if (r.conference.status === 'ACTIVE') onMessage('The peace conference is open. Normal play is paused.')
                },
              },
            )
            onMessage(`${names.nation(counterpart)} has been asked to a peace conference.`)
          }}
        >
          {kind === 'GENERAL' ? 'Request peace conference' : `Ask ${names.nation(counterpart)} for a separate peace`}
        </button>
      )}
      {callsToArms && war.defenderLeaderId === you && yourTurn && !paused && callable.length > 0 && (
        <span className="diplomacy__alliance" role="group" aria-label="Call allies to arms">
          {callable.map((a) => (
            <button
              key={a}
              type="button"
              className="button button--small"
              title={`Ask ${names.nation(a)} to join the defence; it answers on its turn`}
              onClick={() => {
                call.mutate({ warId: war.id, allyNationId: a, idempotencyKey: crypto.randomUUID() })
                onMessage(`${names.nation(a)} has been called to arms. It answers on its turn.`)
              }}
            >
              Call {names.nation(a)} to arms
            </button>
          ))}
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
  return (
    <>
      <h3>Calls to arms</h3>
      <ul className="battle-list">
        {pending.map((c) => (
          <li key={c.id}>
            {names.nation(c.callerNationId)} {c.side === 'ATTACKER' ? 'invites' : 'calls'}{' '}
            {c.allyNationId === you ? 'you' : names.nation(c.allyNationId)}{' '}
            {c.side === 'ATTACKER' ? 'to join its attack' : 'to arms'} (round {c.createdRound}).
            {c.canAnswer && yourTurn && (
              <span className="diplomacy__actions">
                {declining === c.id ? (
                  <>
                    <span>
                      {c.side === 'ATTACKER'
                        ? `Declining keeps your alliance with ${names.nation(c.callerNationId)}.`
                        : `Declining ends your alliance with ${names.nation(c.callerNationId)}.`}
                    </span>
                    <button
                      type="button"
                      className="button button--danger"
                      onClick={() => {
                        answer.mutate({ callId: c.id, accept: false, idempotencyKey: crypto.randomUUID() })
                        setDeclining(null)
                        onMessage(
                          c.side === 'ATTACKER'
                            ? `You declined the invitation of ${names.nation(c.callerNationId)}. Your alliance stands.`
                            : `You declined. Your alliance with ${names.nation(c.callerNationId)} has ended.`,
                        )
                      }}
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
                      onClick={() => {
                        answer.mutate({ callId: c.id, accept: true, idempotencyKey: crypto.randomUUID() })
                        onMessage(`You joined the war on the side of ${names.nation(c.callerNationId)}.`)
                      }}
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
      </ul>
      {answer.error && <Alert>{describeError(answer.error)}</Alert>}
    </>
  )
}
