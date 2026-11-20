package com.reach.strategy.ai.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.reach.strategy.ai.rules.AiState;
import com.reach.strategy.diplomacy.api.DiplomacyActions;
import com.reach.strategy.diplomacy.api.DiplomacyViews;
import com.reach.strategy.economy.api.EconomyActions;
import com.reach.strategy.economy.api.EconomyViews;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryActions;
import com.reach.strategy.military.api.MilitaryViews;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.research.api.ResearchActions;
import com.reach.strategy.platform.read.ReadScope;
import com.reach.strategy.research.api.ResearchViews;
import com.reach.strategy.siege.api.SiegeActions;
import com.reach.strategy.siege.api.SiegeViews;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.stereotype.Component;

/**
 * Builds a computer nation's {@link AiState} from the same read models its human player would see:
 * map and world state, its economy, military, research, fortifications and diplomacy. Six reads per
 * observation (plus one institution query for the capital); map topology is immutable and cached.
 * Must run inside a (read-only) transaction.
 */
@Component
class AiObserver {

	private final WorldApi world;
	private final EconomyActions economy;
	private final MilitaryActions military;
	private final ResearchActions research;
	private final SiegeActions sieges;
	private final DiplomacyActions diplomacy;
	private final Map<UUID, MapData> maps = new ConcurrentHashMap<>();

	AiObserver(WorldApi world, EconomyActions economy, MilitaryActions military, ResearchActions research,
			SiegeActions sieges, DiplomacyActions diplomacy) {
		this.world = world;
		this.economy = economy;
		this.military = military;
		this.research = research;
		this.sieges = sieges;
		this.diplomacy = diplomacy;
	}

	/** Immutable per map template version: terrain, neighbours, rivers, nation keys and capitals. */
	private record MapData(Map<String, String> terrain, Map<String, List<String>> neighbours,
			Map<String, Set<String>> rivers, Map<UUID, String> nationKeys, Map<UUID, String> capitals) {
	}

	/**
	 * The board as the computer sees it. Runs in one read scope (ENGINE_DESIGN.md S1): the five module
	 * views share the world snapshot, membership, research effects and sieges instead of each reading
	 * them again. Must be called inside a read-only transaction.
	 */
	AiState observe(CurrentPlayer actor, UUID gameId, int round, int turnNumber) {
		return ReadScope.run(() -> observeInScope(actor, gameId, round, turnNumber));
	}

