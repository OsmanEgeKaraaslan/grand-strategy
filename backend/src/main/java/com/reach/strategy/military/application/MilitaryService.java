package com.reach.strategy.military.application;

import com.reach.strategy.military.api.MilitaryViews;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.Hostility;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.api.SiegeGate;
import com.reach.strategy.military.api.WarEvents;
import com.reach.strategy.military.domain.Army;
import com.reach.strategy.military.domain.ArmyRepository;
import com.reach.strategy.military.domain.Battalion;
import com.reach.strategy.military.domain.BattalionRepository;
import com.reach.strategy.military.domain.BattleReportRepository;
import com.reach.strategy.military.domain.CohortLedger;
import com.reach.strategy.military.rules.Battle;
import com.reach.strategy.military.rules.MilitaryRules;
import com.reach.strategy.military.rules.Movement;
import com.reach.strategy.military.rules.Recruitment;
import com.reach.strategy.military.rules.Retreat;
import com.reach.strategy.military.rules.UnitType;
import com.reach.strategy.military.rules.Upkeep;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Military use cases: recruitment, battalion movement with immediate battles, regrouping, and the
 * read models. Every mutation runs through the game command pipeline (game row lock, membership,
 * idempotency, active player), so simultaneous requests are serialized: gold and civilians are
 * spent once, a battalion moves once per turn, and a battle is fought once.
 */
@Service
public class MilitaryService {

	static final int RECENT_BATTLES = 10;

	static final int BATTLE_LIST_LIMIT = 50;

	private final GameAccess gameAccess;
	private final GameCommands gameCommands;
	private final MilitaryRulesets rulesets;
	private final WorldApi world;
	private final EconomyApi economy;
	private final BattalionRepository battalions;
	private final ArmyRepository armies;
	private final BattleReportRepository battleReports;
	private final CohortLedger cohorts;
	private final JsonMapper jsonMapper;
	private final List<SiegeGate> siegeGates;
	private final List<MilitaryModifiers> modifiers;
	private final List<Hostility> hostility;
	private final List<WarEvents> warEvents;

	MilitaryService(GameAccess gameAccess, GameCommands gameCommands, MilitaryRulesets rulesets, WorldApi world,
			EconomyApi economy, BattalionRepository battalions, ArmyRepository armies,
			BattleReportRepository battleReports, CohortLedger cohorts, JsonMapper jsonMapper,
			List<SiegeGate> siegeGates, List<MilitaryModifiers> modifiers, List<Hostility> hostility,
			List<WarEvents> warEvents) {
		this.hostility = List.copyOf(hostility);
		this.warEvents = List.copyOf(warEvents);
		this.siegeGates = List.copyOf(siegeGates);
		this.modifiers = List.copyOf(modifiers);
		this.cohorts = cohorts;
		this.gameAccess = gameAccess;
		this.gameCommands = gameCommands;
		this.rulesets = rulesets;
		this.world = world;
		this.economy = economy;
		this.battalions = battalions;
		this.armies = armies;
		this.battleReports = battleReports;
		this.jsonMapper = jsonMapper;
	}

