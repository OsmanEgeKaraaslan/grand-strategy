import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { describeError } from '../api/client'
import {
  useBattle,
  useDiplomacy,
  useEconomy,
  useEndTurn,
  useGameMap,
  useGameState,
  useMilitary,
  useSieges,
} from '../api/queries'
import type {
  BattleReport,
  EconomyOverview,
  GameMap,
  GameState,
  MapProvince,
  MilitaryOverview,
  ProvinceState,
} from '../api/types'
import { BattleReportView } from '../components/BattleReportView'
import { DiplomacyPanel } from '../components/DiplomacyPanel'
import { GameResultView } from '../components/GameResultView'
import { CityEconomyPanel } from '../components/CityEconomyPanel'
import { ForcesCard } from '../components/ForcesCard'
import { FortificationPanel } from '../components/FortificationPanel'
import { InstitutionsPanel } from '../components/InstitutionsPanel'
import { ResearchPanel } from '../components/ResearchPanel'
import { SiegesCard } from '../components/SiegesCard'
import { MilitaryPanel } from '../components/MilitaryPanel'
import { TreasuryPanel } from '../components/TreasuryPanel'
import { Alert } from '../components/Alert'
import { AppShell } from '../components/AppShell'
import { ProvinceMap, type NationStyle, type UnitCounts } from '../components/ProvinceMap'
import { Spinner } from '../components/Spinner'
import { relationsOf, type Names, type Relations } from '../lib/diplomacy'

export function GamePage() {
  const { gameId = '' } = useParams()
  const state = useGameState(gameId)
  const map = useGameMap(state.data?.mapId)

  return (
    <AppShell>
      <Link to="/" className="back-link">
        ← Back to dashboard
      </Link>
      {(state.isPending || (state.data && map.isPending)) && <Spinner label="Unrolling the map…" />}
      {state.error && <Alert>{describeError(state.error)}</Alert>}
      {map.error && <Alert>{describeError(map.error)}</Alert>}
      {state.data && map.data && <GameView state={state.data} map={map.data} />}
    </AppShell>
  )
}

function GameView({ state, map }: { state: GameState; map: GameMap }) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [selectedBattalions, setSelectedBattalions] = useState<string[]>([])
  const [shownBattle, setShownBattle] = useState<BattleReport | null>(null)
  const [requestedBattleId, setRequestedBattleId] = useState<string | null>(null)
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
  const targets = useMemo(
    () => (selected && selectedBattalions.length > 0 ? new Set(selected.neighbours.map((n) => n.provinceKey)) : new Set<string>()),
    [selected, selectedBattalions],
  )
  const enemyColor = state.nations.find((n) => n.id !== yourNationId)?.color

  function selectProvince(key: string) {
    if (key !== selectedKey) setSelectedBattalions([])
    setSelectedKey(key)
  }

  function showBattle(report: BattleReport) {
    setRequestedBattleId(null)
    setShownBattle(report)
  }

  function closeBattle() {
    setRequestedBattleId(null)
    setShownBattle(null)
  }

  return (
    <div className="game">
      <TurnBar state={state} />
      <div className="game__body">
        <section className="card game__map" aria-label="Province map">
          <ProvinceMap
            map={map}
            provinces={provinces}
            nations={nations}
            selectedKey={selectedKey}
            onSelect={selectProvince}
            units={units}
            targets={targets}
            ownColor={yourNationId ? nations.get(yourNationId)?.color : undefined}
            enemyColor={enemyColor}
            fortresses={fortresses}
            besieged={besieged}
          />
        </section>
        <aside className="game__side">
          {finished && <GameResultView gameId={state.id} />}
          {economy.data && <TreasuryPanel economy={economy.data} />}
          {economy.error && <Alert>{describeError(economy.error)}</Alert>}
          <ResearchPanel gameId={state.id} stateVersion={state.stateVersion} military={military.data} />
          {diplomacy.data && (
            <DiplomacyPanel
              gameId={state.id}
              overview={diplomacy.data}
              state={state}
              names={names}
              capitalKeys={capitalKeys}
              treasuryMg={economy.data?.nation.treasuryMg}
            />
          )}
          {diplomacy.error && <Alert>{describeError(diplomacy.error)}</Alert>}
          {military.error && <Alert>{describeError(military.error)}</Alert>}
          {battle && (
            <BattleReportView battle={battle} nations={nations} provinceNames={provinceNames} onClose={closeBattle} />
          )}
          {requestedBattle.error && <Alert>{describeError(requestedBattle.error)}</Alert>}
          {selected ? (
            <ProvincePanel
              gameId={state.id}
              province={selected}
              state={provinces.get(selected.key)}
              provinces={provinces}
              provinceNames={provinceNames}
              nations={nations}
              economy={economy.data}
              military={military.data}
              yourNationId={yourNationId}
              yourTurn={state.yourTurn}
              relations={relations}
              selectedBattalions={selectedBattalions}
              onSelectedBattalionsChange={setSelectedBattalions}
              onBattle={showBattle}
              onSelect={selectProvince}
              stateVersion={state.stateVersion}
            />
          ) : (
            <section className="card">
              <h2>Province</h2>
              <p className="muted">Select a province on the map to see its details.</p>
            </section>
          )}
          {sieges.data && <SiegesCard sieges={sieges.data} nations={nations} onSelect={selectProvince} />}
          {sieges.error && <Alert>{describeError(sieges.error)}</Alert>}
          {military.data && (
            <ForcesCard
              military={military.data}
              nations={nations}
              provinceNames={provinceNames}
              onShowBattle={setRequestedBattleId}
            />
          )}
          <PlayersCard state={state} />
        </aside>
      </div>
    </div>
  )
}

