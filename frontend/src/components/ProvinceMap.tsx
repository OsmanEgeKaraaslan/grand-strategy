import { Suspense, lazy, useCallback, useEffect, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent } from 'react'
import type { GameMap, MapProvince, ProvinceState } from '../api/types'
import type { Perspective } from '../lib/diplomacy'
import { webglAvailable } from '../lib/mapGeometry'
import { Icon, type IconName } from './icons'

// three.js is only downloaded when the 3D view is opened. The view is not ready for players: it is off
// unless a development build sets VITE_ENABLE_3D_MAP=true (playtest feedback, 2026-10-29).
const Map3D = lazy(() => import('./Map3D'))
const MAP_3D_ENABLED = import.meta.env.VITE_ENABLE_3D_MAP === 'true'

export interface NationStyle {
  name: string
  color: string
}

export type TargetKind = 'MOVE' | 'ATTACK'

/** Map modes (UI-B): what the province colours show. */
export type MapMode = 'political' | 'diplomatic' | 'terrain' | 'economy'

/** Battalions in one province from the viewer's perspective: counts and soldiers. */
export interface UnitCounts {
  friendly: number
  hostile: number
  allied?: number
  friendlyMen?: number
  hostileMen?: number
  alliedMen?: number
}

interface ProvinceMapProps {
  map: GameMap
  /** Per-game state by province key (owner/controller come from the server). */
  provinces: Map<string, ProvinceState>
  nations: Map<string, NationStyle>
  selectedKey: string | null
  onSelect: (provinceKey: string) => void
  /** Battalion counts per province key, from the viewer's perspective. */
  units?: Map<string, UnitCounts>
  /**
   * Legal destinations of the selected battalions: MOVE (friendly or empty enemy land) or ATTACK
   * (enemy battalions there). Display only; the server validates every order.
   */
  targets?: Map<string, TargetKind>
  /** Where the selected battalions stand (movement arrows start there). */
  origin?: string | null
  /** Right-click (or M on a focused province): order the selected battalions there. */
  onCommand?: (provinceKey: string) => void
  /** Click on the viewer's army marker: select the battalions it commands in that province. */
  onSelectArmy?: (provinceKey: string) => void
  /** Colours of the viewer's, allied and enemy unit markers. */
  ownColor?: string
  enemyColor?: string
  alliedColor?: string
  /** Fortress level per province key (damaged fortresses are shown broken). */
  fortresses?: Map<string, { level: number; damaged: boolean }>
  /** Provinces whose city is under an active siege. */
  besieged?: Set<string>
  /** Diplomatic mode (absent in games without diplomacy). View-only: it never affects orders. */
  diplomacy?: DiplomaticView
  /** Collectible income per round of the viewer's own provinces, in milligold (economy mode). */
  income?: Map<string, number>
  /** Offers the experimental 3D view (development builds only; never in the player UI). */
  allow3D?: boolean
}

interface View {
  x: number
  y: number
  w: number
  h: number
}

const MIN_ZOOM = 1
const MAX_ZOOM = 6

const MODES: { key: MapMode; label: string; icon: IconName; hint: string }[] = [
  { key: 'political', label: 'Political', icon: 'flag', hint: 'Political map: provinces in their owner\'s colour' },
  { key: 'diplomatic', label: 'Diplomatic', icon: 'diplomacy', hint: 'Diplomatic map: allies, enemies and neutrals of one nation' },
  { key: 'terrain', label: 'Terrain', icon: 'mountain', hint: 'Terrain map: plains and mountains' },
  { key: 'economy', label: 'Economy', icon: 'gold', hint: 'Economy map: income of your provinces' },
]

/** Whose relations the diplomatic map shows, and how a click on the map picks another nation. */
export interface DiplomaticView {
  /** The reference nation (the viewer's own by default). */
  referenceId: string
  yourNationId: string | undefined
  /** The reference nation's relation to a nation. */
  standing: (nationId: string) => Perspective
  onReference: (nationId: string) => void
}

