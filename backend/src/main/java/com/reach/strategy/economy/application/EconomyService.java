package com.reach.strategy.economy.application;

import com.reach.strategy.economy.api.EconomyViews;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.economy.api.EconomyTechnology;
import com.reach.strategy.economy.api.SiegeStatus;
import com.reach.strategy.economy.domain.Building;
import com.reach.strategy.economy.domain.BuildingRepository;
import com.reach.strategy.economy.domain.EconomyReportRepository;
import com.reach.strategy.economy.domain.NationEconomy;
import com.reach.strategy.economy.domain.NationEconomyRepository;
import com.reach.strategy.economy.rules.CityEconomy;
import com.reach.strategy.economy.rules.Construction;
import com.reach.strategy.economy.rules.EconomyRules;
import com.reach.strategy.economy.rules.PopulationGrowth;
import com.reach.strategy.economy.rules.WorkshopRepairs;
import com.reach.strategy.economy.rules.WorkshopUpgrades;
import com.reach.strategy.economy.rules.WorkshopStatus;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Economy use cases for players: what their nation and every city produce, and ordering Tier 1
 * workshops. Construction runs through the game command pipeline (lock, membership, idempotency,
 * active player), so two simultaneous orders are serialized and can never overspend the treasury
 * or exceed the per-city limit.
 */
@Service
public class EconomyService {

	/** Players can build Tier 1 only; Tier 2 needs civilian technology (Phase 8). */
	static final int BUILDABLE_TIER = 1;

	private final GameAccess gameAccess;
	private final GameCommands gameCommands;
	private final EconomyRulesets rulesets;
	private final EconomyState state;
	private final NationEconomyRepository economies;
	private final BuildingRepository buildings;
	private final EconomyReportRepository reports;
	private final TreasuryService treasuries;
	private final List<SiegeStatus> siegeStatus;
	private final List<EconomyTechnology> technology;

	EconomyService(GameAccess gameAccess, GameCommands gameCommands, EconomyRulesets rulesets, EconomyState state,
			NationEconomyRepository economies, BuildingRepository buildings, EconomyReportRepository reports,
			TreasuryService treasuries, List<SiegeStatus> siegeStatus, List<EconomyTechnology> technology) {
		this.technology = List.copyOf(technology);
		this.treasuries = treasuries;
		this.siegeStatus = List.copyOf(siegeStatus);
		this.gameAccess = gameAccess;
		this.gameCommands = gameCommands;
		this.rulesets = rulesets;
		this.state = state;
		this.economies = economies;
		this.buildings = buildings;
		this.reports = reports;
	}

