import type { ReactNode } from 'react'
import { describeError } from '../api/client'
import {
  useBuildFortress,
  useBuildInstitution,
  useBuildWorkshop,
  useCityInstitutions,
  useProvinceFortification,
  useRecruit,
  useRepairFortress,
  useRepairWorkshop,
  useUpgradeWorkshop,
} from '../api/queries'
import type {
  CityEconomy,
  CityRecruitment,
  EconomyOverview,
  FortressWorksOption,
  InstitutionType,
  MapProvince,
  MilitaryOverview,
  ProvinceFortification,
  ProvinceState,
  SiegeSummary,
  UnitType,
  Workshop,
} from '../api/types'
import {
  CONSTRUCTION_BLOCKER_TEXT,
  FORTRESS_BLOCKER_TEXT,
  INSTITUTION_BLOCKER_TEXT,
  RECRUIT_BLOCKER_TEXT,
  WORKSHOP_REPAIR_BLOCKER_TEXT,
  WORKSHOP_UPGRADE_BLOCKER_TEXT,
} from '../lib/blockers'
import { formatGold } from '../lib/gold'
import { INSTITUTION_LABELS, POOL_LABELS, formatBps } from '../lib/research'
import { UNIT_LABELS } from '../lib/units'
import { Alert } from './Alert'
import { CompactAction, FloatingWindow, Stat } from './Floating'
import { Icon, type IconName } from './icons'
import type { NationStyle, UnitCounts } from './ProvinceMap'

const UNIT_ICONS: Record<UnitType, IconName> = { INFANTRY: 'infantry', CAVALRY: 'cavalry', ARTILLERY: 'artillery' }
const INSTITUTION_ICONS: Record<InstitutionType, IconName> = {
  MILITARY_ACADEMY: 'academy',
  UNIVERSITY: 'university',
  BARRACKS: 'barracks',
}
const WORKSHOP_STATUS: Record<Workshop['status'], string> = {
  UNDER_CONSTRUCTION: 'Under construction',
  ACTIVE: 'Active',
  DAMAGED: 'Damaged',
  UNDER_REPAIR: 'Under repair',
}

interface ProvinceInspectorProps {
  gameId: string
  province: MapProvince
  state: ProvinceState | undefined
  nations: Map<string, NationStyle>
  provinceNames: Map<string, string>
  economy: EconomyOverview | undefined
  military: MilitaryOverview | undefined
  yourNationId: string | undefined
  yourTurn: boolean
  stateVersion: number
  capital: boolean
  besieged: boolean
  units: UnitCounts | undefined
  onClose: () => void
  onSelect: (provinceKey: string) => void
  onOpenForces: (provinceKey: string) => void
  /** Opens the diplomacy window of a nation (absent when the game has no diplomacy). */
  onOpenNation?: (nationId: string) => void
}

/**
 * The floating province window (map-first layout): compact statistics, then the province's actions
 * as icon buttons — buildings, recruitment, fortifications — each with its server quote and reasons
 * on hover. Every number and every "allowed" flag is the server's; it re-checks each order.
 */
