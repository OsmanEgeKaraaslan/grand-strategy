/**
 * Formats integer milligold for display without floating point: 50000 → "50", 24500 → "24.5",
 * 250 → "0.25", 1350 → "1.35". The server's integer values stay authoritative.
 */
export function formatGold(mg: number): string {
  const negative = mg < 0
  const abs = Math.abs(Math.trunc(mg))
  const whole = Math.floor(abs / 1000).toLocaleString('en-US')
  const fraction = String(abs % 1000).padStart(3, '0').replace(/0+$/, '')
  return `${negative ? '−' : ''}${whole}${fraction ? `.${fraction}` : ''}`
}

/** Signed variant for income lines: "+50", "−4.5", "0". */
export function formatGoldDelta(mg: number): string {
  if (mg > 0) return `+${formatGold(mg)}`
  return formatGold(mg)
}

/**
 * Parses a player's gold input ("12", "12.5", "0.125") into integer milligold without floating
 * point; null when it is not a non-negative amount with at most three decimals.
 */
export function parseGoldToMg(text: string): number | null {
  const match = /^(\d{1,9})(?:\.(\d{1,3}))?$/.exec(text.trim())
  if (!match) return null
  return Number(match[1]) * 1000 + Number((match[2] ?? '').padEnd(3, '0'))
}
