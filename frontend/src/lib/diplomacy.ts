import type { DiplomacyEvent, DiplomacyOverview, PeaceTerm } from '../api/types'
import { formatGold } from './gold'

/**
 * The caller's relations as the UI needs them. Display only: the server decides every move and
 * battle. Without diplomacy (older games, or while loading) every other nation counts as an enemy
 * and nothing is closed — the pre-diplomacy rule.
 */
export interface Relations {
  atWar: (nationId: string) => boolean
  mayEnter: (controllerNationId: string) => boolean
}

export function relationsOf(overview: DiplomacyOverview | undefined, yourNationId: string | undefined): Relations {
  if (!overview || !overview.available) {
    return { atWar: (id) => id !== yourNationId, mayEnter: () => true }
  }
  const byId = new Map(overview.nations.map((n) => [n.nationId, n]))
  return {
    atWar: (id) => byId.get(id)?.status === 'WAR',
    mayEnter: (id) => id === yourNationId || byId.get(id)?.weMayEnter === true,
  }
}

export interface Names {
  nation: (id: string) => string
  province: (key: string) => string
  city: (id: string) => string
}

/** One line per term, from the point of view of nobody in particular. */
export function describeTerm(term: PeaceTerm, names: Names): string {
  const from = names.nation(term.fromNationId)
  const to = names.nation(term.toNationId)
  switch (term.type) {
    case 'PROVINCE':
      return `${from} cedes ${names.province(term.provinceKey ?? '')} to ${to}`
    case 'GOLD':
      return `${from} pays ${formatGold(term.amountMg ?? 0)} gold to ${to} at once`
    case 'TRIBUTE': {
      const rate = term.tributeRateBps != null ? `${term.tributeRateBps / 100}%` : 'a share'
      const rounds = term.tributeRounds != null ? `${term.tributeRounds} rounds` : 'several rounds'
      return `${from} pays ${rate} of its gross income to ${to} every round for ${rounds}`
    }
    case 'POPULATION':
      return `${(term.people ?? 0).toLocaleString('en-US')} people move from ${names.city(term.sourceCityId ?? '')} (${from}) to ${names.city(term.destinationCityId ?? '')} (${to})`
  }
}

/** What each nation gains and loses if these terms are settled. */
export function settlementSummary(terms: PeaceTerm[], names: Names): Map<string, { gains: string[]; losses: string[] }> {
  const summary = new Map<string, { gains: string[]; losses: string[] }>()
  const entry = (id: string) => {
    let e = summary.get(id)
    if (!e) {
      e = { gains: [], losses: [] }
      summary.set(id, e)
    }
    return e
  }
  for (const t of terms) {
    const giver = entry(t.fromNationId)
    const taker = entry(t.toNationId)
    switch (t.type) {
      case 'PROVINCE':
        giver.losses.push(`Province ${names.province(t.provinceKey ?? '')}`)
        taker.gains.push(`Province ${names.province(t.provinceKey ?? '')}`)
        break
      case 'GOLD':
        giver.losses.push(`${formatGold(t.amountMg ?? 0)} gold`)
        taker.gains.push(`${formatGold(t.amountMg ?? 0)} gold`)
        break
      case 'TRIBUTE':
        giver.losses.push('Tribute from its income')
        taker.gains.push('Tribute income')
        break
      case 'POPULATION': {
        const people = `${(t.people ?? 0).toLocaleString('en-US')} people`
        giver.losses.push(`${people} from ${names.city(t.sourceCityId ?? '')}`)
        taker.gains.push(`${people} into ${names.city(t.destinationCityId ?? '')}`)
        break
      }
    }
  }
  return summary
}

/**
 * Territory, people or long-running tribute change hands, or a large payment: the UI asks for an
 * explicit confirmation before such a treaty is offered or accepted.
 */
export function isMajorSettlement(terms: PeaceTerm[]): boolean {
  return terms.some(
    (t) => t.type === 'PROVINCE' || t.type === 'POPULATION' || t.type === 'TRIBUTE' || (t.type === 'GOLD' && (t.amountMg ?? 0) >= 50_000),
  )
}

