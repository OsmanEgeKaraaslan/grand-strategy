package com.reach.strategy.research.application;

import com.reach.strategy.research.api.ResearchViews;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.EconomyApi;
import com.reach.strategy.economy.api.SiegeStatus;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.research.domain.ResearchLogRepository;
import com.reach.strategy.research.domain.ResearchStore;
import com.reach.strategy.research.rules.Studies;
import com.reach.strategy.research.rules.InstitutionType;
import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.Research;
import com.reach.strategy.research.rules.ResearchRules;
import com.reach.strategy.research.rules.ResearchRules.Track;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Research for players: the caller's own research overview (private), institutions per city, and
 * the commands to build institutions and unlock technology levels and doctrines. Every cost, level,
 * modifier and timing is computed here from the game's ruleset; points and gold are spent in the
 * command's transaction under the game lock.
 */
@Service
public class ResearchService {

	private final GameAccess gameAccess;
	private final GameCommands gameCommands;
	private final ResearchRulesets rulesets;
	private final WorldApi world;
	private final EconomyApi economy;
	private final List<SiegeStatus> siegeStatus;
	private final ResearchStore store;
	private final ResearchLogRepository log;

	ResearchService(GameAccess gameAccess, GameCommands gameCommands, ResearchRulesets rulesets, WorldApi world,
			EconomyApi economy, List<SiegeStatus> siegeStatus, ResearchStore store, ResearchLogRepository log) {
		this.gameAccess = gameAccess;
		this.gameCommands = gameCommands;
		this.rulesets = rulesets;
		this.world = world;
		this.economy = economy;
		this.siegeStatus = List.copyOf(siegeStatus);
		this.store = store;
		this.log = log;
	}

