import { useState } from 'react'
import { describeError } from '../api/client'
import { useAttach, useMove, useRecruit, useRegroup, useRelease } from '../api/queries'
import type {
  Battalion,
  BattleReport,
  CityRecruitment,
  Eligibility,
  MapProvince,
  MilitaryOverview,
  ProvinceState,
  RecruitBlocker,
  UnitType,
} from '../api/types'
import { relationsOf, type Relations } from '../lib/diplomacy'
import { formatGold } from '../lib/gold'
import { UNIT_LABELS } from '../lib/units'
import { Alert } from './Alert'
import { BattalionManagement } from './BattalionManagement'
import type { NationStyle } from './ProvinceMap'

const ELIGIBILITY_TEXT: Record<Eligibility, string> = {
  READY: 'Ready',
  RECRUITED_THIS_TURN: 'Recruited this turn — defends, cannot move yet',
  ALREADY_MOVED: 'Moved this turn',
  REINFORCED_THIS_TURN: 'Reinforced this turn — defends, cannot move',
}

const RECRUIT_BLOCKER_TEXT: Record<RecruitBlocker, string> = {
  NOT_YOUR_TURN: 'You can recruit on your turn.',
  NOT_OWNER: 'Only the owning nation can recruit here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_POPULATION: 'Not enough civilians left in this city.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

interface MilitaryPanelProps {
  gameId: string
  province: MapProvince
  provinces: Map<string, ProvinceState>
  provinceNames: Map<string, string>
  military: MilitaryOverview
  nations: Map<string, NationStyle>
  yourTurn: boolean
  selected: string[]
  onSelectedChange: (ids: string[]) => void
  onBattle: (battle: BattleReport) => void
  /** The game's state version: battalion details refetch when it changes. */
  stateVersion: number
  /** Who is at war with the caller and where its battalions may go (display only; the server decides). */
  relations?: Relations
}

/**
 * Forces in one province: battalion list with selection, movement to adjacent provinces,
 * grouping into an army, and recruitment in the player's own city. The server re-validates
 * every command; what is shown here only explains what it will accept.
 */
export function MilitaryPanel({
  gameId,
  province,
  provinces,
  provinceNames,
  military,
  nations,
  yourTurn,
  selected,
  onSelectedChange,
  onBattle,
  stateVersion,
  relations = relationsOf(undefined, military.nationId),
}: MilitaryPanelProps) {
  const [disbandMessage, setDisbandMessage] = useState<string | null>(null)
  const here = military.battalions.filter((b) => b.provinceKey === province.key)
  const you = military.nationId
  // Battalions you command: your own unless lent to an ally, and allies' battalions lent to you.
  const mine = here.filter((b) => (b.nationId === you && !b.commanderNationId) || b.commanderNationId === you)
  const lent = here.filter((b) => b.nationId === you && b.commanderNationId && b.commanderNationId !== you)
  const others = here.filter((b) => b.nationId !== you && b.commanderNationId !== you)
  const enemies = others.filter((b) => relations.atWar(b.nationId))
  const neutrals = others.filter((b) => !relations.atWar(b.nationId))
  const armyNames = new Map(military.armies.map((a) => [a.id, a.name]))
  const recruitment = military.recruitment.find((r) => r.provinceKey === province.key)
  const selectedMine = mine.filter((b) => selected.includes(b.id))
  const selectedOwn = selectedMine.filter((b) => b.nationId === you)
  const nationName = (id: string) => nations.get(id)?.name ?? 'an ally'

  function toggle(id: string) {
    onSelectedChange(selected.includes(id) ? selected.filter((s) => s !== id) : [...selected, id])
  }

  return (
    <div className="military">
      <h3>Forces</h3>
      {here.length === 0 && <p className="muted">No battalions in this province.</p>}
      {mine.length > 0 && (
        <BattalionList
          title="Your battalions"
          battalions={mine}
          nation={nations.get(military.nationId)}
          armyNames={armyNames}
          selectable={yourTurn}
          selected={selected}
          onToggle={toggle}
          you={you}
          nationName={nationName}
        />
      )}
      {lent.length > 0 && (
        <LentBattalions gameId={gameId} battalions={lent} yourTurn={yourTurn} nationName={nationName} />
      )}
      {yourTurn && selectedOwn.length > 0 && selectedOwn.length === selectedMine.length && relations.partners.length > 0 && (
        <LendControls
          gameId={gameId}
          battalionIds={selectedOwn.map((b) => b.id)}
          partners={relations.partners}
          nationName={nationName}
          onDone={() => onSelectedChange([])}
        />
      )}
      {neutrals.length > 0 && (
        <BattalionList
          title="Battalions of nations at peace with you"
          battalions={neutrals}
          nation={nations.get(neutrals[0].nationId)}
          armyNames={armyNames}
          selectable={false}
          selected={[]}
          onToggle={toggle}
        />
      )}
      {enemies.length > 0 && (
        <BattalionList
          title="Enemy battalions"
          battalions={enemies}
          nation={nations.get(enemies[0].nationId)}
          armyNames={armyNames}
          selectable={false}
          selected={[]}
          onToggle={toggle}
        />
      )}
      {selectedMine.length === 1 && selectedMine[0].nationId === you && (
        <BattalionManagement
          key={selectedMine[0].id}
          gameId={gameId}
          battalionId={selectedMine[0].id}
          stateVersion={stateVersion}
          onDisbanded={(result) => {
            const homes = result.returns.map((r) => `${r.cityName} +${r.soldiers.toLocaleString('en-US')}`).join(', ')
            setDisbandMessage(`Disbanded: ${result.soldiersReturned.toLocaleString('en-US')} soldiers went home (${homes}).`)
            onSelectedChange([])
          }}
        />
      )}
      {disbandMessage && (
        <p className="move-controls__result" role="status">
          {disbandMessage}
        </p>
      )}
      {mine.length > 0 && (
        <MoveControls
          gameId={gameId}
          province={province}
          provinces={provinces}
          provinceNames={provinceNames}
          military={military}
          yourTurn={yourTurn}
          selected={selectedMine}
          onDone={() => onSelectedChange([])}
          onBattle={onBattle}
          relations={relations}
        />
      )}
      {recruitment && <RecruitControls gameId={gameId} city={recruitment} yourTurn={yourTurn} />}
    </div>
  )
}

function BattalionList({
  title,
  battalions,
  nation,
  armyNames,
  selectable,
  selected,
  onToggle,
  you,
  nationName,
}: {
  title: string
  battalions: Battalion[]
  nation: NationStyle | undefined
  armyNames: Map<string, string>
  selectable: boolean
  selected: string[]
  onToggle: (id: string) => void
  you?: string
  nationName?: (id: string) => string
}) {
  return (
    <div className="battalions">
      <h4>
        <span className="swatch" style={{ background: nation?.color }} aria-hidden="true" />
        {title}
        {nation && <span className="muted"> · {nation.name}</span>}
      </h4>
      <ul className="battalion-list">
        {battalions.map((b) => {
          const label = `${UNIT_LABELS[b.unitType]}, ${b.manpower.toLocaleString('en-US')} of ${b.maxManpower.toLocaleString('en-US')} men`
          return (
            <li key={b.id} className="battalion">
              {b.eligibility !== null && (
                <input
                  type="checkbox"
                  className="battalion__select"
                  aria-label={`Select ${label}`}
                  checked={selected.includes(b.id)}
                  disabled={!selectable}
                  onChange={() => onToggle(b.id)}
                />
              )}
              <span className={`unit-icon unit-icon--${b.unitType.toLowerCase()}`} aria-hidden="true" />
              <span className="battalion__body">
                <span className="battalion__name">{UNIT_LABELS[b.unitType]}</span>
                <span className="battalion__strength">
                  {b.manpower.toLocaleString('en-US')} / {b.maxManpower.toLocaleString('en-US')}
                  <meter min={0} max={b.maxManpower} value={b.manpower} aria-label={`${label}`} />
                </span>
                {b.armyId && armyNames.has(b.armyId) && <span className="tag">{armyNames.get(b.armyId)}</span>}
                {you && b.nationId !== you && nationName && (
                  <span className="tag">Lent by {nationName(b.nationId)} — you command it</span>
                )}
                {b.eligibility && (
                  <span className={`tag ${b.eligibility === 'READY' ? 'tag--active' : ''}`}>
                    {ELIGIBILITY_TEXT[b.eligibility]}
                  </span>
                )}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function MoveControls({
  gameId,
  province,
  provinces,
  provinceNames,
  military,
  yourTurn,
  selected,
  onDone,
  onBattle,
  relations,
}: {
  gameId: string
  province: MapProvince
  provinces: Map<string, ProvinceState>
  provinceNames: Map<string, string>
  military: MilitaryOverview
  yourTurn: boolean
  selected: Battalion[]
  onDone: () => void
  onBattle: (battle: BattleReport) => void
  relations: Relations
}) {
  const move = useMove(gameId)
  const regroup = useRegroup(gameId)
  const [armyName, setArmyName] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const notReady = selected.filter((b) => b.eligibility !== 'READY')
  const reason = !yourTurn
    ? 'You can move on your turn.'
    : selected.length === 0
      ? 'Select battalions to move them.'
      : notReady.length > 0
        ? `${notReady.length} selected ${notReady.length === 1 ? 'battalion cannot' : 'battalions cannot'} move this turn.`
        : null

  function handleMove(toProvinceKey: string) {
    setMessage(null)
    move.mutate(
      { battalionIds: selected.map((b) => b.id), toProvinceKey, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (result) => {
          const name = provinceNames.get(result.toProvinceKey) ?? result.toProvinceKey
          setMessage(
            result.outcome === 'MOVED'
              ? `Moved ${result.battalions.length} ${result.battalions.length === 1 ? 'battalion' : 'battalions'} to ${name}.`
              : result.outcome === 'ATTACK_WON'
                ? `Victory at ${name}.`
                : `The attack on ${name} was repelled.`,
          )
          if (result.battle) onBattle(result.battle)
          onDone()
        },
      },
    )
  }

  function handleRegroup() {
    regroup.mutate(
      { name: armyName.trim(), battalionIds: selected.map((b) => b.id), idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (result) => {
          setMessage(`Formed ${result.army.name}.`)
          setArmyName('')
          onDone()
        },
      },
    )
  }

  return (
    <div className="move-controls">
      <h4>Move selected ({selected.length})</h4>
      {reason && <p className="muted">{reason}</p>}
      <ul className="destinations">
        {province.neighbours.map((n) => {
          const target = provinces.get(n.provinceKey)
          const hostile = military.battalions.filter(
            (b) => b.provinceKey === n.provinceKey && relations.atWar(b.nationId),
          ).length
          const enemyTerritory = target !== undefined && relations.atWar(target.controllerNationId)
          const closed = target !== undefined && !relations.mayEnter(target.controllerNationId) && !enemyTerritory
          const name = provinceNames.get(n.provinceKey) ?? n.provinceKey
          return (
            <li key={n.provinceKey} className={`destination ${hostile > 0 ? 'destination--hostile' : ''}`}>
              <button
                type="button"
                className={`button ${hostile > 0 ? 'button--danger' : ''}`}
                disabled={reason !== null || move.isPending || closed}
                onClick={() => handleMove(n.provinceKey)}
              >
                {hostile > 0 ? `Attack ${name}` : `Move to ${name}`}
              </button>
              <span className="destination__info">
                {hostile > 0 ? (
                  <span className="tag tag--warning">
                    {hostile} enemy {hostile === 1 ? 'battalion' : 'battalions'}
                  </span>
                ) : closed ? (
                  <span className="tag">Closed — not at war, no military access</span>
                ) : enemyTerritory ? (
                  <span className="tag">Enemy territory — occupied at round end if undefended</span>
                ) : (
                  <span className="tag">Friendly</span>
                )}
                {n.crossing === 'RIVER' && <span className="tag">River: attack −20%</span>}
              </span>
            </li>
          )
        })}
      </ul>
      {selected.length > 0 && yourTurn && (
        <form
          className="regroup"
          onSubmit={(event) => {
            event.preventDefault()
            handleRegroup()
          }}
        >
          <label>
            Army name
            <input
              value={armyName}
              maxLength={40}
              onChange={(event) => setArmyName(event.target.value)}
              placeholder="e.g. First Army"
            />
          </label>
          <button type="submit" className="button" disabled={armyName.trim() === '' || regroup.isPending}>
            Group as army
          </button>
        </form>
      )}
      {message && (
        <p className="move-controls__result" role="status">
          {message}
        </p>
      )}
      {move.error && <Alert>{describeError(move.error)}</Alert>}
      {regroup.error && <Alert>{describeError(regroup.error)}</Alert>}
    </div>
  )
}

function RecruitControls({ gameId, city, yourTurn }: { gameId: string; city: CityRecruitment; yourTurn: boolean }) {
  const recruit = useRecruit(gameId)
  const [unitType, setUnitType] = useState<UnitType>('INFANTRY')
  const option = city.options.find((o) => o.unitType === unitType) ?? city.options[0]

  function handleRecruit() {
    recruit.mutate({ cityId: city.cityId, unitType: option.unitType, idempotencyKey: crypto.randomUUID() })
  }

  return (
    <div className="recruit">
      <h4>Recruit in {city.cityName}</h4>
      <fieldset className="recruit__types">
        <legend className="visually-hidden">Unit type</legend>
        {city.options.map((o) => (
          <label key={o.unitType} className="recruit__type">
            <input
              type="radio"
              name={`unit-${city.cityId}`}
              value={o.unitType}
              checked={o.unitType === option.unitType}
              onChange={() => setUnitType(o.unitType)}
            />
            {UNIT_LABELS[o.unitType]}
          </label>
        ))}
      </fieldset>
      <dl className="facts">
        <dt>Cost</dt>
        <dd>{formatGold(option.costMg)} gold</dd>
        <dt>Civilians</dt>
        <dd>
          −{option.populationCost.toLocaleString('en-US')} (city: {city.population.toLocaleString('en-US')} →{' '}
          {Math.max(option.populationAfter, 0).toLocaleString('en-US')})
        </dd>
        <dt>Upkeep</dt>
        <dd>{formatGold(option.upkeepMg)} gold per round</dd>
      </dl>
      <p className="muted">New battalions defend at once but cannot move or attack until your next turn.</p>
      {option.blockers.length > 0 && yourTurn && (
        <ul className="blockers">
          {option.blockers.map((b) => (
            <li key={b}>{RECRUIT_BLOCKER_TEXT[b]}</li>
          ))}
        </ul>
      )}
      <button
        type="button"
        className="button button--primary"
        disabled={!option.allowed || recruit.isPending}
        onClick={handleRecruit}
      >
        {recruit.isPending ? 'Recruiting…' : `Recruit ${UNIT_LABELS[option.unitType]}`}
      </button>
      {!yourTurn && <p className="muted">You can recruit on your turn.</p>}
      {recruit.error && <Alert>{describeError(recruit.error)}</Alert>}
    </div>
  )
}

/** Your battalions an ally commands: it may move and attack with them; you pay their upkeep and may recall them. */
function LentBattalions({
  gameId,
  battalions,
  yourTurn,
  nationName,
}: {
  gameId: string
  battalions: Battalion[]
  yourTurn: boolean
  nationName: (id: string) => string
}) {
  const release = useRelease(gameId)
  return (
    <div className="battalions">
      <h4>Your battalions lent to allies</h4>
      <ul className="battalion-list">
        {battalions.map((b) => (
          <li key={b.id} className="battalion">
            <span className={`unit-icon unit-icon--${b.unitType.toLowerCase()}`} aria-hidden="true" />
            <span className="battalion__body">
              <span className="battalion__name">{UNIT_LABELS[b.unitType]}</span>
              <span className="battalion__strength">{b.manpower.toLocaleString('en-US')} men</span>
              <span className="tag">Commanded by {nationName(b.commanderNationId ?? '')}</span>
              {yourTurn && (
                <button
                  type="button"
                  className="link-button"
                  disabled={release.isPending}
                  onClick={() => release.mutate({ battalionIds: [b.id], idempotencyKey: crypto.randomUUID() })}
                >
                  Recall
                </button>
              )}
            </span>
          </li>
        ))}
      </ul>
      <p className="muted">Recalling does not give a battalion another move this round.</p>
      {release.error && <Alert>{describeError(release.error)}</Alert>}
    </div>
  )
}

function LendControls({
  gameId,
  battalionIds,
  partners,
  nationName,
  onDone,
}: {
  gameId: string
  battalionIds: string[]
  partners: string[]
  nationName: (id: string) => string
  onDone: () => void
}) {
  const attach = useAttach(gameId)
  const [partner, setPartner] = useState('')
  const chosen = partners.includes(partner) ? partner : partners[0]
  return (
    <div className="move-controls">
      <label>
        Lend to
        <select value={chosen} onChange={(e) => setPartner(e.target.value)}>
          {partners.map((p) => (
            <option key={p} value={p}>
              {nationName(p)}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        className="button"
        disabled={attach.isPending}
        onClick={() =>
          attach.mutate({ battalionIds, commanderNationId: chosen, idempotencyKey: crypto.randomUUID() }, { onSuccess: onDone })
        }
      >
        Lend {battalionIds.length} battalion(s)
      </button>
      <p className="muted">The ally may move and attack with them; you keep paying their upkeep and can recall them on your turn.</p>
      {attach.error && <Alert>{describeError(attach.error)}</Alert>}
    </div>
  )
}
