import { useState } from 'react'
import { useBattles, useDiplomacyEvents } from '../api/queries'
import type { DiplomacyOverview } from '../api/types'
import { describeEvent, describeTerm, type Names } from '../lib/diplomacy'
import { formatGold } from '../lib/gold'
import { Icon, type IconName } from './icons'
import { WarCard } from './DiplomacyPanel'

type Category = 'wars' | 'conferences' | 'treaties' | 'events' | 'battles'

const CATEGORIES: { key: Category; label: string; icon: IconName; hint: string }[] = [
  { key: 'wars', label: 'Past wars', icon: 'flag', hint: 'Wars that have ended' },
  { key: 'conferences', label: 'Peace conferences', icon: 'diplomacy', hint: 'Peace conferences that have ended' },
  { key: 'treaties', label: 'Treaties', icon: 'scroll', hint: 'Peace treaties, earlier proposals, tribute and answered calls to arms' },
  { key: 'events', label: 'Diplomatic events', icon: 'history', hint: 'The public diplomatic record, newest first' },
  { key: 'battles', label: 'Battles', icon: 'military', hint: 'Every battle of the game, newest first' },
]

/**
 * The game's past, out of the way of live play: one category at a time, chosen from a small icon
 * bar; compact entries open for detail. Only what the server stores is shown — ended wars,
 * finished conferences, treaties, earlier proposals, finished tribute, answered calls, the event log
 * (the latest 200 entries) and battle reports.
 */
export function HistoryPanel({
  gameId,
  stateVersion,
  overview,
  names,
  onShowBattle,
}: {
  gameId: string
  stateVersion: number
  overview: DiplomacyOverview | undefined
  names: Names
  onShowBattle: (battleId: string) => void
}) {
  const [category, setCategory] = useState<Category>('wars')
  const diplomacy = Boolean(overview?.available)
  const shown = CATEGORIES.filter((c) => diplomacy || c.key === 'battles')
  const active = shown.some((c) => c.key === category) ? category : 'battles'
  const events = useDiplomacyEvents(gameId, stateVersion, diplomacy && active === 'events')
  const battles = useBattles(gameId, active === 'battles' ? stateVersion : undefined)
  const current = CATEGORIES.find((c) => c.key === active)!

  return (
    <section className="card history" aria-labelledby="history-title">
      <h2 id="history-title">History</h2>
      <div className="icon-tabs" role="tablist" aria-label="History category">
        {shown.map((c) => (
          <button
            key={c.key}
            type="button"
            role="tab"
            id={`history-tab-${c.key}`}
            aria-selected={active === c.key}
            aria-controls="history-body"
            aria-label={c.label}
            title={c.hint}
            className={`icon-tabs__tab ${active === c.key ? 'icon-tabs__tab--active' : ''}`}
            onClick={() => setCategory(c.key)}
          >
            <Icon name={c.icon} size={18} />
          </button>
        ))}
      </div>
      <div id="history-body" role="tabpanel" aria-labelledby={`history-tab-${active}`}>
        <h3>{current.label}</h3>
        {active === 'wars' && overview && <PastWars overview={overview} names={names} />}
        {active === 'conferences' && overview && <PastConferences overview={overview} names={names} />}
        {active === 'treaties' && overview && <Treaties overview={overview} names={names} />}
        {active === 'events' &&
          (events.data ? (
            events.data.length === 0 ? (
              <p className="muted">Nothing has happened yet.</p>
            ) : (
              <ul className="history__list">
                {events.data.map((e) => (
                  <li key={e.id}>
                    <span className="muted">Round {e.round}:</span> {describeEvent(e, names)}
                  </li>
                ))}
              </ul>
            )
          ) : (
            <p className="muted">Loading…</p>
          ))}
        {active === 'battles' &&
          (battles.data ? (
            battles.data.length === 0 ? (
              <p className="muted">No battle has been fought.</p>
            ) : (
              <ul className="history__list">
                {battles.data.map((b) => (
                  <li key={b.id}>
                    <span className="muted">Round {b.round}:</span> {names.nation(b.winner === 'ATTACKER' ? b.attackerNationId : b.defenderNationId)}{' '}
                    won at {names.province(b.provinceKey)}{' '}
                    <span className="muted">
                      ({names.nation(b.attackerNationId)} attacked {names.nation(b.defenderNationId)}; losses{' '}
                      {b.attackerLosses.toLocaleString('en-US')} / {b.defenderLosses.toLocaleString('en-US')})
                    </span>{' '}
                    <button type="button" className="link-button" onClick={() => onShowBattle(b.id)}>
                      Report
                    </button>
                  </li>
                ))}
              </ul>
            )
          ) : (
            <p className="muted">Loading…</p>
          ))}
      </div>
    </section>
  )
}

