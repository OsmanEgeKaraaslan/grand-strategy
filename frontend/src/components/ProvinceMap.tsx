import type { KeyboardEvent } from 'react'
import type { GameMap, ProvinceState } from '../api/types'

export interface NationStyle {
  name: string
  color: string
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
  /** Where the selected battalions could move. */
  targets?: Set<string>
  /** Colours of the viewer's and the enemy's unit markers. */
  ownColor?: string
  enemyColor?: string
  /** Fortress level per province key (damaged fortresses are shown broken). */
  fortresses?: Map<string, { level: number; damaged: boolean }>
  /** Provinces whose city is under an active siege. */
  besieged?: Set<string>
}

export interface UnitCounts {
  friendly: number
  hostile: number
}

/**
 * Renders the province map as SVG from the template geometry, coloured by the game's real
 * ownership. Occupied provinces (controller ≠ owner) get the controller's colour as hatching.
 * Provinces are keyboard-focusable buttons.
 */
export function ProvinceMap({
  map,
  provinces,
  nations,
  selectedKey,
  onSelect,
  units = new Map(),
  targets = new Set(),
  ownColor = '#e6e1d6',
  enemyColor = '#e0685c',
  fortresses = new Map(),
  besieged = new Set(),
}: ProvinceMapProps) {
  const controllers = new Set(
    [...provinces.values()].filter((p) => p.controllerNationId !== p.ownerNationId).map((p) => p.controllerNationId),
  )

  function handleKey(event: KeyboardEvent, key: string) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onSelect(key)
    }
  }

  return (
    <svg className="province-map" viewBox={map.viewBox} role="group" aria-label={`Map of ${map.name}`}>
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
      </defs>
      <rect className="province-map__sea" x="-50" y="-50" width="1100" height="740" />

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
          counts?.hostile ? `${counts.hostile} enemy battalions` : null,
          targets.has(province.key) ? 'move target' : null,
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
              fill={owner?.color ?? '#5b6470'}
              className="province__shape"
              role="button"
              tabIndex={0}
              aria-label={label}
              aria-pressed={selected}
              data-province={province.key}
              onClick={() => onSelect(province.key)}
              onKeyDown={(event) => handleKey(event, province.key)}
            />
            {province.terrain === 'MOUNTAINS' && (
              <path d={province.svgPath} fill="url(#terrain-mountains)" className="province__overlay" />
            )}
            {occupied && (
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
        <path key={`${river.a}-${river.b}`} d={river.svgPath} className="province-map__river" />
      ))}

      {map.provinces
        .filter((province) => besieged.has(province.key))
        .map((province) => (
          <path key={`siege-${province.key}`} d={province.svgPath} className="province-map__siege" data-siege={province.key} />
        ))}

      {map.provinces
        .filter((province) => targets.has(province.key))
        .map((province) => (
          <path key={`target-${province.key}`} d={province.svgPath} className="province-map__target" />
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

      {map.provinces.map((province) => {
        const counts = units.get(province.key)
        if (!counts || (counts.friendly === 0 && counts.hostile === 0)) return null
        const markers = [
          counts.friendly > 0 ? { kind: 'friendly', count: counts.friendly, color: ownColor } : null,
          counts.hostile > 0 ? { kind: 'hostile', count: counts.hostile, color: enemyColor } : null,
        ].filter((m) => m !== null)
        return (
          <g key={`units-${province.key}`} className="unit-markers" aria-hidden="true">
            {markers.map((marker, index) => {
              const x = province.labelX - (markers.length - 1) * 16 + index * 32
              const y = province.labelY + 24
              return (
                <g key={marker.kind} className={`unit-marker unit-marker--${marker.kind}`} data-units={marker.kind}>
                  <rect x={x - 14} y={y - 10} width={28} height={20} rx={4} fill={marker.color} />
                  <text x={x} y={y + 5} className="unit-marker__count">
                    {marker.count}
                  </text>
                </g>
              )
            })}
          </g>
        )
      })}
    </svg>
  )
}
