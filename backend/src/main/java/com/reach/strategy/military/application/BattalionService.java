package com.reach.strategy.military.application;

import com.reach.strategy.military.api.MilitaryViews;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.api.SiegeGate;
import com.reach.strategy.military.domain.ManpowerTransferRepository;
import com.reach.strategy.military.domain.MilitaryStore;
import com.reach.strategy.military.rules.Cohorts;
import com.reach.strategy.military.rules.Disbanding;
import com.reach.strategy.military.rules.Forces;
import com.reach.strategy.military.rules.MilitaryRules;
import com.reach.strategy.military.rules.Reinforcement;
import com.reach.strategy.military.rules.Upkeep;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reinforcing and disbanding single battalions, and the detail view that explains both. Both
 * commands run through the game command pipeline: civilians, gold, manpower and cohorts change in
 * one transaction, a retry with the same key replays the recorded result, and parallel requests
 * are serialized by the game lock.
 */
@Service
public class BattalionService {

	private final GameAccess gameAccess;
	private final GameCommands gameCommands;
	private final MilitaryRulesets rulesets;
	private final WorldApi world;
	private final EconomyApi economy;
	private final MilitaryStore store;
	private final ManpowerTransferRepository transfers;
	private final JsonMapper jsonMapper;
	private final List<SiegeGate> siegeGates;
	private final List<MilitaryModifiers> modifiers;

	BattalionService(GameAccess gameAccess, GameCommands gameCommands, MilitaryRulesets rulesets, WorldApi world,
			EconomyApi economy, MilitaryStore store, ManpowerTransferRepository transfers, JsonMapper jsonMapper, List<SiegeGate> siegeGates,
			List<MilitaryModifiers> modifiers) {
		this.modifiers = List.copyOf(modifiers);
		this.siegeGates = List.copyOf(siegeGates);
		this.gameAccess = gameAccess;
		this.gameCommands = gameCommands;
		this.rulesets = rulesets;
		this.world = world;
		this.economy = economy;
		this.store = store;
		this.transfers = transfers;
		this.jsonMapper = jsonMapper;
	}

	// --- query --------------------------------------------------------------------------------

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MilitaryViews.BattalionDetails details(CurrentPlayer me, UUID gameId, UUID battalionId) {
		GameAccess.Membership membership = gameAccess.membership(me, gameId);
		if (!membership.started()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
		}
		MilitaryRules rules = rulesets.rules(membership.rulesetVersion());
		MilitaryService.State state = load(gameId);
		UUID nationId = state.nationOfPlayer(membership.gamePlayerId());
		Forces.Unit battalion = ownBattalion(store.unit(gameId, battalionId), gameId, battalionId, nationId);
		WorldApi.ProvinceState province = provinceOf(battalion, state);
		List<Cohorts.Cohort> soldiers = store.cohorts(gameId, battalionId);

		EconomyApi.TreasuryState treasury = economy.treasury(nationId);
		Reinforcement.Quote quote = Reinforcement.quote(reinforcementRequest(battalion, province, nationId,
				membership.yourTurn(), membership.turnNumber(), treasury, besieged(gameId, province)), rules);
		MilitaryViews.ReinforcementView reinforcement = new MilitaryViews.ReinforcementView(province.cityId(),
				province.cityName(), province.population(), quote.missing(), quote.turnAllowance(), quote.maxSoldiers(),
				rules.unit(battalion.unitType()).recruitCostMg(), rules.battalionManpower(), quote.costOfMaxMg(),
				Upkeep.of(battalion.unitType(), battalion.manpower() + quote.maxSoldiers(), battalion.maxManpower(), rules,
						state.mods(nationId).upkeepReductionBps()),
				quote.maxSoldiers() > 0, quote.blockers().stream().map(Enum::name).toList());

		List<String> disbandBlockers = Disbanding
			.blockers(nationId, province.controllerNationId(), membership.yourTurn(), besieged(gameId, province))
			.stream()
			.map(Enum::name)
			.toList();
		MilitaryViews.DisbandView disband = new MilitaryViews.DisbandView(disbandBlockers.isEmpty(), disbandBlockers,
				battalion.manpower(), returns(gameId, soldiers, nationId, province, state),
				Upkeep.of(battalion.unitType(), battalion.manpower(), battalion.maxManpower(), rules,
						state.mods(nationId).upkeepReductionBps()));

		return new MilitaryViews.BattalionDetails(
				MilitaryService.view(battalion, state, nationId, MilitaryService.clock(rules, membership.turnNumber(), membership.round()), rules),
				soldiers.stream().map(c -> cohortView(c, state)).toList(), reinforcement, disband);
	}

	// --- reinforcement ------------------------------------------------------------------------

