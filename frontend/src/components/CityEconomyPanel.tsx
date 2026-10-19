import { describeError } from '../api/client'
import { useBuildWorkshop, useRepairWorkshop, useUpgradeWorkshop } from '../api/queries'
import type { CityEconomy, ConstructionBlocker, Workshop, WorkshopRepairBlocker, WorkshopUpgradeBlocker } from '../api/types'
import { formatGold } from '../lib/gold'
import { formatBps } from '../lib/research'
import { Alert } from './Alert'

const BLOCKER_TEXT: Record<ConstructionBlocker, string> = {
  NOT_YOUR_TURN: 'You can build on your turn.',
  NOT_OWNER: 'Only the owning nation can build here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  TIER_LOCKED: 'This tier needs civilian technology.',
  WORKSHOP_LIMIT_REACHED: 'Workshop limit reached.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

const STATUS_TEXT: Record<Workshop['status'], string> = {
  UNDER_CONSTRUCTION: 'Under construction',
  ACTIVE: 'Active',
  DAMAGED: 'Damaged',
  UNDER_REPAIR: 'Under repair',
}

/**
 * Employment, production and workshops of one city, plus Tier 1 construction. Every number and
 * every "allowed" flag comes from the server; the server re-checks the order when it arrives.
 */
export function CityEconomyPanel({
  gameId,
  city,
  maxWorkshops,
  ownedByYou,
}: {
  gameId: string
  city: CityEconomy
  maxWorkshops: number
  ownedByYou: boolean
}) {
  const build = useBuildWorkshop(gameId)
  const { employment, potentialIncome, construction } = city
  const occupied = city.incomeStatus === 'OCCUPIED'

  function handleBuild() {
    build.mutate({ cityId: city.cityId, idempotencyKey: crypto.randomUUID() })
  }

  return (
    <div className="city-economy">
      <h3>Economy</h3>
      <dl className="facts">
        <dt>Farmers</dt>
        <dd>{employment.agricultureWorkers.toLocaleString('en-US')}</dd>
        <dt>Tier 1 workers</dt>
        <dd>{employment.tier1Workers.toLocaleString('en-US')}</dd>
        {employment.tier2Workers > 0 && (
          <>
            <dt>Tier 2 workers</dt>
            <dd>{employment.tier2Workers.toLocaleString('en-US')}</dd>
          </>
        )}
        <dt>Production</dt>
        <dd>{formatGold(potentialIncome.totalMg)} gold / round</dd>
        <dt>Collected</dt>
        <dd className={occupied ? 'negative' : undefined}>{formatGold(city.collectibleIncomeMg)} gold / round</dd>
      </dl>
      {city.growth && (
        <p className="growth-note" aria-label="Population growth">
          {city.growth.eligible
            ? `Growth: +${city.growth.nextRoundGrowth.toLocaleString('en-US')} people at the end of this round (${formatBps(city.growth.rateBps)} per round); they work from next round.`
            : `No growth this round: ${city.growth.blockers
                .map((b) => (b === 'BESIEGED' ? 'the city is under siege (it loses 5% instead)' : 'the city is occupied'))
                .join(', ')}.`}
        </p>
      )}
      {occupied && (
        <p className="occupation-note">
          Occupied: the owner and the controller differ, so neither collects this city&apos;s production until it is
          liberated.
        </p>
      )}

      <h3>
        Workshops ({city.workshopCount}/{maxWorkshops})
      </h3>
      {city.workshops.length === 0 ? (
        <p className="placeholder">No workshops yet.</p>
      ) : (
        <ul className="workshop-list">
          {city.workshops.map((workshop) => (
            <li key={workshop.id} className={`workshop workshop--${workshop.status.toLowerCase()}`}>
              <span>Tier {workshop.tier} workshop</span>
              <span className="muted">
                {STATUS_TEXT[workshop.status]}
                {workshop.status === 'UNDER_CONSTRUCTION' &&
                  workshop.operationalFromRound !== null &&
                  ` · operational from round ${workshop.operationalFromRound}`}
                {workshop.status === 'DAMAGED' && ' · employs nobody until repaired'}
                {workshop.status === 'UNDER_REPAIR' &&
                  workshop.repairCompletesAfterRound !== null &&
                  ` · repaired at the end of round ${workshop.repairCompletesAfterRound}, works from round ${workshop.repairCompletesAfterRound + 1}`}
              </span>
              {workshop.upgradeTargetTier !== null && workshop.upgradeCompletesAfterRound !== null && (
                <span className="muted">
                  {' '}
                  · upgrading to Tier {workshop.upgradeTargetTier}: done at the end of round{' '}
                  {workshop.upgradeCompletesAfterRound}, Tier {workshop.upgradeTargetTier} output from round{' '}
                  {workshop.upgradeCompletesAfterRound + 1}
                </span>
              )}
              {ownedByYou && workshop.upgrade && (
                <WorkshopUpgrade
                  gameId={gameId}
                  cityId={city.cityId}
                  workshopId={workshop.id}
                  upgrade={workshop.upgrade}
                  currentIncomeMg={potentialIncome.totalMg}
                />
              )}
              {ownedByYou && workshop.repair && (
                <WorkshopRepair gameId={gameId} cityId={city.cityId} workshopId={workshop.id} repair={workshop.repair} />
              )}
            </li>
          ))}
        </ul>
      )}

      {ownedByYou && (
        <div className="construction">
          <button
            type="button"
            className="button button--primary"
            disabled={!construction.allowed || build.isPending}
            onClick={handleBuild}
          >
            {build.isPending ? 'Ordering…' : `Build Tier 1 workshop · ${formatGold(construction.costMg)} gold`}
          </button>
          <p className="muted construction__timing">
            Ready at the end of round {construction.completesAfterRound}; produces from round{' '}
            {construction.operationalFromRound}.
          </p>
          {!construction.allowed && (
            <ul className="blockers" aria-label="Why you cannot build">
              {construction.blockers.map((blocker) => (
                <li key={blocker}>{BLOCKER_TEXT[blocker]}</li>
              ))}
            </ul>
          )}
          {build.isSuccess && (
            <Alert kind="success">
              Construction started. Treasury: {formatGold(build.data.treasuryMg)} gold.
            </Alert>
          )}
          {build.error && <Alert>{describeError(build.error)}</Alert>}
        </div>
      )}
    </div>
  )
}

const REPAIR_BLOCKER_TEXT: Record<WorkshopRepairBlocker, string> = {
  NOT_YOUR_TURN: 'You can repair on your turn.',
  NOT_OWNER: 'Only the owning nation can repair here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  NOT_DAMAGED: 'Nothing to repair.',
  NOT_AVAILABLE_IN_RULESET: 'This game has no repairs.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

/** Repair button for one damaged workshop, priced and scheduled by the server. */
function WorkshopRepair({
  gameId,
  cityId,
  workshopId,
  repair,
}: {
  gameId: string
  cityId: string
  workshopId: string
  repair: NonNullable<Workshop['repair']>
}) {
  const mutation = useRepairWorkshop(gameId)
  return (
    <div className="workshop__repair">
      <button
        type="button"
        className="button"
        disabled={!repair.allowed || mutation.isPending}
        onClick={() => mutation.mutate({ cityId, buildingId: workshopId, idempotencyKey: crypto.randomUUID() })}
      >
        {mutation.isPending ? 'Ordering repair…' : `Repair · ${formatGold(repair.costMg)} gold`}
      </button>
      <span className="muted">
        {' '}
        {repair.durationRounds} round; works again from round {repair.operationalFromRound}
      </span>
      {!repair.allowed && (
        <ul className="blockers" aria-label="Why you cannot repair">
          {repair.blockers.map((b) => (
            <li key={b}>{REPAIR_BLOCKER_TEXT[b]}</li>
          ))}
        </ul>
      )}
      {mutation.error && <Alert>{describeError(mutation.error)}</Alert>}
    </div>
  )
}

const UPGRADE_BLOCKER_TEXT: Record<WorkshopUpgradeBlocker, string> = {
  NOT_YOUR_TURN: 'You can upgrade on your turn.',
  NOT_OWNER: 'Only the owning nation can upgrade here.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  TECHNOLOGY_REQUIRED: 'Requires the civilian technology that unlocks Tier 2.',
  NOT_ELIGIBLE: 'This workshop cannot be upgraded now.',
  NOT_AVAILABLE_IN_RULESET: 'This game has no upgrades.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

/** Tier 1 → 2 upgrade for one workshop, with the server's price, timing and projected production. */
function WorkshopUpgrade({
  gameId,
  cityId,
  workshopId,
  upgrade,
  currentIncomeMg,
}: {
  gameId: string
  cityId: string
  workshopId: string
  upgrade: NonNullable<Workshop['upgrade']>
  currentIncomeMg: number
}) {
  const mutation = useUpgradeWorkshop(gameId)
  return (
    <div className="workshop__upgrade">
      <button
        type="button"
        className="button"
        disabled={!upgrade.allowed || mutation.isPending}
        onClick={() => mutation.mutate({ cityId, buildingId: workshopId, idempotencyKey: crypto.randomUUID() })}
      >
        {mutation.isPending ? 'Ordering upgrade…' : `Upgrade to Tier ${upgrade.targetTier} · ${formatGold(upgrade.costMg)} gold`}
      </button>
      <span className="muted">
        {' '}
        Production {formatGold(currentIncomeMg)} → {formatGold(upgrade.potentialIncomeAfterMg)} gold from round{' '}
        {upgrade.operationalFromRound}; keeps Tier 1 output until then.
      </span>
      {!upgrade.allowed && (
        <ul className="blockers" aria-label="Why you cannot upgrade">
          {upgrade.blockers.map((b) => (
            <li key={b}>{UPGRADE_BLOCKER_TEXT[b]}</li>
          ))}
        </ul>
      )}
      {mutation.error && <Alert>{describeError(mutation.error)}</Alert>}
    </div>
  )
}
