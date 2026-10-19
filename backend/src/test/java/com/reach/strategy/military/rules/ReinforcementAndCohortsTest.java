package com.reach.strategy.military.rules;

import static com.reach.strategy.military.rules.MilitaryRulesFixture.V1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.military.rules.Cohorts.Cohort;
import com.reach.strategy.military.rules.Movement.Eligibility;
import com.reach.strategy.military.rules.Reinforcement.Blocker;
import com.reach.strategy.military.rules.Reinforcement.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Phase 6: reinforcement quotes and costs, origin cohorts, disband destinations. */
class ReinforcementAndCohortsTest {

	static final UUID ME = new UUID(0, 1);
	static final UUID ENEMY = new UUID(0, 2);
	static final UUID CITY_A = new UUID(0, 10);
	static final UUID CITY_B = new UUID(0, 11);
	static final UUID CITY_C = new UUID(0, 12);

	static Request request(int manpower, int reinforcedThisTurn, int population, long treasuryMg) {
		return new Request(ME, ME, ME, true, UnitType.INFANTRY, manpower, 1000, reinforcedThisTurn, population, treasuryMg,
				false);
	}

	// --- reinforcement ---------------------------------------------------------------------

	@Test
	void specificationExample_350ReplacementsFor650ManInfantryCost3point5Gold() {
		Reinforcement.Quote quote = Reinforcement.quote(request(650, 0, 8000, 50_000), V1);

		assertThat(quote.missing()).isEqualTo(350);
		assertThat(quote.maxSoldiers()).isEqualTo(350);
		assertThat(quote.costOfMaxMg()).isEqualTo(3_500);
		assertThat(Reinforcement.blockers(request(650, 0, 8000, 50_000), 350, V1)).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({ "INFANTRY, 350, 3500", "INFANTRY, 1, 10", "CAVALRY, 333, 8325", "ARTILLERY, 500, 20000",
			"ARTILLERY, 1, 40" })
	void costIsTheRecruitmentCostProRata(UnitType type, int soldiers, long expectedMg) {
		assertThat(Reinforcement.cost(type, soldiers, V1)).isEqualTo(expectedMg);
	}

	@Test
	void atMost500PerTurnEvenForABadlyDamagedBattalion() {
		assertThat(Reinforcement.quote(request(200, 0, 8000, 50_000), V1).maxSoldiers()).isEqualTo(500);
		assertThat(Reinforcement.quote(request(500, 300, 8000, 50_000), V1).maxSoldiers()).isEqualTo(200);
		assertThat(Reinforcement.blockers(request(200, 0, 8000, 50_000), 501, V1))
			.containsExactly(Blocker.TURN_LIMIT_REACHED);
		assertThat(Reinforcement.quote(request(700, 500, 8000, 50_000), V1).blockers())
			.containsExactly(Blocker.TURN_LIMIT_REACHED);
	}

	@Test
	void neverAboveMaximumManpower() {
		assertThat(Reinforcement.quote(request(1000, 0, 8000, 50_000), V1).blockers())
			.containsExactly(Blocker.AT_FULL_STRENGTH);
		assertThat(Reinforcement.blockers(request(900, 0, 8000, 50_000), 101, V1))
			.containsExactly(Blocker.EXCEEDS_MAXIMUM_MANPOWER);
		assertThat(Reinforcement.blockers(request(900, 0, 8000, 50_000), 100, V1)).isEmpty();
	}

	@Test
	void populationAndGoldLimitTheQuote() {
		assertThat(Reinforcement.quote(request(500, 0, 1200, 50_000), V1).maxSoldiers()).as("keeps 1,000 civilians")
			.isEqualTo(200);
		assertThat(Reinforcement.quote(request(500, 0, 8000, 1_234), V1).maxSoldiers()).as("123 × 10 mg affordable")
			.isEqualTo(123);
		assertThat(Reinforcement.quote(request(500, 0, 1000, 50_000), V1).blockers())
			.containsExactly(Blocker.INSUFFICIENT_POPULATION);
		assertThat(Reinforcement.blockers(request(500, 0, 8000, 2_999), 300, V1))
			.containsExactly(Blocker.INSUFFICIENT_GOLD);
	}

	@Test
	void onlyInAnOwnedAndControlledCityOnYourTurnAndNotInArrears() {
		assertThat(Reinforcement.quote(new Request(ME, ENEMY, ENEMY, true, UnitType.INFANTRY, 500, 1000, 0, 8000,
				50_000, false), V1).blockers()).containsExactly(Blocker.NOT_OWNED_CITY);
		assertThat(Reinforcement.quote(new Request(ME, ME, ENEMY, true, UnitType.INFANTRY, 500, 1000, 0, 8000, 50_000,
				false), V1).blockers()).containsExactly(Blocker.OCCUPIED);
		assertThat(Reinforcement.quote(new Request(ME, ME, ME, false, UnitType.INFANTRY, 500, 1000, 0, 8000, 50_000,
				true), V1).blockers()).containsExactly(Blocker.NOT_YOUR_TURN, Blocker.IN_ARREARS);
		assertThat(Reinforcement.quote(new Request(ME, ME, ENEMY, true, UnitType.INFANTRY, 500, 1000, 0, 8000, 50_000,
				false), V1).maxSoldiers()).isZero();
	}

	@Test
	void aBesiegedCityNeitherRecruitsNorReinforces() {
		assertThat(Reinforcement.quote(new Request(ME, ME, ME, true, UnitType.INFANTRY, 500, 1000, 0, 8000, 50_000,
				false, true), V1).blockers()).containsExactly(Blocker.BESIEGED);
		assertThat(Recruitment.blockers(new Recruitment.Request(ME, ME, ME, true, UnitType.INFANTRY, 8000, 50_000, false,
				true), V1)).containsExactly(Recruitment.Blocker.BESIEGED);
	}

	@Test
	void reinforcementSpendsMovementButNotDefence() {
		assertThat(Movement.eligibility(1, null, 9, 9)).isEqualTo(Eligibility.REINFORCED_THIS_TURN);
		assertThat(Movement.eligibility(1, null, 9, 10)).isEqualTo(Eligibility.READY);
		assertThat(Movement.eligibility(1, 9, 9, 9)).as("moved first, then reinforced").isEqualTo(Eligibility.ALREADY_MOVED);
	}

	// --- cohorts -----------------------------------------------------------------------------

	@Test
	void reinforcementsFormOrJoinTheCohortOfTheirCity() {
		List<Cohort> start = List.of(new Cohort(CITY_A, 600));

		List<Cohort> withB = Cohorts.add(start, CITY_B, 400);
		assertThat(withB).containsExactly(new Cohort(CITY_A, 600), new Cohort(CITY_B, 400));
		assertThat(Cohorts.add(withB, CITY_A, 0 + 1)).containsExactly(new Cohort(CITY_A, 601), new Cohort(CITY_B, 400));
	}

	@Test
	void lossesAreSplitByCohortSizeAndPreserveTheTotalExactly() {
		List<Cohort> cohorts = List.of(new Cohort(CITY_A, 600), new Cohort(CITY_B, 400));

		assertThat(Cohorts.afterLosses(cohorts, 250)).containsExactly(new Cohort(CITY_A, 450), new Cohort(CITY_B, 300));
		// 3 × 600/1000 = 1.8, 3 × 400/1000 = 1.2 → floors 1 + 1, the extra man to the larger remainder (A).
		assertThat(Cohorts.afterLosses(cohorts, 3)).containsExactly(new Cohort(CITY_A, 598), new Cohort(CITY_B, 399));
		assertThat(Cohorts.afterLosses(cohorts, 1000)).isEmpty();
	}

	@Test
	void equalRemaindersAreBrokenByOriginCityId() {
		List<Cohort> cohorts = List.of(new Cohort(CITY_B, 500), new Cohort(CITY_A, 500));

		assertThat(Cohorts.afterLosses(cohorts, 1)).containsExactly(new Cohort(CITY_A, 499), new Cohort(CITY_B, 500));
	}

	@Test
	void cohortLossesNeverGoNegativeOrExceedTheTotal() {
		List<Cohort> cohorts = List.of(new Cohort(CITY_A, 7), new Cohort(CITY_B, 2), new Cohort(CITY_C, 1));
		for (int losses = 0; losses <= 10; losses++) {
			List<Cohort> after = Cohorts.afterLosses(cohorts, losses);
			assertThat(Cohorts.total(after)).isEqualTo(10 - losses);
			for (Cohort c : after) {
				int before = cohorts.stream().filter(x -> x.originCityId().equals(c.originCityId())).findFirst().orElseThrow()
					.soldiers();
				assertThat(c.soldiers()).isBetween(1, before);
			}
		}
		assertThatThrownBy(() -> Cohorts.afterLosses(cohorts, 11)).isInstanceOf(IllegalArgumentException.class);
	}

	// --- disbanding ----------------------------------------------------------------------------

	@Test
	void specificationExample_survivorsGoBackToTheirOwnCities() {
		List<Cohort> cohorts = List.of(new Cohort(CITY_A, 500), new Cohort(CITY_B, 250));

		assertThat(Cohorts.disbandReturns(cohorts, Set.of(CITY_A, CITY_B, CITY_C), CITY_C))
			.containsExactly(Map.entry(CITY_A, 500), Map.entry(CITY_B, 250));
	}

	@Test
	void aLostOriginCitySendsItsCohortToTheLocalCity() {
		List<Cohort> cohorts = List.of(new Cohort(CITY_A, 500), new Cohort(CITY_B, 250));

		assertThat(Cohorts.disbandReturns(cohorts, Set.of(CITY_A, CITY_C), CITY_C))
			.containsExactly(Map.entry(CITY_A, 500), Map.entry(CITY_C, 250));
		assertThat(Cohorts.disbandReturns(cohorts, Set.of(CITY_A), CITY_A)).as("local city is an origin too")
			.containsExactly(Map.entry(CITY_A, 750));
	}

	@Test
	void disbandingIsOnlyAllowedOnYourTurnInYourOwnControlledProvince() {
		assertThat(Disbanding.blockers(ME, ME, true, false)).isEmpty();
		assertThat(Disbanding.blockers(ME, ENEMY, true, false)).containsExactly(Disbanding.Blocker.NOT_FRIENDLY_TERRITORY);
		assertThat(Disbanding.blockers(ME, ME, false, false)).containsExactly(Disbanding.Blocker.NOT_YOUR_TURN);
		assertThat(Disbanding.blockers(ME, ME, true, true)).as("no disbanding inside a siege")
			.containsExactly(Disbanding.Blocker.BESIEGED);
	}

	@Test
	void populationTeleportationIsImpossible() {
		// Recruited in A, lost 400, refilled from B, then disbanded: B's people return to B.
		List<Cohort> cohorts = List.of(new Cohort(CITY_A, 1000));
		cohorts = Cohorts.afterLosses(cohorts, 400);
		cohorts = Cohorts.add(cohorts, CITY_B, 400);

		assertThat(Cohorts.disbandReturns(cohorts, Set.of(CITY_A, CITY_B), CITY_A))
			.containsExactly(Map.entry(CITY_A, 600), Map.entry(CITY_B, 400));
	}

}
