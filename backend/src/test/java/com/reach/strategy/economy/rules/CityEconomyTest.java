package com.reach.strategy.economy.rules;

import static com.reach.strategy.economy.rules.WorkshopStatus.ACTIVE;
import static com.reach.strategy.economy.rules.WorkshopStatus.DAMAGED;
import static com.reach.strategy.economy.rules.WorkshopStatus.UNDER_CONSTRUCTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.economy.rules.CityEconomy.Production;
import com.reach.strategy.economy.rules.CityEconomy.Workshop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Golden tests: the worked examples of docs/ECONOMY.md §2.3 and the Phase 4 specification. */
class CityEconomyTest {

	static final EconomyRules V1 = new EconomyRules("v1", 1, 4000, 6, 50_000, 1,
			Map.of(1, new EconomyRules.WorkshopTier(3, 40_000, 1, true), 2,
					new EconomyRules.WorkshopTier(5, 100_000, 1, false)), null, null, null);

	static Workshop t1() {
		return new Workshop(1, ACTIVE);
	}

	static Workshop t2() {
		return new Workshop(2, ACTIVE);
	}

	@Test
	void agricultureOnly() {
		Production p = CityEconomy.production(12_000, List.of(), V1);

		assertThat(p.agricultureWorkers()).isEqualTo(12_000);
		assertThat(p.workersOfTier(1)).isZero();
		assertThat(p.potentialIncomeMg()).isEqualTo(12_000); // 12 gold
	}

	@Test
	void exampleA_oneTier1Workshop() {
		Production p = CityEconomy.production(12_000, List.of(t1()), V1);

		assertThat(p.workersOfTier(1)).isEqualTo(4_000);
		assertThat(p.agricultureWorkers()).isEqualTo(8_000);
		assertThat(p.incomeOfTierMg(1)).isEqualTo(12_000);
		assertThat(p.agricultureIncomeMg()).isEqualTo(8_000);
		assertThat(p.potentialIncomeMg()).isEqualTo(20_000); // 20 gold
	}

	@Test
	void exampleB_threeTier1Workshops() {
		Production p = CityEconomy.production(12_000, List.of(t1(), t1(), t1()), V1);

		assertThat(p.workersOfTier(1)).isEqualTo(12_000);
		assertThat(p.agricultureWorkers()).isZero();
		assertThat(p.potentialIncomeMg()).isEqualTo(36_000); // 36 gold
	}

	@Test
	void exampleC_tier2FillsBeforeTier1BeforeAgriculture() {
		Production p = CityEconomy.production(12_000, List.of(t1(), t2()), V1);

		assertThat(p.workersOfTier(2)).isEqualTo(4_000);
		assertThat(p.workersOfTier(1)).isEqualTo(4_000);
		assertThat(p.agricultureWorkers()).isEqualTo(4_000);
		assertThat(p.potentialIncomeMg()).isEqualTo(36_000); // 20 + 12 + 4
	}

	@Test
	void removingCiviliansEmptiesFarmsFirstThenTier1ThenTier2() {
		List<Workshop> workshops = List.of(t1(), t2());

		assertThat(CityEconomy.production(11_000, workshops, V1).potentialIncomeMg()).isEqualTo(35_000);
		Production p7000 = CityEconomy.production(7_000, workshops, V1);
		assertThat(p7000.agricultureWorkers()).isZero();
		assertThat(p7000.workersOfTier(1)).isEqualTo(3_000);
		assertThat(p7000.workersOfTier(2)).isEqualTo(4_000);
		Production p3000 = CityEconomy.production(3_000, workshops, V1);
		assertThat(p3000.workersOfTier(1)).isZero();
		assertThat(p3000.workersOfTier(2)).isEqualTo(3_000);
	}

	@Test
	void populationBelowWorkshopCapacityLeavesWorkshopsPartlyFilled() {
		Production p = CityEconomy.production(5_500, List.of(t1(), t2()), V1);

		assertThat(p.workersOfTier(2)).isEqualTo(4_000);
		assertThat(p.workersOfTier(1)).isEqualTo(1_500);
		assertThat(p.agricultureWorkers()).isZero();
		assertThat(p.potentialIncomeMg()).isEqualTo(24_500); // 24.5 gold
	}

