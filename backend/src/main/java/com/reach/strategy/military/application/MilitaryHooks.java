package com.reach.strategy.military.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.UpkeepSource;
import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.military.api.MilitaryModifiers;
import com.reach.strategy.military.domain.Battalion;
import com.reach.strategy.military.domain.BattalionRepository;
import com.reach.strategy.military.domain.CohortLedger;
import com.reach.strategy.military.rules.MilitaryRules;
import com.reach.strategy.military.rules.UnitType;
import com.reach.strategy.military.rules.Upkeep;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The military module's contributions to other modules' processes: battalion upkeep for the
 * economy settlement and starting forces at game start. Round-end occupation and sieges live in the
 * siege module.
 */
@Configuration
class MilitaryHooks {

	@Bean
	UpkeepSource battalionUpkeep(BattalionRepository battalions, MilitaryRulesets rulesets,
			List<MilitaryModifiers> modifiers) {
		return new BattalionUpkeep(battalions, rulesets, modifiers);
	}

	@Bean
	GameStartInitializer militaryStartInitializer(MilitaryRulesets rulesets, WorldApi world,
			BattalionRepository battalions, CohortLedger cohorts, Clock clock) {
		return new StartingForces(rulesets, world, battalions, cohorts, clock);
	}

	/**
	 * Upkeep of every existing battalion, proportional to its manpower ({@link Upkeep}). Read from
	 * current state, so destroyed battalions cost nothing; the economy charges it once per round
	 * inside its settlement, which the round-resolution guard makes unrepeatable.
	 * A Military Logistics doctrine reduces each of the nation's battalions (PROVISIONAL, Phase 8).
	 */
	static class BattalionUpkeep implements UpkeepSource {

		private final BattalionRepository battalions;
		private final MilitaryRulesets rulesets;
		private final List<MilitaryModifiers> modifiers;

		BattalionUpkeep(BattalionRepository battalions, MilitaryRulesets rulesets, List<MilitaryModifiers> modifiers) {
			this.battalions = battalions;
			this.rulesets = rulesets;
			this.modifiers = List.copyOf(modifiers);
		}

		@Override
		@Transactional(readOnly = true)
		public Map<UUID, Long> upkeepByNation(UUID gameId, String rulesetVersion) {
			MilitaryRules rules = rulesets.rules(rulesetVersion);
			Map<UUID, Long> total = new HashMap<>();
			Map<UUID, Integer> reduction = new HashMap<>();
			for (Battalion b : battalions.findByGameIdOrderById(gameId)) {
				int bps = reduction.computeIfAbsent(b.nationId(), nation -> modifiers.isEmpty() ? 0
						: modifiers.getFirst().of(gameId, nation).upkeepReductionBps());
				total.merge(b.nationId(), Upkeep.of(b.unitType(), b.manpower(), b.maxManpower(), rules, bps),
						Math::addExact);
			}
			return total;
		}

	}

	/**
	 * Starting forces from the ruleset (Q-W3, provisional): raised in each capital from its own
	 * civilians, so no population is created. Ruleset v1 has none, so games created before Phase 5
	 * are unaffected; their stored state is never rewritten.
	 */
	static class StartingForces implements GameStartInitializer {

		private final MilitaryRulesets rulesets;
		private final WorldApi world;
		private final BattalionRepository battalions;
		private final CohortLedger cohorts;
		private final Clock clock;

		StartingForces(MilitaryRulesets rulesets, WorldApi world, BattalionRepository battalions, CohortLedger cohorts,
				Clock clock) {
			this.cohorts = cohorts;
			this.rulesets = rulesets;
			this.world = world;
			this.battalions = battalions;
			this.clock = clock;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void initialize(UUID gameId, UUID mapId, String rulesetVersion) {
			MilitaryRules rules = rulesets.rules(rulesetVersion);
			WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
			Map<UUID, WorldApi.NationOption> templates = world.mapSummary(mapId)
				.nations()
				.stream()
				.collect(Collectors.toMap(WorldApi.NationOption::id, n -> n));
			snapshot.nations().stream().sorted(Comparator.comparing(n -> n.id().toString())).forEach(nation -> {
				WorldApi.NationOption template = templates.get(nation.nationTemplateId());
				// Ruleset v6: forces per faction tier; earlier rulesets give every nation the same.
				Map<UnitType, Integer> forces = rules.startingForces(template.tier());
				if (forces.isEmpty()) {
					return;
				}
				WorldApi.ProvinceState capital = snapshot.province(template.capitalProvinceKey()).orElseThrow();
				int soldiers = 0;
				for (UnitType type : UnitType.values()) {
					int count = forces.getOrDefault(type, 0);
					for (int i = 0; i < count; i++) {
						// Turn 0: starting forces are ready to move in the first player turn.
						Battalion battalion = battalions.saveAndFlush(Battalion.recruit(gameId, nation.id(), type,
								rules.battalionManpower(), capital.cityId(), capital.provinceStateId(), 0, 0, 0,
								clock.instant()));
						cohorts.raised(battalion);
						soldiers += rules.battalionManpower();
					}
				}
				if (capital.population() - soldiers < rules.minResidualPopulation()) {
					throw new IllegalStateException("Ruleset " + rulesetVersion + " starting forces exceed the capital of "
							+ nation.name());
				}
				world.changePopulation(gameId, capital.cityId(), -soldiers);
			});
			battalions.flush();
		}

	}

}
