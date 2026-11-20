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
import com.reach.strategy.military.domain.MilitaryStore;
import com.reach.strategy.military.rules.Forces;
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
	UpkeepSource battalionUpkeep(MilitaryStore store, MilitaryRulesets rulesets, List<MilitaryModifiers> modifiers) {
		return new BattalionUpkeep(store, rulesets, modifiers);
	}

	@Bean
	GameStartInitializer militaryStartInitializer(MilitaryRulesets rulesets, WorldApi world,
			MilitaryStore store, Clock clock) {
		return new StartingForces(rulesets, world, store, clock);
	}

	/**
	 * Upkeep of every existing battalion, proportional to its manpower ({@link Upkeep}). Read from
	 * current state, so destroyed battalions cost nothing; the economy charges it once per round
	 * inside its settlement, which the round-resolution guard makes unrepeatable.
	 * A Military Logistics doctrine reduces each of the nation's battalions (PROVISIONAL, Phase 8).
	 */
	static class BattalionUpkeep implements UpkeepSource {

		private final MilitaryStore store;
		private final MilitaryRulesets rulesets;
		private final List<MilitaryModifiers> modifiers;

		BattalionUpkeep(MilitaryStore store, MilitaryRulesets rulesets, List<MilitaryModifiers> modifiers) {
			this.store = store;
			this.rulesets = rulesets;
			this.modifiers = List.copyOf(modifiers);
		}

		@Override
		@Transactional(readOnly = true)
		public Map<UUID, Long> upkeepByNation(UUID gameId, String rulesetVersion) {
			MilitaryRules rules = rulesets.rules(rulesetVersion);
			Map<UUID, Long> total = new HashMap<>();
			List<Forces.Unit> all = store.units(gameId);
			// Every owning nation's doctrine reduction in one read, not one per nation.
			Map<UUID, MilitaryModifiers.NationModifiers> mods = modifiers.isEmpty() ? Map.of()
					: modifiers.getFirst().ofGame(gameId, all.stream().map(Forces.Unit::nationId).distinct().toList());
			for (Forces.Unit b : all) {
				int bps = mods.getOrDefault(b.nationId(), MilitaryModifiers.NONE).upkeepReductionBps();
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
		private final MilitaryStore store;
		private final Clock clock;

		StartingForces(MilitaryRulesets rulesets, WorldApi world, MilitaryStore store, Clock clock) {
			this.rulesets = rulesets;
			this.world = world;
			this.store = store;
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
			// A new game has no battalions yet: everything raised here is written in one batch at the end.
			Forces[] forces = { Forces.empty() };
			snapshot.nations().stream().sorted(Comparator.comparing(n -> n.id().toString())).forEach(nation -> {
				WorldApi.NationOption template = templates.get(nation.nationTemplateId());
				// Ruleset v6: forces per faction tier; earlier rulesets give every nation the same.
				Map<UnitType, Integer> starting = rules.startingForces(template.tier());
				if (starting.isEmpty()) {
					return;
				}
				WorldApi.ProvinceState capital = snapshot.province(template.capitalProvinceKey()).orElseThrow();
				int soldiers = 0;
				for (UnitType type : UnitType.values()) {
					int count = starting.getOrDefault(type, 0);
					for (int i = 0; i < count; i++) {
						// Turn 0: starting forces are ready to move in the first player turn.
						forces[0] = forces[0].raise(Forces.Unit.raised(UUID.randomUUID(), nation.id(), type,
								rules.battalionManpower(), capital.cityId(), capital.provinceStateId(), 0, 0, 0,
								clock.instant()));
						soldiers += rules.battalionManpower();
					}
				}
				if (capital.population() - soldiers < rules.minResidualPopulation()) {
					throw new IllegalStateException("Ruleset " + rulesetVersion + " starting forces exceed the capital of "
							+ nation.name());
				}
				world.changePopulation(gameId, capital.cityId(), -soldiers);
			});
			store.persist(gameId, Forces.empty(), forces[0]);
		}

	}

}