	// --- queries ------------------------------------------------------------------------------

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public EconomyViews.Overview overview(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		EconomyRules rules = rulesets.rules(membership.rulesetVersion());
		EconomyState.Snapshot snapshot = state.load(gameId, rules);
		WorldApi.NationState nation = snapshot.nationOfPlayer(membership.gamePlayerId());
		NationEconomy treasury = treasuryOf(nation.id());
		List<EconomyViews.City> cities = snapshot.cities()
			.stream()
			.map(city -> cityView(gameId, city, nation.id(), treasury, membership.yourTurn(), membership.round(), rules))
			.toList();
		long income = snapshot.collectibleIncomeOf(nation.id());
		long upkeep = treasuries.upkeepOf(gameId, membership.rulesetVersion(), nation.id());
		EconomyViews.LastRound lastRound = reports.latest(gameId, nation.id())
			.map(r -> new EconomyViews.LastRound(r.round(), r.grossIncomeMg(), r.upkeepMg(), r.deficitMg(),
					r.treasuryAfterMg(), r.inArrears()))
			.orElse(null);
		return new EconomyViews.Overview(gameId, membership.round(), membership.yourTurn(), rulesView(rules),
				new EconomyViews.NationEconomy(nation.id(), treasury.treasuryMg(), treasury.inArrears(), income, upkeep,
						Math.subtractExact(income, upkeep), lastRound),
				cities);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public EconomyViews.City city(CurrentPlayer me, UUID gameId, UUID cityId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		EconomyRules rules = rulesets.rules(membership.rulesetVersion());
		EconomyState.Snapshot snapshot = state.load(gameId, rules);
		EconomyState.City city = cityOf(snapshot, cityId);
		WorldApi.NationState nation = snapshot.nationOfPlayer(membership.gamePlayerId());
		return cityView(gameId, city, nation.id(), treasuryOf(nation.id()), membership.yourTurn(), membership.round(), rules);
	}

	// --- command ------------------------------------------------------------------------------

	/**
	 * Orders a workshop in one of the caller's cities. Price and timing come from the game's
	 * ruleset; the cost is deducted in the same transaction that creates the project.
	 */
	public EconomyViews.BuildResult buildWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, int tier,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "BUILD_WORKSHOP", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<EconomyViews.BuildResult> execute(GameCommands.CommandContext context) {
				EconomyRules rules = rulesets.rules(context.rulesetVersion());
				EconomyState.Snapshot snapshot = state.load(gameId, rules);
				EconomyState.City city = cityOf(snapshot, cityId);
				WorldApi.NationState nation = snapshot.nationOfPlayer(context.gamePlayerId());
				NationEconomy treasury = treasuryOf(nation.id());

				List<Construction.Blocker> blockers = Construction.blockers(new Construction.Request(nation.id(),
						city.province().ownerNationId(), city.province().controllerNationId(), true, tier,
						city.buildings().size(), treasury.treasuryMg(), treasury.inArrears(), besieged(gameId, cityId)), rules);
				if (!blockers.isEmpty()) {
					throw rejection(blockers.getFirst());
				}

				EconomyRules.WorkshopTier tierRules = rules.tier(tier);
				treasury.spend(tierRules.buildCostMg());
				Building project = buildings.save(Building.workshopProject(gameId, cityId, tier, context.round(),
						Construction.completesAfterRound(context.round(), tierRules.buildDurationRounds()),
						tierRules.buildCostMg(), context.now()));
				economies.saveAndFlush(treasury);
				return new GameCommands.Outcome<>(result(gameId, cityId, project, nation.id(), context, rules),
						project.id());
			}

			@Override
			public EconomyViews.BuildResult replay(GameCommands.CommandContext context, UUID resultRef) {
				EconomyRules rules = rulesets.rules(context.rulesetVersion());
				Building project = buildings.findByGameIdAndId(gameId, resultRef)
					.orElseThrow(() -> new IllegalStateException("Replayed workshop " + resultRef + " is missing"));
				WorldApi.NationState nation = state.load(gameId, rules).nationOfPlayer(context.gamePlayerId());
				return result(gameId, project.cityId(), project, nation.id(), context, rules);
			}

		});
	}

	private EconomyViews.BuildResult result(UUID gameId, UUID cityId, Building project, UUID nationId,
			GameCommands.CommandContext context, EconomyRules rules) {
		EconomyState.Snapshot snapshot = state.load(gameId, rules);
		NationEconomy treasury = treasuryOf(nationId);
		boolean stillYourTurn = true;
		return new EconomyViews.BuildResult(workshopView(project), treasury.treasuryMg(),
				cityView(gameId, cityOf(snapshot, cityId), nationId, treasury, stillYourTurn, context.round(), rules));
	}

	// --- views --------------------------------------------------------------------------------

	private EconomyViews.City cityView(UUID gameId, EconomyState.City city, UUID viewerNationId, NationEconomy viewerTreasury,
			boolean yourTurn, int round, EconomyRules rules) {
		CityEconomy.Income income = city.income();
		CityEconomy.Production production = income.production();
		EconomyRules.WorkshopTier buildable = rules.tier(BUILDABLE_TIER);
		List<String> blockers = Construction
			.blockers(new Construction.Request(viewerNationId, city.province().ownerNationId(),
					city.province().controllerNationId(), yourTurn, BUILDABLE_TIER, city.buildings().size(),
					viewerTreasury.treasuryMg(), viewerTreasury.inArrears(), besieged(gameId, city.cityId())), rules)
			.stream()
			.map(Enum::name)
			.toList();
		int completes = Construction.completesAfterRound(Math.max(round, 1), buildable.buildDurationRounds());
		return new EconomyViews.City(city.cityId(), city.province().provinceKey(), city.province().cityName(),
				production.population(), city.province().ownerNationId(), city.province().controllerNationId(),
				income.status().name(),
				new EconomyViews.Employment(production.agricultureWorkers(), production.workersOfTier(1),
						production.workersOfTier(2)),
				new EconomyViews.PotentialIncome(production.agricultureIncomeMg(), production.incomeOfTierMg(1),
						production.incomeOfTierMg(2), production.potentialIncomeMg()),
				income.collectibleIncomeMg(),
				city.buildings()
					.stream()
					.map(b -> workshopView(b, repairOption(gameId, b, city, viewerNationId, viewerTreasury, yourTurn, round,
							rules), upgradeOption(gameId, b, city, viewerNationId, viewerTreasury, yourTurn, round, rules)))
					.toList(),
				city.buildings().size(),
				new EconomyViews.ConstructionOption(BUILDABLE_TIER, buildable.buildCostMg(),
						buildable.buildDurationRounds(), completes, Construction.operationalFromRound(completes),
						blockers.isEmpty(), blockers),
				growthView(gameId, city, rules));
	}

	private static EconomyViews.Workshop workshopView(Building building) {
		return workshopView(building, null, null);
	}

	private static EconomyViews.Workshop workshopView(Building building, EconomyViews.RepairOption repair,
			EconomyViews.UpgradeOption upgrade) {
		Integer completes = building.completesAfterRound();
		return new EconomyViews.Workshop(building.id(), building.tier(), building.status().name(),
				building.orderedRound(), completes, completes == null ? null : Construction.operationalFromRound(completes),
				building.repairCompletesAfterRound(), repair, building.upgradeTargetTier(),
				building.upgradeCompletesAfterRound(), upgrade);
	}

	/** Repair quote for a damaged workshop; null for any other status or a ruleset without repairs. */
	private EconomyViews.RepairOption repairOption(UUID gameId, Building building, EconomyState.City city,
			UUID viewerNationId, NationEconomy treasury, boolean yourTurn, int round, EconomyRules rules) {
		if (building.status() != WorkshopStatus.DAMAGED || rules.workshopRepair() == null) {
			return null;
		}
		List<String> blockers = WorkshopRepairs.blockers(repairRequest(gameId, building, city, viewerNationId,
				yourTurn, treasury), rules).stream().map(Enum::name).toList();
		int completes = Construction.completesAfterRound(Math.max(round, 1), rules.workshopRepair().durationRounds());
		return new EconomyViews.RepairOption(WorkshopRepairs.cost(building.tier(), rules),
				rules.workshopRepair().durationRounds(), completes, Construction.operationalFromRound(completes),
				blockers.isEmpty(), blockers);
	}

	private WorkshopRepairs.Request repairRequest(UUID gameId, Building building, EconomyState.City city,
			UUID nationId, boolean yourTurn, NationEconomy treasury) {
		return new WorkshopRepairs.Request(nationId, city.province().ownerNationId(),
				city.province().controllerNationId(), yourTurn, besieged(gameId, city.cityId()), building.tier(),
				building.status(), treasury.treasuryMg(), treasury.inArrears());
	}

	/**
	 * Upgrade quote for an ACTIVE workshop of the upgradable tier; null otherwise or when the ruleset
	 * has no upgrades. Includes the city's production once it completes, from the same calculator.
	 */
	private EconomyViews.UpgradeOption upgradeOption(UUID gameId, Building building, EconomyState.City city,
			UUID viewerNationId, NationEconomy treasury, boolean yourTurn, int round, EconomyRules rules) {
		EconomyRules.WorkshopUpgrade upgrade = rules.workshopUpgrade();
		if (upgrade == null || building.status() != WorkshopStatus.ACTIVE || building.tier() != upgrade.fromTier()
				|| building.upgrading()) {
			return null;
		}
		List<String> blockers = WorkshopUpgrades
			.blockers(upgradeRequest(gameId, building, city, viewerNationId, yourTurn, treasury), rules)
			.stream()
			.map(Enum::name)
			.toList();
		int completes = Construction.completesAfterRound(Math.max(round, 1), upgrade.durationRounds());
		List<CityEconomy.Workshop> after = city.buildings()
			.stream()
			.map(b -> b.id().equals(building.id()) ? new CityEconomy.Workshop(upgrade.toTier(), WorkshopStatus.ACTIVE)
					: b.asWorkshop())
			.toList();
		long incomeAfter = CityEconomy.production(city.province().population(), after, rules).potentialIncomeMg();
		return new EconomyViews.UpgradeOption(upgrade.toTier(), upgrade.costMg(), upgrade.durationRounds(), completes,
				Construction.operationalFromRound(completes), incomeAfter, blockers.isEmpty(), blockers);
	}

	private WorkshopUpgrades.Request upgradeRequest(UUID gameId, Building building, EconomyState.City city,
			UUID nationId, boolean yourTurn, NationEconomy treasury) {
		int unlocked = technology.stream().mapToInt(t -> t.highestWorkshopTier(gameId, nationId)).max().orElse(1);
		return new WorkshopUpgrades.Request(nationId, city.province().ownerNationId(),
				city.province().controllerNationId(), yourTurn, besieged(gameId, city.cityId()), building.tier(),
				building.status(), building.upgrading(), unlocked, treasury.treasuryMg(), treasury.inArrears());
	}

	/** Next round's growth of a city (0 and the reasons when it does not grow). */
	private EconomyViews.Growth growthView(UUID gameId, EconomyState.City city, EconomyRules rules) {
		if (rules.populationGrowth() == null) {
			return null;
		}
		WorldApi.ProvinceState p = city.province();
		List<PopulationGrowth.Blocker> blockers = PopulationGrowth.blockers(p.ownerNationId(), p.controllerNationId(),
				besieged(gameId, city.cityId()), rules);
		int bonus = technology.stream().mapToInt(t -> t.growthBonusBps(gameId, p.ownerNationId())).max().orElse(0);
		int next = blockers.isEmpty() ? PopulationGrowth.growth(p.population(), bonus, rules) : 0;
		return new EconomyViews.Growth(blockers.isEmpty(), rules.populationGrowth().baseBps() + bonus, next,
				blockers.stream().map(Enum::name).toList());
	}

	// --- upgrade command ----------------------------------------------------------------------

	/**
	 * Upgrades a Tier 1 workshop to Tier 2: price and duration from the ruleset, paid now; it keeps
	 * producing at Tier 1 until the upgrade completes at a round end (Q-E3, Q-E8).
	 */
	public EconomyViews.UpgradeResult upgradeWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, UUID buildingId,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "UPGRADE_WORKSHOP", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<EconomyViews.UpgradeResult> execute(GameCommands.CommandContext context) {
				EconomyRules rules = rulesets.rules(context.rulesetVersion());
				EconomyState.Snapshot snapshot = state.load(gameId, rules);
				EconomyState.City city = cityOf(snapshot, cityId);
				Building building = buildings.findByGameIdAndId(gameId, buildingId)
					.filter(b -> b.cityId().equals(cityId))
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BUILDING_NOT_FOUND",
							"Building not found in this city."));
				WorldApi.NationState nation = snapshot.nationOfPlayer(context.gamePlayerId());
				NationEconomy treasury = treasuryOf(nation.id());
				List<WorkshopUpgrades.Blocker> blockers = WorkshopUpgrades
					.blockers(upgradeRequest(gameId, building, city, nation.id(), true, treasury), rules);
				if (!blockers.isEmpty()) {
					throw upgradeRejection(blockers.getFirst());
				}
				EconomyRules.WorkshopUpgrade upgrade = rules.workshopUpgrade();
				treasury.spend(upgrade.costMg());
				building.startUpgrade(upgrade.toTier(), context.round(),
						Construction.completesAfterRound(context.round(), upgrade.durationRounds()), upgrade.costMg());
				buildings.saveAndFlush(building);
				economies.saveAndFlush(treasury);
				return new GameCommands.Outcome<>(new EconomyViews.UpgradeResult(workshopView(building), upgrade.costMg(),
						treasury.treasuryMg()), building.id());
			}

			@Override
			public EconomyViews.UpgradeResult replay(GameCommands.CommandContext context, UUID resultRef) {
				EconomyRules rules = rulesets.rules(context.rulesetVersion());
				Building building = buildings.findByGameIdAndId(gameId, resultRef)
					.orElseThrow(() -> new IllegalStateException("Replayed upgrade " + resultRef + " is missing"));
				WorldApi.NationState nation = state.load(gameId, rules).nationOfPlayer(context.gamePlayerId());
				return new EconomyViews.UpgradeResult(workshopView(building), building.upgradeCostPaidMg(),
						treasuryOf(nation.id()).treasuryMg());
			}

		});
	}

	private static ApiException upgradeRejection(WorkshopUpgrades.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case NOT_OWNER -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER",
					"You can only upgrade workshops in cities your nation legally owns.");
			case OCCUPIED -> new ApiException(HttpStatus.CONFLICT, "PROVINCE_OCCUPIED",
					"This city is occupied by another nation; liberate it first.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; nothing can be built or upgraded until the siege ends.");
			case TECHNOLOGY_REQUIRED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TECHNOLOGY_REQUIRED",
					"Tier 2 workshops need civilian technology you have not researched yet.");
			case NOT_ELIGIBLE -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "WORKSHOP_NOT_UPGRADABLE",
					"Only an active Tier 1 workshop without another project can be upgraded.");
			case NOT_AVAILABLE_IN_RULESET -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"NOT_AVAILABLE_IN_RULESET", "This game's ruleset has no workshop upgrades.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot upgrade this round.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for this upgrade.");
		};
	}

	// --- repair command -----------------------------------------------------------------------

	/**
	 * Starts repairing a damaged workshop: 30% of a new one's cost (ruleset), paid now; it works
	 * again from the round after the repair completes (Q-E8). The server decides price and timing.
	 */
	public EconomyViews.RepairResult repairWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, UUID buildingId,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "REPAIR_WORKSHOP", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<EconomyViews.RepairResult> execute(GameCommands.CommandContext context) {
				EconomyRules rules = rulesets.rules(context.rulesetVersion());
				EconomyState.Snapshot snapshot = state.load(gameId, rules);
				EconomyState.City city = cityOf(snapshot, cityId);
				Building building = buildings.findByGameIdAndId(gameId, buildingId)
					.filter(b -> b.cityId().equals(cityId))
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BUILDING_NOT_FOUND",
							"Building not found in this city."));
				WorldApi.NationState nation = snapshot.nationOfPlayer(context.gamePlayerId());
				NationEconomy treasury = treasuryOf(nation.id());
				List<WorkshopRepairs.Blocker> blockers = WorkshopRepairs
					.blockers(repairRequest(gameId, building, city, nation.id(), true, treasury), rules);
				if (!blockers.isEmpty()) {
					throw repairRejection(blockers.getFirst());
				}
				long cost = WorkshopRepairs.cost(building.tier(), rules);
				treasury.spend(cost);
				building.startRepair(context.round(),
						Construction.completesAfterRound(context.round(), rules.workshopRepair().durationRounds()), cost);
				buildings.saveAndFlush(building);
				economies.saveAndFlush(treasury);
				return new GameCommands.Outcome<>(new EconomyViews.RepairResult(workshopView(building), cost,
						treasury.treasuryMg()), building.id());
			}

			@Override
			public EconomyViews.RepairResult replay(GameCommands.CommandContext context, UUID resultRef) {
				EconomyRules rules = rulesets.rules(context.rulesetVersion());
				Building building = buildings.findByGameIdAndId(gameId, resultRef)
					.orElseThrow(() -> new IllegalStateException("Replayed repair " + resultRef + " is missing"));
				WorldApi.NationState nation = state.load(gameId, rules).nationOfPlayer(context.gamePlayerId());
				return new EconomyViews.RepairResult(workshopView(building), building.repairCostPaidMg(),
						treasuryOf(nation.id()).treasuryMg());
			}

		});
	}

	private boolean besieged(UUID gameId, UUID cityId) {
		return siegeStatus.stream().anyMatch(s -> s.cityBesieged(gameId, cityId));
	}

	private static ApiException repairRejection(WorkshopRepairs.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case NOT_OWNER -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER",
					"You can only repair buildings in cities your nation legally owns.");
			case OCCUPIED -> new ApiException(HttpStatus.CONFLICT, "PROVINCE_OCCUPIED",
					"This city is occupied by another nation; nothing can be repaired until it is liberated.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; nothing can be built or repaired until the siege ends.");
			case NOT_DAMAGED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BUILDING_NOT_DAMAGED",
					"Only a damaged building can be repaired.");
			case NOT_AVAILABLE_IN_RULESET -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"NOT_AVAILABLE_IN_RULESET", "This game's ruleset has no repairs.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot repair this round.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for this repair.");
		};
	}

	private static EconomyViews.Rules rulesView(EconomyRules rules) {
		List<EconomyViews.Tier> tiers = rules.workshopTiers()
			.entrySet()
			.stream()
			.sorted(java.util.Map.Entry.comparingByKey())
			.map(entry -> new EconomyViews.Tier(entry.getKey(), entry.getValue().mgPerWorker(),
					entry.getValue().buildCostMg(), entry.getValue().buildDurationRounds(),
					entry.getValue().constructible()))
			.toList();
		return new EconomyViews.Rules(rules.version(), rules.agricultureMgPerWorker(), rules.workshopCapacity(),
				rules.maxWorkshopsPerCity(), tiers);
	}

	// --- helpers ------------------------------------------------------------------------------

	private GameAccess.Membership startedGameMembership(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = gameAccess.membership(me, gameId);
		if (!membership.started()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
		}
		return membership;
	}

	private NationEconomy treasuryOf(UUID nationId) {
		return economies.findById(nationId)
			.orElseThrow(() -> new IllegalStateException("Nation " + nationId + " has no treasury"));
	}

	private static EconomyState.City cityOf(EconomyState.Snapshot snapshot, UUID cityId) {
		EconomyState.City city = snapshot.city(cityId);
		if (city == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND", "City not found in this game.");
		}
		return city;
	}

	private static ApiException rejection(Construction.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case NOT_OWNER -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER",
					"You can only build in cities your nation legally owns.");
			case OCCUPIED -> new ApiException(HttpStatus.CONFLICT, "PROVINCE_OCCUPIED",
					"This province is occupied by another nation; nothing can be built until it is liberated.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; nothing can be built or repaired until the siege ends.");
			case TIER_LOCKED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "TIER_LOCKED",
					"This workshop tier requires technology you do not have yet.");
			case WORKSHOP_LIMIT_REACHED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "WORKSHOP_LIMIT_REACHED",
					"This city already has the maximum number of workshops.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot construct this round.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for this workshop.");
		};
	}

}
