import { useState } from 'react'
import { describeError } from '../api/client'
import { useAttach, useRegroup, useRelease } from '../api/queries'
import type { Battalion, MapProvince, MilitaryOverview, ProvinceState, UnitType } from '../api/types'
import { ELIGIBILITY_TEXT } from '../lib/blockers'
import type { Relations } from '../lib/diplomacy'
import { UNIT_LABELS } from '../lib/units'
import { Alert } from './Alert'
import { BattalionManagement } from './BattalionManagement'
import { FloatingWindow } from './Floating'
import { Icon, type IconName } from './icons'
import type { NationStyle, TargetKind } from './ProvinceMap'

const UNIT_ICONS: Record<UnitType, IconName> = { INFANTRY: 'infantry', CAVALRY: 'cavalry', ARTILLERY: 'artillery' }
const UNIT_ORDER: UnitType[] = ['INFANTRY', 'CAVALRY', 'ARTILLERY']

type Tool = 'reinforce' | 'disband' | 'group' | 'lend' | null

interface ArmyInspectorProps {
  gameId: string
  province: MapProvince
  provinces: Map<string, ProvinceState>
  provinceNames: Map<string, string>
  military: MilitaryOverview
  nations: Map<string, NationStyle>
  yourTurn: boolean
  relations: Relations
  selected: string[]
  onSelectedChange: (ids: string[]) => void
  /** Where the selection may go (the map's highlights) and why nothing can be ordered, if so. */
  targets: Map<string, TargetKind>
  reason: string | null
  /** Orders the selection into a neighbour: the same path as a right-click on the map. */
  onOrder: (provinceKey: string) => void
  stateVersion: number
  onClose: () => void
}

/**
 * The floating army window: who stands in a province, the composition of the player's stack, icon
 * commands (reinforce, disband, group, lend, recall) and a scrollable battalion list whose
 * checkboxes are the selection the map's right-click orders. Allied battalions are commanded only
 * when attached to the player; the server re-validates every command.
 */