	private AiState observeInScope(CurrentPlayer actor, UUID gameId, int round, int turnNumber) {
		WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
		MapData map = maps.computeIfAbsent(snapshot.mapId(), this::load);
		DiplomacyViews.Overview d = diplomacy.overview(actor, gameId);
		UUID me = d.yourNationId();
		EconomyViews.Overview e = economy.overview(actor, gameId);
		MilitaryViews.Overview m = military.overview(actor, gameId);
		SiegeViews.Overview s = sieges.overview(actor, gameId);
		ResearchViews.Overview r = research.overview(actor, gameId);

		Map<UUID, DiplomacyViews.NationRelation> relations = new HashMap<>();
		d.nations().forEach(n -> relations.put(n.nationId(), n));
		Map<UUID, AiState.Nation> nations = new HashMap<>();
		for (WorldApi.NationState n : snapshot.nations()) {
			DiplomacyViews.NationRelation rel = relations.get(n.id());
			String key = map.nationKeys().getOrDefault(n.nationTemplateId(), n.name());
			nations.put(n.id(), new AiState.Nation(n.id(), key, n.name(), n.control(), n.eliminated(),
					map.capitals().get(n.nationTemplateId()), rel == null ? "PEACE" : rel.status(),
					rel == null ? null : rel.allianceKind(), rel == null ? null : rel.allianceId(),
					rel == null ? null : rel.truceUntilRound(), rel != null && rel.weMayEnter(),
					rel == null ? List.of() : rel.warBlockers(), rel == null ? List.of() : rel.allianceBlockers()));
		}

		Map<String, AiState.Province> provinces = new HashMap<>();
		for (WorldApi.ProvinceState p : snapshot.provinces()) {
			provinces.put(p.provinceKey(), new AiState.Province(p.provinceKey(), map.terrain().get(p.provinceKey()),
					p.ownerNationId(), p.controllerNationId(), p.population(), p.cityId(),
					map.neighbours().getOrDefault(p.provinceKey(), List.of()),
					map.rivers().getOrDefault(p.provinceKey(), Set.of())));
		}

		List<AiState.Unit> units = m.battalions()
			.stream()
			.map(b -> new AiState.Unit(b.id(), b.nationId(), b.commanderNationId(), b.unitType(), b.manpower(),
					b.maxManpower(), b.provinceKey(), "READY".equals(b.eligibility())))
			.toList();

		AiState.Economy economyState = economy(e, me);
		List<AiState.RecruitCity> recruitment = m.recruitment()
			.stream()
			.map(c -> new AiState.RecruitCity(c.cityId(), c.provinceKey(), c.population(),
					c.options().stream().map(o -> new AiState.RecruitOption(o.unitType(), o.costMg(), o.upkeepMg(), o.allowed())).toList()))
			.toList();

		Map<String, Integer> fortLevels = new HashMap<>();
		s.fortifications().forEach(f -> fortLevels.put(f.provinceKey(), f.effectiveLevel()));
		List<AiState.Siege> siegeList = s.activeSieges()
			.stream()
			.map(x -> new AiState.Siege(x.provinceKey(), x.besiegerNationId(), x.defenderNationId(), x.estimatedRoundsLeft()))
			.toList();

		String capital = nations.containsKey(me) ? nations.get(me).capitalKey() : null;
		AiState.Research researchState = research(actor, gameId, r, capital == null ? null : provinces.get(capital));

		AiState.Combat combat = new AiState.Combat(m.modifiers().militaryLevel(), bps(m.modifiers().riverAttackMultiplier()),
				bps(m.modifiers().mountainAttackMultiplier()));

		List<AiState.FortressOption> fortresses = new ArrayList<>();
		if (capital != null && s.fortificationsAvailable() && me.equals(provinces.get(capital).owner())) {
			try {
				SiegeViews.ProvinceFortification f = sieges.province(actor, gameId, capital);
				if (f.upgrade() != null && f.cityId() != null) {
					fortresses.add(new AiState.FortressOption(f.cityId(), capital,
							f.fortification() == null ? 0 : f.fortification().level(), f.upgrade().targetLevel(),
							f.upgrade().costMg(), f.upgrade().allowed()));
				}
			}
			catch (ApiException notAvailable) {
				// No fortress works possible here now.
			}
		}

		int maxRounds = d.rules() == null ? 0 : d.rules().victoryMaxRounds();
		return new AiState(gameId, round, turnNumber, maxRounds, me, nations.get(me).key(), nations, provinces, units,
				economyState, recruitment, researchState, diplomacy(d, me), fortLevels, siegeList, combat, fortresses);
	}

	private MapData load(UUID mapId) {
		WorldApi.Topology topology = world.topology(mapId);
		Map<String, String> terrain = new HashMap<>();
		topology.provinces().forEach(p -> terrain.put(p.key(), p.terrain().name()));
		Map<String, List<String>> neighbours = new HashMap<>();
		Map<String, Set<String>> rivers = new HashMap<>();
		for (WorldApi.Border b : topology.borders()) {
			neighbours.computeIfAbsent(b.a(), k -> new ArrayList<>()).add(b.b());
			neighbours.computeIfAbsent(b.b(), k -> new ArrayList<>()).add(b.a());
			if (b.river()) {
				rivers.computeIfAbsent(b.a(), k -> new HashSet<>()).add(b.b());
				rivers.computeIfAbsent(b.b(), k -> new HashSet<>()).add(b.a());
			}
		}
		neighbours.values().forEach(list -> list.sort(Comparator.naturalOrder()));
		Map<UUID, String> keys = new HashMap<>();
		Map<UUID, String> capitals = new HashMap<>();
		for (WorldApi.NationOption n : world.mapSummary(mapId).nations()) {
			keys.put(n.id(), n.key());
			capitals.put(n.id(), n.capitalProvinceKey());
		}
		return new MapData(Map.copyOf(terrain), Map.copyOf(neighbours), Map.copyOf(rivers), Map.copyOf(keys),
				Map.copyOf(capitals));
	}

