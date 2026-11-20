package com.reach.strategy.siege.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.economy.api.SiegeStatus;
import com.reach.strategy.economy.api.UpkeepSource;
import com.reach.strategy.game.api.GameStartInitializer;
import com.reach.strategy.game.api.RoundPhase;
import com.reach.strategy.military.api.SiegeGate;
import com.reach.strategy.siege.domain.Fortification;
import com.reach.strategy.siege.domain.FortificationRepository;
import com.reach.strategy.siege.domain.Siege;
import com.reach.strategy.siege.domain.SiegeRepository;
import com.reach.strategy.siege.rules.FortressWorks;
import com.reach.strategy.siege.rules.SiegeRules;
import com.reach.strategy.world.api.WorldApi;
import com.reach.strategy.platform.read.ReadScope;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The siege module's contributions to other modules: the siege status hooks for military and
 * economy commands, the round phases SIEGES and FORTIFICATIONS, fortress upkeep and the starting
 * fortresses of new games.
 */
@Configuration
class SiegeHooks {

	@Bean
	SiegeChecks siegeChecks(SiegeRepository sieges, SiegeEngine engine) {
		return new SiegeChecks(sieges, engine);
	}

	@Bean
	RoundPhase siegePhase(SiegeEngine engine) {
		return new SiegePhase(engine);
	}

	@Bean
	RoundPhase fortificationPhase(FortificationRepository fortifications, SiegeRepository sieges,
			SiegeRulesets rulesets) {
		return new FortificationPhase(fortifications, sieges, rulesets);
	}

	@Bean
	UpkeepSource fortressUpkeep(FortificationRepository fortifications, SiegeRulesets rulesets, WorldApi world) {
		return new FortressUpkeep(fortifications, rulesets, world);
	}

	@Bean
	GameStartInitializer fortificationStartInitializer(SiegeRulesets rulesets, WorldApi world,
			FortificationRepository fortifications, Clock clock) {
		return new StartingFortresses(rulesets, world, fortifications, clock);
	}

	/** {@link SiegeGate} for military commands and {@link SiegeStatus} for economy commands. */
	static class SiegeChecks implements SiegeGate, SiegeStatus {

		private final SiegeRepository sieges;
		private final SiegeEngine engine;

		SiegeChecks(SiegeRepository sieges, SiegeEngine engine) {
			this.sieges = sieges;
			this.engine = engine;
		}

		@Override
		@Transactional(readOnly = true)
		public boolean besieged(UUID gameId, UUID provinceStateId) {
			if (ReadScope.active()) {
				return besiegedProvinces(gameId).contains(provinceStateId);
			}
			return sieges.findByGameIdAndProvinceStateIdAndStatus(gameId, provinceStateId, Siege.Status.ACTIVE)
				.isPresent();
		}

		@Override
		@Transactional(readOnly = true)
		public boolean cityBesieged(UUID gameId, UUID cityId) {
			if (ReadScope.active()) {
				return besiegedCities(gameId).contains(cityId);
			}
			return sieges.existsByGameIdAndCityIdAndStatus(gameId, cityId, Siege.Status.ACTIVE);
		}

		@Override
		@Transactional(readOnly = true)
		public Set<UUID> besiegedProvinces(UUID gameId) {
			return activeSieges(gameId).stream()
				.map(ActiveSiege::provinceStateId)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}

		@Override
		@Transactional(readOnly = true)
		public Set<UUID> besiegedCities(UUID gameId) {
			return activeSieges(gameId).stream()
				.map(ActiveSiege::cityId)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}

		/** The game's active sieges, read once per read scope. */
		private java.util.List<ActiveSiege> activeSieges(UUID gameId) {
			return ReadScope.remember(java.util.List.of("siege.active", gameId),
					() -> sieges.findByGameIdAndStatusOrderById(gameId, Siege.Status.ACTIVE)
						.stream()
						.map(s -> new ActiveSiege(s.provinceStateId(), s.cityId()))
						.toList());
		}

		private record ActiveSiege(UUID provinceStateId, UUID cityId) {
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, int round, int turnNumber, Instant now) {
			engine.presenceChanged(gameId, provinceStateIds, round, turnNumber, now);
		}

	}

	/** Step SIEGES: occupation and sieges (see {@link SiegeEngine#roundEnd}). */
	static class SiegePhase implements RoundPhase {

		private final SiegeEngine engine;

		SiegePhase(SiegeEngine engine) {
			this.engine = engine;
		}

