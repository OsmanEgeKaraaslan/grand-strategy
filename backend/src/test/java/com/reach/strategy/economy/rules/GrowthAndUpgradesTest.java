package com.reach.strategy.economy.rules;

import static com.reach.strategy.economy.rules.WorkshopStatus.ACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.economy.rules.CityEconomy.Workshop;
import org.junit.jupiter.api.Test;

/** Phase 8: Tier 2 employment, the Tier 1 → 2 upgrade, and population growth (ruleset v4 values). */
class GrowthAndUpgradesTest {

	static final EconomyRules V4 = new EconomyRules("v4", 1, 4000, 6, 50_000, 1,
			Map.of(1, new EconomyRules.WorkshopTier(3, 40_000, 1, true), 2,
					new EconomyRules.WorkshopTier(5, 100_000, 1, false)),
			new EconomyRules.WorkshopRepair(3000, 1), new EconomyRules.WorkshopUpgrade(1, 2, 60_000, 1),
			new EconomyRules.GrowthRules(50));

	static final UUID ME = new UUID(0, 1);
	static final UUID ENEMY = new UUID(0, 2);

	@Test
	void specificationExample_oneTier2AndOneTier1WorkshopIn12000People() {
		CityEconomy.Production p = CityEconomy.production(12_000, List.of(new Workshop(1, ACTIVE), new Workshop(2, ACTIVE)),
				V4);

		assertThat(p.workersOfTier(2)).isEqualTo(4_000);
		assertThat(p.workersOfTier(1)).isEqualTo(4_000);
		assertThat(p.agricultureWorkers()).isEqualTo(4_000);
		assertThat(p.incomeOfTierMg(2)).isEqualTo(20_000);
		assertThat(p.incomeOfTierMg(1)).isEqualTo(12_000);
		assertThat(p.agricultureIncomeMg()).isEqualTo(4_000);
		assertThat(p.potentialIncomeMg()).isEqualTo(36_000);
	}

	@Test
	void tier2FillsBeforeTier1WhenPeopleAreScarce() {
		CityEconomy.Production p = CityEconomy.production(5_000, List.of(new Workshop(1, ACTIVE), new Workshop(2, ACTIVE)),
				V4);

		assertThat(p.workersOfTier(2)).isEqualTo(4_000);
		assertThat(p.workersOfTier(1)).isEqualTo(1_000);
		assertThat(p.agricultureWorkers()).isZero();
	}

	static WorkshopUpgrades.Request upgrade(int tier, WorkshopStatus status, boolean upgrading, int unlocked,
			boolean besieged, long treasury) {
		return new WorkshopUpgrades.Request(ME, ME, ME, true, besieged, tier, status, upgrading, unlocked, treasury, false);
	}

	@Test
	void anActiveTier1WorkshopUpgradesOnceTheTechnologyIsKnown() {
		assertThat(WorkshopUpgrades.blockers(upgrade(1, ACTIVE, false, 2, false, 60_000), V4)).isEmpty();
		assertThat(WorkshopUpgrades.blockers(upgrade(1, ACTIVE, false, 1, false, 60_000), V4))
			.containsExactly(WorkshopUpgrades.Blocker.TECHNOLOGY_REQUIRED);
		assertThat(WorkshopUpgrades.blockers(upgrade(2, ACTIVE, false, 2, false, 60_000), V4))
			.containsExactly(WorkshopUpgrades.Blocker.NOT_ELIGIBLE);
		assertThat(WorkshopUpgrades.blockers(upgrade(1, ACTIVE, true, 2, false, 60_000), V4))
			.as("no double upgrade").containsExactly(WorkshopUpgrades.Blocker.NOT_ELIGIBLE);
		assertThat(WorkshopUpgrades.blockers(upgrade(1, WorkshopStatus.DAMAGED, false, 2, false, 60_000), V4))
			.containsExactly(WorkshopUpgrades.Blocker.NOT_ELIGIBLE);
		assertThat(WorkshopUpgrades.blockers(upgrade(1, ACTIVE, false, 2, true, 60_000), V4))
			.containsExactly(WorkshopUpgrades.Blocker.BESIEGED);
		assertThat(WorkshopUpgrades.blockers(upgrade(1, ACTIVE, false, 2, false, 59_999), V4))
			.containsExactly(WorkshopUpgrades.Blocker.INSUFFICIENT_GOLD);
		assertThat(WorkshopUpgrades.blockers(new WorkshopUpgrades.Request(ME, ME, ENEMY, false, false, 1, ACTIVE, false, 2,
				60_000, true), V4))
			.containsExactly(WorkshopUpgrades.Blocker.NOT_YOUR_TURN, WorkshopUpgrades.Blocker.OCCUPIED,
					WorkshopUpgrades.Blocker.IN_ARREARS);
	}

	@Test
	void olderRulesetsHaveNoUpgradesAndNoGrowth() {
		EconomyRules v3 = new EconomyRules("v3", 1, 4000, 6, 50_000, 1, V4.workshopTiers(), V4.workshopRepair(), null,
				null);

		assertThat(WorkshopUpgrades.blockers(upgrade(1, ACTIVE, false, 2, false, 99_000), v3))
			.containsExactly(WorkshopUpgrades.Blocker.NOT_AVAILABLE_IN_RULESET);
		assertThat(PopulationGrowth.growth(12_000, 0, v3)).isZero();
		assertThat(PopulationGrowth.blockers(ME, ME, false, v3))
			.containsExactly(PopulationGrowth.Blocker.NOT_AVAILABLE_IN_RULESET);
	}

	// --- growth -------------------------------------------------------------------------------

	@Test
	void halfAPercentPerRoundFloored() {
		assertThat(PopulationGrowth.growth(12_000, 0, V4)).isEqualTo(60);
		assertThat(PopulationGrowth.growth(8_000, 0, V4)).isEqualTo(40);
		assertThat(PopulationGrowth.growth(7_650, 0, V4)).as("38.25 → 38").isEqualTo(38);
		assertThat(PopulationGrowth.growth(199, 0, V4)).as("small towns do not grow by rounding").isZero();
		assertThat(PopulationGrowth.growth(0, 0, V4)).isZero();
		assertThat(PopulationGrowth.growth(12_000, 25, V4)).as("civilian technology bonus").isEqualTo(90);
	}

	@Test
	void repeatedGrowthCompoundsOnTheWholePeople() {
		int population = 10_000;
		for (int round = 0; round < 3; round++) {
			population += PopulationGrowth.growth(population, 0, V4);
		}
		assertThat(population).as("10,000 → 10,050 → 10,100 → 10,150").isEqualTo(10_150);
	}

	@Test
	void growthUsesLongArithmeticAndRefusesToOverflow() {
		assertThat(PopulationGrowth.growth(2_000_000_000, 0, V4)).isEqualTo(10_000_000);
		assertThatThrownBy(() -> PopulationGrowth.growth(Integer.MAX_VALUE - 10, 0, V4))
			.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void besiegedAndOccupiedCitiesDoNotGrow() {
		assertThat(PopulationGrowth.blockers(ME, ME, false, V4)).isEmpty();
		assertThat(PopulationGrowth.blockers(ME, ENEMY, false, V4)).containsExactly(PopulationGrowth.Blocker.OCCUPIED);
		assertThat(PopulationGrowth.blockers(ME, ME, true, V4)).containsExactly(PopulationGrowth.Blocker.BESIEGED);
	}

}