	private static AiState.Economy economy(EconomyViews.Overview e, UUID me) {
		EconomyViews.NationEconomy n = e.nation();
		long agriculture = e.rules().agricultureMgPerWorker();
		long tier1 = e.rules().workshopTiers().stream().filter(t -> t.tier() == 1).mapToLong(EconomyViews.Tier::mgPerWorker)
			.findFirst()
			.orElse(agriculture);
		Map<Integer, Long> perWorker = new HashMap<>();
		e.rules().workshopTiers().forEach(t -> perWorker.put(t.tier(), t.mgPerWorker()));
		int capacity = e.rules().workshopCapacity();
		List<AiState.City> cities = new ArrayList<>();
		for (EconomyViews.City c : e.cities()) {
			if (!me.equals(c.ownerNationId())) {
				continue;
			}
			List<AiState.WorkshopOption> options = new ArrayList<>();
			for (EconomyViews.Workshop w : c.workshops()) {
				if (w.upgrade() != null) {
					long gain = Math.max(0, w.upgrade().potentialIncomeAfterMg() - c.potentialIncome().totalMg());
					options.add(new AiState.WorkshopOption(w.id(), "UPGRADE", w.upgrade().costMg(), gain, w.upgrade().allowed()));
				}
				if (w.repair() != null) {
					long gain = (long) capacity * Math.max(0, perWorker.getOrDefault(w.tier(), tier1) - agriculture);
					options.add(new AiState.WorkshopOption(w.id(), "REPAIR", w.repair().costMg(), gain, w.repair().allowed()));
				}
			}
			AiState.Option build = c.construction() == null ? null
					: new AiState.Option(c.construction().costMg(), c.construction().allowed());
			cities.add(new AiState.City(c.cityId(), c.provinceKey(), c.population(), me.equals(c.controllerNationId()),
					c.employment().agricultureWorkers(), c.workshopCount(), build, options));
		}
		cities.sort(Comparator.comparing(AiState.City::province));
		return new AiState.Economy(n.treasuryMg(), n.inArrears(), n.projectedGrossIncomeMg(), n.projectedUpkeepMg(),
				n.projectedNetMg(), e.rules().maxWorkshopsPerCity(), Math.max(0, tier1 - agriculture), capacity, cities);
	}

	private AiState.Research research(CurrentPlayer actor, UUID gameId, ResearchViews.Overview r,
			AiState.Province capital) {
		if (!r.available()) {
			return AiState.Research.none();
		}
		Map<String, Integer> points = new HashMap<>();
		r.pools().forEach(p -> points.put(p.pool(), p.points()));
		List<AiState.TechOption> techs = new ArrayList<>();
		for (ResearchViews.TrackView t : List.of(r.military(), r.civilian())) {
			if (t != null && t.next() != null) {
				techs.add(new AiState.TechOption(t.track(), t.level(), t.next().targetLevel(), t.next().costPoints(),
						t.next().allowed()));
			}
		}
		List<AiState.DoctrineOption> doctrines = r.doctrines()
			.stream()
			.filter(x -> !x.chosen())
			.map(x -> new AiState.DoctrineOption(x.key(), x.costPoints(), x.riverPenaltyReductionBps(),
					x.mountainPenaltyReductionBps(), x.siegeArtilleryBonusBps(), x.militaryUpkeepReductionBps(), x.allowed()))
			.toList();
		List<String> chosen = r.doctrines().stream().filter(ResearchViews.DoctrineView::chosen).map(ResearchViews.DoctrineView::key).toList();
		List<String> types = r.institutions().stream().map(ResearchViews.InstitutionView::type).toList();
		List<AiState.InstitutionOption> options = new ArrayList<>();
		if (capital != null && capital.cityId() != null) {
			try {
				ResearchViews.CityInstitutions city = research.city(actor, gameId, capital.cityId());
				city.options()
					.forEach(o -> options.add(new AiState.InstitutionOption(capital.cityId(), capital.key(), o.type(),
							o.costMg(), o.upkeepMg(), o.allowed() && o.existing() == null)));
			}
			catch (ApiException notOwned) {
				// The capital is not ours to build in (occupied or ceded): no institution options.
			}
		}
		return new AiState.Research(true, points, techs, doctrines, chosen, types, options);
	}