	// --- queries ------------------------------------------------------------------------------

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MilitaryViews.Overview overview(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		MilitaryRules rules = rulesets.rules(membership.rulesetVersion());
		State state = load(gameId);
		UUID nationId = state.nationOfPlayer(membership.gamePlayerId());
		List<Battalion> all = battalions.findByGameIdOrderById(gameId);
		List<MilitaryViews.BattalionView> views = all.stream()
			.map(b -> view(b, state, nationId, MilitaryService.clock(rules, membership.turnNumber(), membership.round()), rules))
			.toList();
		EconomyApi.TreasuryState treasury = economy.treasury(nationId);
		// One query for the whole game instead of one per own province.
		Set<UUID> besieged = new java.util.HashSet<>();
		siegeGates.forEach(gate -> besieged.addAll(gate.besiegedProvinces(gameId)));
		List<MilitaryViews.CityRecruitment> recruitment = state.snapshot()
			.provinces()
			.stream()
			.filter(p -> p.ownerNationId().equals(nationId))
			.sorted(Comparator.comparing(WorldApi.ProvinceState::provinceKey))
			.map(p -> cityRecruitment(p, nationId, membership.yourTurn(), treasury, besieged.contains(p.provinceStateId()),
					rules))
			.toList();
		List<MilitaryViews.BattleSummary> recent = battleReports.recent(gameId, RECENT_BATTLES)
			.stream()
			.map(row -> summary(row, state))
			.toList();
		return new MilitaryViews.Overview(gameId, membership.round(), membership.turnNumber(), membership.yourTurn(),
				nationId, rulesView(rules), views, armyViews(gameId, all, state), upkeepView(all, nationId, rules, state.mods(nationId).upkeepReductionBps()),
				recruitment, recent, modifiersView(state.mods(nationId), rules));
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MilitaryViews.ProvinceMilitary province(CurrentPlayer me, UUID gameId, String provinceKey) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		MilitaryRules rules = rulesets.rules(membership.rulesetVersion());
		State state = load(gameId);
		UUID nationId = state.nationOfPlayer(membership.gamePlayerId());
		WorldApi.ProvinceState province = state.province(provinceKey);
		List<Battalion> all = battalions.findByGameIdOrderById(gameId);
		List<MilitaryViews.BattalionView> here = all.stream()
			.filter(b -> b.provinceStateId().equals(province.provinceStateId()))
			.map(b -> view(b, state, nationId, MilitaryService.clock(rules, membership.turnNumber(), membership.round()), rules))
			.toList();
		List<MilitaryViews.MoveTarget> targets = state.topology().neighbours(provinceKey).stream().map(key -> {
			WorldApi.ProvinceState neighbour = state.province(key);
			int hostile = (int) all.stream()
				.filter(b -> b.provinceStateId().equals(neighbour.provinceStateId())
						&& state.relations().atWar(nationId, b.nationId()))
				.count();
			return new MilitaryViews.MoveTarget(key, state.terrain(key).name(),
					state.topology().border(provinceKey, key).orElseThrow().river(), neighbour.controllerNationId(),
					hostile > 0, hostile,
					state.relations().mayEnter(nationId, neighbour.ownerNationId(), neighbour.controllerNationId()));
		}).toList();
		return new MilitaryViews.ProvinceMilitary(provinceKey, province.ownerNationId(), province.controllerNationId(),
				!province.ownerNationId().equals(province.controllerNationId()), here, targets);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public List<MilitaryViews.BattleSummary> battles(CurrentPlayer me, UUID gameId) {
		startedGameMembership(me, gameId);
		State state = load(gameId);
		return battleReports.recent(gameId, BATTLE_LIST_LIMIT).stream().map(row -> summary(row, state)).toList();
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MilitaryViews.BattleView battle(CurrentPlayer me, UUID gameId, UUID battleId) {
		startedGameMembership(me, gameId);
		State state = load(gameId);
		return battleReports.find(gameId, battleId)
			.map(row -> battleView(row, state))
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BATTLE_NOT_FOUND", "Battle not found in this game."));
	}

	// --- recruitment --------------------------------------------------------------------------

	/**
	 * Recruits a full battalion in a city: deducts the cost and exactly {@code battalionManpower}
	 * civilians and creates the battalion, all in the command transaction. It may not move or
	 * attack during this player turn (Q-M3) but defends at once.
	 */
	public MilitaryViews.RecruitResult recruit(CurrentPlayer me, UUID gameId, UUID cityId, UnitType type,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "RECRUIT", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.RecruitResult> execute(GameCommands.CommandContext context) {
				MilitaryRules rules = rulesets.rules(context.rulesetVersion());
				State state = load(gameId);
				UUID nationId = state.nationOfPlayer(context.gamePlayerId());
				WorldApi.ProvinceState province = state.snapshot()
					.provinceOfCity(cityId)
					.orElseThrow(MilitaryService::cityNotFound);
				EconomyApi.TreasuryState treasury = economy.treasury(nationId);
				List<Recruitment.Blocker> blockers = Recruitment.blockers(new Recruitment.Request(nationId,
						province.ownerNationId(), province.controllerNationId(), true, type, province.population(),
						treasury.treasuryMg(), treasury.inArrears(), besieged(gameId, province.provinceStateId())), rules);
				if (!blockers.isEmpty()) {
					throw rejection(blockers.getFirst());
				}
				long cost = rules.unit(type).recruitCostMg();
				economy.spend(nationId, cost);
				int population = world.changePopulation(gameId, cityId, -rules.battalionManpower());
				Battalion battalion = battalions.saveAndFlush(Battalion.recruit(gameId, nationId, type,
						rules.battalionManpower(), cityId, province.provinceStateId(), cost, context.round(),
						context.turnNumber(), context.now()));
				cohorts.raised(battalion);
				return new GameCommands.Outcome<>(new MilitaryViews.RecruitResult(
						view(battalion, state, nationId, MilitaryService.clock(rules, context), rules),
						economy.treasury(nationId).treasuryMg(), population), battalion.id());
			}

			@Override
			public MilitaryViews.RecruitResult replay(GameCommands.CommandContext context, UUID resultRef) {
				MilitaryRules rules = rulesets.rules(context.rulesetVersion());
				State state = load(gameId);
				UUID nationId = state.nationOfPlayer(context.gamePlayerId());
				WorldApi.ProvinceState city = state.snapshot().provinceOfCity(cityId).orElseThrow(MilitaryService::cityNotFound);
				// The battalion may since have been destroyed; the replay still reports the current treasury.
				MilitaryViews.BattalionView battalion = battalions.findById(resultRef)
					.map(b -> view(b, state, nationId, MilitaryService.clock(rules, context), rules))
					.orElse(null);
				return new MilitaryViews.RecruitResult(battalion, economy.treasury(nationId).treasuryMg(),
						city.population());
			}

		});
	}

	// --- movement and battle ------------------------------------------------------------------

	/**
	 * Moves the selected battalions one province. All of them must belong to the caller, stand in
	 * the same province and still be eligible this turn — otherwise nothing moves. Entering a
	 * province with hostile battalions fights a battle at once, in the same transaction.
	 */
	public MilitaryViews.MoveResult move(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, String toProvinceKey,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "MOVE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.MoveResult> execute(GameCommands.CommandContext context) {
				return executeMove(context, gameId, battalionIds, toProvinceKey);
			}

