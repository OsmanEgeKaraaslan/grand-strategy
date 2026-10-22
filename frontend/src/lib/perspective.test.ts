import { describe, expect, it } from 'vitest'
import type { AllianceView, DiplomacyOverview, NationRelation, WarView } from '../api/types'
import { perspectiveOf } from './diplomacy'

const relation = (nationId: string, status: NationRelation['status'], allianceKind: NationRelation['allianceKind'] = null) =>
  ({ nationId, status, allianceKind }) as NationRelation
const alliance = (nationA: string, nationB: string, kind: AllianceView['kind'], status: AllianceView['status'] = 'ACTIVE') =>
  ({ id: `${nationA}-${nationB}`, nationA, nationB, kind, status }) as AllianceView
const participant = (nationId: string, side: 'ATTACKER' | 'DEFENDER', leftRound: number | null = null) =>
  ({ nationId, name: nationId, side, joinReason: 'DECLARED', joinedRound: 1, leftRound })

/**
 * You are "you". Public facts: a normal alliance a–b, a defensive alliance a–c, an unanswered
 * proposal a–g, and a war in which a and d attack e; f fought with e but made a separate peace.
 */
function overview(nations: NationRelation[] = []): DiplomacyOverview {
  return {
    available: true,
    yourNationId: 'you',
    nations,
    alliances: [alliance('a', 'b', 'NORMAL'), alliance('c', 'a', 'DEFENSIVE'), alliance('a', 'g', 'NORMAL', 'PROPOSED')],
    wars: [
      {
        id: 'w1',
        status: 'ACTIVE',
        participants: [participant('a', 'ATTACKER'), participant('d', 'ATTACKER'), participant('e', 'DEFENDER'), participant('f', 'DEFENDER', 3)],
      } as WarView,
      { id: 'w0', status: 'ENDED', participants: [participant('a', 'ATTACKER'), participant('h', 'DEFENDER')] } as WarView,
    ],
  } as unknown as DiplomacyOverview
}

describe('perspectiveOf (diplomatic map from any nation)', () => {
  it('tells allies by kind, enemies and same-side nations apart from the server data', () => {
    const of = perspectiveOf(overview(), 'a')
    expect(of('a')).toBe('SELF')
    expect(of('b')).toBe('ALLY')
    expect(of('c')).toBe('DEFENSIVE_ALLY')
    expect(of('d')).toBe('CO_BELLIGERENT')
    expect(of('e')).toBe('WAR')
    expect(of('f')).toBe('NEUTRAL') // left the war by separate peace
    expect(of('g')).toBe('NEUTRAL') // a proposal is not an alliance
    expect(of('h')).toBe('NEUTRAL') // that war has ended
  })

  it('never infers an alliance from fighting together, and sees the war from the other side', () => {
    const ofE = perspectiveOf(overview(), 'e')
    expect(ofE('a')).toBe('WAR')
    expect(ofE('d')).toBe('WAR')
    expect(ofE('b')).toBe('NEUTRAL')
    expect(perspectiveOf(overview(), 'd')('a')).toBe('CO_BELLIGERENT')
  })

  it('uses the server’s own relations for the viewer', () => {
    const own = [relation('x', 'WAR'), relation('y', 'ALLIED', 'DEFENSIVE'), relation('z', 'ALLIED', 'NORMAL'), relation('p', 'PEACE')]
    const of = perspectiveOf(overview(own), 'you')
    expect(of('you')).toBe('SELF')
    expect(of('x')).toBe('WAR')
    expect(of('y')).toBe('DEFENSIVE_ALLY')
    expect(of('z')).toBe('ALLY')
    expect(of('p')).toBe('NEUTRAL')
  })
})
