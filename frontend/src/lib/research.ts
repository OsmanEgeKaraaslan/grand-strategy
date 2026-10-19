import type { InstitutionType, ResearchPool } from '../api/types'

/** Names only — every number shown next to them comes from the server. */
export const POOL_LABELS: Record<ResearchPool, string> = {
  MILITARY: 'Military',
  CIVILIAN: 'Civilian',
  DOCTRINE: 'Doctrine',
}

export const INSTITUTION_LABELS: Record<InstitutionType, string> = {
  MILITARY_ACADEMY: 'Military Academy',
  UNIVERSITY: 'University',
  BARRACKS: 'Barracks',
}

const DOCTRINE_LABELS: Record<string, string> = {
  MOUNTAIN_WARFARE: 'Mountain Warfare',
  RIVER_ENGINEERING: 'River Engineering',
  SIEGE_ENGINEERING: 'Siege Engineering',
  MILITARY_LOGISTICS: 'Military Logistics',
}

export function doctrineLabel(key: string): string {
  return DOCTRINE_LABELS[key] ?? key.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase())
}

/** Basis points as a percentage without floating-point noise: 1000 → "10%", 25 → "0.25%", 5000 → "50%". */
export function formatBps(bps: number): string {
  const whole = Math.trunc(bps / 100)
  const fraction = String(Math.abs(bps % 100)).padStart(2, '0').replace(/0+$/, '')
  return `${whole}${fraction ? `.${fraction}` : ''}%`
}

/** A battle multiplier as a penalty: 0.8 → "−20%", 0.825 → "−17.5%". */
export function formatPenalty(multiplier: number): string {
  const bps = Math.round((1 - multiplier) * 10_000)
  return bps === 0 ? 'none' : `−${formatBps(bps)}`
}