const PERSPECTIVES: Perspective[] = ['SELF', 'ALLY', 'DEFENSIVE_ALLY', 'CO_BELLIGERENT', 'WAR', 'NEUTRAL']

const STANDING_COLORS: Record<Perspective, string> = {
  SELF: '#c9a24d',
  ALLY: '#4f9d6a',
  DEFENSIVE_ALLY: '#4f8fb8',
  CO_BELLIGERENT: '#9bb85a',
  WAR: '#b8443a',
  NEUTRAL: '#68717c',
}

function standingLabels(reference: string, yours: boolean): Record<Perspective, string> {
  return {
    SELF: yours ? 'Your nation' : reference,
    ALLY: 'Allies',
    DEFENSIVE_ALLY: 'Defensive allies',
    CO_BELLIGERENT: 'Same side of a war, not allied',
    WAR: yours ? 'At war with you' : `At war with ${reference}`,
    NEUTRAL: 'At peace',
  }
}

const TERRAIN_COLORS = { PLAINS: '#7f8f5c', MOUNTAINS: '#8d7b67' } as const
const INCOME_COLORS = ['#3d4249', '#5a4a24', '#7d6326', '#a88426', '#d6ad3a']

function parseViewBox(viewBox: string): View {
  const [x, y, w, h] = viewBox.trim().split(/[\s,]+/).map(Number)
  return { x, y, w, h }
}

/** Keeps the view inside the map and between the zoom limits. */
function clampView(view: View, base: View): View {
  const w = Math.min(base.w, Math.max(base.w / MAX_ZOOM, view.w))
  const h = (w / base.w) * base.h
  const x = Math.min(base.x + base.w - w, Math.max(base.x, view.x))
  const y = Math.min(base.y + base.h - h, Math.max(base.y, view.y))
  return { x, y, w, h }
}

function zoomAround(current: View, base: View, factor: number, cx: number, cy: number): View {
  const w = current.w / factor
  const h = current.h / factor
  return clampView({ x: cx - ((cx - current.x) / current.w) * w, y: cy - ((cy - current.y) / current.h) * h, w, h }, base)
}

/**
 * Screen pixels per map unit. The SVG keeps the map's aspect ratio (xMidYMid meet), so in a frame of
 * another shape the view is letterboxed and both axes share the smaller scale.
 */
function fitScale(rect: { width: number; height: number }, view: View): number {
  return Math.min(rect.width / view.w, rect.height / view.h)
}

/** A pointer position in map coordinates, allowing for the letterbox around the view. */
function clientToView(rect: DOMRect, view: View, clientX: number, clientY: number): { x: number; y: number } {
  const scale = fitScale(rect, view)
  const offsetX = (rect.width - view.w * scale) / 2
  const offsetY = (rect.height - view.h * scale) / 2
  return { x: view.x + (clientX - rect.left - offsetX) / scale, y: view.y + (clientY - rect.top - offsetY) / scale }
}

/** "1,650" → "1.7k" for compact markers. */
function men(n: number | undefined): string {
  if (!n) return ''
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : String(n)
}

/** 0–4: how much a province earns compared with the viewer's best province. */
function incomeLevel(value: number | undefined, max: number): number {
  if (value === undefined || value <= 0 || max <= 0) return 0
  return Math.max(1, Math.ceil((value / max) * 4))
}

interface Hover {
  lines: string[]
  x: number
  y: number
}

/**
 * Hover details of a province. Kept in its own component so moving the pointer re-renders only the
 * tooltip, never the province shapes.
 */
function MapTooltip({ register }: { register: (show: (hover: Hover | null) => void) => void }) {
  const [hover, setHover] = useState<Hover | null>(null)
  useEffect(() => register(setHover), [register])
  if (!hover) return null
  return (
    <div className="map-tooltip" style={{ left: hover.x + 14, top: hover.y + 14 }} role="tooltip">
      {hover.lines.map((line, i) => (
        <div key={i} className={i === 0 ? 'map-tooltip__title' : undefined}>
          {line}
        </div>
      ))}
    </div>
  )
}