	private static AiState.Diplomacy diplomacy(DiplomacyViews.Overview d, UUID me) {
		if (!d.available() || d.rules() == null) {
			return AiState.Diplomacy.none();
		}
		DiplomacyViews.Features f = d.rules().features();
		AiState.Features features = f == null ? new AiState.Features(false, false, false, false, false, false)
				: new AiState.Features(f.attachments(), f.callsToArms(), f.peaceConferences(), f.separatePeace(),
						f.capitalsCedable(), f.elimination(), f.offensiveCallsToArms(), f.truceAllowsCallsToArms());
		List<AiState.War> wars = d.wars()
			.stream()
			.filter(w -> "ACTIVE".equals(w.status()))
			.map(w -> {
				Map<UUID, String> sides = new HashMap<>();
				w.participants().stream().filter(p -> p.leftRound() == null).forEach(p -> sides.put(p.nationId(), p.side()));
				return new AiState.War(w.id(), w.attackerLeaderId(), w.defenderLeaderId(), sides, w.declaredRound(),
						w.score().net());
			})
			.toList();
		List<AiState.Alliance> alliances = d.alliances()
			.stream()
			.filter(a -> "ACTIVE".equals(a.status()) || "PROPOSED".equals(a.status()) || "REJECTED".equals(a.status()))
			.map(a -> new AiState.Alliance(a.id(), a.kind(), a.nationA(), a.nationB(), a.proposerNationId(), a.status(),
					a.canAccept(), a.canEnd(), a.proposedRound()))
			.toList();
		List<AiState.Call> calls = (d.calls() == null ? List.<DiplomacyViews.CallView>of() : d.calls()).stream()
			.filter(c -> "PENDING".equals(c.status()))
			.map(c -> new AiState.Call(c.id(), c.warId(), c.callerNationId(), c.allyNationId(), c.status(), c.canAnswer(),
					c.side() == null ? "DEFENDER" : c.side()))
			.toList();
		List<AiState.Conference> conferences = (d.conferences() == null ? List.<DiplomacyViews.ConferenceView>of()
				: d.conferences())
			.stream()
			// Refused requests are kept so a nation does not ask again at once.
			.filter(c -> "REQUESTED".equals(c.status()) || "ACTIVE".equals(c.status()) || "REJECTED".equals(c.status()))
			.map(c -> conference(c, me))
			.toList();
		List<AiState.Gift> gifts = d.gifts()
			.stream()
			.map(g -> new AiState.Gift(g.senderNationId(), g.recipientNationId(), g.amountMg(), g.round()))
			.toList();
		long giftMax = d.rules().giftMaxMg() == null ? 0 : d.rules().giftMaxMg();
		return new AiState.Diplomacy(true, features, wars, alliances, calls, conferences, gifts, giftMax,
				d.rules().maxPeaceTerms(), d.rules().reparationsMaxMg());
	}

	static AiState.Conference conference(DiplomacyViews.ConferenceView c, UUID me) {
		Map<UUID, String> approvals = new HashMap<>();
		c.approvals().forEach(a -> approvals.put(a.nationId(), a.decision()));
		List<AiState.Term> terms = c.terms()
			.stream()
			.map(t -> new AiState.Term(t.type(), t.fromNationId(), t.toNationId(), t.provinceKey(), t.amountMg(),
					t.sourceCityId(), t.destinationCityId(), t.people()))
			.toList();
		int byMe = (int) c.revisions().stream().filter(r -> me.equals(r.authorNationId())).count();
		UUID lastAuthor = c.revisions().isEmpty() ? null : c.revisions().getLast().authorNationId();
		return new AiState.Conference(c.id(), c.warId(), c.kind(), c.requesterNationId(), c.counterpartNationId(),
				c.status(), c.currentRevision(), terms, approvals, c.violations(), c.canAnswer(), c.canRevise(),
				c.youMustDecide(), c.canCancel(), byMe, lastAuthor, c.requestedRound());
	}

	private static int bps(BigDecimal multiplier) {
		return multiplier == null ? 10_000 : multiplier.movePointRight(4).intValue();
	}

	Optional<UUID> nationOf(CurrentPlayer actor, UUID gameId) {
		return Optional.ofNullable(diplomacy.overview(actor, gameId).yourNationId());
	}

}
