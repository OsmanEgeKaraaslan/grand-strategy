import { describeError } from '../api/client'
import { useBuildFortress, useProvinceFortification, useRepairFortress } from '../api/queries'
import type { FortressBlocker, FortressWorksOption, ProvinceFortification, SiegeSummary } from '../api/types'
import { formatGold } from '../lib/gold'
import { Alert } from './Alert'
import type { NationStyle } from './ProvinceMap'

const FORTRESS_BLOCKER_TEXT: Record<FortressBlocker, string> = {
  NOT_YOUR_TURN: 'You can order fortress work on your turn.',
  NOT_OWNER: 'Only the nation that legally owns this city can fortify it.',
  OCCUPIED: 'Occupied by another nation — liberate it first.',
  BESIEGED: 'The city is under siege.',
  PROJECT_IN_PROGRESS: 'Another fortress project is under way.',
  FORTRESS_DAMAGED: 'Repair the fortress first.',
  NOT_DAMAGED: 'The fortress is intact.',
  MAX_LEVEL_REACHED: 'Already at the highest level.',
  NOT_AVAILABLE_IN_RULESET: 'This game has no fortifications.',
  IN_ARREARS: 'Your nation is in arrears.',
  INSUFFICIENT_GOLD: 'Not enough gold in the treasury.',
}

/**
 * Fortress and siege state of one province, with fortress construction, upgrades and repairs.
 * Progress, estimates, prices and reasons are the server's; this only displays them.
 */
export function FortificationPanel({
  gameId,
  provinceKey,
  stateVersion,
  nations,
  yourNationId,
}: {
  gameId: string
  provinceKey: string
  stateVersion: number
  nations: Map<string, NationStyle>
  yourNationId: string | undefined
}) {
  const query = useProvinceFortification(gameId, provinceKey, stateVersion)
  if (query.error) return <Alert>{describeError(query.error)}</Alert>
  if (!query.data) return <p className="placeholder">Loading fortifications…</p>
  const data = query.data
  if (!data.fortificationsAvailable && !data.fortification) return null
  return (
    <section className="fortification" aria-label="Fortification">
      <h3>Fortress</h3>
      <FortressState data={data} />
      {data.activeSiege && (
        <SiegeState siege={data.activeSiege} data={data} nations={nations} yourNationId={yourNationId} />
      )}
      {data.ownerNationId === yourNationId && <FortressWorks gameId={gameId} data={data} />}
    </section>
  )
}

function FortressState({ data }: { data: ProvinceFortification }) {
  const fort = data.fortification
  if (!fort || (fort.level === 0 && fort.project)) {
    return (
      <p className="muted">
        Unfortified: an enemy force holding this province alone takes control at the end of the round.
        {fort?.project && ` Level 1 is under construction until the end of round ${fort.project.completesAfterRound}.`}
      </p>
    )
  }
  return (
    <>
      <dl className="facts">
        <dt>Level</dt>
        <dd>
          {fort.level}
          {fort.status === 'DAMAGED' && <span className="tag tag--warning">Damaged — no resistance</span>}
        </dd>
        <dt>Integrity</dt>
        <dd>
          {fort.integrity} / {fort.maxIntegrity}
          <meter min={0} max={fort.maxIntegrity} value={fort.integrity} aria-label="Fortress integrity" />
        </dd>
      </dl>
      {fort.project && (
        <p className="muted">
          {fort.project.kind === 'REPAIR' ? 'Repair' : `Raising to level ${fort.project.targetLevel}`} completes at the end
          of round {fort.project.completesAfterRound}; effective from round {fort.project.operationalFromRound}.
        </p>
      )}
      {fort.status === 'DAMAGED' && (
        <p className="muted">A damaged fortress offers no resistance: an enemy holding the province takes it at round end.</p>
      )}
    </>
  )
}