	/**
	 * Adds {@code soldiers} replacements from the city where the battalion stands. Cost and
	 * civilians are decided here from the game's ruleset; the client only names the number.
	 */
	public MilitaryViews.ReinforceResult reinforce(CurrentPlayer me, UUID gameId, UUID battalionId, int soldiers,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "REINFORCE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.ReinforceResult> execute(GameCommands.CommandContext context) {
				MilitaryRules rules = rulesets.rules(context.rulesetVersion());
				MilitaryService.State state = load(gameId);
				UUID nationId = state.nationOfPlayer(context.gamePlayerId());
				Forces before = store.load(gameId);
				Forces.Unit battalion = ownBattalion(before.unit(battalionId), gameId, battalionId, nationId);
				WorldApi.ProvinceState province = provinceOf(battalion, state);
				Reinforcement.Request request = reinforcementRequest(battalion, province, nationId, true,
						context.turnNumber(), economy.treasury(nationId), besieged(gameId, province));
				List<Reinforcement.Blocker> blockers = Reinforcement.blockers(request, soldiers, rules);
				if (!blockers.isEmpty()) {
					throw rejection(blockers.getFirst());
				}
				long cost = Reinforcement.cost(battalion.unitType(), soldiers, rules);
				economy.spend(nationId, cost);
				int population = world.changePopulation(gameId, province.cityId(), -soldiers);
				Forces after = before.reinforce(battalionId, province.cityId(), soldiers,
						MilitaryService.clock(rules, context));
				store.persist(gameId, before, after);
				Forces.Unit reinforced = after.unit(battalionId).orElseThrow();

				MilitaryViews.ReinforceResult result = new MilitaryViews.ReinforceResult(
						MilitaryService.view(reinforced, state, nationId, MilitaryService.clock(rules, context), rules), soldiers, cost,
						economy.treasury(nationId).treasuryMg(), province.cityId(), population);
				UUID transferId = record(gameId, ManpowerTransferRepository.Kind.REINFORCE, battalion, nationId,
						soldiers, cost, result, context);
				return new GameCommands.Outcome<>(result, transferId);
			}

