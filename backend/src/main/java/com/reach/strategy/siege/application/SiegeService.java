package com.reach.strategy.siege.application;

import com.reach.strategy.siege.api.SiegeViews;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.api.MilitaryPresence;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.siege.domain.SiegeRoundRepository;
import com.reach.strategy.siege.domain.SiegeStore;
import com.reach.strategy.siege.rules.Strongholds;
import com.reach.strategy.siege.rules.FortressWorks;
import com.reach.strategy.siege.rules.SiegeMath;
import com.reach.strategy.siege.rules.SiegeRules;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fortifications and sieges for players: what every fortress and siege looks like (no fog of
 * war), and building, upgrading and repairing fortresses through the game command pipeline.
 * Prices, durations, progress and estimates are always computed here from the game's ruleset.
 */
@Service
public class SiegeService {

	static final int RECENT_SIEGES = 20;

	private final GameAccess gameAccess;
	private final GameCommands gameCommands;
	private final SiegeRulesets rulesets;
	private final WorldApi world;
	private final EconomyApi economy;
	private final MilitaryPresence military;
	private final SiegeStore store;
	private final SiegeRoundRepository siegeRounds;
	private final List<MilitaryModifiers> modifiers;

	SiegeService(GameAccess gameAccess, GameCommands gameCommands, SiegeRulesets rulesets, WorldApi world,
			EconomyApi economy, MilitaryPresence military, SiegeStore store, SiegeRoundRepository siegeRounds,
			List<MilitaryModifiers> modifiers) {
		this.modifiers = List.copyOf(modifiers);
		this.gameAccess = gameAccess;
		this.gameCommands = gameCommands;
		this.rulesets = rulesets;
		this.world = world;
		this.economy = economy;
		this.military = military;
		this.store = store;
		this.siegeRounds = siegeRounds;
	}

	// --- queries ------------------------------------------------------------------------------

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SiegeViews.Overview overview(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		Optional<SiegeRules> rules = rulesets.find(membership.rulesetVersion());
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		List<MilitaryPresence.Presence> presence = military.presence(gameId);
		List<SiegeViews.FortificationView> forts = store.fortresses(gameId)
			.stream()
			.map(f -> fortView(f, snapshot))
			.sorted(Comparator.comparing(SiegeViews.FortificationView::provinceKey))
			.toList();
		List<SiegeViews.SiegeSummary> active = store.activeSieges(gameId)
			.stream()
			.map(s -> summary(gameId, s, snapshot, presence, rules.orElse(null)))
			.toList();
		List<SiegeViews.SiegeSummary> recent = store.endedSieges(gameId, RECENT_SIEGES)
			.stream()
			.map(s -> summary(gameId, s, snapshot, presence, null))
			.toList();
		return new SiegeViews.Overview(gameId, membership.round(), rules.isPresent(), forts, active, recent);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SiegeViews.ProvinceFortification province(CurrentPlayer me, UUID gameId, String provinceKey) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		Optional<SiegeRules> rules = rulesets.find(membership.rulesetVersion());
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		WorldApi.ProvinceState province = snapshot.province(provinceKey)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROVINCE_NOT_FOUND", "Province not found."));
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		Strongholds.Fortress fort = store.fortressOfCity(gameId, province.cityId()).orElse(null);
		Strongholds.Siege active = store.activeSieges(gameId)
			.stream()
			.filter(s -> s.provinceStateId().equals(province.provinceStateId()))
			.findFirst()
			.orElse(null);
		SiegeViews.WorksOption upgrade = null;
		SiegeViews.WorksOption repair = null;
		if (rules.isPresent()) {
			FortressWorks.State works = worksState(nationId, province, fort, active != null, membership.yourTurn(),
					economy.treasury(nationId));
			upgrade = option(FortressWorks.upgrade(works, rules.get()), membership.round());
			repair = fort == null ? null : option(FortressWorks.repair(works, rules.get()), membership.round());
		}
		return new SiegeViews.ProvinceFortification(province.provinceKey(), province.cityId(), province.cityName(),
				province.population(), province.ownerNationId(), province.controllerNationId(), rules.isPresent(),
				fort == null ? null : fortView(fort, snapshot),
				active == null ? null : summary(gameId, active, snapshot, military.presence(gameId), rules.orElse(null)),
				upgrade, repair);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SiegeViews.SiegeDetails siege(CurrentPlayer me, UUID gameId, UUID siegeId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		Strongholds.Siege siege = store.siege(gameId, siegeId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SIEGE_NOT_FOUND", "Siege not found in this game."));
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		List<SiegeViews.RoundView> rounds = siegeRounds.of(gameId, siegeId)
			.stream()
			.map(r -> new SiegeViews.RoundView(r.round(), r.artilleryManpower(), r.progressAdded(), r.progressAfter(),
					r.populationBefore(), r.populationLost()))
			.toList();
		return new SiegeViews.SiegeDetails(summary(gameId, siege, snapshot, military.presence(gameId),
				rulesets.find(membership.rulesetVersion()).orElse(null)), rounds);
	}

	// --- commands -----------------------------------------------------------------------------

	/**
	 * Builds the first level or raises the fortress by one. {@code targetLevel} must be the next
	 * level: a stale or repeated click with a different key cannot buy two levels.
	 */
	public SiegeViews.WorksResult upgrade(CurrentPlayer me, UUID gameId, UUID cityId, int targetLevel,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "BUILD_FORTRESS",
				works(gameId, cityId, FortressWorks.Project.UPGRADE, targetLevel));
	}

	public SiegeViews.WorksResult repair(CurrentPlayer me, UUID gameId, UUID cityId, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "REPAIR_FORTRESS",
				works(gameId, cityId, FortressWorks.Project.REPAIR, null));
	}

