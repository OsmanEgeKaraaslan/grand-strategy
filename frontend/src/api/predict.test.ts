import { describe, expect, it } from 'vitest'
import type { AllianceView, CallToArms } from './types'
import * as predict from './predict'
import {
  OSTRA,
  VARN,
  battalionDetailsFixture,
  battalionFixture,
  cityEconomyFixture,
  diplomacyFixture,
  economyFixture,
  militaryFixture,
  proposalFixture,
} from '../test/fixtures'

// Predictions show the player's own command before the server answers (D-UX1). They may only use what
// the player already sees, and the server's answer always replaces them.

describe('military predictions', () => {
  it('recruits a pending battalion and takes its civilians from the city', () => {
    const military = militaryFixture()
    const id = predict.pendingId('k1')
    const next = predict.recruit(military, { cityId: 'city-elmsford', unitType: 'INFANTRY', battalionId: id })

    const battalion = next.battalions.find((b) => b.id === id)!
    expect(battalion).toMatchObject({ provinceKey: 'elmsford', manpower: 1000, eligibility: 'RECRUITED_THIS_TURN', originCityId: 'city-elmsford' })
    expect(predict.isPendingId(battalion.id)).toBe(true)
    expect(next.upkeep).toMatchObject({ battalions: 3, soldiers: 2650 })
    const city = next.recruitment.find((c) => c.cityId === 'city-elmsford')!
    expect(city.population).toBe(7000)
    expect(city.options[0].populationAfter).toBe(6000)
    // The other city is untouched.
    expect(next.recruitment.find((c) => c.cityId === 'city-varnhold')).toBe(military.recruitment.find((c) => c.cityId === 'city-varnhold'))
  })

  it('blocks recruitment that would leave fewer civilians than the residual minimum', () => {
    const military = militaryFixture()
    const small = {
      ...military,
      recruitment: military.recruitment.map((c) => (c.cityId === 'city-elmsford' ? { ...c, population: 2500 } : c)),
    }
    const next = predict.recruit(small, { cityId: 'city-elmsford', unitType: 'INFANTRY', battalionId: 'pending-k' })
    const option = next.recruitment.find((c) => c.cityId === 'city-elmsford')!.options[0]
    expect(option.populationAfter).toBe(500)
    expect(option.allowed).toBe(false)
    expect(option.blockers).toContain('INSUFFICIENT_POPULATION')
  })

  it('replaces the pending battalion with the server one', () => {
    const pending = predict.pendingId('k1')
    const predicted = predict.recruit(militaryFixture(), { cityId: 'city-elmsford', unitType: 'INFANTRY', battalionId: pending })
    const confirmed = predict.confirmRecruit(predicted, pending, battalionFixture({ id: 'b9' }))
    expect(confirmed.battalions.map((b) => b.id)).toEqual(['b1', 'b2', 'e1', 'b9'])
  })

  it('prices replacements pro rata, rounded up', () => {
    const details = battalionDetailsFixture()
    expect(predict.reinforcementCost(details, 350)).toBe(3500)
    const odd = { ...details, reinforcement: { ...details.reinforcement, battalionManpower: 3000 } }
    expect(predict.reinforcementCost(odd, 7)).toBe(24) // 23.33 milligold rounds up
  })

  it('reinforces a battalion and its detail view', () => {
    const next = predict.reinforceDetails(battalionDetailsFixture(), 200)
    expect(next.battalion.manpower).toBe(850)
    expect(next.cohorts).toEqual([
      { originCityId: 'city-varnhold', cityName: 'Varn', provinceKey: 'varnhold', soldiers: 650 },
      { originCityId: 'city-elmsford', cityName: 'Elmsford', provinceKey: 'elmsford', soldiers: 200 },
    ])
    expect(next.reinforcement).toMatchObject({ cityPopulation: 7800, maxSoldiers: 150, missing: 150, allowed: true })
    expect(predict.reinforce(militaryFixture(), 'b2', 200).upkeep.soldiers).toBe(1850)
  })

  it('disbands a battalion and drops the army it leaves empty', () => {
    const military = predict.formArmy(militaryFixture(), { armyId: 'a1', name: ' First ', battalionIds: ['b2'] })
    expect(military.armies).toEqual([{ id: 'a1', nationId: VARN, name: 'First', provinceKey: 'elmsford', battalionIds: ['b2'] }])

    const next = predict.disband(military, 'b2')
    expect(next.battalions.map((b) => b.id)).toEqual(['b1', 'e1'])
    expect(next.armies).toEqual([])
    expect(next.upkeep).toMatchObject({ battalions: 1, soldiers: 1000 })
  })

  it('moves battalions of a pending army to the server army', () => {
    const pending = predict.pendingId('k2')
    const formed = predict.formArmy(militaryFixture(), { armyId: pending, name: 'First', battalionIds: ['b1', 'b2'] })
    const confirmed = predict.confirmArmy(formed, pending, { id: 'a7', nationId: VARN, name: 'First', provinceKey: 'elmsford', battalionIds: ['b1', 'b2'] })
    expect(confirmed.armies.map((a) => a.id)).toEqual(['a7'])
    expect(confirmed.battalions.filter((b) => b.armyId === 'a7').map((b) => b.id)).toEqual(['b1', 'b2'])
  })
})