function unitCounts(military: MilitaryOverview | undefined, relations: Relations): Map<string, UnitCounts> {
  const counts = new Map<string, UnitCounts>()
  for (const b of military?.battalions ?? []) {
    const entry = counts.get(b.provinceKey) ?? { friendly: 0, hostile: 0 }
    if (b.nationId === military?.nationId) entry.friendly++
    else if (relations.atWar(b.nationId)) entry.hostile++
    counts.set(b.provinceKey, entry)
  }
  return counts
}

function TurnBar({ state }: { state: GameState }) {
  const endTurn = useEndTurn(state.id)
  const active = state.players.find((p) => p.seat === state.activeSeat)
  const activeNation = state.nations.find((n) => n.seat === state.activeSeat)

  function handleEndTurn() {
    // One key per click: a network retry of this click can never end a second turn.
    endTurn.mutate({ expectedTurnNumber: state.turnNumber, idempotencyKey: crypto.randomUUID() })
  }

  return (
    <section className="turn-bar" aria-label="Turn">
      <div className="turn-bar__info">
        <h1 className="turn-bar__title">{state.name}</h1>
        <span className="turn-bar__round">Round {state.round}</span>
      </div>
      <div className="turn-bar__status" role="status" aria-live="polite">
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
          <span className="turn-bar__waiting">
            <span className="pulse" aria-hidden="true" />
            Waiting for {active?.displayName ?? 'the other player'}
            {activeNation ? ` (${activeNation.name})` : ''}…
          </span>
        )}
      </div>
      <div className="turn-bar__actions">
        {endTurn.error && <span className="turn-bar__error">{describeError(endTurn.error)}</span>}
        {state.yourTurn && !state.pausedConferenceId && (
          <button
            type="button"
            className="button button--primary"
            onClick={handleEndTurn}
            disabled={endTurn.isPending}
          >
            {endTurn.isPending ? 'Ending turn…' : 'End turn'}
          </button>
        )}
      </div>
    </section>
  )
}

const TERRAIN_LABELS = { PLAINS: 'Plains', MOUNTAINS: 'Mountains' } as const

function ProvincePanel({
  gameId,
  province,
  state,
  provinces,
  provinceNames: names,
  nations,
  economy,
  military,
  yourNationId,
  yourTurn,
  relations,
  selectedBattalions,
  onSelectedBattalionsChange,
  onBattle,
  onSelect,
  stateVersion,
}: {
  gameId: string
  province: MapProvince
  state: ProvinceState | undefined
  provinces: Map<string, ProvinceState>
  provinceNames: Map<string, string>
  nations: Map<string, NationStyle>
  economy: EconomyOverview | undefined
  military: MilitaryOverview | undefined
  yourNationId: string | undefined
  yourTurn: boolean
  relations: Relations
  selectedBattalions: string[]
  onSelectedBattalionsChange: (ids: string[]) => void
  onBattle: (battle: BattleReport) => void
  onSelect: (key: string) => void
  stateVersion: number
}) {
  const cityEconomy = economy?.cities.find((c) => c.provinceKey === province.key)
  const owner = state ? nations.get(state.ownerNationId) : undefined
  const controller = state ? nations.get(state.controllerNationId) : undefined
  const occupied = state !== undefined && state.ownerNationId !== state.controllerNationId

  return (
    <section className="card province-panel" aria-labelledby="province-title">
      <h2 id="province-title">{province.name}</h2>
      <dl className="facts">
        <dt>Terrain</dt>
        <dd>{TERRAIN_LABELS[province.terrain]}</dd>
        <dt>Owner</dt>
        <dd>
          <NationName nation={owner} />
        </dd>
        <dt>Controller</dt>
        <dd>
          <NationName nation={controller} />
          {occupied && <span className="tag tag--warning">Occupied</span>}
        </dd>
        <dt>City</dt>
        <dd>{state?.city.name ?? province.cityName}</dd>
        <dt>Population</dt>
        <dd>{state ? state.city.population.toLocaleString('en-US') : '—'}</dd>
      </dl>

      <h3>Neighbours</h3>
      <ul className="neighbours">
        {province.neighbours.map((n) => (
          <li key={n.provinceKey}>
            <button type="button" className="link-button" onClick={() => onSelect(n.provinceKey)}>
              {names.get(n.provinceKey) ?? n.provinceKey}
            </button>
            {n.crossing === 'RIVER' && <span className="tag">across river</span>}
          </li>
        ))}
      </ul>

      <FortificationPanel
        key={province.key}
        gameId={gameId}
        provinceKey={province.key}
        stateVersion={stateVersion}
        nations={nations}
        yourNationId={yourNationId}
      />
      {cityEconomy && cityEconomy.ownerNationId === yourNationId && (
        <InstitutionsPanel key={cityEconomy.cityId} gameId={gameId} cityId={cityEconomy.cityId} stateVersion={stateVersion} />
      )}
      {cityEconomy ? (
        <CityEconomyPanel
          key={cityEconomy.cityId}
          gameId={gameId}
          city={cityEconomy}
          maxWorkshops={economy!.rules.maxWorkshopsPerCity}
          ownedByYou={cityEconomy.ownerNationId === yourNationId}
        />
      ) : (
        <p className="placeholder">Loading the economy…</p>
      )}
      {military ? (
        <MilitaryPanel
          gameId={gameId}
          province={province}
          provinces={provinces}
          provinceNames={names}
          military={military}
          nations={nations}
          yourTurn={yourTurn}
          relations={relations}
          selected={selectedBattalions}
          onSelectedChange={onSelectedBattalionsChange}
          onBattle={onBattle}
          stateVersion={stateVersion}
        />
      ) : (
        <p className="placeholder">Loading forces…</p>
      )}
    </section>
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