	// --- queries ------------------------------------------------------------------------------

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ResearchViews.Overview overview(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		return overview(gameId, membership.round(), membership.yourTurn(), membership.rulesetVersion(), nationId,
				snapshot);
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ResearchViews.CityInstitutions city(CurrentPlayer me, UUID gameId, UUID cityId) {
		GameAccess.Membership membership = startedGameMembership(me, gameId);
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		WorldApi.ProvinceState p = snapshot.provinceOfCity(cityId).orElseThrow(ResearchService::cityNotFound);
		ResearchRules rules = rulesets.find(membership.rulesetVersion()).orElse(null);
		if (rules == null) {
			return new ResearchViews.CityInstitutions(cityId, p.cityName(), p.provinceKey(), false, List.of());
		}
		UUID nationId = nationOf(snapshot, membership.gamePlayerId());
		EconomyApi.TreasuryState treasury = economy.treasury(nationId);
		Map<InstitutionType, Studies.Institution> existing = new HashMap<>();
		store.institutionsOfCity(gameId, cityId).forEach(i -> existing.put(i.type(), i));
		boolean besieged = besieged(gameId, cityId);
		List<ResearchViews.InstitutionOption> options = new ArrayList<>();
		int ordered = Math.max(membership.round(), 1);
		for (InstitutionType type : InstitutionType.values()) {
			ResearchRules.InstitutionRules ir = rules.institution(type);
			List<String> blockers = Research
				.institutionBlockers(type, new Research.InstitutionRequest(nationId, p.ownerNationId(),
						p.controllerNationId(), membership.yourTurn(), besieged, existing.containsKey(type),
						treasury.treasuryMg(), treasury.inArrears()), rules)
				.stream()
				.map(Enum::name)
				.toList();
			int completes = Research.completesAfterRound(ordered, ir.buildDurationRounds());
			Studies.Institution i = existing.get(type);
			options.add(new ResearchViews.InstitutionOption(type.name(), type.pool().name(), ir.buildCostMg(),
					ir.buildDurationRounds(), completes, completes + 1, ir.pointsPerRound(), ir.upkeepMg(),
					blockers.isEmpty(), blockers, i == null ? null : institutionView(i, p, rules)));
		}
		return new ResearchViews.CityInstitutions(cityId, p.cityName(), p.provinceKey(), true, options);
	}

	// --- commands -----------------------------------------------------------------------------

	/** Orders an institution in one of the caller's cities. Price and timing come from the ruleset. */
	public ResearchViews.InstitutionResult buildInstitution(CurrentPlayer me, UUID gameId, UUID cityId,
			InstitutionType type, UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "BUILD_INSTITUTION", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<ResearchViews.InstitutionResult> execute(GameCommands.CommandContext context) {
				ResearchRules rules = rulesOf(context.rulesetVersion());
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				WorldApi.ProvinceState p = snapshot.provinceOfCity(cityId).orElseThrow(ResearchService::cityNotFound);
				UUID nationId = nationOf(snapshot, context.gamePlayerId());
				EconomyApi.TreasuryState treasury = economy.treasury(nationId);
				boolean exists = store.institutionsOfCity(gameId, cityId).stream().anyMatch(i -> i.type() == type);
				List<Research.InstitutionBlocker> blockers = Research.institutionBlockers(type,
						new Research.InstitutionRequest(nationId, p.ownerNationId(), p.controllerNationId(), true,
								besieged(gameId, cityId), exists, treasury.treasuryMg(), treasury.inArrears()),
						rules);
				if (!blockers.isEmpty()) {
					throw institutionRejection(blockers.getFirst());
				}
				ResearchRules.InstitutionRules ir = rules.institution(type);
				economy.spend(nationId, ir.buildCostMg());
				Studies.Institution institution = Studies.Institution.project(UUID.randomUUID(), cityId, type,
						context.round(), Research.completesAfterRound(context.round(), ir.buildDurationRounds()),
						ir.buildCostMg(), context.rulesetVersion(), context.now());
				store.persist(gameId, Studies.empty(), Studies.empty().found(institution));
				return new GameCommands.Outcome<>(new ResearchViews.InstitutionResult(
						institutionView(institution, p, rules), ir.buildCostMg(), economy.treasury(nationId).treasuryMg()),
						institution.id());
			}

			@Override
			public ResearchViews.InstitutionResult replay(GameCommands.CommandContext context, UUID resultRef) {
				ResearchRules rules = rulesOf(context.rulesetVersion());
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				Studies.Institution institution = store.institution(gameId, resultRef)
					.orElseThrow(() -> new IllegalStateException("Replayed institution " + resultRef + " is missing"));
				WorldApi.ProvinceState p = snapshot.provinceOfCity(institution.cityId()).orElseThrow();
				UUID nationId = nationOf(snapshot, context.gamePlayerId());
				return new ResearchViews.InstitutionResult(institutionView(institution, p, rules), institution.costPaidMg(),
						economy.treasury(nationId).treasuryMg());
			}

		});
	}

	/**
	 * Buys the next level of a technology track with that track's points. {@code targetLevel} must
	 * be the next level, so a stale or repeated click can never buy two levels.
	 */
	public ResearchViews.UnlockResult unlockTechnology(CurrentPlayer me, UUID gameId, Track track, int targetLevel,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "UNLOCK_TECHNOLOGY", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<ResearchViews.UnlockResult> execute(GameCommands.CommandContext context) {
				ResearchRules rules = rulesOf(context.rulesetVersion());
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID nationId = nationOf(snapshot, context.gamePlayerId());
				Studies.Progress r = researchOf(gameId, nationId);
				Pool pool = track == Track.MILITARY ? Pool.MILITARY : Pool.CIVILIAN;
				Research.Quote quote = Research.technology(track, r.level(track), r.points(pool), true, rules);
				if (!quote.blockers().isEmpty()) {
					throw unlockRejection(quote.blockers().getFirst());
				}
				if (targetLevel != quote.targetLevel()) {
					throw new ApiException(HttpStatus.CONFLICT, "STALE_TECHNOLOGY_LEVEL",
							"That technology level is not the next one; refresh and try again.");
				}
				Studies before = Studies.of(List.of(r), List.of(), List.of());
				store.persist(gameId, before, before.update(r.spend(pool, quote.costPoints()).raise(track,
						quote.targetLevel())));
				return recorded(gameId, nationId, "TECHNOLOGY", track.name(), quote.targetLevel(), quote.costPoints(),
						context, snapshot);
			}

			@Override
			public ResearchViews.UnlockResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return replayed(gameId, resultRef, context);
			}

		});
	}

	/** Chooses a doctrine with doctrine points (PROVISIONAL: one per tier, permanent). */
	public ResearchViews.UnlockResult unlockDoctrine(CurrentPlayer me, UUID gameId, String doctrineKey,
			UUID idempotencyKey) {
		return gameCommands.execute(me, gameId, idempotencyKey, "UNLOCK_DOCTRINE", new GameCommands.Handler<>() {

			@Override
			public GameCommands.Outcome<ResearchViews.UnlockResult> execute(GameCommands.CommandContext context) {
				ResearchRules rules = rulesOf(context.rulesetVersion());
				WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
				UUID nationId = nationOf(snapshot, context.gamePlayerId());
				Studies.Progress r = researchOf(gameId, nationId);
				Set<String> chosen = chosenDoctrines(gameId, nationId);
				Research.Quote quote = Research.doctrine(doctrineKey, chosen, r.points(Pool.DOCTRINE), true, rules);
				if (!quote.blockers().isEmpty()) {
					throw unlockRejection(quote.blockers().getFirst());
				}
				Studies before = Studies.of(List.of(r), List.of(), List.of());
				store.persist(gameId, before, before.update(r.spend(Pool.DOCTRINE, quote.costPoints()))
					.choose(new Studies.Doctrine(UUID.randomUUID(), nationId, doctrineKey, quote.targetLevel(),
							quote.costPoints(), context.round(), context.now())));
				return recorded(gameId, nationId, "DOCTRINE", doctrineKey, null, quote.costPoints(), context, snapshot);
			}

			@Override
			public ResearchViews.UnlockResult replay(GameCommands.CommandContext context, UUID resultRef) {
				return replayed(gameId, resultRef, context);
			}

		});
	}

	private GameCommands.Outcome<ResearchViews.UnlockResult> recorded(UUID gameId, UUID nationId, String kind,
			String subject, Integer level, int cost, GameCommands.CommandContext context,
			WorldApi.WorldSnapshot snapshot) {
		UUID id = UUID.randomUUID();
		log.insertUnlock(gameId, new ResearchLogRepository.Unlock(id, nationId, kind, subject, level, cost,
				context.round()), context.now());
		return new GameCommands.Outcome<>(new ResearchViews.UnlockResult(kind, subject, level, cost,
				overview(gameId, context.round(), true, context.rulesetVersion(), nationId, snapshot)), id);
	}

	private ResearchViews.UnlockResult replayed(UUID gameId, UUID resultRef, GameCommands.CommandContext context) {
		ResearchLogRepository.Unlock unlock = log.findUnlock(gameId, resultRef)
			.orElseThrow(() -> new IllegalStateException("Replayed unlock " + resultRef + " is missing"));
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		return new ResearchViews.UnlockResult(unlock.kind(), unlock.subject(), unlock.level(), unlock.costPoints(),
				overview(gameId, context.round(), true, context.rulesetVersion(), unlock.nationId(), snapshot));
	}

	// --- views --------------------------------------------------------------------------------

	private ResearchViews.Overview overview(UUID gameId, int round, boolean yourTurn, String rulesetVersion,
			UUID nationId, WorldApi.WorldSnapshot snapshot) {
		ResearchRules rules = rulesets.find(rulesetVersion).orElse(null);
		Studies.Progress r = rules == null ? null : store.progress(gameId, nationId).orElse(null);
		if (r == null) {
			return new ResearchViews.Overview(gameId, round, yourTurn, false, nationId, List.of(), null, null, List.of(),
					List.of(), null);
		}
		Map<UUID, WorldApi.ProvinceState> byCity = new HashMap<>();
		snapshot.provinces().forEach(p -> byCity.put(p.cityId(), p));
		List<Studies.Institution> own = store.institutions(gameId)
			.stream()
			.filter(i -> nationId.equals(byCity.get(i.cityId()).ownerNationId()))
			.toList();
		List<InstitutionType> producing = own.stream()
			.filter(i -> {
				WorldApi.ProvinceState p = byCity.get(i.cityId());
				return Research.produces(i.active(), p.ownerNationId(), p.controllerNationId());
			})
			.map(Studies.Institution::type)
			.toList();
		Map<Pool, Integer> perRound = Research.pointsPerRound(producing, rules);
		int[] last = log.lastRound(gameId, nationId);
		List<ResearchViews.PoolView> pools = new ArrayList<>();
		for (Pool pool : Pool.values()) {
			pools.add(new ResearchViews.PoolView(pool.name(), r.points(pool), perRound.get(pool),
					last == null ? null : last[pool.ordinal()]));
		}
		Set<String> chosen = chosenDoctrines(gameId, nationId);
		List<ResearchViews.DoctrineView> doctrineViews = rules.doctrines().entrySet().stream().map(e -> {
			Research.Quote q = Research.doctrine(e.getKey(), chosen, r.points(Pool.DOCTRINE), yourTurn, rules);
			ResearchRules.DoctrineRules d = e.getValue();
			return new ResearchViews.DoctrineView(e.getKey(), d.tier(), d.costPoints(), d.riverPenaltyReductionBps(),
					d.mountainPenaltyReductionBps(), d.siegeArtilleryBonusBps(), d.militaryUpkeepReductionBps(),
					chosen.contains(e.getKey()), q.allowed(), q.blockers().stream().map(Enum::name).toList());
		}).toList();
		Research.Effects e = Research.effects(r.level(Track.MILITARY), r.level(Track.CIVILIAN), chosen, rules);
		return new ResearchViews.Overview(gameId, round, yourTurn, true, nationId, pools,
				track(Track.MILITARY, r, yourTurn, rules), track(Track.CIVILIAN, r, yourTurn, rules), doctrineViews,
				own.stream()
					.map(i -> institutionView(i, byCity.get(i.cityId()), rules))
					.sorted(Comparator.comparing(ResearchViews.InstitutionView::provinceKey)
						.thenComparing(ResearchViews.InstitutionView::type))
					.toList(),
				new ResearchViews.Effects(e.militaryLevel(), e.civilianLevel(), e.damageBonusBps(),
						e.riverPenaltyReductionBps(), e.mountainPenaltyReductionBps(), e.siegeArtilleryBonusBps(),
						e.militaryUpkeepReductionBps(), e.growthBonusBps(), e.highestUnlockedWorkshopTier(), e.doctrines()));
	}

	private static ResearchViews.TrackView track(Track track, Studies.Progress r, boolean yourTurn, ResearchRules rules) {
		int level = r.level(track);
		Pool pool = track == Track.MILITARY ? Pool.MILITARY : Pool.CIVILIAN;
		List<ResearchViews.LevelView> levels = new ArrayList<>();
		for (int l = 1; l <= rules.maxLevel(track); l++) {
			if (track == Track.MILITARY) {
				ResearchRules.MilitaryLevel m = rules.military(l);
				levels.add(new ResearchViews.LevelView(l, m.costPoints(), l <= level, m.damageBonusBps(), null, 0));
			}
			else {
				ResearchRules.CivilianLevel c = rules.civilian(l);
				levels.add(new ResearchViews.LevelView(l, c.costPoints(), l <= level, Map.of(), c.unlocksWorkshopTier(),
						c.growthBonusBps()));
			}
		}
		Research.Quote q = Research.technology(track, level, r.points(pool), yourTurn, rules);
		ResearchViews.Quote next = q.blockers().contains(Research.Blocker.MAX_LEVEL_REACHED) ? null
				: new ResearchViews.Quote(q.targetLevel(), q.costPoints(), q.allowed(),
						q.blockers().stream().map(Enum::name).toList());
		return new ResearchViews.TrackView(track.name(), level, rules.maxLevel(track), levels, next);
	}

	private static ResearchViews.InstitutionView institutionView(Studies.Institution i, WorldApi.ProvinceState p,
			ResearchRules rules) {
		ResearchRules.InstitutionRules ir = rules.institution(i.type());
		return new ResearchViews.InstitutionView(i.id(), i.cityId(), p.cityName(), p.provinceKey(), i.type().name(),
				i.type().pool().name(), i.status().name(), i.completesAfterRound(), i.completesAfterRound() + 1,
				ir.pointsPerRound(), ir.upkeepMg(), Research.produces(i.active(), p.ownerNationId(), p.controllerNationId()));
	}

	// --- helpers ------------------------------------------------------------------------------

	private ResearchRules rulesOf(String version) {
		return rulesets.find(version)
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_AVAILABLE_IN_RULESET",
					"This game's ruleset has no research."));
	}

	private Studies.Progress researchOf(UUID gameId, UUID nationId) {
		return store.progress(gameId, nationId)
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NOT_AVAILABLE_IN_RULESET",
					"This game's ruleset has no research."));
	}

	private Set<String> chosenDoctrines(UUID gameId, UUID nationId) {
		return store.doctrines(gameId, nationId)
			.stream()
			.map(Studies.Doctrine::doctrineKey)
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private boolean besieged(UUID gameId, UUID cityId) {
		return siegeStatus.stream().anyMatch(s -> s.cityBesieged(gameId, cityId));
	}

	private GameAccess.Membership startedGameMembership(CurrentPlayer me, UUID gameId) {
		GameAccess.Membership membership = gameAccess.membership(me, gameId);
		if (!membership.started()) {
			throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_IN_PROGRESS", "The game has not started.");
		}
		return membership;
	}

	private static UUID nationOf(WorldApi.WorldSnapshot snapshot, UUID gamePlayerId) {
		return snapshot.nations()
			.stream()
			.filter(n -> gamePlayerId.equals(n.gamePlayerId()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Player " + gamePlayerId + " has no nation"))
			.id();
	}

	private static ApiException cityNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND", "City not found in this game.");
	}

	private static ApiException institutionRejection(Research.InstitutionBlocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case NOT_OWNER -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER",
					"You can only build institutions in cities your nation legally owns.");
			case OCCUPIED -> new ApiException(HttpStatus.CONFLICT, "PROVINCE_OCCUPIED",
					"This city is occupied by another nation; liberate it first.");
			case BESIEGED -> new ApiException(HttpStatus.CONFLICT, "CITY_BESIEGED",
					"This city is under siege; nothing can be built until the siege ends.");
			case ALREADY_EXISTS -> new ApiException(HttpStatus.CONFLICT, "INSTITUTION_EXISTS",
					"This city already has (or is building) an institution of this type.");
			case IN_ARREARS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IN_ARREARS",
					"Your nation is in arrears and cannot build this round.");
			case INSUFFICIENT_GOLD -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_GOLD",
					"Your treasury cannot pay for this institution.");
		};
	}

	private static ApiException unlockRejection(Research.Blocker blocker) {
		return switch (blocker) {
			case NOT_YOUR_TURN -> new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			case MAX_LEVEL_REACHED -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MAX_LEVEL_REACHED",
					"This technology is already at its highest level.");
			case INSUFFICIENT_POINTS -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_RESEARCH_POINTS",
					"Your nation does not have enough research points for this.");
			case ALREADY_CHOSEN -> new ApiException(HttpStatus.CONFLICT, "DOCTRINE_ALREADY_CHOSEN",
					"Your nation already follows this doctrine.");
			case TIER_FULL -> new ApiException(HttpStatus.CONFLICT, "DOCTRINE_TIER_FULL",
					"Your nation already chose its doctrine of this tier.");
			case UNKNOWN_DOCTRINE -> new ApiException(HttpStatus.NOT_FOUND, "DOCTRINE_NOT_FOUND",
					"No such doctrine in this game's ruleset.");
		};
	}

}