	private GameCommands.Handler<SiegeViews.WorksResult> works(UUID gameId, UUID cityId, FortressWorks.Project kind,
			Integer targetLevel) {
		return new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<SiegeViews.WorksResult> execute(GameCommands.CommandContext context) {
				SiegeRules rules = rulesets.find(context.rulesetVersion())
					.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_AVAILABLE_IN_RULESET",
							"This game's ruleset has no fortifications."));
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				WorldApi.ProvinceState province = snapshot.provinceOfCity(cityId)
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND",
							"City not found in this game."));
				UUID nationId = nationOf(snapshot, context.gamePlayerId());
				Strongholds.Fortress fort = store.fortressOfCity(gameId, cityId).orElse(null);
				if (kind == FortressWorks.Project.REPAIR && fort == null) {
					throw new ApiException(HttpStatus.NOT_FOUND, "FORTRESS_NOT_FOUND", "This city has no fortress.");
				}
				boolean besieged = store.activeSieges(gameId).stream().anyMatch(s -> s.cityId().equals(cityId));
				FortressWorks.State state = worksState(nationId, province, fort, besieged, true,
						economy.treasury(nationId));
				FortressWorks.Quote quote = kind == FortressWorks.Project.UPGRADE ? FortressWorks.upgrade(state, rules)
						: FortressWorks.repair(state, rules);
				if (!quote.blockers().isEmpty()) {
					throw rejection(quote.blockers().getFirst());
				}
				if (targetLevel != null && targetLevel != quote.targetLevel()) {
					throw new ApiException(HttpStatus.CONFLICT, "STALE_FORTRESS_LEVEL",
							"The fortress is not at the level you expected; refresh and try again.");
				}
				EconomyApi.TreasuryState paid = economy.spend(nationId, quote.costMg());
				Strongholds.Fortress existing = fort != null ? fort
						: Strongholds.Fortress.firstLevelProject(UUID.randomUUID(), cityId, context.rulesetVersion(),
								context.now());
				Strongholds.Fortress target = existing.projectStarted(kind,
						kind == FortressWorks.Project.UPGRADE ? quote.targetLevel() : null, context.round(),
						SiegeMath.completesAfterRound(context.round(), quote.durationRounds()), quote.costMg());
				Strongholds before = fort == null ? Strongholds.empty() : Strongholds.of(List.of(fort), List.of());
				store.persist(gameId, before, before.with(target));
				return new GameCommands.Outcome<>(new SiegeViews.WorksResult(fortView(target, snapshot), quote.costMg(),
						paid.treasuryMg()), target.id());
			}

			@Override
			public SiegeViews.WorksResult replay(GameCommands.CommandContext context, UUID resultRef) {
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				Strongholds.Fortress fort = store.fortress(gameId, resultRef)
					.orElseThrow(() -> new IllegalStateException("Replayed fortification " + resultRef + " is missing"));
				UUID nationId = nationOf(snapshot, context.gamePlayerId());
				return new SiegeViews.WorksResult(fortView(fort, snapshot), fort.projectCostMg(),
						economy.treasury(nationId).treasuryMg());
			}

		};
	}

	// --- views --------------------------------------------------------------------------------

	private static FortressWorks.State worksState(UUID nationId, WorldApi.ProvinceState province,
			Strongholds.Fortress fort,
			boolean besieged, boolean yourTurn, EconomyApi.TreasuryState treasury) {
		return new FortressWorks.State(nationId, province.ownerNationId(), province.controllerNationId(), yourTurn,
				besieged, fort == null ? 0 : fort.level(), fort == null ? null : fort.status(),
				fort == null ? null : fort.project(), treasury.treasuryMg(), treasury.inArrears());
	}

	private static SiegeViews.WorksOption option(FortressWorks.Quote quote, int round) {
		int ordered = Math.max(round, 1);
		int completes = quote.durationRounds() == 0 ? ordered
				: SiegeMath.completesAfterRound(ordered, quote.durationRounds());
		return new SiegeViews.WorksOption(quote.targetLevel(), quote.costMg(), quote.durationRounds(), completes,
				completes + 1, quote.allowed(), quote.blockers().stream().map(Enum::name).toList());
	}

	private static SiegeViews.FortificationView fortView(Strongholds.Fortress f, WorldApi.WorldSnapshot snapshot) {
		WorldApi.ProvinceState p = snapshot.provinceOfCity(f.cityId()).orElseThrow();
		SiegeViews.ProjectView project = f.project() == null ? null
				: new SiegeViews.ProjectView(f.project().name(), f.projectTargetLevel(), f.projectCompletesAfterRound(),
						f.projectCompletesAfterRound() + 1);
		return new SiegeViews.FortificationView(f.cityId(), p.provinceKey(), p.cityName(), f.level(),
				f.effectiveLevel(), f.status().name(), f.integrity(), f.maxIntegrity(), project);
	}

	/** @param rules the game's ruleset for estimates of an active siege; null for ended sieges */
	private SiegeViews.SiegeSummary summary(UUID gameId, Strongholds.Siege s, WorldApi.WorldSnapshot snapshot,
			List<MilitaryPresence.Presence> presence, SiegeRules rules) {
		WorldApi.ProvinceState p = snapshot.provinces()
			.stream()
			.filter(x -> x.provinceStateId().equals(s.provinceStateId()))
			.findFirst()
			.orElseThrow();
		Integer rate = null;
		Integer artillery = null;
		Integer roundsLeft = null;
		Integer nextAttrition = null;
		Integer artilleryBonus = null;
		if (s.status() == Strongholds.Siege.Status.ACTIVE && rules != null) {
			artillery = presence.stream()
				.filter(b -> b.provinceStateId().equals(s.provinceStateId()) && b.nationId().equals(s.besiegerNationId())
						&& b.artillery())
				.mapToInt(MilitaryPresence.Presence::manpower)
				.sum();
			artilleryBonus = modifiers.isEmpty() ? 0
					: modifiers.getFirst().of(gameId, s.besiegerNationId()).siegeArtilleryBonusBps();
			rate = SiegeMath.progressPerRound(artillery, rules, artilleryBonus);
			roundsLeft = SiegeMath.estimatedRoundsLeft(s.progress(), s.requiredProgress(), rate);
			nextAttrition = SiegeMath.attrition(p.population(), rules);
		}
		return new SiegeViews.SiegeSummary(s.id(), p.provinceKey(), p.cityName(), s.cityId(), s.besiegerNationId(),
				s.defenderNationId(), s.status().name(), s.startedRound(), s.endedRound(),
				s.endReason() == null ? null : s.endReason().name(), s.fortressLevel(), s.requiredProgress(),
				s.progress(), rate, artillery, artilleryBonus, roundsLeft, nextAttrition, s.populationLost(),
				s.artillerySiege(), s.damagedBuildingId());
	}

	// --- helpers ------------------------------------------------------------------------------

	private GameAccess.Membership startedGameMembership(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = gameAccess.membership(me, gameId);
		if (!membership.started()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
		}
		return membership;
	}

	private static UUID nationOf(WorldApi.WorldSnapshot snapshot, UUID gamePlayerId) {
		Map<UUID, UUID> byPlayer = snapshot.nations()
			.stream()
			.collect(Collectors.toMap(WorldApi.NationState::gamePlayerId, WorldApi.NationState::id));
		UUID nation = byPlayer.get(gamePlayerId);
		if (nation == null) {
			throw new IllegalStateException("Player " + gamePlayerId + " has no nation");
		}
		return nation;
	}

	private static ApiException rejection(FortressWorks.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case NOT_OWNER -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER",
					"Only the nation that legally owns this city can fortify it.");
			case OCCUPIED -> new ApiException(HttpStatus.CONFLICT, "PROVINCE_OCCUPIED",
					"This city is occupied by another nation; liberate it first.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; no fortress work is possible until the siege ends.");
			case PROJECT_IN_PROGRESS -> new ApiException(HttpStatus.CONFLICT, "FORTRESS_PROJECT_IN_PROGRESS",
					"A fortress project is already under way in this city.");
			case FORTRESS_DAMAGED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FORTRESS_DAMAGED",
					"The fortress is damaged; repair it before raising it further.");
			case NOT_DAMAGED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FORTRESS_NOT_DAMAGED",
					"Only a damaged fortress can be repaired.");
			case MAX_LEVEL_REACHED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MAX_LEVEL_REACHED",
					"This fortress is already at the highest level.");
			case NOT_AVAILABLE_IN_RULESET -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
					"NOT_AVAILABLE_IN_RULESET", "This game's ruleset has no fortifications.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot build this round.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for this.");
		};
	}

}
