import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { describeError } from '../api/client'
import {
  useBattle,
  useDiplomacy,
  useEconomy,
  useEndTurn,
  useGameMap,
  useGameState,
  useMe,
  useMilitary,
  useMove,
  useSieges,
} from '../api/queries'
import type {
  BattleReport,
  DiplomacyOverview,
  EconomyOverview,
  GameMap,
  GameState,
  MapProvince,
  MilitaryOverview,
  ProvinceState,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { ArmyInspector } from '../components/ArmyInspector'
import { BattleReportView } from '../components/BattleReportView'
import { DiplomacyPanel } from '../components/DiplomacyPanel'
import { ForcesCard } from '../components/ForcesCard'
import { GameResultView } from '../components/GameResultView'
import { GameToolbar, type PanelKey } from '../components/GameToolbar'
import { Icon } from '../components/icons'
import { ConferenceCard } from '../components/PeaceConference'
import { ProvinceInspector } from '../components/ProvinceInspector'
import { ProvinceMap, type NationStyle, type TargetKind, type UnitCounts } from '../components/ProvinceMap'
import { ResearchPanel } from '../components/ResearchPanel'
import { ResourceBar } from '../components/ResourceBar'
import { SiegesCard } from '../components/SiegesCard'
import { Spinner } from '../components/Spinner'
import { TreasuryPanel } from '../components/TreasuryPanel'
import { relationsOf, type Names, type Relations } from '../lib/diplomacy'
import { formatGold } from '../lib/gold'

/**
 * The campaign screen: a full-viewport map with the interface floating over it — top bar
 * (resources, turn, End turn), left icon toolbar (management windows), contextual province or
 * army window on the right, map controls and order prompts at the bottom.
 */
export function GamePage() {
  const { gameId = '' } = useParams()
  const state = useGameState(gameId)
  const map = useGameMap(state.data?.mapId)

  if (state.data && map.data) return <GameView state={state.data} map={map.data} />
  return (
    <div className="game-shell game-shell--loading">
      <Link to="/" className="back-link">
        ← Back to dashboard
      </Link>
      {(state.isPending || (state.data && map.isPending)) && <Spinner label="Unrolling the map…" />}
      {state.error && <Alert>{describeError(state.error)}</Alert>}
      {map.error && <Alert>{describeError(map.error)}</Alert>}
    </div>
  )
}

/** Which floating window describes the selection: the province, or the forces standing in it. */
type Inspector = 'province' | 'army' | null

function GameView({ state, map }: { state: GameState; map: GameMap }) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [inspector, setInspector] = useState<Inspector>(null)
  const [selectedBattalions, setSelectedBattalions] = useState<string[]>([])
  const [shownBattle, setShownBattle] = useState<BattleReport | null>(null)
  const [requestedBattleId, setRequestedBattleId] = useState<string | null>(null)
  const [panel, setPanel] = useState<PanelKey | null>(null)
  const [pendingAttack, setPendingAttack] = useState<string | null>(null)
  const [orderMessage, setOrderMessage] = useState<string | null>(null)
  // The conference window opens by itself whenever a conference pauses the game; it can be minimised.
  const [minimisedConference, setMinimisedConference] = useState<string | null>(null)
  const [resultHidden, setResultHidden] = useState(false)
  const nations = useMemo(
    () => new Map<string, NationStyle>(state.nations.map((n) => [n.id, { name: n.name, color: n.color }])),
    [state.nations],
  )
  const provinces = useMemo(() => new Map(state.provinces.map((p) => [p.key, p])), [state.provinces])
  const provinceNames = useMemo(() => new Map(map.provinces.map((p) => [p.key, p.name])), [map.provinces])
  const selected = map.provinces.find((p) => p.key === selectedKey) ?? null
  const economy = useEconomy(state.id, state.stateVersion)
  const military = useMilitary(state.id, state.stateVersion)
  const sieges = useSieges(state.id, state.stateVersion)
  const diplomacy = useDiplomacy(state.id, state.stateVersion)
  const move = useMove(state.id)
  const finished = state.status === 'FINISHED'
  const fortresses = useMemo(
    () =>
      new Map(
        (sieges.data?.fortifications ?? []).map((f) => [f.provinceKey, { level: f.level, damaged: f.status === 'DAMAGED' }]),
      ),
    [sieges.data],
  )
  const besieged = useMemo(() => new Set((sieges.data?.activeSieges ?? []).map((s) => s.provinceKey)), [sieges.data])
  const requestedBattle = useBattle(state.id, requestedBattleId)
  const yourNationId = state.players.find((p) => p.you)?.nationId
  const relations = useMemo(() => relationsOf(diplomacy.data, yourNationId), [diplomacy.data, yourNationId])
  const names = useMemo<Names>(() => {
    const cities = new Map(state.provinces.map((p) => [p.city.id, p.city.name]))
    return {
      nation: (id) => nations.get(id)?.name ?? 'Unknown nation',
      province: (key) => provinceNames.get(key) ?? key,
      city: (id) => cities.get(id) ?? 'a city',
    }
  }, [nations, provinceNames, state.provinces])
  const capitalKeys = useMemo(() => new Set(map.nations.map((n) => n.capitalProvinceKey)), [map.nations])
  const battle = requestedBattleId ? (requestedBattle.data ?? null) : shownBattle
  const units = useMemo(() => unitCounts(military.data, relations), [military.data, relations])
  const orders = useMemo(
    () => moveTargets(selected, selectedBattalions, military.data, provinces, relations, state),
    [selected, selectedBattalions, military.data, provinces, relations, state],
  )
  const enemyColor = state.nations.find((n) => n.id !== yourNationId)?.color
  const income = useMemo(
    () =>
      new Map(
        (economy.data?.cities ?? [])
          .filter((c) => c.ownerNationId === yourNationId)
          .map((c) => [c.provinceKey, c.collectibleIncomeMg]),
      ),
    [economy.data, yourNationId],
  )
  const population = useMemo(
    () =>
      yourNationId === undefined
        ? undefined
        : state.provinces.filter((p) => p.ownerNationId === yourNationId).reduce((sum, p) => sum + p.city.population, 0),
    [state.provinces, yourNationId],
  )
  const waiting = diplomacyBusiness(diplomacy.data)
  const notices = (economy.data?.nation.inArrears ? 1 : 0) + (state.pausedConferenceId ? 1 : 0)
  const badges = { diplomacy: waiting, notifications: waiting + notices }
  const conference = state.pausedConferenceId
    ? (diplomacy.data?.conferences ?? []).find((c) => c.id === state.pausedConferenceId)
    : undefined
  const conferenceOpen = conference !== undefined && minimisedConference !== conference.id && panel !== 'diplomacy'

  // Escape steps back one layer at a time: attack prompt, battalion selection, window, panel.
  useEffect(() => {
    function onKey(event: globalThis.KeyboardEvent) {
      if (event.key !== 'Escape') return
      if (pendingAttack) setPendingAttack(null)
      else if (selectedBattalions.length > 0) setSelectedBattalions([])
      else if (inspector) {
        setInspector(null)
        setSelectedKey(null)
      }
      else if (panel) setPanel(null)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [pendingAttack, selectedBattalions, inspector, panel])

  function selectProvince(key: string) {
    if (key !== selectedKey) setSelectedBattalions([])
    setSelectedKey(key)
    setInspector('province')
    setPendingAttack(null)
  }

  /** Opens the army window of a province and selects the battalions there that can still move this round. */
  function selectArmy(key: string) {
    const ready = (military.data?.battalions ?? []).filter((b) => b.provinceKey === key && b.eligibility === 'READY')
    setSelectedKey(key)
    setInspector('army')
    setPendingAttack(null)
    setSelectedBattalions(ready.map((b) => b.id))
    setOrderMessage(
      ready.length > 0
        ? null
        : `No battalion in ${provinceNames.get(key) ?? key} can move ${state.yourTurn ? 'this round' : 'now — it is not your turn'}.`,
    )
  }

  /** Shows who stands in a province without changing the selection's intent. */
  function openForces(key: string) {
    if (key !== selectedKey) setSelectedBattalions([])
    setSelectedKey(key)
    setInspector('army')
  }

  function closeInspector() {
    setInspector(null)
    setSelectedKey(null)
    setSelectedBattalions([])
    setPendingAttack(null)
  }

  /** Right-click / M / an order button: move to a highlighted province, or ask to confirm an attack. */
  function command(key: string) {
    const kind = orders.targets.get(key)
    const name = provinceNames.get(key) ?? key
    if (!kind) {
      setOrderMessage(orders.reason ?? `${name} is not a legal destination for the selected battalions.`)
      return
    }
    if (kind === 'ATTACK') {
      setPendingAttack(key)
      return
    }
    execute(key)
  }

  function execute(key: string) {
    setPendingAttack(null)
    setOrderMessage(null)
    move.mutate(
      { battalionIds: selectedBattalions, toProvinceKey: key, idempotencyKey: crypto.randomUUID() },
      {
        onSuccess: (result) => {
          const name = provinceNames.get(result.toProvinceKey) ?? result.toProvinceKey
          setOrderMessage(
            result.outcome === 'MOVED'
              ? `Moved ${result.battalions.length} ${result.battalions.length === 1 ? 'battalion' : 'battalions'} to ${name}.`
              : result.outcome === 'ATTACK_WON'
                ? `Victory at ${name}.`
                : `The attack on ${name} was repelled.`,
          )
          if (result.battle) showBattle(result.battle)
          setSelectedBattalions([])
        },
      },
    )
  }

  function showBattle(report: BattleReport) {
    setRequestedBattleId(null)
    setShownBattle(report)
  }

  function closeBattle() {
    setRequestedBattleId(null)
    setShownBattle(null)
  }

  function openPanel(next: PanelKey | null) {
    setPanel(next)
  }

  const errors = [requestedBattle.error, economy.error, military.error, diplomacy.error, sieges.error].filter((e) => e != null)

  return (
    <div className="game-shell">
      <TopBar
        state={state}
        nation={yourNationId ? nations.get(yourNationId) : undefined}
        conferencePaused={conference !== undefined && !conferenceOpen}
        onShowResult={finished && resultHidden ? () => setResultHidden(false) : undefined}
        onOpenConference={() => {
          setMinimisedConference(null)
          if (panel === 'diplomacy') setPanel(null)
        }}
        notifications={badges.notifications}
        onOpenNotifications={() => setPanel(panel === 'notifications' ? null : 'notifications')}
      >
        <ResourceBar
          gameId={state.id}
          stateVersion={state.stateVersion}
          economy={economy.data}
          military={military.data}
          population={population}
        />
      </TopBar>

      <main className="game-shell__map" aria-label="Province map">
        <ProvinceMap
          map={map}
          provinces={provinces}
          nations={nations}
          selectedKey={selectedKey}
          onSelect={selectProvince}
          units={units}
          targets={orders.targets}
          origin={selectedBattalions.length > 0 ? selectedKey : null}
          standing={diplomacy.data?.available ? relations.standing : undefined}
          income={income}
          onCommand={selectedBattalions.length > 0 ? command : undefined}
          onSelectArmy={selectArmy}
          ownColor={yourNationId ? nations.get(yourNationId)?.color : undefined}
          enemyColor={enemyColor}
          fortresses={fortresses}
          besieged={besieged}
        />
      </main>

      <GameToolbar active={panel} onSelect={openPanel} badges={badges} />

      {panel && (
        <aside className={`panel-host ${panel === 'diplomacy' || panel === 'technology' ? 'panel-host--wide' : ''}`} aria-label="Management window">
          <button type="button" className="icon-button panel-host__close" aria-label="Close window" title="Close (Esc)" onClick={() => setPanel(null)}>
            <Icon name="close" />
          </button>
          <div className="panel-host__body">
            {panel === 'nation' && (
              <NationPanel
                state={state}
                nation={yourNationId ? nations.get(yourNationId) : undefined}
                economy={economy.data}
                military={military.data}
                population={population}
              />
            )}
            {panel === 'economy' && economy.data && (
              <>
                <TreasuryPanel economy={economy.data} />
                <CitiesList economy={economy.data} yourNationId={yourNationId} provinceNames={provinceNames} onSelect={selectProvince} />
              </>
            )}
            {panel === 'military' && (
              <>
                {military.data && (
                  <>
                    <ArmiesList military={military.data} provinceNames={provinceNames} onSelect={selectArmy} />
                    <ForcesCard military={military.data} nations={nations} provinceNames={provinceNames} onShowBattle={setRequestedBattleId} />
                  </>
                )}
                {sieges.data && <SiegesCard sieges={sieges.data} nations={nations} onSelect={selectProvince} />}
              </>
            )}
            {panel === 'technology' && <ResearchPanel gameId={state.id} stateVersion={state.stateVersion} military={military.data} />}
            {panel === 'diplomacy' && diplomacy.data && (
              <DiplomacyPanel
                gameId={state.id}
                overview={diplomacy.data}
                state={state}
                names={names}
                capitalKeys={capitalKeys}
                treasuryMg={economy.data?.nation.treasuryMg}
              />
            )}
            {panel === 'players' && <PlayersCard state={state} />}
            {panel === 'notifications' && (
              <NotificationsPanel
                state={state}
                waiting={waiting}
                economy={economy.data}
                military={military.data}
                provinceNames={provinceNames}
                nations={nations}
                onOpen={setPanel}
                onShowBattle={setRequestedBattleId}
              />
            )}
            {panel === 'settings' && <SettingsPanel />}
          </div>
        </aside>
      )}

      {selected && inspector === 'province' && (
        <ProvinceInspector
          key={selected.key}
          gameId={state.id}
          province={selected}
          state={provinces.get(selected.key)}
          nations={nations}
          provinceNames={provinceNames}
          economy={economy.data}
          military={military.data}
          yourNationId={yourNationId}
          yourTurn={state.yourTurn}
          stateVersion={state.stateVersion}
          capital={capitalKeys.has(selected.key)}
          besieged={besieged.has(selected.key)}
          units={units.get(selected.key)}
          onClose={closeInspector}
          onSelect={selectProvince}
          onOpenForces={openForces}
        />
      )}
      {selected && inspector === 'army' && military.data && (
        <ArmyInspector
          key={selected.key}
          gameId={state.id}
          province={selected}
          provinces={provinces}
          provinceNames={provinceNames}
          military={military.data}
          nations={nations}
          yourTurn={state.yourTurn}
          relations={relations}
          selected={selectedBattalions}
          onSelectedChange={setSelectedBattalions}
          targets={orders.targets}
          reason={orders.reason}
          onOrder={command}
          stateVersion={state.stateVersion}
          onClose={closeInspector}
        />
      )}

      <MapOrders
        selectedCount={selectedBattalions.length}
        origin={selected?.name}
        targetCount={orders.targets.size}
        reason={orders.reason}
        pendingAttack={pendingAttack ? (provinceNames.get(pendingAttack) ?? pendingAttack) : null}
        busy={move.isPending}
        message={orderMessage}
        error={move.error}
        onConfirm={() => pendingAttack && execute(pendingAttack)}
        onCancel={() => setPendingAttack(null)}
        onDismiss={() => setOrderMessage(null)}
        onClear={() => {
          setSelectedBattalions([])
          setPendingAttack(null)
        }}
      />

      {(battle || (finished && !resultHidden)) && (
        <div className="center-stage">
          {finished && !resultHidden && (
            <>
              <GameResultView gameId={state.id} />
              <button type="button" className="button button--small center-stage__hide" onClick={() => setResultHidden(true)}>
                Hide the result to look at the map
              </button>
            </>
          )}
          {battle && <BattleReportView battle={battle} nations={nations} provinceNames={provinceNames} onClose={closeBattle} />}
        </div>
      )}

      {errors.length > 0 && (
        <div className="toast-stack">
          {errors.map((error, i) => (
            <Alert key={i}>{describeError(error)}</Alert>
          ))}
        </div>
      )}

      {conferenceOpen && diplomacy.data?.rules && (
        <div className="modal-backdrop">
          <div className="modal" role="dialog" aria-modal="true" aria-label="Peace conference">
            <header className="modal__header">
              <h2>Peace conference</h2>
              <span className="muted">The game is paused until it ends.</span>
              <button type="button" className="button button--small" onClick={() => setMinimisedConference(conference.id)}>
                Minimise
              </button>
            </header>
            <div className="modal__body">
              <ConferenceCard
                gameId={state.id}
                conference={conference}
                war={diplomacy.data.wars.find((w) => w.id === conference.warId)}
                yourNationId={diplomacy.data.yourNationId}
                state={state}
                names={names}
                capitalKeys={capitalKeys}
                rules={diplomacy.data.rules}
                onMessage={setOrderMessage}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * Where the selected battalions may go now (display only; the server decides): adjacent provinces
 * the player may enter — ATTACK where battalions at war with it stand, MOVE otherwise. Nothing when
 * it is not the player's turn, the game is paused, or a selected battalion cannot move.
 */
function moveTargets(
  origin: MapProvince | null,
  selectedIds: string[],
  military: MilitaryOverview | undefined,
  provinces: Map<string, ProvinceState>,
  relations: Relations,
  state: GameState,
): { targets: Map<string, TargetKind>; reason: string | null } {
  const targets = new Map<string, TargetKind>()
  if (!origin || selectedIds.length === 0 || !military) return { targets, reason: null }
  const selected = military.battalions.filter((b) => selectedIds.includes(b.id))
  if (!state.yourTurn || state.pausedConferenceId) {
    return { targets, reason: 'Orders can be given on your turn (and not during a peace conference).' }
  }
  const notReady = selected.filter((b) => b.eligibility !== 'READY').length
  if (notReady > 0) {
    return { targets, reason: `${notReady} selected ${notReady === 1 ? 'battalion cannot' : 'battalions cannot'} move this turn.` }
  }
  for (const n of origin.neighbours) {
    const target = provinces.get(n.provinceKey)
    const hostile = military.battalions.some((b) => b.provinceKey === n.provinceKey && relations.atWar(b.nationId))
    const enemyTerritory = target !== undefined && relations.atWar(target.controllerNationId)
    const closed = target !== undefined && !relations.mayEnter(target.controllerNationId) && !enemyTerritory
    if (closed) continue
    targets.set(n.provinceKey, hostile ? 'ATTACK' : 'MOVE')
  }
  return { targets, reason: null }
}

/** Conference answers, approvals, calls to arms and proposals waiting for the player. */
function diplomacyBusiness(overview: DiplomacyOverview | undefined): number {
  if (!overview) return 0
  const conferences = (overview.conferences ?? []).filter(
    (c) => (c.status === 'REQUESTED' || c.status === 'ACTIVE') && (c.canAnswer || c.youMustDecide),
  ).length
  const calls = (overview.calls ?? []).filter((c) => c.status === 'PENDING' && c.canAnswer).length
  const alliances = overview.alliances.filter((a) => a.canAccept).length
  const proposals = overview.proposals.filter((p) => p.status === 'PENDING' && p.incoming).length
  return conferences + calls + alliances + proposals
}

/** Order prompts floating at the bottom of the map: selection hint, attack confirmation, results. */
function MapOrders({
  selectedCount,
  origin,
  targetCount,
  reason,
  pendingAttack,
  busy,
  message,
  error,
  onConfirm,
  onCancel,
  onClear,
  onDismiss,
}: {
  selectedCount: number
  origin: string | undefined
  targetCount: number
  reason: string | null
  pendingAttack: string | null
  busy: boolean
  message: string | null
  error: unknown
  onConfirm: () => void
  onCancel: () => void
  onClear: () => void
  onDismiss: () => void
}) {
  if (selectedCount === 0 && !pendingAttack && !message && error == null) return null
  return (
    <div className="map-orders">
      {selectedCount > 0 && (
        <p className="map-orders__hint">
          <strong>
            {selectedCount} {selectedCount === 1 ? 'battalion' : 'battalions'} selected{origin ? ` in ${origin}` : ''}.
          </strong>{' '}
          {reason ??
            (targetCount > 0
              ? 'Right-click a highlighted province (or press M on it) to move there; red means attack.'
              : 'No neighbouring province can be entered.')}{' '}
          <button type="button" className="link-button" onClick={onClear}>
            Clear selection (Esc)
          </button>
        </p>
      )}
      {pendingAttack && (
        <div className="map-orders__confirm" role="alertdialog" aria-label="Confirm attack" aria-describedby="attack-text">
          <p id="attack-text">
            Attack {pendingAttack} with {selectedCount} {selectedCount === 1 ? 'battalion' : 'battalions'}? The battle is resolved at
            once.
          </p>
          <button type="button" className="button button--danger" onClick={onConfirm} disabled={busy} autoFocus>
            Attack {pendingAttack}
          </button>
          <button type="button" className="button" onClick={onCancel}>
            Cancel
          </button>
        </div>
      )}
      {message && (
        <p className="map-orders__result" role="status">
          {message}{' '}
          <button type="button" className="icon-button" aria-label="Dismiss" onClick={onDismiss}>
            <Icon name="close" size={12} />
          </button>
        </p>
      )}
      {error != null && <Alert>{describeError(error)}</Alert>}
    </div>
  )
}

/** Battalions and soldiers per province: the player's own, allied (and co-belligerent) and enemy ones. */
function unitCounts(military: MilitaryOverview | undefined, relations: Relations): Map<string, UnitCounts> {
  const counts = new Map<string, UnitCounts>()
  for (const b of military?.battalions ?? []) {
    const entry = counts.get(b.provinceKey) ?? { friendly: 0, hostile: 0, allied: 0, friendlyMen: 0, hostileMen: 0, alliedMen: 0 }
    if (b.nationId === military?.nationId) {
      entry.friendly++
      entry.friendlyMen = (entry.friendlyMen ?? 0) + b.manpower
    }
    else if (relations.atWar(b.nationId)) {
      entry.hostile++
      entry.hostileMen = (entry.hostileMen ?? 0) + b.manpower
    }
    else if (relations.standing(b.nationId) === 'ALLY') {
      entry.allied = (entry.allied ?? 0) + 1
      entry.alliedMen = (entry.alliedMen ?? 0) + b.manpower
    }
    counts.set(b.provinceKey, entry)
  }
  return counts
}

/** The top bar: back to the dashboard, nation, round, whose turn it is, resources and End turn. */
function TopBar({
  state,
  nation,
  conferencePaused,
  onShowResult,
  onOpenConference,
  notifications,
  onOpenNotifications,
  children,
}: {
  state: GameState
  nation: NationStyle | undefined
  conferencePaused: boolean
  /** Brings the final result back after the player hid it. */
  onShowResult?: () => void
  onOpenConference: () => void
  notifications: number
  onOpenNotifications: () => void
  children: ReactNode
}) {
  const endTurn = useEndTurn(state.id)
  const active = state.players.find((p) => p.seat === state.activeSeat)
  const activeNation = state.nations.find((n) => n.seat === state.activeSeat)

  function handleEndTurn() {
    // One key per click: a network retry of this click can never end a second turn.
    endTurn.mutate({ expectedTurnNumber: state.turnNumber, idempotencyKey: crypto.randomUUID() })
  }

  return (
    <header className="top-bar">
      <Link to="/" className="icon-button top-bar__back" aria-label="Back to dashboard" title="Back to dashboard">
        <Icon name="back" />
      </Link>
      <div className="top-bar__nation" title={state.name}>
        <span className="top-bar__flag" style={{ background: nation?.color }} aria-hidden="true" />
        <span className="top-bar__nation-name">{nation?.name ?? 'Observer'}</span>
        <h1 className="visually-hidden">{state.name}</h1>
      </div>
      <span className="top-bar__round" title="Global round">
        Round {state.round}
      </span>
      {children}
      <section className="top-bar__turn" aria-label="Turn">
        <div className="top-bar__status" role="status" aria-live="polite">
          <span className="swatch" style={{ background: activeNation?.color }} aria-hidden="true" />
          {state.status === 'FINISHED' ? (
            <strong>Game over — final state after round {state.round}</strong>
          ) : state.pausedConferenceId ? (
            <strong>Paused for a peace conference — no turn passes until it ends (see Diplomacy)</strong>
          ) : state.youAreEliminated ? (
            <span>Your nation has been eliminated. You can keep watching.</span>
          ) : state.yourTurn ? (
            <strong>Your turn</strong>
          ) : (
            <span className="top-bar__waiting">
              <span className="pulse" aria-hidden="true" />
              {active?.computer
                ? `${activeNation?.name ?? 'A computer nation'} (computer) is playing its turn…`
                : `Waiting for ${active?.displayName ?? 'the other player'}${activeNation ? ` (${activeNation.name})` : ''}…`}
            </span>
          )}
        </div>
        {onShowResult && (
          <button type="button" className="button button--small" onClick={onShowResult}>
            Show the result
          </button>
        )}
        {conferencePaused && (
          <button type="button" className="button button--small" onClick={onOpenConference}>
            Open peace conference
          </button>
        )}
        <button
          type="button"
          className="icon-button top-bar__bell"
          aria-label={`Notifications${notifications > 0 ? ` (${notifications})` : ''}`}
          title="Notifications"
          onClick={onOpenNotifications}
        >
          <Icon name="bell" />
          {notifications > 0 && (
            <span className="game-toolbar__badge" aria-hidden="true">
              {notifications}
            </span>
          )}
        </button>
        {endTurn.error && <span className="top-bar__error">{describeError(endTurn.error)}</span>}
        {state.yourTurn && !state.pausedConferenceId && (
          <button type="button" className="button button--primary top-bar__end" onClick={handleEndTurn} disabled={endTurn.isPending}>
            {endTurn.isPending ? 'Ending turn…' : 'End turn'}
          </button>
        )}
      </section>
    </header>
  )
}

function NationName({ nation }: { nation: NationStyle | undefined }) {
  if (!nation) return <span className="muted">—</span>
  return (
    <span className="nation-name">
      <span className="swatch" style={{ background: nation.color }} aria-hidden="true" />
      {nation.name}
    </span>
  )
}

/** The nation at a glance: territory, people, money and soldiers. */
function NationPanel({
  state,
  nation,
  economy,
  military,
  population,
}: {
  state: GameState
  nation: NationStyle | undefined
  economy: EconomyOverview | undefined
  military: MilitaryOverview | undefined
  population: number | undefined
}) {
  const you = state.players.find((p) => p.you)?.nationId
  const owned = state.provinces.filter((p) => p.ownerNationId === you)
  const occupied = owned.filter((p) => p.controllerNationId !== you).length
  return (
    <section className="card" aria-labelledby="nation-title">
      <h2 id="nation-title">
        <NationName nation={nation} />
      </h2>
      <dl className="facts facts--compact">
        <dt>Round</dt>
        <dd>{state.round}</dd>
        <dt>Provinces</dt>
        <dd>
          {owned.length}
          {occupied > 0 && <span className="tag tag--warning">{occupied} occupied</span>}
        </dd>
        <dt>Population</dt>
        <dd>{population?.toLocaleString('en-US') ?? '—'}</dd>
        <dt>Treasury</dt>
        <dd>{economy ? `${formatGold(economy.nation.treasuryMg)} gold` : '—'}</dd>
        <dt>Battalions</dt>
        <dd>{military ? `${military.upkeep.battalions} · ${military.upkeep.soldiers.toLocaleString('en-US')} men` : '—'}</dd>
      </dl>
    </section>
  )
}

/** The player's cities with their income; a click opens the province on the map. */
function CitiesList({
  economy,
  yourNationId,
  provinceNames,
  onSelect,
}: {
  economy: EconomyOverview
  yourNationId: string | undefined
  provinceNames: Map<string, string>
  onSelect: (key: string) => void
}) {
  const cities = economy.cities.filter((c) => c.ownerNationId === yourNationId).sort((a, b) => b.collectibleIncomeMg - a.collectibleIncomeMg)
  return (
    <section className="card" aria-labelledby="cities-title">
      <h2 id="cities-title">Your provinces</h2>
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Province</th>
            <th scope="col">People</th>
            <th scope="col">Workshops</th>
            <th scope="col">Income</th>
          </tr>
        </thead>
        <tbody>
          {cities.map((c) => (
            <tr key={c.cityId}>
              <td>
                <button type="button" className="link-button" onClick={() => onSelect(c.provinceKey)}>
                  {provinceNames.get(c.provinceKey) ?? c.cityName}
                </button>
                {c.incomeStatus === 'OCCUPIED' && <span className="tag tag--warning">Occupied</span>}
              </td>
              <td>{c.population.toLocaleString('en-US')}</td>
              <td>{c.workshopCount}</td>
              <td>{formatGold(c.collectibleIncomeMg)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

/** Where the player's battalions stand; a click opens that army on the map. */
function ArmiesList({
  military,
  provinceNames,
  onSelect,
}: {
  military: MilitaryOverview
  provinceNames: Map<string, string>
  onSelect: (key: string) => void
}) {
  const you = military.nationId
  const stacks = new Map<string, { battalions: number; men: number; ready: number }>()
  for (const b of military.battalions) {
    const commanded = (b.nationId === you && !b.commanderNationId) || b.commanderNationId === you
    if (!commanded) continue
    const entry = stacks.get(b.provinceKey) ?? { battalions: 0, men: 0, ready: 0 }
    entry.battalions++
    entry.men += b.manpower
    if (b.eligibility === 'READY') entry.ready++
    stacks.set(b.provinceKey, entry)
  }
  const sorted = [...stacks.entries()].sort(([a], [b]) => (provinceNames.get(a) ?? a).localeCompare(provinceNames.get(b) ?? b))
  return (
    <section className="card" aria-labelledby="armies-title">
      <h2 id="armies-title">Armies</h2>
      {sorted.length === 0 ? (
        <p className="muted">You command no battalions.</p>
      ) : (
        <ul className="army-list">
          {sorted.map(([key, s]) => (
            <li key={key}>
              <button type="button" className="link-button" onClick={() => onSelect(key)}>
                {provinceNames.get(key) ?? key}
              </button>
              <span className="muted">
                {s.battalions} {s.battalions === 1 ? 'battalion' : 'battalions'} · {s.men.toLocaleString('en-US')} men · {s.ready} ready
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** What needs the player's attention, each with a way to get to it. */
function NotificationsPanel({
  state,
  waiting,
  economy,
  military,
  provinceNames,
  nations,
  onOpen,
  onShowBattle,
}: {
  state: GameState
  waiting: number
  economy: EconomyOverview | undefined
  military: MilitaryOverview | undefined
  provinceNames: Map<string, string>
  nations: Map<string, NationStyle>
  onOpen: (panel: PanelKey) => void
  onShowBattle: (battleId: string) => void
}) {
  const battles = (military?.recentBattles ?? []).slice(0, 5)
  return (
    <section className="card" aria-labelledby="notifications-title">
      <h2 id="notifications-title">Notifications</h2>
      <ul className="notification-list">
        {state.pausedConferenceId && <li>A peace conference has paused the game.</li>}
        {waiting > 0 && (
          <li>
            {waiting} diplomatic {waiting === 1 ? 'matter waits' : 'matters wait'} for your answer.{' '}
            <button type="button" className="link-button" onClick={() => onOpen('diplomacy')}>
              Open diplomacy
            </button>
          </li>
        )}
        {economy?.nation.inArrears && <li>Your nation is in arrears: no construction until a round ends without a deficit.</li>}
        {battles.map((b) => {
          const winner = nations.get(b.winner === 'ATTACKER' ? b.attackerNationId : b.defenderNationId)?.name ?? 'unknown'
          return (
            <li key={b.id}>
              Round {b.round}: {winner} won at {provinceNames.get(b.provinceKey) ?? b.provinceKey}.{' '}
              <button type="button" className="link-button" onClick={() => onShowBattle(b.id)}>
                Report
              </button>
            </li>
          )
        })}
        {!state.pausedConferenceId && waiting === 0 && !economy?.nation.inArrears && battles.length === 0 && (
          <li className="muted">Nothing needs your attention.</li>
        )}
      </ul>
    </section>
  )
}

/** Leaving the game screen and signing out (the game itself keeps running). */
function SettingsPanel() {
  const { signOut } = useAuth()
  const me = useMe()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  async function handleSignOut() {
    await signOut()
    queryClient.clear()
    navigate('/login', { replace: true })
  }

  return (
    <section className="card" aria-labelledby="settings-title">
      <h2 id="settings-title">Settings</h2>
      {me.data && <p className="muted">Signed in as {me.data.displayName}</p>}
      <p>
        <Link to="/" className="button button--small">
          Return to the dashboard
        </Link>
      </p>
      <p>
        <button type="button" className="button button--ghost button--small" onClick={handleSignOut}>
          <Icon name="logout" size={14} /> Log out
        </button>
      </p>
      <p className="muted">Map: drag to pan, wheel or + / − to zoom, 0 to show everything. Esc closes windows.</p>
    </section>
  )
}

function PlayersCard({ state }: { state: GameState }) {
  return (
    <section className="card" aria-labelledby="players-title">
      <h2 id="players-title">Players</h2>
      <ol className="seats">
        {state.players.map((player) => {
          const nation = state.nations.find((n) => n.id === player.nationId)
          const active = player.seat === state.activeSeat
          return (
            <li key={player.seat} className={`seat seat--taken ${active ? 'seat--active' : ''}`}>
              <span className="seat__number" style={{ background: nation?.color }}>
                {player.seat + 1}
              </span>
              <span className="seat__name">
                {player.displayName}
                {player.you && <span className="tag tag--you">You</span>}
                {player.computer && <span className="tag">Computer</span>}
                {active && <span className="tag tag--active">Active</span>}
                {nation?.eliminatedRound != null && <span className="tag tag--warning">Eliminated</span>}
                <span className="seat__nation muted">{nation?.name}</span>
              </span>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
