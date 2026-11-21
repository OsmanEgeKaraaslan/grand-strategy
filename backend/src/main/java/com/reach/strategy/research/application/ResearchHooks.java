package com.reach.strategy.research.application;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.economy.api.EconomyTechnology;
import com.reach.strategy.economy.api.UpkeepSource;
import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.game.api.RoundPhase;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.platform.read.ReadScope;
import com.reach.strategy.research.domain.ResearchLogRepository;
import com.reach.strategy.research.domain.ResearchStore;
import com.reach.strategy.research.rules.Studies;
import com.reach.strategy.research.rules.InstitutionType;
import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.Research;
import com.reach.strategy.research.rules.ResearchRules;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The research module's contributions to other modules: technology and doctrine effects for the
 * military, siege and economy rules (through their own SPI interfaces), institution upkeep,
 * research rows for new games, and the round steps RESEARCH and INSTITUTIONS.
 */
@Configuration
class ResearchHooks {

	@Bean
	MilitaryModifiers researchMilitaryModifiers(ResearchEffects effects) {
		return new MilitaryModifiers() {

			@Override
			public MilitaryModifiers.NationModifiers of(UUID gameId, UUID nationId) {
				return modifiers(effects.of(gameId, nationId));
			}

			@Override
			public Map<UUID, MilitaryModifiers.NationModifiers> ofGame(UUID gameId, java.util.Collection<UUID> nationIds) {
				Map<UUID, Research.Effects> all = effects.ofGame(gameId);
				Map<UUID, MilitaryModifiers.NationModifiers> result = new HashMap<>();
				nationIds.forEach(id -> result.put(id, modifiers(all.getOrDefault(id, Research.Effects.NONE))));
				return result;
			}

		};
	}

	private static MilitaryModifiers.NationModifiers modifiers(Research.Effects e) {
		return new MilitaryModifiers.NationModifiers(e.militaryLevel(), e.doctrines(), e.damageBonusBps(),
				e.riverPenaltyReductionBps(), e.mountainPenaltyReductionBps(), e.siegeArtilleryBonusBps(),
				e.militaryUpkeepReductionBps());
	}

	@Bean
	EconomyTechnology researchEconomyTechnology(ResearchEffects effects) {
		return new EconomyTechnology() {

			@Override
			public int highestWorkshopTier(UUID gameId, UUID nationId) {
				return effects.of(gameId, nationId).highestUnlockedWorkshopTier();
			}

			@Override
			public int growthBonusBps(UUID gameId, UUID nationId) {
				return effects.of(gameId, nationId).growthBonusBps();
			}

			@Override
			public Map<UUID, EconomyTechnology.Technology> ofGame(UUID gameId) {
				Map<UUID, EconomyTechnology.Technology> result = new HashMap<>();
				effects.ofGame(gameId)
					.forEach((nationId, e) -> result.put(nationId,
							new EconomyTechnology.Technology(e.highestUnlockedWorkshopTier(), e.growthBonusBps())));
				return result;
			}

		};
	}

	@Bean
	UpkeepSource institutionUpkeep(ResearchStore store, ResearchRulesets rulesets, WorldApi world) {
		return new InstitutionUpkeep(store, rulesets, world);
	}

	@Bean
	GameStartInitializer researchStartInitializer(ResearchRulesets rulesets, WorldApi world,
			ResearchStore store) {
		return (gameId, mapId, rulesetVersion) -> {
			if (rulesets.find(rulesetVersion).isEmpty()) {
				return;
			}
			// Every nation starts at level 1 of both tracks with no points and no institutions.
			Studies started = Studies.empty();
			for (WorldApi.NationState n : world.snapshot(gameId)
				.nations()
				.stream()
				.sorted(java.util.Comparator.comparing(n -> n.id().toString()))
				.toList()) {
				started = started.begin(Studies.Progress.start(n.id(), rulesetVersion));
			}
			store.persist(gameId, Studies.empty(), started);
		};
	}

	@Bean
	RoundPhase researchPhase(ResearchRulesets rulesets, WorldApi world, ResearchStore store,
			ResearchLogRepository log) {
		return new ResearchPhase(rulesets, world, store, log);
	}

	@Bean
	RoundPhase institutionPhase(ResearchStore store) {
		return new InstitutionPhase(store);
	}

	/** A nation's research effects, read from authoritative state. */
	@Component
	static class ResearchEffects {

		private final ResearchRulesets rulesets;
		private final ResearchStore store;

		ResearchEffects(ResearchRulesets rulesets, ResearchStore store) {
			this.rulesets = rulesets;
			this.store = store;
		}

		@Transactional(readOnly = true)
		public Research.Effects of(UUID gameId, UUID nationId) {
			if (ReadScope.active()) {
				// Same result as below, from the game-wide read shared by the scope.
				return ofGame(gameId).getOrDefault(nationId, Research.Effects.NONE);
			}
			Studies.Progress r = store.progress(gameId, nationId).orElse(null);
			if (r == null) {
				return Research.Effects.NONE;
			}
			ResearchRules rules = rulesets.find(r.rulesetVersion()).orElse(null);
			if (rules == null) {
				return Research.Effects.NONE;
			}
			List<String> chosen = store.doctrines(gameId, nationId)
				.stream()
				.map(Studies.Doctrine::doctrineKey)
				.toList();
			return Research.effects(r.level(ResearchRules.Track.MILITARY), r.level(ResearchRules.Track.CIVILIAN), chosen,
					rules);
		}

