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
import com.reach.strategy.research.domain.Institution;
import com.reach.strategy.research.domain.InstitutionRepository;
import com.reach.strategy.research.domain.NationDoctrine;
import com.reach.strategy.research.domain.NationDoctrineRepository;
import com.reach.strategy.research.domain.NationResearch;
import com.reach.strategy.research.domain.NationResearchRepository;
import com.reach.strategy.research.domain.ResearchLogRepository;
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
	UpkeepSource institutionUpkeep(InstitutionRepository institutions, ResearchRulesets rulesets, WorldApi world) {
		return new InstitutionUpkeep(institutions, rulesets, world);
	}

	@Bean
	GameStartInitializer researchStartInitializer(ResearchRulesets rulesets, WorldApi world,
			NationResearchRepository research) {
		return (gameId, mapId, rulesetVersion) -> {
			if (rulesets.find(rulesetVersion).isEmpty()) {
				return;
			}
			// Every nation starts at level 1 of both tracks with no points and no institutions.
			world.snapshot(gameId)
				.nations()
				.stream()
				.sorted(java.util.Comparator.comparing(n -> n.id().toString()))
				.forEach(n -> research.save(NationResearch.start(gameId, n.id(), rulesetVersion)));
			research.flush();
		};
	}

	@Bean
	RoundPhase researchPhase(ResearchRulesets rulesets, WorldApi world, InstitutionRepository institutions,
			NationResearchRepository research, ResearchLogRepository log) {
		return new ResearchPhase(rulesets, world, institutions, research, log);
	}

	@Bean
	RoundPhase institutionPhase(InstitutionRepository institutions) {
		return new InstitutionPhase(institutions);
	}

	/** A nation's research effects, read from authoritative state. */
	@Component
	static class ResearchEffects {

		private final ResearchRulesets rulesets;
		private final NationResearchRepository research;
		private final NationDoctrineRepository doctrines;

		ResearchEffects(ResearchRulesets rulesets, NationResearchRepository research, NationDoctrineRepository doctrines) {
			this.rulesets = rulesets;
			this.research = research;
			this.doctrines = doctrines;
		}

		@Transactional(readOnly = true)
		public Research.Effects of(UUID gameId, UUID nationId) {
			if (ReadScope.active()) {
				// Same result as below, from the game-wide read shared by the scope.
				return ofGame(gameId).getOrDefault(nationId, Research.Effects.NONE);
			}
			NationResearch r = research.findById(nationId).filter(x -> x.gameId().equals(gameId)).orElse(null);
			if (r == null) {
				return Research.Effects.NONE;
			}
			ResearchRules rules = rulesets.find(r.rulesetVersion()).orElse(null);
			if (rules == null) {
				return Research.Effects.NONE;
			}
			List<String> chosen = doctrines.findByGameIdAndNationIdOrderByDoctrineKey(gameId, nationId)
				.stream()
				.map(NationDoctrine::doctrineKey)
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
			for (NationDoctrine d : doctrines.findByGameIdOrderByNationIdAscDoctrineKeyAsc(gameId)) {
				chosen.computeIfAbsent(d.nationId(), n -> new java.util.ArrayList<>()).add(d.doctrineKey());
			}
			Map<UUID, Research.Effects> effects = new HashMap<>();
			for (NationResearch r : research.findByGameIdOrderByNationId(gameId)) {
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
		private final InstitutionRepository institutions;
		private final NationResearchRepository research;
		private final ResearchLogRepository log;

		ResearchPhase(ResearchRulesets rulesets, WorldApi world, InstitutionRepository institutions,
				NationResearchRepository research, ResearchLogRepository log) {
			this.rulesets = rulesets;
			this.world = world;
			this.institutions = institutions;
			this.research = research;
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
			for (Institution i : institutions.findByGameIdOrderById(gameId)) {
				WorldApi.ProvinceState p = byCity.get(i.cityId());
				if (Research.produces(i.active(), p.ownerNationId(), p.controllerNationId())) {
					producing.computeIfAbsent(p.ownerNationId(), n -> new java.util.ArrayList<>()).add(i.type());
				}
			}
			for (NationResearch r : research.findByGameIdOrderByNationId(gameId)) {
				Map<Pool, Integer> gained = Research.pointsPerRound(producing.getOrDefault(r.nationId(), List.of()), rules);
				gained.forEach(r::add);
				log.insertRound(gameId, context.round(), r.nationId(), gained.get(Pool.MILITARY), gained.get(Pool.CIVILIAN),
						gained.get(Pool.DOCTRINE));
			}
			research.flush();
		}

	}

	/** Step INSTITUTIONS (Q-E8): institutions due this round become ACTIVE and produce from the next one. */
	static class InstitutionPhase implements RoundPhase {

		private final InstitutionRepository institutions;

		InstitutionPhase(InstitutionRepository institutions) {
			this.institutions = institutions;
		}

		@Override
		public Step step() {
			return Step.INSTITUTIONS;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			institutions
				.findByGameIdAndStatusAndCompletesAfterRoundLessThanEqualOrderById(context.gameId(),
						Institution.Status.UNDER_CONSTRUCTION, context.round())
				.forEach(Institution::complete);
			institutions.flush();
		}

	}

	/**
	 * Institution upkeep (PROVISIONAL 1 gold per round): an ACTIVE institution costs its city's owner
	 * while the owner also controls it, through the existing income → upkeep → arrears settlement.
	 */
	static class InstitutionUpkeep implements UpkeepSource {

		private final InstitutionRepository institutions;
		private final ResearchRulesets rulesets;
		private final WorldApi world;

		InstitutionUpkeep(InstitutionRepository institutions, ResearchRulesets rulesets, WorldApi world) {
			this.institutions = institutions;
			this.rulesets = rulesets;
			this.world = world;
		}

		@Override
		@Transactional(readOnly = true)
		public Map<UUID, Long> upkeepByNation(UUID gameId, String rulesetVersion) {
			Map<UUID, Long> total = new HashMap<>();
			List<Institution> all = institutions.findByGameIdOrderById(gameId);
			if (all.isEmpty()) {
				return total;
			}
			Map<UUID, WorldApi.ProvinceState> byCity = new HashMap<>();
			world.snapshot(gameId).provinces().forEach(p -> byCity.put(p.cityId(), p));
			Map<InstitutionType, Long> costs = new EnumMap<>(InstitutionType.class);
			for (Institution i : all) {
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