export function ArmyInspector({
  gameId,
  province,
  provinces,
  provinceNames,
  military,
  nations,
  yourTurn,
  relations,
  selected,
  onSelectedChange,
  targets,
  reason,
  onOrder,
  stateVersion,
  onClose,
}: ArmyInspectorProps) {
  const [tool, setTool] = useState<Tool>(null)
  const [message, setMessage] = useState<string | null>(null)
  const you = military.nationId
  const here = military.battalions.filter((b) => b.provinceKey === province.key)
  // Battalions you command: your own unless lent to an ally, and allies' battalions lent to you.
  const mine = here.filter((b) => (b.nationId === you && !b.commanderNationId) || b.commanderNationId === you)
  const lent = here.filter((b) => b.nationId === you && b.commanderNationId && b.commanderNationId !== you)
  const others = here.filter((b) => b.nationId !== you && b.commanderNationId !== you)
  const enemies = others.filter((b) => relations.atWar(b.nationId))
  const friends = others.filter((b) => !relations.atWar(b.nationId))
  const armyNames = new Map(military.armies.map((a) => [a.id, a.name]))
  const selectedMine = mine.filter((b) => selected.includes(b.id))
  const selectedOwn = selectedMine.filter((b) => b.nationId === you)
  const single = selectedMine.length === 1 && selectedMine[0].nationId === you ? selectedMine[0] : null
  const ready = mine.filter((b) => b.eligibility === 'READY')
  const soldiers = mine.reduce((sum, b) => sum + b.manpower, 0)
  const nation = nations.get(you)
  const nationName = (id: string) => nations.get(id)?.name ?? 'an ally'
  const canLend = yourTurn && selectedOwn.length > 0 && selectedOwn.length === selectedMine.length && relations.partners.length > 0

  function toggle(id: string) {
    onSelectedChange(selected.includes(id) ? selected.filter((s) => s !== id) : [...selected, id])
  }

  function pickTool(next: Tool) {
    setMessage(null)
    setTool((current) => (current === next ? null : next))
  }

  const title = mine.length > 0 ? `Army in ${province.name}` : `Forces in ${province.name}`
  return (
    <FloatingWindow
      title={title}
      placement="right"
      className="inspector army-inspector"
      onClose={onClose}
      closeLabel="Close army"
      marker={<span className="window-swatch" style={{ background: mine.length > 0 ? nation?.color : undefined }} aria-hidden="true" />}
      subtitle={
        mine.length > 0 ? (
          <>
            <strong>{soldiers.toLocaleString('en-US')} men</strong> · {mine.length} {mine.length === 1 ? 'battalion' : 'battalions'} ·{' '}
            {!yourTurn ? 'orders on your turn' : ready.length > 0 ? `${ready.length} ready to move` : 'none can move this round'}
          </>
        ) : (
          <span className="muted">None of your battalions stand here.</span>
        )
      }
    >
      {mine.length > 0 && (
        <>
          <ul className="composition" aria-label="Composition">
            {UNIT_ORDER.map((type) => {
              const count = mine.filter((b) => b.unitType === type).length
              return (
                <li key={type} className={count === 0 ? 'composition--none' : undefined} title={UNIT_LABELS[type]}>
                  <Icon name={UNIT_ICONS[type]} size={18} />
                  <span>
                    {count} <span className="visually-hidden">{UNIT_LABELS[type]}</span>
                  </span>
                </li>
              )
            })}
          </ul>

          <div className="army-tools" role="toolbar" aria-label="Army commands">
            <ToolButton
              icon="select"
              label="Select all ready battalions"
              disabled={!yourTurn || ready.length === 0}
              hint={!yourTurn ? 'Orders can be given on your turn.' : ready.length === 0 ? 'No battalion here can move this round.' : undefined}
              onClick={() => onSelectedChange(ready.map((b) => b.id))}
            />
            <ToolButton
              icon="reinforce"
              label="Reinforce"
              pressed={tool === 'reinforce'}
              disabled={!single}
              hint={single ? undefined : 'Select exactly one of your own battalions.'}
              onClick={() => pickTool('reinforce')}
            />
            <ToolButton
              icon="group"
              label="Group as army"
              pressed={tool === 'group'}
              disabled={!yourTurn || selectedMine.length === 0}
              hint={!yourTurn ? 'Orders can be given on your turn.' : selectedMine.length === 0 ? 'Select the battalions to group.' : undefined}
              onClick={() => pickTool('group')}
            />
            {relations.partners.length > 0 && (
              <ToolButton
                icon="attach"
                label="Lend to an ally"
                pressed={tool === 'lend'}
                disabled={!canLend}
                hint={canLend ? undefined : 'Select only your own battalions, on your turn.'}
                onClick={() => pickTool('lend')}
              />
            )}
            <ToolButton
              icon="disband"
              label="Disband"
              tone="danger"
              pressed={tool === 'disband'}
              disabled={!single}
              hint={single ? undefined : 'Select exactly one of your own battalions.'}
              onClick={() => pickTool('disband')}
            />
          </div>

          {tool === 'reinforce' && single && (
            <BattalionManagement key={`r-${single.id}`} gameId={gameId} battalionId={single.id} stateVersion={stateVersion} mode="reinforce" onDisbanded={() => undefined} />
          )}
          {tool === 'disband' && single && (
            <BattalionManagement
              key={`d-${single.id}`}
              gameId={gameId}
              battalionId={single.id}
              stateVersion={stateVersion}
              mode="disband"
              onDisbanded={(result) => {
                const homes = result.returns.map((r) => `${r.cityName} +${r.soldiers.toLocaleString('en-US')}`).join(', ')
                setMessage(`Disbanded: ${result.soldiersReturned.toLocaleString('en-US')} soldiers went home (${homes}).`)
                setTool(null)
                onSelectedChange([])
              }}
            />
          )}
          {tool === 'group' && selectedMine.length > 0 && (
            <GroupForm
              gameId={gameId}
              battalionIds={selectedMine.map((b) => b.id)}
              onDone={(name) => {
                setMessage(`Formed ${name}.`)
                setTool(null)
                onSelectedChange([])
              }}
            />
          )}
          {tool === 'lend' && canLend && (
            <LendForm
              gameId={gameId}
              battalionIds={selectedOwn.map((b) => b.id)}
              partners={relations.partners}
              nationName={nationName}
              onDone={() => {
                setTool(null)
                onSelectedChange([])
              }}
            />
          )}
          {message && (
            <p className="action-result" role="status">
              {message}
            </p>
          )}
        </>
      )}

      <div className="battalion-scroll" role="group" aria-label="Battalions">
        {mine.length > 0 && (
          <BattalionGroup
            title="Your battalions"
            battalions={mine}
            nation={nation}
            armyNames={armyNames}
            selectable={yourTurn}
            selected={selected}
            onToggle={toggle}
            you={you}
            nationName={nationName}
          />
        )}
        {lent.length > 0 && <LentBattalions gameId={gameId} battalions={lent} yourTurn={yourTurn} nationName={nationName} />}
        {friends.length > 0 && (
          <BattalionGroup
            title={friends.every((b) => relations.standing(b.nationId) === 'ALLY') ? 'Allied battalions' : 'Battalions of nations at peace with you'}
            battalions={friends}
            nation={nations.get(friends[0].nationId)}
            armyNames={armyNames}
            selectable={false}
            selected={[]}
            onToggle={toggle}
          />
        )}
        {enemies.length > 0 && (
          <BattalionGroup
            title="Enemy battalions"
            battalions={enemies}
            nation={nations.get(enemies[0].nationId)}
            armyNames={armyNames}
            selectable={false}
            selected={[]}
            onToggle={toggle}
          />
        )}
        {here.length === 0 && <p className="muted">No battalions in this province.</p>}
      </div>

      {mine.length > 0 && (
        <Orders
          province={province}
          provinces={provinces}
          provinceNames={provinceNames}
          military={military}
          relations={relations}
          selectedCount={selectedMine.length}
          yourTurn={yourTurn}
          targets={targets}
          reason={reason}
          onOrder={onOrder}
        />
      )}
    </FloatingWindow>
  )
}