describe('economy predictions', () => {
  it('starts a workshop and pays for it', () => {
    const economy = economyFixture()
    const next = predict.buildWorkshop(economy, 'city-elmsford', 'pending-w')
    const city = next.cities.find((c) => c.cityId === 'city-elmsford')!
    expect(next.nation.treasuryMg).toBe(economy.nation.treasuryMg - city.construction.costMg)
    expect(city.workshopCount).toBe(1)
    expect(city.workshops).toEqual([expect.objectContaining({ id: 'pending-w', status: 'UNDER_CONSTRUCTION', completesAfterRound: 1, operationalFromRound: 2 })])
    expect(city.construction.allowed).toBe(true)
  })

  it('blocks construction once the city has the most workshops allowed', () => {
    const almost = cityEconomyFixture({ cityId: 'city-elmsford', workshopCount: 5 })
    const next = predict.buildWorkshop(economyFixture({}, [almost]), 'city-elmsford', 'pending-w')
    expect(next.cities[0].construction).toMatchObject({ allowed: false, blockers: ['WORKSHOP_LIMIT_REACHED'] })
  })
})

describe('diplomacy predictions', () => {
  it('declares war and ends the peaceful ties between the two nations', () => {
    const proposed: AllianceView = {
      id: 'al1', kind: 'NORMAL', nationA: VARN, nationB: OSTRA, proposerNationId: OSTRA, status: 'PROPOSED',
      proposedRound: 3, acceptedRound: null, endedRound: null, endReason: null, expiresAfterRound: 6,
      grantsMilitaryAccess: true, canAccept: true, canEnd: false, endBlockers: [],
    }
    const next = predict.declareWar(diplomacyFixture('PEACE', { alliances: [proposed] }), { targetNationId: OSTRA, warId: 'pending-w', targetName: 'Ostran Confederacy' })

    expect(next.wars).toEqual([expect.objectContaining({ id: 'pending-w', attackerLeaderId: VARN, defenderLeaderId: OSTRA, yourSide: 'ATTACKER' })])
    expect(next.alliances[0]).toMatchObject({ status: 'CANCELLED', endReason: 'WAR', endedRound: 4 })
    expect(next.nations.find((n) => n.nationId === OSTRA)).toMatchObject({
      status: 'WAR', warId: 'pending-w', warBlockers: ['ALREADY_AT_WAR'], allianceBlockers: ['AT_WAR'],
    })
  })

  it('accepts peace: the war ends and a truce starts', () => {
    const next = predict.acceptPeace(diplomacyFixture('WAR', { proposals: [proposalFixture()] }), 'pp1')
    expect(next.proposals[0].status).toBe('ACCEPTED')
    expect(next.wars[0]).toMatchObject({ status: 'ENDED', endReason: 'PEACE_TREATY', endedRound: 4 })
    expect(next.nations.find((n) => n.nationId === OSTRA)).toMatchObject({
      status: 'PEACE', warId: null, truceUntilRound: 14, weMayEnter: false, warBlockers: ['TRUCE'],
    })
  })

  it('sums the reparations the terms move into or out of a treasury', () => {
    const terms = proposalFixture().terms
    expect(predict.goldChangeOf(terms, VARN)).toBe(20000)
    expect(predict.goldChangeOf(terms, OSTRA)).toBe(-20000)
  })

  it('declining a defensive call to arms ends the alliance and its access', () => {
    const alliance: AllianceView = {
      id: 'al1', kind: 'DEFENSIVE', nationA: VARN, nationB: OSTRA, proposerNationId: VARN, status: 'ACTIVE',
      proposedRound: 1, acceptedRound: 2, endedRound: null, endReason: null, expiresAfterRound: 4,
      grantsMilitaryAccess: true, canAccept: false, canEnd: true, endBlockers: [],
    }
    const call: CallToArms = {
      id: 'c1', warId: 'w9', callerNationId: OSTRA, allyNationId: VARN, status: 'PENDING',
      createdRound: 4, answeredRound: null, canAnswer: true, side: 'DEFENDER',
    }
    const base = diplomacyFixture('PEACE', { alliances: [alliance], calls: [call] })
    const allied = { ...base, nations: base.nations.map((n) => (n.nationId === OSTRA ? { ...n, status: 'ALLIED' as const, allianceId: 'al1', weMayEnter: true, theyMayEnter: true } : n)) }

    const next = predict.answerCall(allied, 'c1', false)
    expect(next.calls![0]).toMatchObject({ status: 'DECLINED', canAnswer: false })
    expect(next.alliances[0]).toMatchObject({ status: 'ENDED', endReason: 'TERMINATED' })
    expect(next.nations.find((n) => n.nationId === OSTRA)).toMatchObject({ status: 'PEACE', allianceId: null, weMayEnter: false, theyMayEnter: false })
  })

  it('the last approval of a general conference ends the war; an earlier one does not', () => {
    const requested = predict.requestConference(diplomacyFixture('WAR'), { conferenceId: 'cf1', warId: 'w1', kind: 'GENERAL' })
    const conference = { ...requested.conferences![0], status: 'ACTIVE' as const, currentRevision: 1, youMustDecide: true }
    const withApprovals = (ostra: 'APPROVED' | null) => ({
      ...requested,
      conferences: [{
        ...conference,
        approvals: [
          { nationId: VARN, name: 'Kingdom of Varn', npc: false, decision: null },
          { nationId: OSTRA, name: 'Ostran Confederacy', npc: true, decision: ostra },
        ],
      }],
    })

    const early = predict.decideDraft(withApprovals(null), 'cf1', true)
    expect(early.conferences![0].status).toBe('ACTIVE')
    expect(early.wars[0].status).toBe('ACTIVE')

    const last = predict.decideDraft(withApprovals('APPROVED'), 'cf1', true)
    expect(last.conferences![0].status).toBe('CONCLUDED')
    expect(last.wars[0]).toMatchObject({ status: 'ENDED', endReason: 'PEACE_TREATY' })
  })
})