export const EVENT_LABELS: Record<string, string> = {
  WAR_DECLARED: 'declared war on',
  JOINED_WAR: 'joined the war against',
  ALLIANCE_FORMED: 'formed an alliance with',
  ALLIANCE_ENDED: 'ended its alliance with',
  PEACE_SIGNED: 'signed a peace treaty with',
  PROVINCE_CEDED: 'ceded a province to',
  REPARATIONS_PAID: 'paid reparations to',
  POPULATION_TRANSFERRED: 'transferred people to',
  TRIBUTE_PAID: 'paid tribute to',
  TRIBUTE_COMPLETED: 'finished paying tribute to',
  TRIBUTE_CANCELLED: 'stopped paying tribute to',
  FORCES_WITHDRAWN: 'withdrew battalions',
  CONTROL_RETURNED: 'regained control of a province from',
}

export function describeEvent(event: DiplomacyEvent, names: Names): string {
  const actor = event.actorNationId ? names.nation(event.actorNationId) : 'A nation'
  const target = event.targetNationId ? names.nation(event.targetNationId) : ''
  const label = EVENT_LABELS[event.kind] ?? event.kind
  const d = event.details
  switch (event.kind) {
    case 'PROVINCE_CEDED':
      return `${actor} ceded ${names.province(String(d.provinceKey))} to ${target}`
    case 'CONTROL_RETURNED':
      return `${actor} regained control of ${names.province(String(d.provinceKey))} from ${target}`
    case 'REPARATIONS_PAID':
      return `${actor} paid ${formatGold(Number(d.amountMg))} gold to ${target}`
    case 'TRIBUTE_PAID':
      return `${actor} paid ${formatGold(Number(d.paidMg))} gold tribute to ${target} (${d.round}/${d.rounds}${Number(d.paidMg) < Number(d.dueMg) ? `, ${formatGold(Number(d.dueMg))} was due` : ''})`
    case 'POPULATION_TRANSFERRED':
      return `${Number(d.people).toLocaleString('en-US')} people moved from ${names.province(String(d.fromProvinceKey))} to ${names.province(String(d.toProvinceKey))}`
    case 'FORCES_WITHDRAWN':
      return `${d.battalions} battalion(s) withdrew from territory they may no longer enter`
    default:
      return `${actor} ${label}${target ? ` ${target}` : ''}`
  }
}

export const BLOCKER_TEXT: Record<string, string> = {
  SELF: 'Not possible with your own nation.',
  UNKNOWN_NATION: 'Unknown nation.',
  ALREADY_AT_WAR: 'Already at war.',
  ALLIED: 'You are allied — end the alliance first.',
  TRUCE: 'A truce is in effect.',
  AT_WAR: 'You are at war — make peace first.',
  ALREADY_ALLIED: 'Already allied.',
  ALREADY_PROPOSED: 'A proposal is already waiting for an answer.',
  ENEMY_OF_ALLY: 'At war with one of your allies (or the reverse).',
  FIGHTING_TOGETHER: 'You fight on the same side of a war.',
}

export const VIOLATION_TEXT: Record<string, string> = {
  TOO_MANY_TERMS: 'Too many terms.',
  NOT_A_PARTY: 'Only the two war leaders can give or receive.',
  SAME_NATION: 'A nation cannot give to itself.',
  PROVINCE_NOT_FOUND: 'Unknown province.',
  PROVINCE_NOT_OWNED: 'The giving nation does not own this province.',
  PROVINCE_DUPLICATE: 'This province is already part of the treaty.',
  CAPITAL_NOT_CEDABLE: 'A capital cannot be ceded.',
  AMOUNT_NOT_POSITIVE: 'The amount must be positive.',
  AMOUNT_TOO_LARGE: 'The amount is above the limit.',
  INSUFFICIENT_GOLD: 'The paying nation does not have that much gold.',
  TRIBUTE_DUPLICATE: 'Only one tribute per treaty.',
  CITY_NOT_FOUND: 'Unknown city.',
  SOURCE_NOT_OWNED: 'The giving nation does not own the source city.',
  SOURCE_CEDED: 'The source city is ceded by the same treaty.',
  DESTINATION_NOT_OWNED: 'The destination city does not belong to the receiving nation.',
  SAME_CITY: 'Source and destination must differ.',
  PEOPLE_OUT_OF_RANGE: 'The number of people is outside the allowed range.',
  SHARE_EXCEEDED: 'Too large a share of the source city would leave.',
  RESIDUAL_TOO_LOW: 'The source city would keep too few people.',
}