	@Test
	void damagedWorkshopsAndUnfinishedProjectsEmployNobody() {
		Production p = CityEconomy.production(12_000,
				List.of(t1(), t1(), new Workshop(1, DAMAGED), new Workshop(1, UNDER_CONSTRUCTION)), V1);

		assertThat(p.workersOfTier(1)).isEqualTo(8_000);
		assertThat(p.agricultureWorkers()).isEqualTo(4_000);
		assertThat(p.potentialIncomeMg()).isEqualTo(28_000);
	}

	@ParameterizedTest(name = "{0} farmers produce {1} mg")
	@CsvSource({ "250, 250", "1350, 1350", "1, 1", "0, 0", "11999, 11999" })
	void exactMilligoldWithoutRounding(int population, long expectedMg) {
		assertThat(CityEconomy.production(population, List.of(), V1).potentialIncomeMg()).isEqualTo(expectedMg);
	}

	@Test
	void tier1WorkersAreExactToo() {
		// 1,350 workers in a Tier 1 workshop: 4,050 mg = 4.05 gold; nothing is lost to rounding.
		assertThat(CityEconomy.production(1_350, List.of(t1()), V1).potentialIncomeMg()).isEqualTo(4_050);
	}

	@Test
	void occupiedCitiesProduceButNobodyCollects() {
		UUID varn = UUID.randomUUID();
		UUID ostra = UUID.randomUUID();
		Production production = CityEconomy.production(12_000, List.of(t1(), t1()), V1);

		CityEconomy.Income occupied = CityEconomy.income(production, varn, ostra);

		assertThat(occupied.status()).isEqualTo(CityEconomy.IncomeStatus.OCCUPIED);
		assertThat(occupied.production().potentialIncomeMg()).isEqualTo(28_000);
		assertThat(CityEconomy.collectedBy(varn, varn, occupied)).as("owner").isZero();
		assertThat(CityEconomy.collectedBy(ostra, varn, occupied)).as("occupier").isZero();
	}

	@Test
	void liberationRestoresTheOwnersIncome() {
		UUID varn = UUID.randomUUID();
		UUID ostra = UUID.randomUUID();
		Production production = CityEconomy.production(8_000, List.of(t1()), V1);

		assertThat(CityEconomy.collectedBy(varn, varn, CityEconomy.income(production, varn, varn))).isEqualTo(16_000);
		assertThat(CityEconomy.collectedBy(varn, varn, CityEconomy.income(production, varn, ostra))).isZero();
		assertThat(CityEconomy.collectedBy(varn, varn, CityEconomy.income(production, varn, varn))).isEqualTo(16_000);
		// After a treaty makes Ostra the owner while it controls the city, Ostra collects.
		assertThat(CityEconomy.collectedBy(ostra, ostra, CityEconomy.income(production, ostra, ostra))).isEqualTo(16_000);
		assertThat(CityEconomy.collectedBy(varn, ostra, CityEconomy.income(production, ostra, ostra))).isZero();
	}

	@Test
	void calculationIsDeterministicAndIndependentOfWorkshopOrder() {
		List<Workshop> workshops = new ArrayList<>(List.of(t1(), t2(), new Workshop(1, DAMAGED), t1()));
		Production first = CityEconomy.production(9_876, workshops, V1);
		java.util.Collections.reverse(workshops);

		for (int i = 0; i < 100; i++) {
			assertThat(CityEconomy.production(9_876, workshops, V1)).isEqualTo(first);
		}
	}

	@Test
	void incomeIsMonotonicInPopulation() {
		List<Workshop> workshops = List.of(t1(), t2(), t1());
		long previous = -1;
		for (int population = 0; population <= 20_000; population += 37) {
			long income = CityEconomy.production(population, workshops, V1).potentialIncomeMg();
			assertThat(income).isGreaterThanOrEqualTo(previous);
			previous = income;
		}
	}

	@Test
	void rejectsNegativePopulation() {
		assertThatThrownBy(() -> CityEconomy.production(-1, List.of(), V1)).isInstanceOf(IllegalArgumentException.class);
	}

}