		@Override
		public Step step() {
			return Step.SIEGES;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			engine.roundEnd(context.gameId(), context.round(), 0, context.now());
		}

	}

	/**
	 * Step FORTIFICATIONS (Q-E8): fortress projects due this round complete after income and after
	 * this round's sieges, so a new level resists from the next round end. A project in a city under
	 * siege waits until the siege is over (PROVISIONAL), so a fortress never changes mid-siege.
	 */
	static class FortificationPhase implements RoundPhase {

		private final FortificationRepository fortifications;
		private final SiegeRepository sieges;
		private final SiegeRulesets rulesets;

		FortificationPhase(FortificationRepository fortifications, SiegeRepository sieges, SiegeRulesets rulesets) {
			this.fortifications = fortifications;
			this.sieges = sieges;
			this.rulesets = rulesets;
		}

		@Override
		public Step step() {
			return Step.FORTIFICATIONS;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			for (Fortification f : fortifications.findByGameIdAndProjectCompletesAfterRoundLessThanEqualOrderById(
					context.gameId(), context.round())) {
				if (sieges.existsByGameIdAndCityIdAndStatus(context.gameId(), f.cityId(), Siege.Status.ACTIVE)) {
					continue;
				}
				SiegeRules rules = rulesets.rules(f.rulesetVersion());
				int level = f.project() == FortressWorks.Project.UPGRADE ? f.projectTargetLevel() : f.level();
				f.completeProject(rules.level(level).maxIntegrity());
			}
			fortifications.flush();
		}

	}

	/**
	 * Fortress upkeep (ECONOMY §4, PROVISIONAL 1 gold × level per round): paid by the legal owner
	 * while it also controls the city; a damaged fortress, a fortress still under construction and an
	 * occupied city's fortress cost nobody.
	 */
	static class FortressUpkeep implements UpkeepSource {

		private final FortificationRepository fortifications;
		private final SiegeRulesets rulesets;
		private final WorldApi world;

		FortressUpkeep(FortificationRepository fortifications, SiegeRulesets rulesets, WorldApi world) {
			this.fortifications = fortifications;
			this.rulesets = rulesets;
			this.world = world;
		}

		@Override
		@Transactional(readOnly = true)
		public Map<UUID, Long> upkeepByNation(UUID gameId, String rulesetVersion) {
			Map<UUID, Long> total = new HashMap<>();
			var forts = fortifications.findByGameIdOrderById(gameId);
			if (forts.isEmpty()) {
				return total;
			}
			WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
			for (Fortification f : forts) {
				if (f.effectiveLevel() < 1) {
					continue;
				}
				WorldApi.ProvinceState p = snapshot.provinceOfCity(f.cityId()).orElseThrow();
				if (!p.ownerNationId().equals(p.controllerNationId())) {
					continue;
				}
				long upkeep = rulesets.rules(f.rulesetVersion()).level(f.level()).upkeepMg();
				total.merge(p.ownerNationId(), upkeep, Math::addExact);
			}
			return total;
		}

	}

	/** New games under a ruleset with fortifications get its starting fortresses (intact, ACTIVE). */
	static class StartingFortresses implements GameStartInitializer {

		private final SiegeRulesets rulesets;
		private final WorldApi world;
		private final FortificationRepository fortifications;
		private final Clock clock;

		StartingFortresses(SiegeRulesets rulesets, WorldApi world, FortificationRepository fortifications, Clock clock) {
			this.rulesets = rulesets;
			this.world = world;
			this.fortifications = fortifications;
			this.clock = clock;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void initialize(UUID gameId, UUID mapId, String rulesetVersion) {
			SiegeRules rules = rulesets.find(rulesetVersion).orElse(null);
			if (rules == null) {
				return;
			}
			WorldApi.WorldSnapshot snapshot = world.snapshot(gameId);
			rules.startingFortresses(world.mapSummary(mapId).key())
				.entrySet()
				.stream()
				.sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
				.forEach(entry -> {
					WorldApi.ProvinceState p = snapshot.province(entry.getKey())
						.orElseThrow(() -> new IllegalStateException(
								"Ruleset " + rulesetVersion + " fortifies unknown province " + entry.getKey()));
					fortifications.save(Fortification.starting(gameId, p.cityId(), entry.getValue(),
							rules.level(entry.getValue()).maxIntegrity(), rulesetVersion, clock.instant()));
				});
			fortifications.flush();
		}

	}

}
