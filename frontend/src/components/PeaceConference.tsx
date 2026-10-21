import { useState } from 'react'
import { describeError } from '../api/client'
import { useAnswerConference, useCancelConference, useDecideDraft, useSubmitDraft } from '../api/queries'
import type { Conference, DiplomacyRules, GameState, PeaceTerm, WarView } from '../api/types'
import { describeTerm, settlementSummary, type Names } from '../lib/diplomacy'
import { Alert } from './Alert'
import { SettlementSummary, TermBuilder } from './PeaceNegotiation'

const STATUS_TEXT: Record<Conference['status'], string> = {
  REQUESTED: 'requested — waiting for an answer',
  ACTIVE: 'in session — the game is paused',
  CONCLUDED: 'concluded with a treaty',
  REJECTED: 'refused',
  CANCELLED: 'ended without a treaty',
}

const DECISION_TEXT = { APPROVED: 'approved', REJECTED: 'rejected' } as const

interface ConferenceCardProps {
  gameId: string
  conference: Conference
  war: WarView | undefined
  yourNationId: string
  state: GameState
  names: Names
  capitalKeys: Set<string>
  rules: DiplomacyRules
  onMessage: (text: string) => void
}

/**
 * One peace conference: who asked whom, the current revision of the draft with the approvals it
 * needs, and the actions the caller may take. Every action is allowed out of turn; the server
 * validates the terms, binds each approval to its revision and settles atomically.
 */
export function ConferenceCard({
  gameId,
  conference: c,
  war,
  yourNationId,
  state,
  names,
  capitalKeys,
  rules,
  onMessage,
}: ConferenceCardProps) {
  const [editing, setEditing] = useState(false)
  const answer = useAnswerConference(gameId)
  const cancel = useCancelConference(gameId)
  const decide = useDecideDraft(gameId)
  const error = answer.error ?? cancel.error ?? decide.error
  const title =
    c.kind === 'GENERAL'
      ? `General peace: ${names.nation(c.requesterNationId)} and ${names.nation(c.counterpartNationId)}`
      : `Separate peace: ${names.nation(c.requesterNationId)} with ${names.nation(c.counterpartNationId)}`
  const terms = c.terms.map((t) => ({ ...t }))

  function decideCurrent(action: 'approve' | 'reject') {
    decide.mutate(
      { conferenceId: c.id, revision: c.currentRevision, action, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (r) =>
          onMessage(
            r.treaty
              ? 'The treaty is settled. Play resumes.'
              : action === 'approve'
                ? `You approved revision ${c.currentRevision}.`
                : `You rejected revision ${c.currentRevision}.`,
          ),
      },
    )
  }

  return (
    <article className="conference" aria-label={title}>
      <h4>{title}</h4>
      <p className="muted">
        Requested in round {c.requestedRound} · {STATUS_TEXT[c.status]}
        {c.kind === 'SEPARATE' ? ` · ${names.nation(c.requesterNationId)} would leave the war; it goes on for the others` : ''}
      </p>

      {c.canAnswer && (
        <div className="diplomacy__actions">
          <span>Accepting opens the conference and pauses the game for everyone until it ends.</span>
          <button
            type="button"
            className="button button--primary"
            disabled={answer.isPending}
            onClick={() =>
              answer.mutate(
                { conferenceId: c.id, accept: true, idempotencyKey: crypto.randomUUID() },
                { onSuccess: () => onMessage('The peace conference is open. Normal play is paused.') },
              )
            }
          >
            Accept conference
          </button>
          <button
            type="button"
            className="button"
            disabled={answer.isPending}
            onClick={() =>
              answer.mutate(
                { conferenceId: c.id, accept: false, idempotencyKey: crypto.randomUUID() },
                { onSuccess: () => onMessage('You refused the conference.') },
              )
            }
          >
            Refuse
          </button>
        </div>
      )}

      {c.status !== 'REQUESTED' && c.currentRevision > 0 && (
        <>
          <h5>
            Revision {c.currentRevision}
            {c.revisions.length > 0 && (
              <span className="muted">
                {' '}
                · proposed by {names.nation(c.revisions[c.revisions.length - 1].authorNationId)}
              </span>
            )}
          </h5>
          {terms.length === 0 ? (
            <p className="muted">White peace: nothing changes hands.</p>
          ) : (
            <ol className="peace__terms">
              {terms.map((t, i) => (
                <li key={i}>{describeTerm(t, names)}</li>
              ))}
            </ol>
          )}
          <SettlementSummary summary={settlementSummary(terms, names)} names={names} you={yourNationId} />
          <h5>Approvals needed</h5>
          <ul className="conference__approvals">
            {c.approvals.map((a) => (
              <li key={a.nationId}>
                {a.name}
                {a.nationId === yourNationId ? ' (you)' : ''}
                {a.npc ? <span className="tag">computer</span> : null}:{' '}
                <strong>{a.decision ? DECISION_TEXT[a.decision] : 'not yet decided'}</strong>
              </li>
            ))}
          </ul>
          <p className="muted">
            Both negotiators and every nation that gives something up must approve this exact revision. Any change creates
            a new revision and earlier approvals no longer count. There is no deadline.
          </p>
          {c.violations.length > 0 && c.status === 'ACTIVE' && (
            <Alert>It could not be settled as it stands: {c.violations.join('; ')}.</Alert>
          )}
        </>
      )}

      <div className="diplomacy__actions">
        {c.youMustDecide && (
          <>
            <button type="button" className="button button--primary" disabled={decide.isPending} onClick={() => decideCurrent('approve')}>
              Approve revision {c.currentRevision}
            </button>
            <button type="button" className="button" disabled={decide.isPending} onClick={() => decideCurrent('reject')}>
              Reject
            </button>
          </>
        )}
        {c.canRevise && !editing && (
          <button type="button" className="button" onClick={() => setEditing(true)}>
            Revise the draft
          </button>
        )}
        {c.canCancel && (
          <button
            type="button"
            className="button button--danger"
            disabled={cancel.isPending}
            onClick={() =>
              cancel.mutate(
                { conferenceId: c.id, idempotencyKey: crypto.randomUUID() },
                { onSuccess: () => onMessage('The conference ended without a treaty. Play resumes where it stopped.') },
              )
            }
          >
            End conference
          </button>
        )}
      </div>
      {editing && war && (
        <DraftEditor
          gameId={gameId}
          conference={c}
          war={war}
          yourNationId={yourNationId}
          state={state}
          names={names}
          capitalKeys={capitalKeys}
          rules={rules}
          onDone={(text) => {
            setEditing(false)
            onMessage(text)
          }}
          onCancel={() => setEditing(false)}
        />
      )}
      {c.revisions.length > 1 && (
        <details>
          <summary>Earlier revisions ({c.revisions.length - 1})</summary>
          <ul className="battle-list">
            {c.revisions.slice(0, -1).map((r) => (
              <li key={r.revision}>
                Revision {r.revision} by {names.nation(r.authorNationId)} · {r.termCount} term(s)
              </li>
            ))}
          </ul>
        </details>
      )}
      {error && <Alert>{describeError(error)}</Alert>}
    </article>
  )
}

