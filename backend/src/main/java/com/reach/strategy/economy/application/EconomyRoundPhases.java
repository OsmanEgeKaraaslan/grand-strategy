package com.reach.strategy.economy.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.reach.strategy.economy.api.EconomyTechnology;
import com.reach.strategy.economy.api.SiegeStatus;
import com.reach.strategy.economy.domain.EconomyReportRepository;
import com.reach.strategy.economy.domain.EconomyStore;
import com.reach.strategy.economy.rules.EconomyRules;
import com.reach.strategy.economy.rules.Ledger;
import com.reach.strategy.economy.rules.PopulationGrowth;
import com.reach.strategy.economy.rules.Treasury;
import com.reach.strategy.economy.rules.WorkshopStatus;
import com.reach.strategy.game.api.RoundPhase;
import com.reach.strategy.world.api.WorldApi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The economy's round-end phases (income, growth, construction) (docs/TURN_SYSTEM.md §5). Both run inside the final
 * end-turn transaction: if anything fails, no nation is paid and no project completes.
 */
@Configuration
class EconomyRoundPhases {

	@Bean
	RoundPhase incomePhase(EconomyRulesets rulesets, EconomyState state, EconomyStore store,
			EconomyReportRepository reports, TreasuryService treasuryService) {
		return new IncomePhase(rulesets, state, store, reports, treasuryService);
	}

	@Bean
	RoundPhase constructionPhase(EconomyStore store) {
		return new ConstructionPhase(store);
	}

	@Bean
	RoundPhase growthPhase(EconomyRulesets rulesets, WorldApi world, EconomyReportRepository reports,
			List<SiegeStatus> siegeStatus, List<EconomyTechnology> technology) {
		return new GrowthPhase(rulesets, world, reports, siegeStatus, technology);
	}

	/**
	 * Income → upkeep → settlement (APPROVED order Q-E1, deficits Q-E1b) for every nation, in nation
	 * id order. Income is calculated from the buildings that are ACTIVE now, i.e. before this
	 * round's projects complete (Q-E8). Occupied cities credit nobody (Q-W2).
	 */
	static class IncomePhase implements RoundPhase {

		private final EconomyRulesets rulesets;
		private final EconomyState state;
		private final EconomyStore store;
		private final EconomyReportRepository reports;
		private final TreasuryService treasuryService;

		IncomePhase(EconomyRulesets rulesets, EconomyState state, EconomyStore store,
				EconomyReportRepository reports, TreasuryService treasuryService) {
			this.rulesets = rulesets;
			this.state = state;
			this.store = store;
			this.reports = reports;
			this.treasuryService = treasuryService;
		}

		@Override
		public Step step() {
			return Step.ECONOMY;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			EconomyRules rules = rulesets.rules(context.rulesetVersion());
			EconomyState.Snapshot snapshot = state.load(context.gameId(), rules);
			Ledger before = Ledger.of(store.purses(context.gameId()), List.of());
			Map<UUID, Ledger.Purse> treasuries = before.purses()
				.stream()
				.collect(Collectors.toMap(Ledger.Purse::nationId, Function.identity()));
			Ledger[] after = { before };
			snapshot.world()
				.nations()
				.stream()
				.map(WorldApi.NationState::id)
				.sorted()
				.forEach(nationId -> {
					Ledger.Purse treasury = treasuries.get(nationId);
					if (treasury == null) {
						throw new IllegalStateException("Nation " + nationId + " has no treasury");
					}
					long income = snapshot.collectibleIncomeOf(nationId);
					// Every UpkeepSource (battalions since Phase 5; fortresses in Phase 7) as of now:
					// units destroyed earlier in this round cost nothing.
					long upkeep = treasuryService.upkeepOf(context.gameId(), context.rulesetVersion(), nationId);
					Treasury.Settlement settlement = Treasury.settle(treasury.treasuryMg(), income, upkeep);
					after[0] = after[0].settle(nationId, settlement);
					reports.insert(context.gameId(), context.round(), nationId, settlement, context.now());
				});
			store.persist(context.gameId(), before, after[0]);
		}

	}

	/**
	 * Q-E8: projects and repairs due this round become ACTIVE — after income, so they earn from the
	 * next round.
	 */
	static class ConstructionPhase implements RoundPhase {

		private final EconomyStore store;

		ConstructionPhase(EconomyStore store) {
			this.store = store;
		}

		@Override
		public Step step() {
			return Step.CONSTRUCTION;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			int round = context.round();
			Ledger before = Ledger.of(List.of(), store.buildings(context.gameId()));
			Ledger after = before;
			for (Ledger.Building b : before.buildings()) {
				Ledger.Building done = b;
				if (done.status() == WorkshopStatus.UNDER_CONSTRUCTION && done.completesAfterRound() != null
						&& done.completesAfterRound() <= round) {
					done = done.completed();
				}
				if (done.status() == WorkshopStatus.UNDER_REPAIR && done.repairCompletesAfterRound() != null
						&& done.repairCompletesAfterRound() <= round) {
					done = done.repairCompleted();
				}
				if (done.upgradeCompletesAfterRound() != null && done.upgradeCompletesAfterRound() <= round) {
					done = done.upgradeCompleted();
				}
				if (done != b) {
					after = after.update(done);
				}
			}
			store.persist(context.gameId(), before, after);
		}

	}


	/**
	 * Step GROWTH (Phase 8, PROVISIONAL Q-E6): after income, every city that is owned and controlled
	 * by the same nation and not besieged gains {@code floor(P × (base + civilian bonus) / 10,000)}
	 * people, in city order. Besieged cities suffer attrition instead (never both); occupied cities do
	 * not grow. The new people first work in the next round. Games before v4 have no growth.
	 */
	static class GrowthPhase implements RoundPhase {

		private final EconomyRulesets rulesets;
		private final WorldApi world;
		private final EconomyReportRepository reports;
		private final List<SiegeStatus> siegeStatus;
		private final List<EconomyTechnology> technology;

		GrowthPhase(EconomyRulesets rulesets, WorldApi world, EconomyReportRepository reports,
				List<SiegeStatus> siegeStatus, List<EconomyTechnology> technology) {
			this.rulesets = rulesets;
			this.world = world;
			this.reports = reports;
			this.siegeStatus = List.copyOf(siegeStatus);
			this.technology = List.copyOf(technology);
		}

		@Override
		public Step step() {
			return Step.GROWTH;
		}

		@Override
		@Transactional(propagation = Propagation.MANDATORY)
		public void apply(RoundContext context) {
			EconomyRules rules = rulesets.rules(context.rulesetVersion());
			if (rules.populationGrowth() == null) {
				return;
			}
			UUID gameId = context.gameId();
			world.snapshot(gameId)
				.provinces()
				.stream()
				.sorted(java.util.Comparator.comparing(p -> p.cityId().toString()))
				.forEach(p -> {
					boolean besieged = siegeStatus.stream().anyMatch(s -> s.cityBesieged(gameId, p.cityId()));
					if (!PopulationGrowth.blockers(p.ownerNationId(), p.controllerNationId(), besieged, rules).isEmpty()) {
						return;
					}
					int bonus = technology.stream().mapToInt(t -> t.growthBonusBps(gameId, p.ownerNationId())).max().orElse(0);
					int growth = PopulationGrowth.growth(p.population(), bonus, rules);
					reports.insertGrowth(gameId, context.round(), p.cityId(), p.population(), growth);
					if (growth > 0) {
						world.changePopulation(gameId, p.cityId(), growth);
					}
				});
		}

	}

}