/**
 * Renders the province map as SVG from the template geometry, coloured by the active map mode
 * (political ownership by default). Occupied provinces (controller ≠ owner) get the controller's
 * colour as hatching. Provinces are keyboard-focusable buttons. The view can be zoomed (buttons,
 * mouse wheel, double-click, + / −) and panned (drag, arrow keys); a click without dragging still
 * selects a province.
 */
export function ProvinceMap({
  map,
  provinces,
  nations,
  selectedKey,
  onSelect,
  units = new Map(),
  targets = new Map(),
  origin = null,
  onCommand,
  onSelectArmy,
  ownColor = '#e6e1d6',
  enemyColor = '#e0685c',
  alliedColor = '#4f9d6a',
  fortresses = new Map(),
  besieged = new Set(),
  diplomacy,
  income = new Map(),
  allow3D = MAP_3D_ENABLED,
}: ProvinceMapProps) {
  const controllers = new Set(
    [...provinces.values()].filter((p) => p.controllerNationId !== p.ownerNationId).map((p) => p.controllerNationId),
  )

  const base = parseViewBox(map.viewBox)
  const [view, setView] = useState<View>(base)
  const [mode, setMode] = useState<MapMode>('political')
  const [threeD, setThreeD] = useState(false)
  const [canUse3D] = useState(webglAvailable)
  const svgRef = useRef<SVGSVGElement>(null)
  const frameRef = useRef<HTMLDivElement>(null)
  const drag = useRef<{ startX: number; startY: number; view: View; moved: boolean } | null>(null)
  const suppressClick = useRef(false)
  const showHover = useRef<(hover: Hover | null) => void>(() => undefined)
  const registerTooltip = useCallback((show: (hover: Hover | null) => void) => {
    showHover.current = show
  }, [])
  const zoom = base.w / view.w
  const maxIncome = Math.max(0, ...income.values())
  const byKey = new Map(map.provinces.map((p) => [p.key, p]))

  function zoomBy(factor: number, centreX = view.x + view.w / 2, centreY = view.y + view.h / 2) {
    setView((current) => zoomAround(current, base, factor, centreX, centreY))
  }

  function pan(dx: number, dy: number) {
    setView((current) => clampView({ ...current, x: current.x + dx * current.w, y: current.y + dy * current.h }, base))
  }

  function toMap(clientX: number, clientY: number): { x: number; y: number } | null {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect || rect.width === 0) return null
    return clientToView(rect, view, clientX, clientY)
  }

  // Wheel zoom needs a non-passive listener so the page does not scroll at the same time.
  useEffect(() => {
    const svg = svgRef.current
    if (!svg) return
    function onWheel(event: WheelEvent) {
      event.preventDefault()
      const rect = svg!.getBoundingClientRect()
      if (rect.width === 0) return
      setView((current) => {
        const point = clientToView(rect, current, event.clientX, event.clientY)
        return zoomAround(current, base, event.deltaY < 0 ? 1.2 : 1 / 1.2, point.x, point.y)
      })
    }
    svg.addEventListener('wheel', onWheel, { passive: false })
    return () => svg.removeEventListener('wheel', onWheel)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map.viewBox, threeD])

  function onPointerDown(event: PointerEvent<SVGSVGElement>) {
    drag.current = { startX: event.clientX, startY: event.clientY, view, moved: false }
  }

  function onPointerMove(event: PointerEvent<SVGSVGElement>) {
    const d = drag.current
    const svg = svgRef.current
    if (!d || !svg) return
    const dx = event.clientX - d.startX
    const dy = event.clientY - d.startY
    if (!d.moved && Math.hypot(dx, dy) < 5) return
    d.moved = true
    showHover.current(null)
    const rect = svg.getBoundingClientRect()
    if (rect.width === 0) return
    const scale = fitScale(rect, d.view)
    setView(clampView({ ...d.view, x: d.view.x - dx / scale, y: d.view.y - dy / scale }, base))
  }

  function onPointerUp() {
    suppressClick.current = drag.current?.moved ?? false
    drag.current = null
  }

  function onDoubleClick(event: MouseEvent<SVGSVGElement>) {
    const point = toMap(event.clientX, event.clientY)
    zoomBy(1.5, point?.x, point?.y)
  }

  function select(key: string) {
    if (suppressClick.current) {
      suppressClick.current = false
      return
    }
    choose(key)
  }

  /**
   * Selects a province; in diplomatic mode its legal owner (not an occupier) also becomes the map's
   * reference nation. Army markers never get here: they select armies only.
   */
  function choose(key: string) {
    onSelect(key)
    const owner = provinces.get(key)?.ownerNationId
    if (mode === 'diplomatic' && diplomacy && owner && owner !== diplomacy.referenceId) diplomacy.onReference(owner)
  }

  function handleKey(event: KeyboardEvent, key: string) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      choose(key)
    }
    else if ((event.key === 'm' || event.key === 'M') && onCommand) {
      event.preventDefault()
      onCommand(key)
    }
  }

  /** Camera keys anywhere in the map frame: arrows pan, + / − zoom, 0 shows the whole map. */
  function handleFrameKey(event: KeyboardEvent<HTMLDivElement>) {
    const step = 0.15
    const actions: Record<string, () => void> = {
      ArrowLeft: () => pan(-step, 0),
      ArrowRight: () => pan(step, 0),
      ArrowUp: () => pan(0, -step),
      ArrowDown: () => pan(0, step),
      '+': () => zoomBy(1.5),
      '=': () => zoomBy(1.5),
      '-': () => zoomBy(1 / 1.5),
      '0': () => setView(base),
    }
    const action = actions[event.key]
    if (action && !(event.target instanceof HTMLButtonElement)) {
      event.preventDefault()
      action()
    }
  }

  function handleContextMenu(event: MouseEvent, key: string) {
    // The browser menu is replaced only on the map, and only when there is an order to give.
    if (!onCommand) return
    event.preventDefault()
    onCommand(key)
  }

  function describe(province: MapProvince): string[] {
    const state = provinces.get(province.key)
    const owner = state ? nations.get(state.ownerNationId) : undefined
    const occupied = state !== undefined && state.controllerNationId !== state.ownerNationId
    const counts = units.get(province.key)
    const fortress = fortresses.get(province.key)
    const target = targets.get(province.key)
    const earned = income.get(province.key)
    return [
      province.name,
      `${province.terrain === 'MOUNTAINS' ? 'Mountains' : 'Plains'} · ${owner ? owner.name : 'unowned'}`,
      occupied ? `Occupied by ${nations.get(state.controllerNationId)?.name ?? 'another nation'}` : '',
      state ? `${state.city.name}: ${state.city.population.toLocaleString('en-US')} people` : '',
      mode === 'economy' && earned !== undefined ? `Income ${(earned / 1000).toLocaleString('en-US')} gold per round` : '',
      counts?.friendly
        ? `Your battalions: ${counts.friendly}${counts.friendlyMen ? ` (${counts.friendlyMen.toLocaleString('en-US')} men)` : ''}`
        : '',
      counts?.allied ? `Allied battalions: ${counts.allied}` : '',
      counts?.hostile
        ? `Enemy battalions: ${counts.hostile}${counts.hostileMen ? ` (${counts.hostileMen.toLocaleString('en-US')} men)` : ''}`
        : '',
      fortress && fortress.level > 0 ? `Fortress level ${fortress.level}${fortress.damaged ? ' (damaged)' : ''}` : '',
      besieged.has(province.key) ? 'Under siege' : '',
      target ? `Right-click to ${target === 'ATTACK' ? 'attack' : 'move'} here` : '',
    ].filter(Boolean)
  }

  function hover(event: PointerEvent, province: MapProvince) {
    if (drag.current?.moved) return
    hoverAt(province, event.clientX, event.clientY)
  }

  function hoverAt(province: MapProvince | null, clientX: number, clientY: number) {
    if (!province) {
      showHover.current(null)
      return
    }
    const rect = frameRef.current?.getBoundingClientRect()
    showHover.current({ lines: describe(province), x: clientX - (rect?.left ?? 0), y: clientY - (rect?.top ?? 0) })
  }

  function fillOf(province: MapProvince): string {
    const state = provinces.get(province.key)
    const owner = state ? nations.get(state.ownerNationId) : undefined
    switch (mode) {
      case 'diplomatic':
        return state && diplomacy ? STANDING_COLORS[diplomacy.standing(state.ownerNationId)] : '#5b6470'
      case 'terrain':
        return TERRAIN_COLORS[province.terrain]
      case 'economy':
        return INCOME_COLORS[incomeLevel(income.get(province.key), maxIncome)]
      default:
        return owner?.color ?? '#5b6470'
    }
  }

  const originProvince = origin ? byKey.get(origin) : undefined

  return (
    <div className="province-map__frame" ref={frameRef} onKeyDown={handleFrameKey}>
      <div className="province-map__controls" role="toolbar" aria-label="Map view">
        <button type="button" className="button" onClick={() => zoomBy(1.5)} disabled={zoom >= MAX_ZOOM} aria-label="Zoom in">
          +
        </button>
        <button type="button" className="button" onClick={() => zoomBy(1 / 1.5)} disabled={zoom <= MIN_ZOOM} aria-label="Zoom out">
          −
        </button>
        <button type="button" className="button" onClick={() => setView(base)} disabled={zoom <= MIN_ZOOM} aria-label="Show the whole map">
          ⤢
        </button>
        {allow3D && (
          <button
            type="button"
            className="button"
            onClick={() => {
              showHover.current(null)
              setThreeD((on) => !on)
            }}
            disabled={!canUse3D}
            aria-pressed={threeD}
            title={canUse3D ? undefined : 'Your browser has no WebGL; the 2D map is used.'}
          >
            3D
          </button>
        )}
      </div>
      <div className="province-map__modes" role="group" aria-label="Map mode">
        {MODES.filter((m) => m.key !== 'diplomatic' || diplomacy).map((m) => (
          <button
            key={m.key}
            type="button"
            className={`button province-map__mode ${mode === m.key ? 'province-map__mode--active' : ''}`}
            aria-pressed={mode === m.key}
            aria-label={m.label}
            title={m.hint}
            onClick={() => setMode(m.key)}
          >
            <Icon name={m.icon} size={16} />
          </button>
        ))}
      </div>
      {mode === 'diplomatic' && diplomacy && (
        <PerspectiveBar diplomacy={diplomacy} nations={nations} />
      )}
      <MapLegend
        mode={mode}
        reference={
          diplomacy && {
            name: nations.get(diplomacy.referenceId)?.name ?? 'this nation',
            yours: diplomacy.referenceId === diplomacy.yourNationId,
          }
        }
      />
      {threeD && allow3D ? (
        <Suspense fallback={<p className="province-map province-map--loading">Loading 3D view…</p>}>
          <Map3D
            map={map}
            fills={new Map(map.provinces.map((p) => [p.key, fillOf(p)]))}
            selectedKey={selectedKey}
            targets={targets}
            units={units}
            ownColor={ownColor}
            enemyColor={enemyColor}
            alliedColor={alliedColor}
            view={view}
            onSelect={choose}
            onCommand={onCommand}
            onSelectArmy={onSelectArmy}
            onHover={hoverAt}
            onPan={pan}
            onZoom={zoomBy}
          />
        </Suspense>
      ) : (
      <svg
        ref={svgRef}
        className="province-map"
        viewBox={`${view.x} ${view.y} ${view.w} ${view.h}`}
        role="group"
        aria-label={`Map of ${map.name}`}
        data-mode={mode}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerLeave={() => {
          onPointerUp()
          showHover.current(null)
        }}
        onDoubleClick={onDoubleClick}
      >
        <defs>
          <pattern id="terrain-mountains" width="18" height="14" patternUnits="userSpaceOnUse">
            <path d="M1 13 L6 4 L11 13 M8 13 L12 7 L17 13" fill="none" stroke="rgb(20 24 30 / 0.45)" strokeWidth="1.2" />
          </pattern>
          {[...controllers].map((nationId) => (
            <pattern
              key={nationId}
              id={`occupied-${nationId}`}
              width="10"
              height="10"
              patternUnits="userSpaceOnUse"
              patternTransform="rotate(45)"
            >
              <rect width="5" height="10" fill={nations.get(nationId)?.color ?? '#888'} />
            </pattern>
          ))}
          <marker id="arrow-move" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 Z" className="move-arrow__head" />
          </marker>
          <marker id="arrow-attack" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0 0 L10 5 L0 10 Z" className="move-arrow__head move-arrow__head--attack" />
          </marker>
        </defs>
        <rect className="province-map__sea" x={base.x - 50} y={base.y - 50} width={base.w + 100} height={base.h + 100} />

        {map.provinces.map((province) => {
          const state = provinces.get(province.key)
          const owner = state ? nations.get(state.ownerNationId) : undefined
          const occupied = state !== undefined && state.controllerNationId !== state.ownerNationId
          const controller = occupied ? nations.get(state.controllerNationId) : undefined
          const selected = province.key === selectedKey
          const counts = units.get(province.key)
          const label = [
            province.name,
            owner ? `owned by ${owner.name}` : 'unowned',
            occupied && controller ? `occupied by ${controller.name}` : null,
            province.terrain === 'MOUNTAINS' ? 'mountains' : null,
            counts?.friendly ? `${counts.friendly} of your battalions` : null,
            counts?.allied ? `${counts.allied} allied battalions` : null,
            counts?.hostile ? `${counts.hostile} enemy battalions` : null,
            targets.get(province.key) === 'ATTACK' ? 'attack target' : targets.has(province.key) ? 'move target' : null,
            fortresses.get(province.key)
              ? `fortress level ${fortresses.get(province.key)!.level}${fortresses.get(province.key)!.damaged ? ' (damaged)' : ''}`
              : null,
            besieged.has(province.key) ? 'under siege' : null,
          ]
            .filter(Boolean)
            .join(', ')
          return (
            <g key={province.key} className={`province ${selected ? 'province--selected' : ''}`}>
              <path
                d={province.svgPath}
                fill={fillOf(province)}
                className="province__shape"
                role="button"
                tabIndex={0}
                aria-label={label}
                aria-pressed={selected}
                data-province={province.key}
                data-target={targets.get(province.key)?.toLowerCase()}
                data-standing={mode === 'diplomatic' && state && diplomacy ? diplomacy.standing(state.ownerNationId).toLowerCase().replace('_', '-') : undefined}
                data-income={mode === 'economy' ? incomeLevel(income.get(province.key), maxIncome) : undefined}
                onClick={() => select(province.key)}
                onContextMenu={(event) => handleContextMenu(event, province.key)}
                onKeyDown={(event) => handleKey(event, province.key)}
                onPointerEnter={(event) => hover(event, province)}
                onPointerMove={(event) => hover(event, province)}
              />
              {province.terrain === 'MOUNTAINS' && (
                <path d={province.svgPath} fill="url(#terrain-mountains)" className="province__overlay" />
              )}
              {occupied && mode !== 'terrain' && (
                <path
                  d={province.svgPath}
                  fill={`url(#occupied-${state.controllerNationId})`}
                  className="province__overlay province__overlay--occupied"
                />
              )}
            </g>
          )
        })}

        {map.rivers.map((river) => (
          <path
            key={`${river.a}-${river.b}`}
            d={river.svgPath}
            className={`province-map__river ${mode === 'terrain' ? 'province-map__river--strong' : ''}`}
          />
        ))}

        {map.provinces
          .filter((province) => besieged.has(province.key))
          .map((province) => (
            <path key={`siege-${province.key}`} d={province.svgPath} className="province-map__siege" data-siege={province.key} />
          ))}

        {map.provinces
          .filter((province) => targets.has(province.key))
          .map((province) => (
            <path
              key={`target-${province.key}`}
              d={province.svgPath}
              className={`province-map__target ${targets.get(province.key) === 'ATTACK' ? 'province-map__target--attack' : ''}`}
            />
          ))}

        {/* Selection outline drawn last so it sits above neighbouring borders. */}
        {map.provinces
          .filter((province) => province.key === selectedKey)
          .map((province) => (
            <path key="selection" d={province.svgPath} className="province-map__selection" />
          ))}

        {map.provinces.map((province) => {
          const capital = map.nations.some((nation) => nation.capitalProvinceKey === province.key)
          return (
            <text key={`label-${province.key}`} x={province.labelX} y={province.labelY} className="province-map__label">
              {capital ? '★ ' : ''}
              {province.name}
            </text>
          )
        })}

        {map.provinces
          .filter((province) => province.cityX !== undefined && (province.cityX !== province.labelX || province.cityY !== province.labelY))
          .map((province) => {
            const capital = map.nations.some((nation) => nation.capitalProvinceKey === province.key)
            return (
              <circle
                key={`city-${province.key}`}
                cx={province.cityX}
                cy={province.cityY}
                r={capital ? 5 : 3}
                className={`province-map__city ${capital ? 'province-map__city--capital' : ''}`}
                aria-hidden="true"
              />
            )
          })}

        {map.provinces.map((province) => {
          const fortress = fortresses.get(province.key)
          if (!fortress || fortress.level < 1) return null
          const x = province.labelX
          const y = province.labelY - 30
          return (
            <g
              key={`fortress-${province.key}`}
              className={`fortress-marker ${fortress.damaged ? 'fortress-marker--damaged' : ''}`}
              data-fortress={province.key}
              aria-hidden="true"
            >
              <path d={`M${x - 10} ${y + 8} V${y - 6} h4 v3 h4 v-3 h4 v3 h4 v-3 h4 V${y + 8} Z`} />
              <text x={x} y={y + 6} className="fortress-marker__level">
                {fortress.level}
              </text>
            </g>
          )
        })}

        {/* Movement preview: arrows from the selected army to every legal destination. */}
        {originProvince &&
          [...targets.entries()].map(([key, kind]) => {
            const to = byKey.get(key)
            if (!to) return null
            const fx = originProvince.labelX
            const fy = originProvince.labelY + 24
            const dx = to.labelX - fx
            const dy = to.labelY - fy
            const length = Math.hypot(dx, dy) || 1
            const shorten = Math.min(28, length / 3)
            return (
              <line
                key={`arrow-${key}`}
                x1={fx + (dx / length) * shorten}
                y1={fy + (dy / length) * shorten}
                x2={to.labelX - (dx / length) * shorten}
                y2={to.labelY - (dy / length) * shorten}
                className={`move-arrow ${kind === 'ATTACK' ? 'move-arrow--attack' : ''}`}
                markerEnd={`url(#arrow-${kind === 'ATTACK' ? 'attack' : 'move'})`}
                data-arrow={kind.toLowerCase()}
                aria-hidden="true"
              />
            )
          })}

        {map.provinces.map((province) => {
          const counts = units.get(province.key)
          if (!counts || (counts.friendly === 0 && counts.hostile === 0 && !counts.allied)) return null
          const markers = [
            counts.friendly > 0 ? { kind: 'friendly', count: counts.friendly, men: counts.friendlyMen, color: ownColor } : null,
            counts.allied ? { kind: 'allied', count: counts.allied, men: counts.alliedMen, color: alliedColor } : null,
            counts.hostile > 0 ? { kind: 'hostile', count: counts.hostile, men: counts.hostileMen, color: enemyColor } : null,
          ].filter((m) => m !== null)
          return (
            <g key={`units-${province.key}`} className="unit-markers">
              {markers.map((marker, index) => {
                const x = province.labelX - (markers.length - 1) * 20 + index * 40
                const y = province.labelY + 24
                // The viewer's own marker selects that army; the others are pure decoration.
                const selectable = marker.kind === 'friendly' && onSelectArmy !== undefined
                const selectedArmy = marker.kind === 'friendly' && origin === province.key
                return (
                  <g
                    key={marker.kind}
                    className={`unit-marker unit-marker--${marker.kind} ${selectable ? 'unit-marker--selectable' : ''} ${selectedArmy ? 'unit-marker--selected' : ''}`}
                    data-units={marker.kind}
                    {...(selectable
                      ? {
                          role: 'button',
                          tabIndex: 0,
                          'aria-label': `Select your army in ${province.name} (${marker.count} ${marker.count === 1 ? 'battalion' : 'battalions'})`,
                          'aria-pressed': selectedArmy,
                          onClick: (event: MouseEvent) => {
                            event.stopPropagation()
                            if (suppressClick.current) {
                              suppressClick.current = false
                              return
                            }
                            onSelectArmy!(province.key)
                          },
                          onKeyDown: (event: KeyboardEvent) => {
                            if (event.key === 'Enter' || event.key === ' ') {
                              event.preventDefault()
                              onSelectArmy!(province.key)
                            }
                          },
                          onContextMenu: (event: MouseEvent) => handleContextMenu(event, province.key),
                        }
                      : { 'aria-hidden': true })}
                  >
                    <rect x={x - 18} y={y - 11} width={36} height={marker.men ? 30 : 22} rx={4} fill={marker.color} />
                    <text x={x} y={y + 5} className="unit-marker__count">
                      {marker.count}
                    </text>
                    {marker.men ? (
                      <text x={x} y={y + 16} className="unit-marker__men">
                        {men(marker.men)}
                      </text>
                    ) : null}
                  </g>
                )
              })}
            </g>
          )
        })}
      </svg>
      )}
      <MapTooltip register={registerTooltip} />
    </div>
  )
}

