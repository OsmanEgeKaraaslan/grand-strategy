package com.reach.strategy.military.rules;

import static com.reach.strategy.military.rules.MilitaryRulesFixture.V1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.military.rules.Movement.Eligibility;
import com.reach.strategy.military.rules.Recruitment.Blocker;
import com.reach.strategy.military.rules.Recruitment.Request;
import com.reach.strategy.military.rules.Retreat.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Recruitment, upkeep, movement eligibility, retreat and occupation rules. */
class OtherMilitaryRulesTest {

	static final UUID ME = new UUID(0, 1);
	static final UUID ENEMY = new UUID(0, 2);

	static Request request(UnitType type, int population, long treasuryMg) {
		return new Request(ME, ME, ME, true, type, population, treasuryMg, false);
	}

	@Test
	void recruitingWithEnoughGoldAndPopulationIsAllowed() {
		assertThat(Recruitment.blockers(request(UnitType.INFANTRY, 2000, 10_000), V1)).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({ "INFANTRY, 9999", "CAVALRY, 24999", "ARTILLERY, 39999" })
	void eachTypeCostsItsRulesetPrice(UnitType type, long treasury) {
		assertThat(Recruitment.blockers(request(type, 12_000, treasury), V1)).containsExactly(Blocker.INSUFFICIENT_GOLD);
		assertThat(Recruitment.blockers(request(type, 12_000, treasury + 1), V1)).isEmpty();
	}

	@Test
	void theCityMustKeepItsMinimumPopulation() {
		assertThat(Recruitment.blockers(request(UnitType.INFANTRY, 1999, 50_000), V1))
			.containsExactly(Blocker.INSUFFICIENT_POPULATION);
	}

	@Test
	void onlyTheOwnerWhoAlsoControlsTheCityRecruits() {
		assertThat(Recruitment.blockers(new Request(ME, ENEMY, ENEMY, true, UnitType.INFANTRY, 12_000, 50_000, false), V1))
			.containsExactly(Blocker.NOT_OWNER);
		assertThat(Recruitment.blockers(new Request(ME, ME, ENEMY, true, UnitType.INFANTRY, 12_000, 50_000, false), V1))
			.containsExactly(Blocker.OCCUPIED);
	}

	@Test
	void notOnYourTurnOrInArrears() {
		assertThat(Recruitment.blockers(new Request(ME, ME, ME, false, UnitType.INFANTRY, 12_000, 50_000, true), V1))
			.containsExactly(Blocker.NOT_YOUR_TURN, Blocker.IN_ARREARS);
	}

	@ParameterizedTest
	@CsvSource({ "INFANTRY, 1000, 500", "CAVALRY, 1000, 1500", "ARTILLERY, 1000, 2000", "INFANTRY, 650, 325",
			"CAVALRY, 333, 499", "ARTILLERY, 1, 2", "INFANTRY, 1, 0" })
	void upkeepIsProportionalToManpowerAndFloored(UnitType type, int manpower, long expectedMg) {
		assertThat(Upkeep.of(type, manpower, 1000, V1)).isEqualTo(expectedMg);
	}

	@Test
	void upkeepRejectsImpossibleManpower() {
		assertThatThrownBy(() -> Upkeep.of(UnitType.INFANTRY, 1001, 1000, V1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void newRecruitsCannotMoveThisTurnButCanNextTurn() {
		assertThat(Movement.eligibility(7, null, null, 7)).isEqualTo(Eligibility.RECRUITED_THIS_TURN);
		assertThat(Movement.eligibility(7, null, null, 8)).isEqualTo(Eligibility.READY);
	}

	@Test
	void aBattalionMovesOncePerTurn() {
		assertThat(Movement.eligibility(1, 9, null, 9)).isEqualTo(Eligibility.ALREADY_MOVED);
		assertThat(Movement.eligibility(1, 9, null, 10)).isEqualTo(Eligibility.READY);
	}

	@Test
	void retreatPrefersTheFriendlyProvinceWithMostBattalionsThenLowestKey() {
		List<Candidate> neighbours = List.of(new Candidate("b", ME, 1, 0), new Candidate("a", ME, 1, 0),
				new Candidate("c", ME, 3, 1), new Candidate("d", ENEMY, 5, 0));

		assertThat(Retreat.destination(ME, neighbours)).contains("a");
		assertThat(Retreat.destination(ME, List.of(new Candidate("b", ME, 0, 0), new Candidate("e", ME, 2, 0))))
			.contains("e");
	}

	@Test
	void noFriendlyNeighbourMeansNoRetreat() {
		assertThat(Retreat.destination(ME, List.of(new Candidate("d", ENEMY, 0, 0), new Candidate("c", ME, 0, 2))))
			.isEmpty();
	}

}