		/**
		 * The effects of every nation of the game in two queries, for views and commands that need
		 * all of them (the military state, the economy overview). Nations without research (games
		 * before ruleset v4) are absent; callers treat them as {@link Research.Effects#NONE}.
		 */
		@Transactional(readOnly = true)
		public Map<UUID, Research.Effects> ofGame(UUID gameId) {
			return ReadScope.remember(List.of("research.effects", gameId), () -> loadGame(gameId));
		}

		private Map<UUID, Research.Effects> loadGame(UUID gameId) {
			Map<UUID, List<String>> chosen = new HashMap<>();
			for (Studies.Doctrine d : store.doctrines(gameId)) {
				chosen.computeIfAbsent(d.nationId(), n -> new java.util.ArrayList<>()).add(d.doctrineKey());
			}
			Map<UUID, Research.Effects> effects = new HashMap<>();
			for (Studies.Progress r : store.progress(gameId)) {
				rulesets.find(r.rulesetVersion())
					.ifPresent(rules -> effects.put(r.nationId(), Research.effects(r.level(ResearchRules.Track.MILITARY),
							r.level(ResearchRules.Track.CIVILIAN), chosen.getOrDefault(r.nationId(), List.of()), rules)));
			}
			return Map.copyOf(effects);
		}

	}

	/**
	 * Step RESEARCH: every nation of a v4+ game gains the points of its ACTIVE institutions in cities
	 * it owns and controls — once per round (research_round_report primary key next to the
	 * round_resolution guard). Institutions completing this round are still under construction here.
	 */
	static class ResearchPhase implements RoundPhase {

		private final ResearchRulesets rulesets;
		private final WorldApi world;
		private final ResearchStore store;
		private final ResearchLogRepository log;

		ResearchPhase(ResearchRulesets rulesets, WorldApi world, ResearchStore store, ResearchLogRepository log) {
			this.rulesets = rulesets;
			this.world = world;
			this.store = store;
			this.log = log;
		}

		@Override
		public Step step() {
			return Step.RESEARCH;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			ResearchRules rules = rulesets.find(context.rulesetVersion()).orElse(null);
			if (rules == null) {
				return;
			}
			UUID gameId = context.gameId();
			Map<UUID, WorldApi.ProvinceState> byCity = new HashMap<>();
			world.snapshot(gameId).provinces().forEach(p -> byCity.put(p.cityId(), p));
			Map<UUID, List<InstitutionType>> producing = new HashMap<>();
			for (Studies.Institution i : store.institutions(gameId)) {
				WorldApi.ProvinceState p = byCity.get(i.cityId());
				if (Research.produces(i.active(), p.ownerNationId(), p.controllerNationId())) {
					producing.computeIfAbsent(p.ownerNationId(), n -> new java.util.ArrayList<>()).add(i.type());
				}
			}
			Studies before = Studies.of(store.progress(gameId), List.of(), List.of());
			Studies after = before;
			for (Studies.Progress r : before.progress()) {
				Map<Pool, Integer> gained = Research.pointsPerRound(producing.getOrDefault(r.nationId(), List.of()), rules);
				Studies.Progress updated = r;
				for (Pool pool : Pool.values()) {
					updated = updated.add(pool, gained.get(pool));
				}
				after = after.update(updated);
				log.insertRound(gameId, context.round(), r.nationId(), gained.get(Pool.MILITARY), gained.get(Pool.CIVILIAN),
						gained.get(Pool.DOCTRINE));
			}
			store.persist(gameId, before, after);
		}

	}

	/** Step INSTITUTIONS (Q-E8): institutions due this round become ACTIVE and produce from the next one. */
	static class InstitutionPhase implements RoundPhase {

		private final ResearchStore store;

		InstitutionPhase(ResearchStore store) {
			this.store = store;
		}

		@Override
		public Step step() {
			return Step.INSTITUTIONS;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			Studies before = Studies.of(List.of(), store.institutions(context.gameId()), List.of());
			Studies after = before;
			for (Studies.Institution i : before.institutions()) {
				if (i.status() == Studies.Institution.Status.UNDER_CONSTRUCTION && i.completesAfterRound() <= context.round()) {
					after = after.update(i.completed());
				}
			}
			store.persist(context.gameId(), before, after);
		}

	}

	/**
	 * Institution upkeep (PROVISIONAL 1 gold per round): an ACTIVE institution costs its city's owner
	 * while the owner also controls it, through the existing income → upkeep → arrears settlement.
	 */
	static class InstitutionUpkeep implements UpkeepSource {

		private final ResearchStore store;
		private final ResearchRulesets rulesets;
		private final WorldApi world;

		InstitutionUpkeep(ResearchStore store, ResearchRulesets rulesets, WorldApi world) {
			this.store = store;
			this.rulesets = rulesets;
			this.world = world;
		}

		@Override
		@Transactional(readOnly = true)
		public Map<UUID, Long> upkeepByNation(UUID gameId, String rulesetVersion) {
			Map<UUID, Long> total = new HashMap<>();
			List<Studies.Institution> all = store.institutions(gameId);
			if (all.isEmpty()) {
				return total;
			}
			Map<UUID, WorldApi.ProvinceState> byCity = new HashMap<>();
			world.snapshot(gameId).provinces().forEach(p -> byCity.put(p.cityId(), p));
			Map<InstitutionType, Long> costs = new EnumMap<>(InstitutionType.class);
			for (Studies.Institution i : all) {
				WorldApi.ProvinceState p = byCity.get(i.cityId());
				if (!Research.produces(i.active(), p.ownerNationId(), p.controllerNationId())) {
					continue;
				}
				long upkeep = costs.computeIfAbsent(i.type(),
						type -> rulesets.rules(i.rulesetVersion()).institution(type).upkeepMg());
				total.merge(p.ownerNationId(), upkeep, Math::addExact);
			}
			return total;
		}

	}

}