function ToolButton({
  icon,
  label,
  hint,
  disabled,
  pressed,
  tone,
  onClick,
}: {
  icon: IconName
  label: string
  hint?: string
  disabled?: boolean
  pressed?: boolean
  tone?: 'danger'
  onClick: () => void
}) {
  return (
    <button
      type="button"
      className={`icon-button icon-button--tool ${tone ? `icon-button--${tone}` : ''}`}
      aria-label={label}
      aria-pressed={pressed}
      title={hint ? `${label} — ${hint}` : label}
      disabled={disabled}
      onClick={onClick}
    >
      <Icon name={icon} size={18} />
    </button>
  )
}

/** One nation's (or side's) battalions as compact rows: icon, type, strength bar, status, selection. */
function BattalionGroup({
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
      <h4 className="battalions__title">
        <span className="swatch" style={{ background: nation?.color }} aria-hidden="true" />
        {title}
        {nation && <span className="muted"> · {nation.name}</span>}
      </h4>
      <ul className="battalion-list">
        {battalions.map((b, index) => {
          const label = `${UNIT_LABELS[b.unitType]}, ${b.manpower.toLocaleString('en-US')} of ${b.maxManpower.toLocaleString('en-US')} men`
          const percent = b.maxManpower > 0 ? Math.round((b.manpower * 100) / b.maxManpower) : 0
          return (
            <li key={b.id} className={`battalion-row ${selected.includes(b.id) ? 'battalion-row--selected' : ''}`}>
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
              <Icon name={UNIT_ICONS[b.unitType]} size={18} className="battalion-row__icon" />
              <span className="battalion-row__name">
                {UNIT_LABELS[b.unitType]}
                <span className="muted battalion-row__id"> #{index + 1}</span>
              </span>
              <span className="battalion-row__strength">
                <span>
                  {b.manpower.toLocaleString('en-US')} / {b.maxManpower.toLocaleString('en-US')}
                </span>
                <meter min={0} max={b.maxManpower} value={b.manpower} low={b.maxManpower * 0.5} aria-label={label} title={`${percent}%`} />
              </span>
              <span className="battalion-row__tags">
                {b.armyId && armyNames.has(b.armyId) && <span className="tag">{armyNames.get(b.armyId)}</span>}
                {you && b.nationId !== you && nationName && <span className="tag">Lent by {nationName(b.nationId)} — you command it</span>}
                {b.eligibility && <span className={`tag ${b.eligibility === 'READY' ? 'tag--active' : ''}`} title={ELIGIBILITY_TEXT[b.eligibility]}>{ELIGIBILITY_TEXT[b.eligibility]}</span>}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/** Neighbours as compact order buttons: the keyboard path to what a right-click does on the map. */
function Orders({
  province,
  provinces,
  provinceNames,
  military,
  relations,
  selectedCount,
  yourTurn,
  targets,
  reason,
  onOrder,
}: {
  province: MapProvince
  provinces: Map<string, ProvinceState>
  provinceNames: Map<string, string>
  military: MilitaryOverview
  relations: Relations
  selectedCount: number
  yourTurn: boolean
  targets: Map<string, TargetKind>
  reason: string | null
  onOrder: (provinceKey: string) => void
}) {
  const explanation = !yourTurn
    ? 'You can move on your turn.'
    : selectedCount === 0
      ? 'Select battalions to move them.'
      : (reason ?? 'Right-click a highlighted province on the map, or choose here. Moving only some battalions splits the stack.')
  return (
    <section className="orders" aria-label="Orders">
      <p className="orders__hint muted">{explanation}</p>
      <ul className="destinations">
        {province.neighbours.map((n) => {
          const target = provinces.get(n.provinceKey)
          const hostile = military.battalions.filter((b) => b.provinceKey === n.provinceKey && relations.atWar(b.nationId)).length
          const enemyTerritory = target !== undefined && relations.atWar(target.controllerNationId)
          const closed = target !== undefined && !relations.mayEnter(target.controllerNationId) && !enemyTerritory
          const name = provinceNames.get(n.provinceKey) ?? n.provinceKey
          const status = hostile > 0
            ? `${hostile} enemy ${hostile === 1 ? 'battalion' : 'battalions'}`
            : closed
              ? 'Closed — not at war, no military access'
              : enemyTerritory
                ? 'Enemy territory — occupied at round end if undefended'
                : 'Friendly'
          return (
            <li key={n.provinceKey} className={`destination ${hostile > 0 ? 'destination--hostile' : closed ? 'destination--closed' : ''}`}>
              <button
                type="button"
                className={`button button--small ${hostile > 0 ? 'button--danger' : ''}`}
                disabled={!targets.has(n.provinceKey)}
                title={status}
                onClick={() => onOrder(n.provinceKey)}
              >
                <Icon name={hostile > 0 ? 'military' : 'move'} size={14} />
                {hostile > 0 ? `Attack ${name}` : `Move to ${name}`}
              </button>
              <span className="destination__info">
                <span className={`tag ${hostile > 0 ? 'tag--warning' : ''}`}>{status}</span>
                {n.crossing === 'RIVER' && <span className="tag">River: attack −20%</span>}
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function GroupForm({ gameId, battalionIds, onDone }: { gameId: string; battalionIds: string[]; onDone: (name: string) => void }) {
  const regroup = useRegroup(gameId)
  const [name, setName] = useState('')
  return (
    <form
      className="army-form"
      onSubmit={(event) => {
        event.preventDefault()
        regroup.mutate(
          { name: name.trim(), battalionIds, idempotencyKey: crypto.randomUUID() },
          { onSuccess: (result) => onDone(result.army.name) },
        )
      }}
    >
      <label>
        Army name
        <input value={name} maxLength={40} onChange={(event) => setName(event.target.value)} placeholder="e.g. First Army" autoFocus />
      </label>
      <button type="submit" className="button button--small" disabled={name.trim() === '' || regroup.isPending}>
        Group {battalionIds.length} as army
      </button>
      {regroup.error && <Alert>{describeError(regroup.error)}</Alert>}
    </form>
  )
}

function LendForm({
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
    <div className="army-form">
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
        className="button button--small"
        disabled={attach.isPending}
        onClick={() => attach.mutate({ battalionIds, commanderNationId: chosen, idempotencyKey: crypto.randomUUID() }, { onSuccess: onDone })}
      >
        Lend {battalionIds.length} battalion(s)
      </button>
      <p className="muted">The ally may move and attack with them; you keep paying their upkeep and can recall them on your turn.</p>
      {attach.error && <Alert>{describeError(attach.error)}</Alert>}
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
      <h4 className="battalions__title">Your battalions lent to allies</h4>
      <ul className="battalion-list">
        {battalions.map((b) => (
          <li key={b.id} className="battalion-row">
            <Icon name={UNIT_ICONS[b.unitType]} size={18} className="battalion-row__icon" />
            <span className="battalion-row__name">{UNIT_LABELS[b.unitType]}</span>
            <span className="battalion-row__strength">{b.manpower.toLocaleString('en-US')} men</span>
            <span className="battalion-row__tags">
              <span className="tag">Commanded by {nationName(b.commanderNationId ?? '')}</span>
              {yourTurn && (
                <button
                  type="button"
                  className="icon-button"
                  aria-label="Recall"
                  title="Recall to your command"
                  disabled={release.isPending}
                  onClick={() => release.mutate({ battalionIds: [b.id], idempotencyKey: crypto.randomUUID() })}
                >
                  <Icon name="recall" size={14} />
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