export function ProvinceInspector({
  gameId,
  province,
  state,
  nations,
  provinceNames,
  economy,
  military,
  yourNationId,
  yourTurn,
  stateVersion,
  capital,
  besieged,
  units,
  onClose,
  onSelect,
  onOpenForces,
  onOpenNation,
}: ProvinceInspectorProps) {
  const city = economy?.cities.find((c) => c.provinceKey === province.key)
  const owner = state ? nations.get(state.ownerNationId) : undefined
  const controller = state ? nations.get(state.controllerNationId) : undefined
  const occupied = state !== undefined && state.ownerNationId !== state.controllerNationId
  const yours = city !== undefined && city.ownerNationId === yourNationId
  const recruitment = military?.recruitment.find((r) => r.provinceKey === province.key)
  const forces = (units?.friendly ?? 0) + (units?.allied ?? 0) + (units?.hostile ?? 0)

  return (
    <FloatingWindow
      title={province.name}
      placement="right"
      className="inspector"
      onClose={onClose}
      closeLabel="Close province"
      marker={<span className="window-swatch" style={{ background: owner?.color }} aria-hidden="true" />}
      subtitle={
        <>
          {capital && <span title="Capital">★ </span>}
          {state?.city.name ?? province.cityName}
          {owner && state && (
            <>
              {' · '}
              {onOpenNation ? (
                <button
                  type="button"
                  className="link-button nation-link"
                  title={`Diplomacy with ${owner.name}: relations, alliances, wars and actions`}
                  aria-label={`Diplomacy with ${owner.name}`}
                  onClick={() => onOpenNation(state.ownerNationId)}
                >
                  {owner.name}
                </button>
              ) : (
                <span className="muted">{owner.name}</span>
              )}
            </>
          )}
          {occupied && <span className="tag tag--warning">Occupied</span>}
          {besieged && <span className="tag tag--danger">Under siege</span>}
        </>
      }
    >
      <dl className="stat-grid">
        <Stat icon="population" label="Population" value={state ? state.city.population.toLocaleString('en-US') : '—'} hint="City population" />
        <Stat
          icon="gold"
          label="Income"
          value={city ? `${formatGold(city.collectibleIncomeMg)}/rd` : '—'}
          hint={city ? `Produces ${formatGold(city.potentialIncome.totalMg)} gold per round; the owner collects ${formatGold(city.collectibleIncomeMg)}` : 'Income'}
          tone={city && city.incomeStatus === 'OCCUPIED' ? 'negative' : undefined}
        />
        <Stat icon={province.terrain === 'MOUNTAINS' ? 'mountain' : 'plains'} label="Terrain" value={province.terrain === 'MOUNTAINS' ? 'Mountains' : 'Plains'} />
        {city && (
          <Stat
            icon="workshop"
            label="Workshops"
            value={`${city.workshopCount}/${economy!.rules.maxWorkshopsPerCity}`}
            hint={`Workshops ${city.workshopCount} of ${economy!.rules.maxWorkshopsPerCity}; ${city.employment.tier1Workers + city.employment.tier2Workers} workers employed, ${city.employment.agricultureWorkers} farmers`}
          />
        )}
      </dl>

      {occupied && (
        <p className="inspector__line">
          <Icon name="flag" size={14} /> Owner <NationName nation={owner} /> · held by <NationName nation={controller} />
        </p>
      )}

      <div className="inspector__line inspector__forces">
        <Icon name="army" size={14} />
        {forces === 0 ? (
          <span className="muted">No battalions here.</span>
        ) : (
          <>
            {units?.friendly ? <span className="force-count force-count--friendly">{units.friendly} yours</span> : null}
            {units?.allied ? <span className="force-count force-count--allied">{units.allied} allied</span> : null}
            {units?.hostile ? <span className="force-count force-count--hostile">{units.hostile} enemy</span> : null}
            <button type="button" className="link-button" onClick={() => onOpenForces(province.key)}>
              Forces in {province.name}
            </button>
          </>
        )}
      </div>

      {city && yours && (
        <ActionGroup title="Buildings">
          <WorkshopBuild gameId={gameId} city={city} />
          <Institutions gameId={gameId} cityId={city.cityId} stateVersion={stateVersion} />
        </ActionGroup>
      )}
      {city && <Workshops gameId={gameId} city={city} maxWorkshops={economy!.rules.maxWorkshopsPerCity} ownedByYou={yours} />}

      {recruitment && (
        <ActionGroup title="Recruit">
          <Recruitment gameId={gameId} city={recruitment} yourTurn={yourTurn} />
        </ActionGroup>
      )}

      <Fortification key={province.key} gameId={gameId} provinceKey={province.key} stateVersion={stateVersion} nations={nations} yourNationId={yourNationId} />

      {city ? <CityDetails city={city} /> : <p className="placeholder">Loading the economy…</p>}

      <details className="inspector__details">
        <summary>Neighbours ({province.neighbours.length})</summary>
        <ul className="neighbours">
          {province.neighbours.map((n) => (
            <li key={n.provinceKey}>
              <button type="button" className="link-button" onClick={() => onSelect(n.provinceKey)}>
                {provinceNames.get(n.provinceKey) ?? n.provinceKey}
              </button>
              {n.crossing === 'RIVER' && <span className="tag">across river</span>}
            </li>
          ))}
        </ul>
      </details>
    </FloatingWindow>
  )
}

function ActionGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="action-group" aria-label={title}>
      <h3 className="action-group__title">{title}</h3>
      <div className="action-group__body">{children}</div>
    </section>
  )
}

function NationName({ nation }: { nation: NationStyle | undefined }) {
  if (!nation) return <span className="muted">—</span>
  return (
    <span className="nation-name">
      <span className="swatch" style={{ background: nation.color }} aria-hidden="true" />
      {nation.name}
    </span>
  )
}

function WorkshopBuild({ gameId, city }: { gameId: string; city: CityEconomy }) {
  const build = useBuildWorkshop(gameId)
  const { construction } = city
  return (
    <div className="action-row">
      <CompactAction
        icon="workshop"
        label={`Build Tier 1 workshop · ${formatGold(construction.costMg)} gold`}
        title="Build a Tier 1 workshop"
        cost={formatGold(construction.costMg)}
        tone="primary"
        details={[
          `Cost ${formatGold(construction.costMg)} gold`,
          `Ready at the end of round ${construction.completesAfterRound}; produces from round ${construction.operationalFromRound}.`,
          'Employs workers who earn more than farmers.',
        ]}
        blockers={construction.allowed ? [] : construction.blockers.map((b) => CONSTRUCTION_BLOCKER_TEXT[b])}
        blockersLabel="Why you cannot build"
        disabled={!construction.allowed}
        pending={false}
        onClick={() => build.mutate({ cityId: city.cityId, idempotencyKey: crypto.randomUUID() })}
      />
      {(build.isPending || build.isSuccess) && (
        <p className="action-result" role="status">
          Construction started.{build.data && ` Treasury: ${formatGold(build.data.treasuryMg)} gold.`}
        </p>
      )}
      {build.error && <Alert>{describeError(build.error)}</Alert>}
    </div>
  )
}

/** Research institutions of one of the player's cities, with the server's prices and timings. */
function Institutions({ gameId, cityId, stateVersion }: { gameId: string; cityId: string; stateVersion: number }) {
  const query = useCityInstitutions(gameId, cityId, stateVersion)
  const build = useBuildInstitution(gameId)
  if (query.error) return <Alert>{describeError(query.error)}</Alert>
  if (!query.data || !query.data.available) return null
  return (
    <div className="action-row" role="region" aria-label="Institutions">
      {query.data.options.map((o) => {
        const effect = `+${o.pointsPerRound} ${POOL_LABELS[o.pool].toLowerCase()} points per round · upkeep ${formatGold(o.upkeepMg)} gold`
        if (o.existing) {
          const status =
            o.existing.status === 'ACTIVE'
              ? 'Active.'
              : `Under construction: ready at the end of round ${o.existing.completesAfterRound}, produces from round ${o.existing.operationalFromRound}.`
          return (
            <span key={o.type} className="compact-chip" title={`${INSTITUTION_LABELS[o.type]}: ${effect}. ${status}`}>
              <Icon name={INSTITUTION_ICONS[o.type]} size={18} />
              <span className="visually-hidden">{INSTITUTION_LABELS[o.type]}: </span>
              <span className="compact-chip__status">{status}</span>
            </span>
          )
        }
        return (
          <CompactAction
            key={o.type}
            icon={INSTITUTION_ICONS[o.type]}
            label={`Build ${INSTITUTION_LABELS[o.type]} · ${formatGold(o.costMg)} gold`}
            title={INSTITUTION_LABELS[o.type]}
            cost={formatGold(o.costMg)}
            details={[
              effect,
              `${o.durationRounds} rounds: ready at the end of round ${o.completesAfterRound}, produces from round ${o.operationalFromRound}.`,
            ]}
            blockers={o.allowed ? [] : o.blockers.map((b) => INSTITUTION_BLOCKER_TEXT[b] ?? b)}
            disabled={!o.allowed}
            pending={false}
            onClick={() => build.mutate({ cityId, type: o.type, idempotencyKey: crypto.randomUUID() })}
          />
        )
      })}
      {(build.isPending || build.isSuccess) && (
        <p className="action-result" role="status">
          Construction started.
        </p>
      )}
      {build.error && <Alert>{describeError(build.error)}</Alert>}
    </div>
  )
}