function PastWars({ overview, names }: { overview: DiplomacyOverview; names: Names }) {
  const ended = overview.wars.filter((w) => w.status === 'ENDED')
  if (ended.length === 0) return <p className="muted">No war has ended yet.</p>
  return (
    <ul className="history__list">
      {ended.map((w) => (
        <li key={w.id}>
          <details>
            <summary>
              {names.nation(w.attackerLeaderId)} against {names.nation(w.defenderLeaderId)}{' '}
              <span className="muted">
                · rounds {w.declaredRound}–{w.endedRound}
              </span>
            </summary>
            <WarCard war={w} names={names} canNegotiate={false} onNegotiate={() => undefined} />
          </details>
        </li>
      ))}
    </ul>
  )
}

function PastConferences({ overview, names }: { overview: DiplomacyOverview; names: Names }) {
  const past = (overview.conferences ?? []).filter((c) => c.status !== 'REQUESTED' && c.status !== 'ACTIVE')
  if (past.length === 0) return <p className="muted">No peace conference has ended yet.</p>
  return (
    <ul className="history__list">
      {past.map((c) => (
        <li key={c.id}>
          <details>
            <summary>
              {c.kind === 'GENERAL' ? 'General peace' : 'Separate peace'}: {names.nation(c.requesterNationId)} and{' '}
              {names.nation(c.counterpartNationId)} · <strong>{c.status.toLowerCase()}</strong>
            </summary>
            <p className="muted">
              {c.currentRevision > 0 ? `${c.currentRevision} draft revision(s).` : 'No draft was submitted.'}
            </p>
          </details>
        </li>
      ))}
    </ul>
  )
}

function Treaties({ overview, names }: { overview: DiplomacyOverview; names: Names }) {
  const proposals = overview.proposals.filter((p) => p.status !== 'PENDING')
  const tributes = overview.tributes.filter((t) => t.status !== 'ACTIVE')
  const calls = (overview.calls ?? []).filter((c) => c.status !== 'PENDING')
  if (overview.treaties.length + proposals.length + tributes.length + calls.length === 0) {
    return <p className="muted">No treaty has been signed yet.</p>
  }
  return (
    <ul className="history__list">
      {overview.treaties.map((t) => (
        <li key={t.id}>
          <details>
            <summary>
              Peace treaty, round {t.signedRound} <span className="muted">· truce until round {t.truceUntilRound}</span>
              {t.terms.length === 0 ? ' · white peace' : ` · ${t.terms.length} term(s)`}
            </summary>
            <ul>
              {t.terms.map((term, i) => (
                <li key={i}>{describeTerm(term, names)}</li>
              ))}
            </ul>
          </details>
        </li>
      ))}
      {proposals.map((p) => (
        <li key={p.id}>
          <details>
            <summary>
              Proposal, round {p.createdRound}:{' '}
              {p.incoming ? `from ${names.nation(p.proposerNationId)}` : `to ${names.nation(p.recipientNationId)}`} ·{' '}
              <strong>{p.status.toLowerCase()}</strong>
            </summary>
            {p.terms.length === 0 ? (
              <p className="muted">White peace.</p>
            ) : (
              <ul>
                {p.terms.map((term, i) => (
                  <li key={i}>{describeTerm(term, names)}</li>
                ))}
              </ul>
            )}
          </details>
        </li>
      ))}
      {tributes.map((t) => (
        <li key={t.id}>
          Tribute {names.nation(t.payerNationId)} → {names.nation(t.receiverNationId)} · {t.roundsCharged}/{t.roundsTotal} rounds ·{' '}
          {formatGold(t.totalPaidMg)} gold · <strong>{t.status.toLowerCase()}</strong>
          {t.endReason === 'WAR_RENEWED' ? ' (war renewed)' : ''}
        </li>
      ))}
      {calls.map((c) => (
        <li key={c.id}>
          Call to arms, round {c.createdRound}: {names.nation(c.callerNationId)} called {names.nation(c.allyNationId)} ·{' '}
          <strong>{c.status.toLowerCase()}</strong>
        </li>
      ))}
    </ul>
  )
}