/**
 * Builds the next revision from the current one. Any nation still fighting may give or receive in a
 * general peace; in a separate peace only the leaving nation and the other side.
 */
function DraftEditor({
  gameId,
  conference: c,
  war,
  yourNationId,
  state,
  names,
  capitalKeys,
  rules,
  onDone,
  onCancel,
}: {
  gameId: string
  conference: Conference
  war: WarView
  yourNationId: string
  state: GameState
  names: Names
  capitalKeys: Set<string>
  rules: DiplomacyRules
  onDone: (message: string) => void
  onCancel: () => void
}) {
  const [terms, setTerms] = useState<PeaceTerm[]>(() =>
    c.terms.map((t) => ({ ...t, tributeRateBps: null, tributeRounds: null })),
  )
  const submit = useSubmitDraft(gameId)
  const fighting = war.participants.filter((p) => p.leftRound == null)
  const sideOf = new Map(fighting.map((p) => [p.nationId, p.side]))
  const exitingSide = c.kind === 'SEPARATE' ? sideOf.get(c.requesterNationId) : undefined
  const parties = fighting
    .filter((p) => c.kind === 'GENERAL' || p.nationId === c.requesterNationId || p.side !== exitingSide)
    .map((p) => p.nationId)
  const receiversOf = (giver: string) => parties.filter((n) => sideOf.get(n) !== sideOf.get(giver))
  const cedableCapitals = rules.features?.capitalsCedable ? new Set<string>() : capitalKeys

  return (
    <div className="peace" role="group" aria-label="Revise the draft">
      <h5>Next revision (builds on revision {c.currentRevision})</h5>
      {terms.length === 0 ? (
        <p className="muted">No terms: a white peace.</p>
      ) : (
        <ol className="peace__terms">
          {terms.map((t, i) => (
            <li key={i}>
              {describeTerm({ ...t, tributeRateBps: rules.tributeRateBps, tributeRounds: rules.tributeRounds }, names)}{' '}
              <button type="button" className="link-button" onClick={() => setTerms((ts) => ts.filter((_, j) => j !== i))}>
                Remove
              </button>
            </li>
          ))}
        </ol>
      )}
      {terms.length < rules.maxPeaceTerms && parties.length > 1 && (
        <TermBuilder
          you={yourNationId}
          givers={parties}
          receiversOf={receiversOf}
          state={state}
          names={names}
          capitalKeys={cedableCapitals}
          rules={rules}
          hasTribute={terms.some((t) => t.type === 'TRIBUTE')}
          onAdd={(term) => setTerms((ts) => [...ts, term])}
        />
      )}
      <div className="peace__actions">
        <button
          type="button"
          className="button button--primary"
          disabled={submit.isPending}
          onClick={() =>
            submit.mutate(
              { conferenceId: c.id, expectedRevision: c.currentRevision, terms, idempotencyKey: crypto.randomUUID() },
              { onSuccess: (r) => onDone(`Revision ${r.conference.currentRevision} published. Everyone affected must approve it.`) },
            )
          }
        >
          Publish revision
        </button>
        <button type="button" className="button" onClick={onCancel}>
          Discard changes
        </button>
      </div>
      {submit.error && <Alert>{describeError(submit.error)}</Alert>}
    </div>
  )
}