/** The city's workshops as compact chips, each with its upgrade or repair action when the server offers one. */
function Workshops({ gameId, city, maxWorkshops, ownedByYou }: { gameId: string; city: CityEconomy; maxWorkshops: number; ownedByYou: boolean }) {
  const upgrade = useUpgradeWorkshop(gameId)
  const repair = useRepairWorkshop(gameId)
  return (
    <section className="action-group" aria-label="Workshops">
      <h3 className="action-group__title">
        Workshops ({city.workshopCount}/{maxWorkshops})
      </h3>
      {city.workshops.length === 0 ? (
        <p className="muted">No workshops yet.</p>
      ) : (
        <ul className="workshop-chips">
          {city.workshops.map((w) => (
            <li key={w.id} className={`workshop-chip workshop-chip--${w.status.toLowerCase()}`}>
              <Icon name="workshop" size={14} />
              <span>Tier {w.tier} workshop</span>
              <span className="muted">{workshopStatus(w)}</span>
              {ownedByYou && w.upgrade && (
                <CompactAction
                  icon="upgrade"
                  label={`Upgrade to Tier ${w.upgrade.targetTier} · ${formatGold(w.upgrade.costMg)} gold`}
                  cost={formatGold(w.upgrade.costMg)}
                  details={[
                    `Production ${formatGold(city.potentialIncome.totalMg)} → ${formatGold(w.upgrade.potentialIncomeAfterMg)} gold from round ${w.upgrade.operationalFromRound}; keeps Tier 1 output until then.`,
                  ]}
                  blockers={w.upgrade.allowed ? [] : w.upgrade.blockers.map((b) => WORKSHOP_UPGRADE_BLOCKER_TEXT[b])}
                  blockersLabel="Why you cannot upgrade"
                  disabled={!w.upgrade.allowed}
                  pending={false}
                  onClick={() => upgrade.mutate({ cityId: city.cityId, buildingId: w.id, idempotencyKey: crypto.randomUUID() })}
                />
              )}
              {ownedByYou && w.repair && (
                <CompactAction
                  icon="repair"
                  label={`Repair · ${formatGold(w.repair.costMg)} gold`}
                  title="Repair workshop"
                  cost={formatGold(w.repair.costMg)}
                  details={[`${w.repair.durationRounds} round; works again from round ${w.repair.operationalFromRound}`]}
                  blockers={w.repair.allowed ? [] : w.repair.blockers.map((b) => WORKSHOP_REPAIR_BLOCKER_TEXT[b])}
                  blockersLabel="Why you cannot repair"
                  disabled={!w.repair.allowed}
                  pending={false}
                  onClick={() => repair.mutate({ cityId: city.cityId, buildingId: w.id, idempotencyKey: crypto.randomUUID() })}
                />
              )}
            </li>
          ))}
        </ul>
      )}
      {upgrade.error && <Alert>{describeError(upgrade.error)}</Alert>}
      {repair.error && <Alert>{describeError(repair.error)}</Alert>}
    </section>
  )
}

function workshopStatus(w: Workshop): string {
  if (w.upgradeTargetTier !== null && w.upgradeCompletesAfterRound !== null) {
    return `upgrading to Tier ${w.upgradeTargetTier}: done at the end of round ${w.upgradeCompletesAfterRound}, Tier ${w.upgradeTargetTier} output from round ${w.upgradeCompletesAfterRound + 1}`
  }
  switch (w.status) {
    case 'UNDER_CONSTRUCTION':
      return w.operationalFromRound !== null ? `operational from round ${w.operationalFromRound}` : WORKSHOP_STATUS[w.status]
    case 'DAMAGED':
      return 'Damaged · employs nobody until repaired'
    case 'UNDER_REPAIR':
      return w.repairCompletesAfterRound !== null
        ? `repaired at the end of round ${w.repairCompletesAfterRound}, works from round ${w.repairCompletesAfterRound + 1}`
        : WORKSHOP_STATUS[w.status]
    default:
      return WORKSHOP_STATUS[w.status]
  }
}