/** Names the diplomatic map's reference nation and leads back to the viewer's own. */
function PerspectiveBar({ diplomacy, nations }: { diplomacy: DiplomaticView; nations: Map<string, NationStyle> }) {
  const reference = nations.get(diplomacy.referenceId)
  const yours = diplomacy.referenceId === diplomacy.yourNationId
  return (
    <div className="province-map__perspective" role="status" aria-label="Diplomatic perspective">
      <span className="swatch" style={{ background: reference?.color }} aria-hidden="true" />
      <span>
        Relations of <strong>{yours ? 'your nation' : (reference?.name ?? 'another nation')}</strong>
      </span>
      {!yours && diplomacy.yourNationId && (
        <button type="button" className="button button--small" onClick={() => diplomacy.onReference(diplomacy.yourNationId!)}>
          Reset to my nation
        </button>
      )}
      <span className="muted province-map__perspective-hint">Click a province to see its owner&apos;s relations.</span>
    </div>
  )
}

function MapLegend({ mode, reference }: { mode: MapMode; reference?: { name: string; yours: boolean } }) {
  const labels = standingLabels(reference?.name ?? '', reference?.yours ?? true)
  const entries: [string, string][] =
    mode === 'diplomatic'
      ? PERSPECTIVES.map((s) => [STANDING_COLORS[s], labels[s]])
      : mode === 'terrain'
        ? [
            [TERRAIN_COLORS.PLAINS, 'Plains'],
            [TERRAIN_COLORS.MOUNTAINS, 'Mountains (harder to attack)'],
            ['#5fa8d3', 'River (harder to cross under attack)'],
          ]
        : mode === 'economy'
          ? [
              [INCOME_COLORS[4], 'Your richest provinces'],
              [INCOME_COLORS[1], 'Your poorer provinces'],
              [INCOME_COLORS[0], 'Not yours or no income'],
            ]
          : []
  if (entries.length === 0) return null
  return (
    <ul className="province-map__legend" aria-label="Map legend">
      {entries.map(([color, label]) => (
        <li key={label}>
          <span className="swatch" style={{ background: color }} aria-hidden="true" />
          {label}
        </li>
      ))}
    </ul>
  )
}