			@Override
			public MilitaryViews.ReinforceResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return recorded(gameId, resultRef, MilitaryViews.ReinforceResult.class);
			}

		});
	}

	// --- disbanding ---------------------------------------------------------------------------

	/**
	 * Dissolves a battalion in friendly-controlled territory. Each cohort's survivors go back to
	 * their origin city if the nation still controls it, otherwise to the local city; nobody else
	 * returns — the dead are gone.
	 */
	public MilitaryViews.DisbandResult disband(CurrentPlayer me, UUID gameId, UUID battalionId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "DISBAND", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<MilitaryViews.DisbandResult> execute(GameCommands.CommandContext context) {
				MilitaryRules rules = rulesets.rules(context.rulesetVersion());
				MilitaryService.State state = load(gameId);
				UUID nationId = state.nationOfPlayer(context.gamePlayerId());
				Forces before = store.load(gameId);
				Forces.Unit battalion = ownBattalion(before.unit(battalionId), gameId, battalionId, nationId);
				WorldApi.ProvinceState province = provinceOf(battalion, state);
				List<Disbanding.Blocker> blockers = Disbanding.blockers(nationId, province.controllerNationId(), true,
						besieged(gameId, province));
				if (blockers.contains(Disbanding.Blocker.NOT_FRIENDLY_TERRITORY)) {
					throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_FRIENDLY_TERRITORY",
							"Battalions can only be disbanded in a province your nation controls.");
				}
				if (!blockers.isEmpty()) {
					throw new ApiException(HttpStatus.CONFLICT, "PROVINCE_BESIEGED",
							"Battalions cannot disband inside a province under siege.");
				}
				List<MilitaryViews.ReturnView> returns = returns(gameId, before.cohortsOf(battalionId), nationId, province, state);
				int returned = returns.stream().mapToInt(MilitaryViews.ReturnView::soldiers).sum();
				if (returned != battalion.manpower()) {
					throw new IllegalStateException("Cohorts of " + battalion.id() + " do not match its manpower");
				}
				returns.forEach(r -> world.changePopulation(gameId, r.cityId(), r.soldiers()));

				long upkeepSaved = Upkeep.of(battalion.unitType(), battalion.manpower(), battalion.maxManpower(), rules,
						state.mods(nationId).upkeepReductionBps());
				Forces after = before.remove(battalionId);
				if (battalion.armyId() != null) {
					after = after.withoutEmptyFormations(Set.of(battalion.armyId()));
				}
				store.persist(gameId, before, after);
				MilitaryViews.DisbandResult result = new MilitaryViews.DisbandResult(battalion.id(),
						battalion.unitType().name(), returned, returns, upkeepSaved);
				UUID transferId = record(gameId, ManpowerTransferRepository.Kind.DISBAND, battalion, nationId,
						returned, 0, result, context);
				return new GameCommands.Outcome<>(result, transferId);
			}

			@Override
			public MilitaryViews.DisbandResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return recorded(gameId, resultRef, MilitaryViews.DisbandResult.class);
			}

		});
	}

	// --- helpers ------------------------------------------------------------------------------

	private Forces.Unit ownBattalion(java.util.Optional<Forces.Unit> found, UUID gameId, UUID battalionId,
			UUID nationId) {
		Forces.Unit battalion = found.orElse(null);
		if (battalion == null) {
			if (transfers.disbanded(gameId, battalionId)) {
				throw new ApiException(HttpStatus.CONFLICT, "BATTALION_DISBANDED", "This battalion has been disbanded.");
			}
			throw new ApiException(HttpStatus.NOT_FOUND, "BATTALION_NOT_FOUND",
					"Battalion not found in this game (it may have been destroyed).");
		}
		if (!battalion.nationId().equals(nationId)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_BATTALION", "You can only command your own battalions.");
		}
		return battalion;
	}

	private boolean besieged(UUID gameId, WorldApi.ProvinceState province) {
		return siegeGates.stream().anyMatch(gate -> gate.besieged(gameId, province.provinceStateId()));
	}

	private static WorldApi.ProvinceState provinceOf(Forces.Unit battalion, MilitaryService.State state) {
		return state.province(state.keyOf(battalion.provinceStateId()));
	}

	private static Reinforcement.Request reinforcementRequest(Forces.Unit battalion, WorldApi.ProvinceState province,
			UUID nationId, boolean yourTurn, int turnNumber, EconomyApi.TreasuryState treasury, boolean besieged) {
		return new Reinforcement.Request(nationId, province.ownerNationId(), province.controllerNationId(), yourTurn,
				battalion.unitType(), battalion.manpower(), battalion.maxManpower(), battalion.reinforcedInTurn(turnNumber),
				province.population(), treasury.treasuryMg(), treasury.inArrears(), besieged);
	}

	/** Disband destinations per cohort, merged per receiving city. */
	private List<MilitaryViews.ReturnView> returns(UUID gameId, List<Cohorts.Cohort> soldiers, UUID nationId,
			WorldApi.ProvinceState local, MilitaryService.State state) {
		// A besieged origin city cannot take its soldiers back (PROVISIONAL Q-S11): they settle locally.
		Set<UUID> friendlyCities = state.snapshot()
			.provinces()
			.stream()
			.filter(p -> p.controllerNationId().equals(nationId) && !besieged(gameId, p))
			.map(WorldApi.ProvinceState::cityId)
			.collect(Collectors.toSet());
		Map<UUID, Integer> byCity = Cohorts.disbandReturns(soldiers, friendlyCities, local.cityId());
		Set<UUID> fallbackOrigins = soldiers.stream()
			.map(Cohorts.Cohort::originCityId)
			.filter(city -> !friendlyCities.contains(city))
			.collect(Collectors.toSet());
		List<MilitaryViews.ReturnView> views = new ArrayList<>();
		byCity.forEach((cityId, count) -> {
			WorldApi.ProvinceState city = state.snapshot().provinceOfCity(cityId).orElseThrow();
			boolean fallback = cityId.equals(local.cityId()) && !fallbackOrigins.isEmpty();
			views.add(new MilitaryViews.ReturnView(cityId, city.cityName(), city.provinceKey(), count, fallback));
		});
		return views;
	}

	private static MilitaryViews.CohortView cohortView(Cohorts.Cohort cohort, MilitaryService.State state) {
		WorldApi.ProvinceState city = state.snapshot().provinceOfCity(cohort.originCityId()).orElseThrow();
		return new MilitaryViews.CohortView(cohort.originCityId(), city.cityName(), city.provinceKey(), cohort.soldiers());
	}

	private UUID record(UUID gameId, ManpowerTransferRepository.Kind kind, Forces.Unit battalion, UUID nationId,
			int soldiers, long costMg, Object result, GameCommands.CommandContext context) {
		UUID id = UUID.randomUUID();
		transfers.insert(gameId, new ManpowerTransferRepository.Row(id, kind, battalion.id(), nationId, context.round(),
				context.turnNumber(), soldiers, costMg, jsonMapper.writeValueAsString(result), context.now()));
		return id;
	}

	private <T> T recorded(UUID gameId, UUID transferId, Class<T> type) {
		ManpowerTransferRepository.Row row = transfers.find(gameId, transferId)
			.orElseThrow(() -> new IllegalStateException("Replayed transfer " + transferId + " is missing"));
		return jsonMapper.readValue(row.detailsJson(), type);
	}

	private MilitaryService.State load(UUID gameId) {
		return MilitaryService.State.load(world, modifiers, gameId);
	}

	private static ApiException rejection(Reinforcement.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case AT_FULL_STRENGTH -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "BATTALION_AT_FULL_STRENGTH",
					"This battalion is already at full strength.");
			case EXCEEDS_MAXIMUM_MANPOWER -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"REINFORCEMENT_EXCEEDS_MAXIMUM", "A battalion cannot exceed its maximum manpower.");
			case TURN_LIMIT_REACHED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REINFORCEMENT_LIMIT_REACHED",
					"A battalion can receive at most 500 replacements per turn.");
			case NOT_OWNED_CITY, OCCUPIED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"INVALID_REINFORCEMENT_LOCATION",
					"Battalions can only be reinforced in a city your nation owns and controls.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; it cannot send replacements until the siege ends.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot reinforce this round.");
			case INSUFFICIENT_POPULATION -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_POPULATION",
					"This city does not have enough civilians for these replacements.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for these replacements.");
		};
	}

}