function Recruitment({ gameId, city, yourTurn }: { gameId: string; city: CityRecruitment; yourTurn: boolean }) {
  const recruit = useRecruit(gameId)
  return (
    <div className="action-row">
      {city.options.map((o) => (
        <CompactAction
          key={o.unitType}
          icon={UNIT_ICONS[o.unitType]}
          label={`Recruit ${UNIT_LABELS[o.unitType]}`}
          title={`${UNIT_LABELS[o.unitType]} battalion`}
          cost={formatGold(o.costMg)}
          details={[
            `Cost ${formatGold(o.costMg)} gold`,
            `Civilians −${o.populationCost.toLocaleString('en-US')} (city: ${city.population.toLocaleString('en-US')} → ${Math.max(o.populationAfter, 0).toLocaleString('en-US')})`,
            `Upkeep ${formatGold(o.upkeepMg)} gold per round`,
            'Defends at once; moves from your next turn.',
          ]}
          blockers={o.allowed || !yourTurn ? (yourTurn ? [] : ['You can recruit on your turn.']) : o.blockers.map((b) => RECRUIT_BLOCKER_TEXT[b])}
          disabled={!o.allowed}
          pending={false}
          onClick={() => recruit.mutate({ cityId: city.cityId, unitType: o.unitType, idempotencyKey: crypto.randomUUID() })}
        />
      ))}
      {(recruit.isPending || recruit.isSuccess) && recruit.variables && (
        <p className="action-result" role="status">
          Recruited {UNIT_LABELS[recruit.variables.unitType]} in {city.cityName}.
        </p>
      )}
      {recruit.error && <Alert>{describeError(recruit.error)}</Alert>}
    </div>
  )
}