function SiegeState({
  siege,
  data,
  nations,
  yourNationId,
}: {
  siege: SiegeSummary
  data: ProvinceFortification
  nations: Map<string, NationStyle>
  yourNationId: string | undefined
}) {
  const besieger = nations.get(siege.besiegerNationId)
  const defender = nations.get(siege.defenderNationId)
  const percent = Math.min(100, Math.floor((siege.progress * 100) / siege.requiredProgress))
  const artillery = siege.artilleryManpower ?? 0
  return (
    <div className="siege" role="region" aria-label="Siege">
      <h4>
        <span className="siege__icon" aria-hidden="true" />
        Under siege by {besieger?.name ?? 'an enemy'}
        {siege.besiegerNationId === yourNationId && <span className="tag tag--you">Your siege</span>}
      </h4>
      <div className="siege__bar" role="progressbar" aria-label="Siege progress" aria-valuemin={0}
        aria-valuemax={siege.requiredProgress} aria-valuenow={siege.progress}>
        <span style={{ width: `${percent}%` }} />
      </div>
      <dl className="facts">
        <dt>Progress</dt>
        <dd>
          {siege.progress} / {siege.requiredProgress} ({percent}%)
        </dd>
        <dt>Per round</dt>
        <dd>+{siege.ratePerRound ?? 0}</dd>
        <dt>Rounds left</dt>
        <dd>{siege.estimatedRoundsLeft === null ? '—' : `about ${siege.estimatedRoundsLeft}`}</dd>
        <dt>Civilians lost</dt>
        <dd>
          {siege.populationLost.toLocaleString('en-US')} so far
          {siege.nextAttrition !== null && ` · −${siege.nextAttrition.toLocaleString('en-US')} next round (5%)`}
        </dd>
      </dl>
      <p className="muted">
        {artillery > 0
          ? `Artillery support: ${artillery.toLocaleString('en-US')} soldiers speed the siege, so the city starves for fewer rounds — but a fall to artillery damages the fortress and one workshop.`
          : 'No artillery: the siege advances at its slowest, the city starves for longer, but the fortress and workshops survive the fall intact.'}
      </p>
      <p className="muted">
        {defender?.name ?? 'The defender'} keeps control of {data.cityName} until the siege succeeds; ownership never
        changes by conquest. The siege ends if the besiegers leave or are defeated.
      </p>
    </div>
  )
}

function FortressWorks({ gameId, data }: { gameId: string; data: ProvinceFortification }) {
  const build = useBuildFortress(gameId)
  const repair = useRepairFortress(gameId)
  const fort = data.fortification
  const damaged = fort?.status === 'DAMAGED'
  return (
    <div className="fortress-works">
      {damaged && data.repair ? (
        <WorksButton
          label={`Repair fortress · ${formatGold(data.repair.costMg)} gold`}
          option={data.repair}
          pending={repair.isPending}
          onClick={() => repair.mutate({ cityId: data.cityId, idempotencyKey: crypto.randomUUID() })}
        />
      ) : (
        data.upgrade &&
        !data.upgrade.blockers.includes('MAX_LEVEL_REACHED') && (
          <WorksButton
            label={`${data.upgrade.targetLevel === 1 ? 'Build fortress' : `Raise to level ${data.upgrade.targetLevel}`} · ${formatGold(data.upgrade.costMg)} gold`}
            option={data.upgrade}
            pending={build.isPending}
            onClick={() =>
              build.mutate({ cityId: data.cityId, targetLevel: data.upgrade!.targetLevel, idempotencyKey: crypto.randomUUID() })
            }
          />
        )
      )}
      {data.upgrade?.blockers.includes('MAX_LEVEL_REACHED') && !damaged && (
        <p className="muted">The fortress is at its highest level.</p>
      )}
      {build.error && <Alert>{describeError(build.error)}</Alert>}
      {repair.error && <Alert>{describeError(repair.error)}</Alert>}
      {(build.isSuccess || repair.isSuccess) && <Alert kind="success">Fortress work ordered.</Alert>}
    </div>
  )
}

function WorksButton({
  label,
  option,
  pending,
  onClick,
}: {
  label: string
  option: FortressWorksOption
  pending: boolean
  onClick: () => void
}) {
  return (
    <>
      <button type="button" className="button" disabled={!option.allowed || pending} onClick={onClick}>
        {pending ? 'Ordering…' : label}
      </button>
      <p className="muted">
        Takes {option.durationRounds} {option.durationRounds === 1 ? 'round' : 'rounds'}: done at the end of round{' '}
        {option.completesAfterRound}, effective from round {option.operationalFromRound}.
      </p>
      {!option.allowed && (
        <ul className="blockers" aria-label="Why this is not possible">
          {option.blockers.map((b) => (
            <li key={b}>{FORTRESS_BLOCKER_TEXT[b]}</li>
          ))}
        </ul>
      )}
    </>
  )
}
