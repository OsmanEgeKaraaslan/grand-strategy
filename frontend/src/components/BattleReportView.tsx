import type { BattleParticipant, BattleReport, UnitType } from '../api/types'
import { UNIT_LABELS } from '../lib/units'
import type { NationStyle } from './ProvinceMap'

const FATE_TEXT = { SURVIVED: 'Survived', RETREATED: 'Retreated', DESTROYED: 'Destroyed' } as const

/** A stored battle report: conditions, phase-by-phase numbers and the outcome, as the server computed them. */
export function BattleReportView({
  battle,
  nations,
  provinceNames,
  onClose,
}: {
  battle: BattleReport
  nations: Map<string, NationStyle>
  provinceNames: Map<string, string>
  onClose?: () => void
}) {
  const { details } = battle
  const attacker = nations.get(battle.attackerNationId)
  const defender = nations.get(battle.defenderNationId)
  const name = (key: string | null) => (key ? (provinceNames.get(key) ?? key) : '')
  const winner = battle.winner === 'ATTACKER' ? attacker : defender
  const pct = (lost: number, initial: number) => `${((lost * 100) / initial).toFixed(1)}%`

  return (
    <section className="card battle-report" aria-labelledby={`battle-${battle.id}`}>
      <div className="battle-report__header">
        <h2 id={`battle-${battle.id}`}>Battle of {name(battle.provinceKey)}</h2>
        {onClose && (
          <button type="button" className="link-button" onClick={onClose}>
            Close
          </button>
        )}
      </div>
      <p className="battle-report__result">
        <strong>{winner?.name ?? 'Unknown'} won.</strong>{' '}
        {details.attackerBroke && details.defenderBroke
          ? 'Both armies broke; the one that lost the smaller share won. '
          : details.broken && `${details.broken === 'ATTACKER' ? attacker?.name : defender?.name} broke. `}
        Round {battle.round}, attack from {name(details.fromProvinceKey)}.
      </p>
      <p>
        {details.river && <span className="tag">River crossing ×0.8</span>}
        {details.mountains && <span className="tag">Mountains ×0.65</span>}
        {!details.river && !details.mountains && <span className="tag">Open plains</span>}
      </p>
      <table className="battle-table">
        <thead>
          <tr>
            <th scope="col">Side</th>
            <th scope="col">Soldiers</th>
            <th scope="col">Losses</th>
            <th scope="col">Remaining</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">Attacker · {attacker?.name}</th>
            <td>{details.attackerInitial.toLocaleString('en-US')}</td>
            <td>
              {details.attackerLosses.toLocaleString('en-US')} ({pct(details.attackerLosses, details.attackerInitial)})
            </td>
            <td>{(details.attackerInitial - details.attackerLosses).toLocaleString('en-US')}</td>
          </tr>
          <tr>
            <th scope="row">Defender · {defender?.name}</th>
            <td>{details.defenderInitial.toLocaleString('en-US')}</td>
            <td>
              {details.defenderLosses.toLocaleString('en-US')} ({pct(details.defenderLosses, details.defenderInitial)})
            </td>
            <td>{(details.defenderInitial - details.defenderLosses).toLocaleString('en-US')}</td>
          </tr>
        </tbody>
      </table>
      <table className="battle-table" aria-label="By unit type">
        <thead>
          <tr>
            <th scope="col">Unit type</th>
            <th scope="col">Attacker start → end</th>
            <th scope="col">Defender start → end</th>
          </tr>
        </thead>
        <tbody>
          {UNIT_TYPES.filter((type) => byType(details.attackers, type).start + byType(details.defenders, type).start > 0).map(
            (type) => (
              <tr key={type}>
                <th scope="row">{UNIT_LABELS[type]}</th>
                <td>{describeType(byType(details.attackers, type))}</td>
                <td>{describeType(byType(details.defenders, type))}</td>
              </tr>
            ),
          )}
        </tbody>
      </table>
      {effects(battle, attacker?.name ?? 'Attacker', defender?.name ?? 'Defender').length > 0 && (
        <ul className="battle-effects" aria-label="What decided the battle">
          {effects(battle, attacker?.name ?? 'Attacker', defender?.name ?? 'Defender').map((text) => (
            <li key={text}>{text}</li>
          ))}
        </ul>
      )}
      <p className="battle-report__aftermath">
        {battle.winner === 'DEFENDER'
          ? 'The attackers fell back to their starting province.'
          : details.defendersDestroyed
            ? 'With no friendly province to fall back to, the defenders were destroyed.'
            : details.retreatProvinceKey
              ? `The defenders retreated to ${name(details.retreatProvinceKey)}.`
              : 'The defenders were wiped out.'}
      </p>
      <details>
        <summary>Phase by phase</summary>
        <table className="battle-table">
          <thead>
            <tr>
              <th scope="col">Phase</th>
              <th scope="col">Attacker damage</th>
              <th scope="col">Defender damage</th>
              <th scope="col">Attacker lost</th>
              <th scope="col">Defender lost</th>
            </tr>
          </thead>
          <tbody>
            {details.phases.map((phase) => (
              <tr key={phase.number}>
                <th scope="row">{phase.number}</th>
                <td>{phase.attacker.damage}</td>
                <td>{phase.defender.damage}</td>
                <td>{sum(phase.attacker.losses)}</td>
                <td>{sum(phase.defender.losses)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <Participants title="Attacking battalions" participants={details.attackers} />
        <Participants title="Defending battalions" participants={details.defenders} />
      </details>
    </section>
  )
}

function Participants({ title, participants }: { title: string; participants: BattleParticipant[] }) {
  return (
    <>
      <h4>{title}</h4>
      <ul className="battle-participants">
        {participants.map((p) => (
          <li key={p.battalionId}>
            {UNIT_LABELS[p.unitType]}: {p.manpowerBefore.toLocaleString('en-US')} → {p.manpowerAfter.toLocaleString('en-US')}{' '}
            <span className="muted">({FATE_TEXT[p.fate]})</span>
          </li>
        ))}
      </ul>
    </>
  )
}

const UNIT_TYPES: UnitType[] = ['INFANTRY', 'CAVALRY', 'ARTILLERY']

function byType(participants: BattleParticipant[], type: UnitType) {
  const ofType = participants.filter((p) => p.unitType === type)
  return {
    start: ofType.reduce((n, p) => n + p.manpowerBefore, 0),
    end: ofType.reduce((n, p) => n + p.manpowerAfter, 0),
  }
}

function describeType({ start, end }: { start: number; end: number }): string {
  if (start === 0) return '—'
  return `${start.toLocaleString('en-US')} → ${end.toLocaleString('en-US')} (−${(start - end).toLocaleString('en-US')})`
}

/** Plain-language notes on the modifiers recorded in the report's phases. */
function effects(battle: BattleReport, attackerName: string, defenderName: string): string[] {
  const { details } = battle
  const notes: string[] = []
  const first = details.phases[0]
  const last = details.phases[details.phases.length - 1]
  if (!first || !last) return notes
  if (details.river) notes.push(`${attackerName} attacked across a river: its damage ×0.8.`)
  if (details.mountains) notes.push(`${attackerName} attacked into mountains: its damage ×0.65; cavalry fought at 60% and could not flank.`)
  for (const [side, name] of [
    ['attacker', attackerName],
    ['defender', defenderName],
  ] as const) {
    const flank = Math.max(...details.phases.map((p) => p[side].flankBonus))
    if (flank > 0) notes.push(`${name}'s surplus cavalry flanked the enemy: up to +${(flank * 100).toFixed(1)}% damage.`)
    const p1 = first[side].protection
    const pn = last[side].protection
    if (Math.min(p1, pn) < 1) {
      notes.push(
        p1 === 1 && pn < 1
          ? `${name}'s infantry screen thinned during the battle: artillery protection fell to ${(pn * 100).toFixed(0)}%.`
          : `${name}'s artillery lacked an infantry screen (protection ${(p1 * 100).toFixed(0)}% → ${(pn * 100).toFixed(0)}%): fewer guns fired and they took heavy losses.`,
      )
    }
  }
  return notes
}

function sum(losses: Partial<Record<string, number>>): string {
  return Object.values(losses)
    .reduce<number>((total, men) => total + (men ?? 0), 0)
    .toLocaleString('en-US')
}