/** Fortress and siege state of one province, with fortress construction, upgrades and repairs. */
function Fortification({
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
  if (!query.data) return null
  const data = query.data
  if (!data.fortificationsAvailable && !data.fortification) return null
  return (
    <section className="action-group fortification" aria-label="Fortification">
      <h3 className="action-group__title">Fortress</h3>
      <div className="action-group__body">
        <FortressState data={data} />
        {data.ownerNationId === yourNationId && <FortressWorks gameId={gameId} data={data} />}
      </div>
      {data.activeSiege && <SiegeState siege={data.activeSiege} data={data} nations={nations} yourNationId={yourNationId} />}
    </section>
  )
}

function FortressState({ data }: { data: ProvinceFortification }) {
  const fort = data.fortification
  if (!fort || (fort.level === 0 && fort.project)) {
    return (
      <p className="muted fortress-state">
        Unfortified: an enemy force holding this province alone takes control at the end of the round.
        {fort?.project && ` Level 1 is under construction until the end of round ${fort.project.completesAfterRound}.`}
      </p>
    )
  }
  return (
    <div className="fortress-state">
      <span className="fortress-state__level" title="Fortress level">
        <Icon name="fortress" size={14} /> Level {fort.level}
      </span>
      <meter min={0} max={fort.maxIntegrity} value={fort.integrity} aria-label="Fortress integrity" />
      <span className="muted">
        {fort.integrity} / {fort.maxIntegrity}
      </span>
      {fort.status === 'DAMAGED' && <span className="tag tag--warning">Damaged — no resistance</span>}
      {fort.project && (
        <span className="muted fortress-state__project">
          {fort.project.kind === 'REPAIR' ? 'Repair' : `Raising to level ${fort.project.targetLevel}`} completes at the end of round{' '}
          {fort.project.completesAfterRound}; effective from round {fort.project.operationalFromRound}.
        </span>
      )}
    </div>
  )
}

function FortressWorks({ gameId, data }: { gameId: string; data: ProvinceFortification }) {
  const build = useBuildFortress(gameId)
  const repair = useRepairFortress(gameId)
  const damaged = data.fortification?.status === 'DAMAGED'
  const atMax = data.upgrade?.blockers.includes('MAX_LEVEL_REACHED') ?? false
  return (
    <div className="action-row">
      {damaged && data.repair ? (
        <WorksAction
          icon="repair"
          label={`Repair fortress · ${formatGold(data.repair.costMg)} gold`}
          option={data.repair}
          pending={false}
          onClick={() => repair.mutate({ cityId: data.cityId, idempotencyKey: crypto.randomUUID() })}
        />
      ) : (
        data.upgrade &&
        !atMax && (
          <WorksAction
            icon={data.upgrade.targetLevel === 1 ? 'fortress' : 'upgrade'}
            label={`${data.upgrade.targetLevel === 1 ? 'Build fortress' : `Raise to level ${data.upgrade.targetLevel}`} · ${formatGold(data.upgrade.costMg)} gold`}
            option={data.upgrade}
            pending={false}
            onClick={() => build.mutate({ cityId: data.cityId, targetLevel: data.upgrade!.targetLevel, idempotencyKey: crypto.randomUUID() })}
          />
        )
      )}
      {atMax && !damaged && <span className="muted">The fortress is at its highest level.</span>}
      {build.error && <Alert>{describeError(build.error)}</Alert>}
      {repair.error && <Alert>{describeError(repair.error)}</Alert>}
      {(build.isPending || build.isSuccess || repair.isPending || repair.isSuccess) && (
        <p className="action-result" role="status">
          Fortress work ordered.
        </p>
      )}
    </div>
  )
}

function WorksAction({
  icon,
  label,
  option,
  pending,
  onClick,
}: {
  icon: IconName
  label: string
  option: FortressWorksOption
  pending: boolean
  onClick: () => void
}) {
  return (
    <CompactAction
      icon={icon}
      label={label}
      cost={formatGold(option.costMg)}
      details={[
        `Takes ${option.durationRounds} ${option.durationRounds === 1 ? 'round' : 'rounds'}: done at the end of round ${option.completesAfterRound}, effective from round ${option.operationalFromRound}.`,
      ]}
      blockers={option.allowed ? [] : option.blockers.map((b) => FORTRESS_BLOCKER_TEXT[b])}
      disabled={!option.allowed}
      pending={pending}
      onClick={onClick}
    />
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
      <div
        className="siege__bar"
        role="progressbar"
        aria-label="Siege progress"
        aria-valuemin={0}
        aria-valuemax={siege.requiredProgress}
        aria-valuenow={siege.progress}
      >
        <span style={{ width: `${percent}%` }} />
      </div>
      <dl className="facts facts--compact">
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
      <details className="inspector__details">
        <summary>How this siege ends</summary>
        <p className="muted">
          {artillery > 0
            ? `Artillery support: ${artillery.toLocaleString('en-US')} soldiers speed the siege, so the city starves for fewer rounds — but a fall to artillery damages the fortress and one workshop.`
            : 'No artillery: the siege advances at its slowest, the city starves for longer, but the fortress and workshops survive the fall intact.'}
        </p>
        <p className="muted">
          {defender?.name ?? 'The defender'} keeps control of {data.cityName} until the siege succeeds; ownership never changes by
          conquest. The siege ends if the besiegers leave or are defeated.
        </p>
      </details>
    </div>
  )
}

/** Employment, production and growth: the breakdown behind the income statistic. */
function CityDetails({ city }: { city: CityEconomy }) {
  const { employment, potentialIncome } = city
  const occupied = city.incomeStatus === 'OCCUPIED'
  return (
    <details className="inspector__details" open={occupied}>
      <summary>Economy</summary>
      <dl className="facts facts--compact">
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
          Occupied: the owner and the controller differ, so neither collects this city&apos;s production until it is liberated.
        </p>
      )}
    </details>
  )
}
