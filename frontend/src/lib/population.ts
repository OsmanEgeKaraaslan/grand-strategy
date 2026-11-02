import type { EconomyOverview, GameState, SiegeOverview } from '../api/types'

/**
 * What the next round end does to the people of the player's own cities, from the server's own
 * projections: growth of each city (economy view) minus siege attrition of each besieged city (siege
 * view). Growth and attrition never apply to the same city, so the sum is exact for round
 * processing. Changes that happen at once — recruitment, battle casualties, disbanding, peace
 * transfers — are not projected (they show in the population itself). Undefined until both views
 * have loaded.
 */
export function projectedPopulationChange(
  state: GameState,
  yourNationId: string | undefined,
  economy: EconomyOverview | undefined,
  sieges: SiegeOverview | undefined,
): { net: number; growth: number; attrition: number } | undefined {
  if (!yourNationId || !economy || !sieges) return undefined
  const owned = new Set(state.provinces.filter((p) => p.ownerNationId === yourNationId).map((p) => p.key))
  const growth = economy.cities
    .filter((c) => owned.has(c.provinceKey))
    .reduce((sum, c) => sum + (c.growth?.nextRoundGrowth ?? 0), 0)
  const attrition = (sieges.activeSieges ?? [])
    .filter((s) => owned.has(s.provinceKey))
    .reduce((sum, s) => sum + (s.nextAttrition ?? 0), 0)
  return { net: growth - attrition, growth, attrition }
}