			@Override
			public MilitaryViews.MoveResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return replayMove(context, gameId, resultRef);
			}

		});
	}


	/**
	 * A move by the caller's nation (the commander). Ruleset v6 (Phase 10): the selected battalions may
	 * include allies' battalions attached to the commander; every nation present at war with the
	 * commander defends together; each battalion fights with its own nation's technology and
	 * doctrine; defeated defenders retreat nation by nation. Before v6 a battle has exactly one nation
	 * per side.
	 */
	private GameCommands.Outcome<MilitaryViews.MoveResult> executeMove(GameCommands.CommandContext context,
			UUID gameId, List<UUID> battalionIds, String toProvinceKey) {
		MilitaryRules rules = rulesets.rules(context.rulesetVersion());
		Movement.Clock clock = clock(rules, context);
		State state = load(gameId);
		UUID nationId = state.nationOfPlayer(context.gamePlayerId());
		List<Battalion> moving = commandedColocated(gameId, battalionIds, nationId);
		for (Battalion battalion : moving) {
			Movement.Eligibility eligibility = battalion.eligibility(clock);
			if (eligibility != Movement.Eligibility.READY) {
				throw notReady(eligibility);
			}
		}
		String fromKey = state.keyOf(moving.getFirst().provinceStateId());
		UUID origin = moving.getFirst().provinceStateId();
		WorldApi.ProvinceState destination = state.provinceOrNull(toProvinceKey);
		if (destination == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PROVINCE_NOT_FOUND", "Province not found on this map.");
		}
		Optional<WorldApi.Border> border = state.topology().border(fromKey, toProvinceKey);
		if (fromKey.equals(toProvinceKey) || border.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_ADJACENT",
					"Battalions can only move to an adjacent province.");
		}
		// Attached battalions keep their owner's diplomatic rights: each owner must be allowed in.
		Set<UUID> movingNations = moving.stream().map(Battalion::nationId).collect(Collectors.toCollection(TreeSet::new));
		for (UUID owner : movingNations) {
			if (!state.relations().mayEnter(owner, destination.ownerNationId(), destination.controllerNationId())) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NO_MILITARY_ACCESS",
						"Your battalions may not enter this province: you are not at war with its holder and have no military access.");
			}
		}

		// Only battalions of nations at war with the commander fight; allied or neutral ones stay out of it.
		List<Battalion> defenders = battalions.findByGameIdAndProvinceStateIdOrderById(gameId,
				destination.provinceStateId())
			.stream()
			.filter(b -> state.relations().atWar(nationId, b.nationId()))
			.toList();
		Set<UUID> armiesTouched = armiesOf(moving);
		if (defenders.isEmpty()) {
			leaveArmiesIfSplit(gameId, moving);
			moving.forEach(b -> b.moved(destination.provinceStateId(), clock));
			battalions.saveAllAndFlush(moving);
			deleteEmptyArmies(gameId, armiesTouched);
			// No battle was fought, so the world and the relations read above still hold: the siege module
			// reuses them instead of reading them again.
			battalions.flush();
			Set<UUID> touched = Set.of(origin, destination.provinceStateId());
			siegeGates.forEach(gate -> gate.presenceChanged(gameId, touched, context.round(), context.turnNumber(),
					context.now(), state.snapshot(), state.relations()));
			List<MilitaryViews.BattalionView> views = moving.stream().map(b -> view(b, state, nationId, clock, rules)).toList();
			return new GameCommands.Outcome<>(new MilitaryViews.MoveResult("MOVED", toProvinceKey, views, null),
					moving.getFirst().id());
		}

		Set<UUID> defenderNations = defenders.stream().map(Battalion::nationId).collect(Collectors.toCollection(TreeSet::new));
		if (defenderNations.size() != 1 && !state.relations().coalitions()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "COALITION_BATTLE_UNSUPPORTED",
					"Battalions of several enemy nations hold this province; coalition battles are not supported in this game.");
		}
		for (UUID owner : movingNations) {
			for (UUID enemy : defenderNations) {
				if (!state.relations().atWar(owner, enemy)) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ATTACHED_NOT_AT_WAR",
							"An attached battalion's nation is not at war with every defender here.");
				}
			}
		}
		UUID defenderNation = leadingNation(defenders);
		armiesTouched.addAll(armiesOf(defenders));
		boolean mountains = state.terrain(toProvinceKey) == WorldApi.Terrain.MOUNTAINS;
		Battle.Result result = Battle.resolve(new Battle.Input(fighters(moving, state), fighters(defenders, state),
				border.get().river(), mountains, state.side(nationId), state.side(defenderNation)), rules);

		Map<UUID, Integer> before = new LinkedHashMap<>();
		moving.forEach(b -> before.put(b.id(), b.manpower()));
		defenders.forEach(b -> before.put(b.id(), b.manpower()));
		Map<UUID, UUID> ownerOf = new LinkedHashMap<>();
		moving.forEach(b -> ownerOf.put(b.id(), b.nationId()));
		defenders.forEach(b -> ownerOf.put(b.id(), b.nationId()));
		Map<UUID, String> fate = new LinkedHashMap<>();
		List<Battalion> survivingAttackers = survivors(moving, result, fate);
		List<Battalion> survivingDefenders = survivors(defenders, result, fate);

		boolean attackerWon = result.winner() == Battle.Side.ATTACKER;
		Map<UUID, String> retreatByNation = new LinkedHashMap<>();
		Set<UUID> destroyedNations = new HashSet<>();
		if (attackerWon) {
			leaveArmiesIfSplit(gameId, survivingAttackers);
			survivingAttackers.forEach(b -> b.moved(destination.provinceStateId(), clock));
			battalions.saveAll(survivingAttackers);
			List<Battalion> staying = new ArrayList<>();
			// Each defeated nation retreats on its own (to a province it controls), in nation id order.
			for (UUID nation : defenderNations) {
				List<Battalion> own = survivingDefenders.stream().filter(b -> b.nationId().equals(nation)).toList();
				if (own.isEmpty()) {
					continue;
				}
				Optional<String> retreat = retreatDestination(gameId, state, toProvinceKey, nation);
				if (retreat.isPresent()) {
					retreatByNation.put(nation, retreat.get());
					UUID target = state.province(retreat.get()).provinceStateId();
					own.forEach(b -> {
						b.retreatTo(target);
						fate.put(b.id(), "RETREATED");
					});
					staying.addAll(own);
					battalions.saveAll(own);
				}
				else {
					// Q-C3: nowhere to retreat — the defeated battalions are destroyed.
					destroyedNations.add(nation);
					own.forEach(b -> {
						fate.put(b.id(), "DESTROYED");
						cohorts.removed(b);
					});
					battalions.deleteAll(own);
				}
			}
			survivingDefenders = staying;
		}
		else {
			// The attack failed: attackers stay where they were, their movement is spent.
			survivingAttackers.forEach(b -> b.moved(b.provinceStateId(), clock));
		}
		battalions.saveAll(survivingAttackers);
		battalions.saveAll(survivingDefenders);
		battalions.flush();
		deleteEmptyArmies(gameId, armiesTouched);

		List<MilitaryViews.NationSide> nations = new ArrayList<>();
		for (UUID nation : movingNations) {
			nations.add(nationSide(nation, "ATTACKER", moving, before, result, null, false, state));
		}
		for (UUID nation : defenderNations) {
			nations.add(nationSide(nation, "DEFENDER", defenders, before, result, retreatByNation.get(nation),
					destroyedNations.contains(nation), state));
		}
		MilitaryViews.BattleDetails details = new MilitaryViews.BattleDetails(fromKey, toProvinceKey,
				border.get().river(), mountains, participants(moving, before, result, fate, ownerOf),
				participants(defenders, before, result, fate, ownerOf), phases(result), result.attackerInitial(),
				result.defenderInitial(), result.attackerLosses(), result.defenderLosses(),
				result.broken() == null ? null : result.broken().name(), result.winner().name(),
				retreatByNation.get(defenderNation), destroyedNations.contains(defenderNation), result.attackerBroke(),
				result.defenderBroke(), state.mods(nationId), state.mods(defenderNation), nations);
		BattleReportRepository.Row row = new BattleReportRepository.Row(UUID.randomUUID(), context.round(),
				context.turnNumber(), destination.provinceStateId(), nationId, defenderNation, result.winner().name(),
				context.rulesetVersion(), jsonMapper.writeValueAsString(details), context.now());
		battleReports.insert(gameId, row);
		WarEvents.Battle fought = new WarEvents.Battle(row.id(), context.round(), context.turnNumber(), nationId,
				defenderNation, attackerWon, result.attackerLosses(), result.defenderLosses());
		warEvents.forEach(events -> events.battleFought(gameId, fought));
		Set<UUID> touched = new HashSet<>(Set.of(destination.provinceStateId(), origin));
		retreatByNation.values().forEach(key -> touched.add(state.province(key).provinceStateId()));
		presenceChanged(gameId, touched, context);

		List<MilitaryViews.BattalionView> views = survivingAttackers.stream()
			.map(b -> view(b, state, nationId, clock, rules))
			.toList();
		return new GameCommands.Outcome<>(new MilitaryViews.MoveResult(attackerWon ? "ATTACK_WON" : "ATTACK_REPELLED",
				toProvinceKey, views, battleView(row, state)), row.id());
	}

	/** The defending nation with the most soldiers present (ties: lowest nation id) leads the defence. */
	private static UUID leadingNation(List<Battalion> defenders) {
		Map<UUID, Integer> manpower = new TreeMap<>();
		defenders.forEach(b -> manpower.merge(b.nationId(), b.manpower(), Integer::sum));
		UUID leader = null;
		int most = -1;
		for (var entry : manpower.entrySet()) {
			if (entry.getValue() > most) {
				leader = entry.getKey();
				most = entry.getValue();
			}
		}
		return leader;
	}

	private static MilitaryViews.NationSide nationSide(UUID nation, String side, List<Battalion> all,
			Map<UUID, Integer> before, Battle.Result result, String retreatKey, boolean destroyed, State state) {
		int start = 0;
		int lost = 0;
		for (Battalion b : all) {
			if (b.nationId().equals(nation)) {
				start += before.get(b.id());
				lost += result.losses().getOrDefault(b.id(), 0);
			}
		}
		return new MilitaryViews.NationSide(nation, side, start, lost, retreatKey, destroyed, state.mods(nation));
	}

	/**
	 * The response to a retried move, rebuilt from current state: the battle if one was fought,
	 * otherwise the moved battalion's position.
	 */
	private MilitaryViews.MoveResult replayMove(GameCommands.CommandContext context, UUID gameId, UUID resultRef) {
		MilitaryRules rules = rulesets.rules(context.rulesetVersion());
		State state = load(gameId);
		UUID nationId = state.nationOfPlayer(context.gamePlayerId());
		Optional<BattleReportRepository.Row> battle = battleReports.find(gameId, resultRef);
		if (battle.isPresent()) {
			MilitaryViews.BattleView view = battleView(battle.get(), state);
			List<UUID> attackerIds = view.details().attackers().stream().map(MilitaryViews.Participant::battalionId).toList();
			List<MilitaryViews.BattalionView> survivors = battalions.findByGameIdAndIdIn(gameId, attackerIds)
				.stream()
				.sorted(Comparator.comparing(Battalion::id))
				.map(b -> view(b, state, nationId, MilitaryService.clock(rules, context), rules))
				.toList();
			return new MilitaryViews.MoveResult("ATTACKER".equals(view.winner()) ? "ATTACK_WON" : "ATTACK_REPELLED",
					view.provinceKey(), survivors, view);
		}
		return battalions.findById(resultRef)
			.map(b -> new MilitaryViews.MoveResult("MOVED", state.keyOf(b.provinceStateId()),
					List.of(view(b, state, nationId, MilitaryService.clock(rules, context), rules)), null))
			.orElseGet(() -> new MilitaryViews.MoveResult("MOVED", null, List.of(), null));
	}

	/** Judged on positions after the victorious attackers have entered the battle province. */
	private Optional<String> retreatDestination(UUID gameId, State state, String battleProvince,
			UUID retreatingNation) {
		battalions.flush();
		List<Battalion> all = battalions.findByGameIdOrderById(gameId);
		List<Retreat.Candidate> candidates = new ArrayList<>();
		for (String key : state.topology().neighbours(battleProvince)) {
			WorldApi.ProvinceState province = state.province(key);
			int friendly = 0;
			int foreign = 0;
			for (Battalion battalion : all) {
				if (!battalion.provinceStateId().equals(province.provinceStateId())) {
					continue;
				}
				if (battalion.nationId().equals(retreatingNation)) {
					friendly++;
				}
				else if (state.relations().atWar(battalion.nationId(), retreatingNation)) {
					foreign++;
				}
			}
			candidates.add(new Retreat.Candidate(key, province.controllerNationId(), friendly, foreign));
		}
		return Retreat.destination(retreatingNation, candidates);
	}

	// --- regrouping ---------------------------------------------------------------------------

	/**
	 * Forms a new army from co-located battalions of the caller (split and merge in one command).
	 * Movement eligibility is per battalion and therefore unaffected (Q-M1).
	 */
	public MilitaryViews.ArmyResult regroup(CurrentPlayer me, UUID gameId, String name, List<UUID> battalionIds,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "REGROUP", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.ArmyResult> execute(GameCommands.CommandContext context) {
				State state = load(gameId);
				UUID nationId = state.nationOfPlayer(context.gamePlayerId());
				List<Battalion> selected = ownColocated(gameId, battalionIds, nationId);
				Set<UUID> previous = armiesOf(selected);
				Army army = armies.saveAndFlush(Army.create(gameId, nationId, name.strip(), context.now()));
				selected.forEach(b -> b.assignArmy(army.id()));
				battalions.saveAllAndFlush(selected);
				deleteEmptyArmies(gameId, previous);
				return new GameCommands.Outcome<>(new MilitaryViews.ArmyResult(armyView(army, selected, state)),
						army.id());
			}

			@Override
			public MilitaryViews.ArmyResult replay(GameCommands.CommandContext context, UUID resultRef) {
				State state = load(gameId);
				return armies.findByGameIdAndId(gameId, resultRef)
					.map(army -> new MilitaryViews.ArmyResult(
							armyView(army, battalions.findByGameIdAndArmyId(gameId, army.id()), state)))
					.orElseGet(() -> new MilitaryViews.ArmyResult(null));
			}

		});
	}

	// --- shared command helpers ---------------------------------------------------------------

	/**
	 * Battalions the caller may give orders to: its own (unless attached to an ally) and allies'
	 * battalions attached to it (Phase 10). All must stand in the same province.
	 */
	private List<Battalion> commandedColocated(UUID gameId, List<UUID> battalionIds, UUID nationId) {
		Set<UUID> ids = new HashSet<>(battalionIds);
		List<Battalion> selected = new ArrayList<>(battalions.findByGameIdAndIdIn(gameId, ids));
		if (ids.isEmpty() || selected.size() != ids.size()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "BATTALION_NOT_FOUND", "Battalion not found in this game.");
		}
		if (selected.stream().anyMatch(b -> b.nationId().equals(nationId) && !b.commander().equals(nationId))) {
			throw new ApiException(HttpStatus.CONFLICT, "BATTALION_ATTACHED",
					"That battalion is attached to an ally, which commands it until you release it.");
		}
		if (selected.stream().anyMatch(b -> !b.commander().equals(nationId))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_BATTALION",
					"You can only command your own battalions and those attached to you.");
		}
		if (selected.stream().map(Battalion::provinceStateId).distinct().count() != 1) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BATTALIONS_NOT_COLOCATED",
					"All selected battalions must be in the same province.");
		}
		selected.sort(Comparator.comparing(b -> b.id().toString()));
		return selected;
	}

	private List<Battalion> ownColocated(UUID gameId, List<UUID> battalionIds, UUID nationId) {
		Set<UUID> ids = new HashSet<>(battalionIds);
		List<Battalion> selected = new ArrayList<>(battalions.findByGameIdAndIdIn(gameId, ids));
		if (ids.isEmpty() || selected.size() != ids.size()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "BATTALION_NOT_FOUND", "Battalion not found in this game.");
		}
		if (selected.stream().anyMatch(b -> !b.nationId().equals(nationId))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_BATTALION",
					"You can only command your own battalions.");
		}
		if (selected.stream().anyMatch(b -> b.commanderNationId() != null)) {
			throw new ApiException(HttpStatus.CONFLICT, "BATTALION_ATTACHED",
					"A selected battalion is under an ally's command; detach it first.");
		}
		if (selected.stream().map(Battalion::provinceStateId).distinct().count() != 1) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BATTALIONS_NOT_COLOCATED",
					"All selected battalions must be in the same province.");
		}
		selected.sort(Comparator.comparing(b -> b.id().toString()));
		return selected;
	}

	/**
	 * Keeps the army invariant "an army's battalions stand together": battalions moving without the
	 * rest of their army leave it; a whole army moving stays an army.
	 */
	private void leaveArmiesIfSplit(UUID gameId, List<Battalion> moving) {
		Map<UUID, Long> movingPerArmy = moving.stream()
			.filter(b -> b.armyId() != null)
			.collect(Collectors.groupingBy(Battalion::armyId, Collectors.counting()));
		for (var entry : movingPerArmy.entrySet()) {
			if (battalions.countByGameIdAndArmyId(gameId, entry.getKey()) > entry.getValue()) {
				moving.stream().filter(b -> entry.getKey().equals(b.armyId())).forEach(b -> b.assignArmy(null));
			}
		}
	}

	private void deleteEmptyArmies(UUID gameId, Set<UUID> armyIds) {
		battalions.flush();
		armyIds.stream()
			.sorted()
			.filter(id -> battalions.countByGameIdAndArmyId(gameId, id) == 0)
			.forEach(id -> armies.findByGameIdAndId(gameId, id).ifPresent(armies::delete));
		armies.flush();
	}

	private static Set<UUID> armiesOf(List<Battalion> list) {
		Set<UUID> ids = new HashSet<>();
		list.forEach(b -> {
			if (b.armyId() != null) {
				ids.add(b.armyId());
			}
		});
		return ids;
	}

	/** Each battalion fights with its own nation's technology and doctrine (coalition sides, Phase 10). */
	private static List<Battle.Fighter> fighters(List<Battalion> list, State state) {
		return list.stream()
			.map(b -> new Battle.Fighter(b.id(), b.unitType(), b.manpower(), state.side(b.nationId())))
			.toList();
	}

	/** Applies battle losses; battalions at zero are deleted (their dead never return to civilians). */
	private List<Battalion> survivors(List<Battalion> side, Battle.Result result, Map<UUID, String> fate) {
		List<Battalion> alive = new ArrayList<>();
		for (Battalion battalion : side) {
			int losses = result.losses().getOrDefault(battalion.id(), 0);
			if (losses >= battalion.manpower()) {
				// Wiped out: delete it with its cohorts before its manpower would reach zero — a flush of a
				// zero-manpower row violates battalion_manpower_ck (defect found in Phase 11).
				fate.put(battalion.id(), "DESTROYED");
				cohorts.removed(battalion);
				battalions.delete(battalion);
				continue;
			}
			battalion.suffer(losses);
			cohorts.suffered(battalion, losses);
			alive.add(battalion);
			fate.put(battalion.id(), "SURVIVED");
		}
		return alive;
	}

	// --- views --------------------------------------------------------------------------------

	/** The movement clock of a ruleset: per global round from v6, per player turn before. */
	static Movement.Clock clock(MilitaryRules rules, int turnNumber, int round) {
		return new Movement.Clock(turnNumber, round, rules.roundBasedMovement());
	}

	static Movement.Clock clock(MilitaryRules rules, GameCommands.CommandContext context) {
		return clock(rules, context.turnNumber(), context.round());
	}

	static MilitaryViews.BattalionView view(Battalion b, State state, UUID viewerNationId, Movement.Clock clock,
			MilitaryRules rules) {
		// Eligibility is shown to whoever commands the battalion (its owner, or the ally it is attached to).
		String eligibility = b.commander().equals(viewerNationId) ? b.eligibility(clock).name() : null;
		return new MilitaryViews.BattalionView(b.id(), b.nationId(), b.unitType().name(), b.manpower(), b.maxManpower(),
				state.keyOf(b.provinceStateId()), b.armyId(), b.originCityId(), b.recruitedRound(), eligibility,
				Upkeep.of(b.unitType(), b.manpower(), b.maxManpower(), rules, state.mods(b.nationId()).upkeepReductionBps()),
				b.commanderNationId());
	}

	private List<MilitaryViews.ArmyView> armyViews(UUID gameId, List<Battalion> all, State state) {
		Map<UUID, List<Battalion>> byArmy = all.stream()
			.filter(b -> b.armyId() != null)
			.collect(Collectors.groupingBy(Battalion::armyId));
		return armies.findByGameIdOrderById(gameId)
			.stream()
			.map(army -> armyView(army, byArmy.getOrDefault(army.id(), List.of()), state))
			.toList();
	}

	private static MilitaryViews.ArmyView armyView(Army army, List<Battalion> members, State state) {
		String province = members.isEmpty() ? null : state.keyOf(members.getFirst().provinceStateId());
		return new MilitaryViews.ArmyView(army.id(), army.nationId(), army.name(), province,
				members.stream().map(Battalion::id).sorted().toList());
	}

	private static MilitaryViews.UpkeepView upkeepView(List<Battalion> all, UUID nationId, MilitaryRules rules,
			int reductionBps) {
		Map<String, Long> byType = new LinkedHashMap<>();
		for (UnitType type : UnitType.values()) {
			byType.put(type.name(), 0L);
		}
		long total = 0;
		int count = 0;
		int soldiers = 0;
		for (Battalion b : all) {
			if (!b.nationId().equals(nationId)) {
				continue;
			}
			long upkeep = Upkeep.of(b.unitType(), b.manpower(), b.maxManpower(), rules, reductionBps);
			byType.merge(b.unitType().name(), upkeep, Math::addExact);
			total = Math.addExact(total, upkeep);
			count++;
			soldiers += b.manpower();
		}
		return new MilitaryViews.UpkeepView(total, byType, count, soldiers);
	}

	private static MilitaryViews.CityRecruitment cityRecruitment(WorldApi.ProvinceState p, UUID nationId,
			boolean yourTurn, EconomyApi.TreasuryState treasury, boolean besieged, MilitaryRules rules) {
		List<MilitaryViews.RecruitOption> options = new ArrayList<>();
		for (UnitType type : UnitType.values()) {
			List<String> blockers = Recruitment
				.blockers(new Recruitment.Request(nationId, p.ownerNationId(), p.controllerNationId(), yourTurn, type,
						p.population(), treasury.treasuryMg(), treasury.inArrears(), besieged), rules)
				.stream()
				.map(Enum::name)
				.toList();
			MilitaryRules.UnitRules unit = rules.unit(type);
			options.add(new MilitaryViews.RecruitOption(type.name(), unit.recruitCostMg(), unit.upkeepMg(),
					rules.battalionManpower(), p.population() - rules.battalionManpower(), blockers.isEmpty(),
					blockers));
		}
		return new MilitaryViews.CityRecruitment(p.cityId(), p.provinceKey(), p.cityName(), p.population(), options);
	}

	private static MilitaryViews.Modifiers modifiersView(MilitaryModifiers.NationModifiers m, MilitaryRules rules) {
		MilitaryRules.CombatRules c = rules.combat();
		return new MilitaryViews.Modifiers(m.militaryLevel(), m.doctrines(), m.damageBonusBps(), c.riverAttackMultiplier(),
				Battle.SideModifiers.reduced(c.riverAttackMultiplier(), m.riverPenaltyReductionBps()),
				c.mountainAttackMultiplier(),
				Battle.SideModifiers.reduced(c.mountainAttackMultiplier(), m.mountainPenaltyReductionBps()),
				m.upkeepReductionBps(), m.siegeArtilleryBonusBps());
	}

	private static MilitaryViews.Rules rulesView(MilitaryRules rules) {
		List<MilitaryViews.UnitOption> units = new ArrayList<>();
		for (UnitType type : UnitType.values()) {
			units.add(new MilitaryViews.UnitOption(type.name(), rules.unit(type).recruitCostMg(),
					rules.unit(type).upkeepMg()));
		}
		return new MilitaryViews.Rules(rules.version(), rules.battalionManpower(), rules.minResidualPopulation(), units);
	}

	private MilitaryViews.BattleSummary summary(BattleReportRepository.Row row, State state) {
		MilitaryViews.BattleDetails details = details(row);
		return new MilitaryViews.BattleSummary(row.id(), row.round(), row.turnNumber(),
				state.keyOf(row.provinceStateId()), row.attackerNationId(), row.defenderNationId(), row.winner(),
				details.attackerLosses(), details.defenderLosses(), row.createdAt());
	}

	private MilitaryViews.BattleView battleView(BattleReportRepository.Row row, State state) {
		return new MilitaryViews.BattleView(row.id(), row.round(), row.turnNumber(), state.keyOf(row.provinceStateId()),
				row.attackerNationId(), row.defenderNationId(), row.winner(), row.rulesetVersion(), row.createdAt(),
				details(row));
	}

	private MilitaryViews.BattleDetails details(BattleReportRepository.Row row) {
		return jsonMapper.readValue(row.reportJson(), MilitaryViews.BattleDetails.class);
	}

	private static List<MilitaryViews.Participant> participants(List<Battalion> side, Map<UUID, Integer> before,
			Battle.Result result, Map<UUID, String> fate, Map<UUID, UUID> ownerOf) {
		return side.stream().map(b -> {
			int lost = result.losses().getOrDefault(b.id(), 0);
			int start = before.get(b.id());
			return new MilitaryViews.Participant(b.id(), b.unitType().name(), start, lost, start - lost, fate.get(b.id()),
					ownerOf.get(b.id()));
		}).toList();
	}

	private static List<MilitaryViews.PhaseView> phases(Battle.Result result) {
		return result.phases()
			.stream()
			.map(p -> new MilitaryViews.PhaseView(p.number(), sideView(p.attacker()), sideView(p.defender())))
			.toList();
	}

	private static MilitaryViews.SideView sideView(Battle.SideLog log) {
		Map<String, Integer> losses = new LinkedHashMap<>();
		Map<UnitType, Integer> ordered = new EnumMap<>(UnitType.class);
		ordered.putAll(log.lossesByType());
		ordered.forEach((type, men) -> losses.put(type.name(), men));
		return new MilitaryViews.SideView(display(log.protection()), display(log.artilleryEffectiveness()),
				display(log.flankBonus()), display(log.terrainMultiplier()), display(log.damage()), losses,
				log.manpowerAfter());
	}

	/** Report display only (casualties were computed at full precision): 4 decimals, HALF_EVEN. */
	private static BigDecimal display(BigDecimal value) {
		return value.setScale(4, RoundingMode.HALF_EVEN).stripTrailingZeros();
	}

	// --- state & errors -----------------------------------------------------------------------

	private State load(UUID gameId) {
		return State.load(world, modifiers, hostility, gameId);
	}

	private GameAccess.Membership startedGameMembership(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = gameAccess.membership(me, gameId);
		if (!membership.started()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
		}
		return membership;
	}

	private boolean besieged(UUID gameId, UUID provinceStateId) {
		return siegeGates.stream().anyMatch(gate -> gate.besieged(gameId, provinceStateId));
	}

	/** Lets the siege module start or lift sieges where this command moved battalions. */
	private void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, GameCommands.CommandContext context) {
		battalions.flush();
		siegeGates.forEach(gate -> gate.presenceChanged(gameId, provinceStateIds, context.round(), context.turnNumber(),
				context.now()));
	}

	private static ApiException cityNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND", "City not found in this game.");
	}

	private static ApiException notReady(Movement.Eligibility eligibility) {
		return switch (eligibility) {
			case RECRUITED_THIS_TURN -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BATTALION_RECRUITED_THIS_TURN",
					"Battalions recruited this turn cannot move or attack until your next turn.");
			case ALREADY_MOVED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BATTALION_ALREADY_MOVED",
					"A selected battalion has already moved this turn.");
			case REINFORCED_THIS_TURN -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"BATTALION_REINFORCED_THIS_TURN",
					"Battalions reinforced this turn cannot move or attack until your next turn.");
			case READY -> throw new IllegalArgumentException("READY is not a rejection");
		};
	}

	private static ApiException rejection(Recruitment.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case NOT_OWNER -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER",
					"You can only recruit in cities your nation legally owns.");
			case OCCUPIED -> new ApiException(HttpStatus.CONFLICT, "PROVINCE_OCCUPIED",
					"This city is occupied by another nation; no one can recruit here until it is liberated.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; no battalions can be raised here until the siege ends.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot recruit this round.");
			case INSUFFICIENT_POPULATION -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_POPULATION",
					"This city does not have enough civilians to raise a battalion.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for this battalion.");
		};
	}

	/** The world as the military module needs it, indexed by province key and id. */
	/**
	 * What a military request reads about the game. The world and the (cached) topology are read at
	 * once; every nation's technology/doctrine modifiers and the diplomatic relations only when first
	 * asked for — recruitment, reinforcement and most views never need them (ENGINE_DESIGN.md S2).
	 * Used within one request and its transaction only.
	 */
	static final class State {

		private final WorldApi.WorldSnapshot snapshot;
		private final WorldApi.Topology topology;
		private final java.util.function.Supplier<Map<UUID, MilitaryModifiers.NationModifiers>> loadModifiers;
		private final java.util.function.Supplier<Hostility.Relations> loadRelations;
		private Map<UUID, MilitaryModifiers.NationModifiers> modifiers;
		private Hostility.Relations relations;

		private State(WorldApi.WorldSnapshot snapshot, WorldApi.Topology topology,
				java.util.function.Supplier<Map<UUID, MilitaryModifiers.NationModifiers>> loadModifiers,
				java.util.function.Supplier<Hostility.Relations> loadRelations) {
			this.snapshot = snapshot;
			this.topology = topology;
			this.loadModifiers = loadModifiers;
			this.loadRelations = loadRelations;
		}

		/** World, topology and every nation's current technology/doctrine effects (read when needed). */
		static State load(WorldApi world, List<MilitaryModifiers> sources, UUID gameId) {
			return load(world, sources, List.of(), gameId);
		}

		/** Also the diplomatic relations (who is at war, who may enter where) as they stand now. */
		static State load(WorldApi world, List<MilitaryModifiers> sources, List<Hostility> hostility, UUID gameId) {
			WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
			return new State(snapshot, world.topology(snapshot.mapId()), () -> {
				List<UUID> nationIds = snapshot.nations().stream().map(WorldApi.NationState::id).toList();
				// One read for all nations (not one per nation).
				Map<UUID, MilitaryModifiers.NationModifiers> mods = new java.util.HashMap<>(
						sources.isEmpty() ? Map.of() : sources.getFirst().ofGame(gameId, nationIds));
				nationIds.forEach(id -> mods.putIfAbsent(id, MilitaryModifiers.NONE));
				return Map.copyOf(mods);
			}, () -> hostility.isEmpty() ? Hostility.Relations.TOTAL_WAR : hostility.getFirst().relations(gameId));
		}

		WorldApi.WorldSnapshot snapshot() {
			return snapshot;
		}

		WorldApi.Topology topology() {
			return topology;
		}

		Map<UUID, MilitaryModifiers.NationModifiers> modifiers() {
			if (modifiers == null) {
				modifiers = loadModifiers.get();
			}
			return modifiers;
		}

		Hostility.Relations relations() {
			if (relations == null) {
				relations = loadRelations.get();
			}
			return relations;
		}

		MilitaryModifiers.NationModifiers mods(UUID nationId) {
			return modifiers().getOrDefault(nationId, MilitaryModifiers.NONE);
		}

		/** The battle engine's view of a nation's modifiers. */
		Battle.SideModifiers side(UUID nationId) {
			MilitaryModifiers.NationModifiers m = mods(nationId);
			Map<UnitType, Integer> damage = new java.util.EnumMap<>(UnitType.class);
			m.damageBonusBps().forEach((type, bps) -> damage.put(UnitType.valueOf(type), bps));
			return new Battle.SideModifiers(damage, m.riverPenaltyReductionBps(), m.mountainPenaltyReductionBps());
		}


		UUID nationOfPlayer(UUID gamePlayerId) {
			return snapshot.nations()
				.stream()
				.filter(n -> gamePlayerId.equals(n.gamePlayerId()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Player " + gamePlayerId + " has no nation"))
				.id();
		}

		WorldApi.ProvinceState provinceOrNull(String key) {
			return snapshot.province(key).orElse(null);
		}

		WorldApi.ProvinceState province(String key) {
			return snapshot.province(key)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROVINCE_NOT_FOUND",
						"Province not found on this map."));
		}

		String keyOf(UUID provinceStateId) {
			return snapshot.provinces()
				.stream()
				.filter(p -> p.provinceStateId().equals(provinceStateId))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Unknown province state " + provinceStateId))
				.provinceKey();
		}

		WorldApi.Terrain terrain(String key) {
			return topology.province(key).orElseThrow().terrain();
		}

	}

}
