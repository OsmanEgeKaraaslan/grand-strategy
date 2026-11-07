import './atlas.css'
import type { CSSProperties } from 'react'
import { campaignMap, type AtlasProvince } from './campaignMap'

const nationByKey = new Map(campaignMap.nations.map((nation) => [nation.key, nation]))
const capitals = new Set(campaignMap.nations.map((nation) => nation.capital))

// Faction colours are mixed into the ink ground here, not with CSS color-mix(): SVG fills must be
// plain colours to paint reliably in every browser.
const INK = [0x13, 0x12, 0x0f]
function toward(hex: string, amount: number) {
  const rgb = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))
  return `rgb(${rgb.map((v, i) => Math.round(v * amount + INK[i] * (1 - amount))).join(' ')})`
}

// West-to-east sweep for the one-time "claim" animation: delay follows the label's x position.
const minX = Math.min(...campaignMap.provinces.map((p) => p.lx))
const maxX = Math.max(...campaignMap.provinces.map((p) => p.lx))

export interface AtlasProps {
  /** Nation whose provinces stay at full colour; all others recede. */
  highlight?: string | null
  /** Plays the west-to-east claim once on mount (skipped for reduced motion in CSS). */
  animate?: boolean
  onHover?: (province: AtlasProvince | null) => void
  className?: string
}

/**
 * The Grand Campaign continent drawn from its template geometry, coloured by starting owner.
 * Decorative: the signed-out pages show the real map, but nothing on it is interactive for
 * assistive technology, so the whole drawing is hidden from it.
 */
export function Atlas({ highlight = null, animate = false, onHover, className = '' }: AtlasProps) {
  return (
    <svg
      className={`atlas${animate ? ' atlas--animate' : ''}${highlight ? ' atlas--focused' : ''} ${className}`}
      viewBox={campaignMap.viewBox}
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      focusable="false"
      onMouseLeave={onHover ? () => onHover(null) : undefined}
    >
      <rect className="atlas__sea" x="-400" y="-400" width="2400" height="1900" />
      <g className="atlas__graticule">
        {Array.from({ length: 9 }, (_, i) => (
          <line key={`v${i}`} x1={(i + 1) * 160} y1="-400" x2={(i + 1) * 160} y2="1500" />
        ))}
        {Array.from({ length: 6 }, (_, i) => (
          <line key={`h${i}`} x1="-400" y1={(i + 1) * 160} x2="2000" y2={(i + 1) * 160} />
        ))}
      </g>
      <g className="atlas__land">
        {campaignMap.provinces.map((province) => {
          const nation = nationByKey.get(province.owner)
          const delay = Math.round(((province.lx - minX) / (maxX - minX)) * 900)
          const color = nation?.color ?? '#555555'
          const style = {
            '--c': color,
            '--fill': toward(color, province.terrain === 'MOUNTAINS' ? 0.58 : 0.72),
            '--d': `${delay}ms`,
          } as CSSProperties
          const dim = highlight !== null && province.owner !== highlight
          return (
            <path
              key={province.key}
              d={province.d}
              style={style}
              className={`atlas__province${province.terrain === 'MOUNTAINS' ? ' atlas__province--mountains' : ''}${dim ? ' atlas__province--dim' : ''}`}
              onMouseEnter={onHover ? () => onHover(province) : undefined}
            />
          )
        })}
      </g>
      <g className="atlas__rivers">
        {campaignMap.rivers.map((d, i) => (
          <path key={i} d={d} />
        ))}
      </g>
      <g className="atlas__capitals">
        {campaignMap.provinces
          .filter((province) => capitals.has(province.key))
          .map((province) => (
            <circle
              key={province.key}
              cx={province.cx}
              cy={province.cy}
              r="7"
              className={highlight !== null && province.owner !== highlight ? 'atlas__capital--dim' : undefined}
            />
          ))}
      </g>
    </svg>
  )
}
