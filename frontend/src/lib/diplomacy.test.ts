import { describe, expect, it } from 'vitest'
import type { DiplomacyOverview, PeaceTerm } from '../api/types'
import { describeTerm, isMajorSettlement, relationsOf, settlementSummary, type Names } from './diplomacy'
import { parseGoldToMg } from './gold'

const names: Names = {
  nation: (id) => ({ a: 'Varn', b: 'Ostra' })[id] ?? id,
  province: (key) => key.toUpperCase(),
  city: (id) => `City ${id}`,
}

describe('relationsOf', () => {
  it('treats every other nation as an enemy when the game has no diplomacy', () => {
    const r = relationsOf(undefined, 'a')
    expect(r.atWar('b')).toBe(true)
    expect(r.atWar('a')).toBe(false)
    expect(r.mayEnter('b')).toBe(true)
  })

  it('follows the server’s standing and access flags', () => {
    const overview = {
      available: true,
      nations: [
        { nationId: 'a', status: 'SELF', weMayEnter: true },
        { nationId: 'b', status: 'PEACE', weMayEnter: false },
        { nationId: 'c', status: 'ALLIED', weMayEnter: true },
      ],
    } as unknown as DiplomacyOverview
    const r = relationsOf(overview, 'a')
    expect(r.atWar('b')).toBe(false)
    expect(r.mayEnter('b')).toBe(false)
    expect(r.mayEnter('c')).toBe(true)
    expect(r.mayEnter('a')).toBe(true)
  })
})

describe('peace terms', () => {
  const terms: PeaceTerm[] = [
    { type: 'PROVINCE', fromNationId: 'b', toNationId: 'a', provinceKey: 'marrow' },
    { type: 'GOLD', fromNationId: 'b', toNationId: 'a', amountMg: 12500 },
    { type: 'POPULATION', fromNationId: 'b', toNationId: 'a', sourceCityId: '1', destinationCityId: '2', people: 1500 },
  ]

  it('describes each term and what both sides gain and lose', () => {
    expect(describeTerm(terms[0], names)).toBe('Ostra cedes MARROW to Varn')
    expect(describeTerm(terms[1], names)).toBe('Ostra pays 12.5 gold to Varn at once')
    expect(describeTerm(terms[2], names)).toBe('1,500 people move from City 1 (Ostra) to City 2 (Varn)')
    const summary = settlementSummary(terms, names)
    expect(summary.get('a')).toEqual({ gains: ['Province MARROW', '12.5 gold', '1,500 people into City 2'], losses: [] })
    expect(summary.get('b')?.losses).toHaveLength(3)
  })

  it('flags territory, people, tribute and large payments as major settlements', () => {
    expect(isMajorSettlement([])).toBe(false)
    expect(isMajorSettlement([terms[1]])).toBe(false)
    expect(isMajorSettlement([{ ...terms[1], amountMg: 50000 }])).toBe(true)
    expect(isMajorSettlement([terms[0]])).toBe(true)
    expect(isMajorSettlement([{ type: 'TRIBUTE', fromNationId: 'b', toNationId: 'a' }])).toBe(true)
  })
})

describe('parseGoldToMg', () => {
  it('parses gold into integer milligold without floating point', () => {
    expect(parseGoldToMg('12')).toBe(12000)
    expect(parseGoldToMg('12.5')).toBe(12500)
    expect(parseGoldToMg('0.125')).toBe(125)
    expect(parseGoldToMg(' 3 ')).toBe(3000)
    expect(parseGoldToMg('1.2345')).toBeNull()
    expect(parseGoldToMg('-1')).toBeNull()
    expect(parseGoldToMg('abc')).toBeNull()
  })
})
