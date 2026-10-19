package com.reach.strategy.siege.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.siege.rules.ControlDecision.ContinueSiege;
import com.reach.strategy.siege.rules.ControlDecision.LiftSiege;
import com.reach.strategy.siege.rules.ControlDecision.None;
import com.reach.strategy.siege.rules.ControlDecision.Occupy;
import com.reach.strategy.siege.rules.ControlDecision.StartSiege;
import com.reach.strategy.siege.rules.FortressWorks.Blocker;
import com.reach.strategy.siege.rules.FortressWorks.Project;
import com.reach.strategy.siege.rules.FortressWorks.Status;
import com.reach.strategy.siege.rules.SiegeMath.WorkshopCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Golden tests: the siege tables of docs/COMBAT_AND_SIEGE.md §5.3–§5.7 (ruleset v3, provisional). */
class SiegeRulesTest {

	static final SiegeRules V3 = new SiegeRules("v3",
			Map.of(1, new SiegeRules.FortressLevel(50_000, 2, 1_000, 100), 2,
					new SiegeRules.FortressLevel(100_000, 2, 2_000, 200), 3,
					new SiegeRules.FortressLevel(150_000, 2, 3_000, 300)),
			new SiegeRules.Progress(5, 1, 100, 100, 50, 1000), 500, new SiegeRules.Bombardment(10_000, 1),
			new SiegeRules.Repair(3000, 1), Map.of("varnhold", 2));

	static final UUID ME = new UUID(0, 1);
	static final UUID ENEMY = new UUID(0, 2);

	// --- duration table (§5.3) -------------------------------------------------------------

	@ParameterizedTest(name = "level {0} with {1} artillery: {2} rounds")
	@CsvSource({ "1, 0, 6", "1, 2000, 3", "1, 4000, 2", "1, 6000, 2", "2, 0, 7", "2, 2000, 4", "2, 4000, 3",
			"2, 6000, 2", "3, 0, 8", "3, 2000, 4", "3, 4000, 3", "3, 6000, 2" })
	void durationTableOfTheDesign(int level, int artilleryManpower, int rounds) {
		assertThat(SiegeMath.duration(level, artilleryManpower, V3)).isEqualTo(rounds);
	}

	@Test
	void requiredProgressGrowsWithTheFortressLevel() {
		assertThat(SiegeMath.required(1, V3)).isEqualTo(600);
		assertThat(SiegeMath.required(2, V3)).isEqualTo(700);
		assertThat(SiegeMath.required(3, V3)).isEqualTo(800);
		assertThatThrownBy(() -> SiegeMath.required(0, V3)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void partialArtilleryCountsByManpowerAndSplittingGainsNothing() {
		assertThat(SiegeMath.progressPerRound(0, V3)).as("no artillery still progresses").isEqualTo(100);
		assertThat(SiegeMath.progressPerRound(650, V3)).isEqualTo(132);
		assertThat(SiegeMath.progressPerRound(1000, V3)).isEqualTo(150);
		// Two half batteries are summed before flooring: 2 × 499 = 998 → +49, not 2 × 24.
		assertThat(SiegeMath.progressPerRound(998, V3)).isEqualTo(149);
	}

	@Test
	void siegeEngineeringSpeedsOnlyTheArtilleryPart() {
		assertThat(SiegeMath.progressPerRound(2000, V3, 5000)).as("100 + 100 × 1.5").isEqualTo(250);
		assertThat(SiegeMath.progressPerRound(650, V3, 5000)).as("100 + floor(32.5 × 1.5)").isEqualTo(148);
		assertThat(SiegeMath.progressPerRound(0, V3, 5000)).as("no artillery, no bonus").isEqualTo(100);
		assertThat(SiegeMath.progressPerRound(2000, V3, 0)).isEqualTo(SiegeMath.progressPerRound(2000, V3));
		assertThat(SiegeMath.estimatedRoundsLeft(0, SiegeMath.required(2, V3), SiegeMath.progressPerRound(2000, V3, 5000)))
			.as("level 2 with 2 batteries: 4 rounds → 3").isEqualTo(3);
	}

	@Test
	void estimatedRoundsUseTheCurrentRate() {
		assertThat(SiegeMath.estimatedRoundsLeft(0, 700, 100)).isEqualTo(7);
		assertThat(SiegeMath.estimatedRoundsLeft(600, 700, 200)).isEqualTo(1);
		assertThat(SiegeMath.estimatedRoundsLeft(700, 700, 100)).isZero();
	}

	// --- attrition (§5.4) ------------------------------------------------------------------

	@Test
	void fivePercentPerRoundFlooredExactlyAsTheDesignTable() {
		int[] expectedLosses = { 600, 570, 541, 514, 488, 464, 441 };
		int population = 12_000;
		int lost = 0;
		for (int expected : expectedLosses) {
			int loss = SiegeMath.attrition(population, V3);
			assertThat(loss).isEqualTo(expected);
			population -= loss;
			lost += loss;
		}
		assertThat(population).as("7 rounds without artillery at level 2").isEqualTo(8_382);
		assertThat(lost).isEqualTo(3_618);
	}

	@Test
	void artillerySiegesKillFewerCiviliansBecauseTheyEndSooner() {
		int population = 12_000;
		for (int round = 0; round < SiegeMath.duration(2, 2000, V3); round++) {
			population -= SiegeMath.attrition(population, V3);
		}
		assertThat(population).isEqualTo(9_775);
	}

	@Test
	void attritionNeverCreatesOrOverdrawsPeople() {
		assertThat(SiegeMath.attrition(19, V3)).isZero();
		assertThat(SiegeMath.attrition(20, V3)).isEqualTo(1);
		assertThat(SiegeMath.attrition(0, V3)).isZero();
		for (int p = 0; p < 500; p++) {
			assertThat(SiegeMath.attrition(p, V3)).isBetween(0, p);
		}
	}

	// --- bombardment (§5.6) ----------------------------------------------------------------

	@Test
	void theHighestTierActiveWorkshopIsDamagedTiesByLowestId() {
		UUID a = new UUID(0, 10);
		UUID b = new UUID(0, 11);
		UUID c = new UUID(0, 12);
		List<WorkshopCandidate> workshops = List.of(new WorkshopCandidate(b, 1, true), new WorkshopCandidate(a, 1, true),
				new WorkshopCandidate(c, 2, false));

		assertThat(SiegeMath.workshopsToDamage(workshops, V3)).as("the tier 2 is not ACTIVE").containsExactly(a);
		assertThat(SiegeMath.workshopsToDamage(List.of(new WorkshopCandidate(a, 1, true),
				new WorkshopCandidate(c, 2, true)), V3)).containsExactly(c);
		assertThat(SiegeMath.workshopsToDamage(List.of(new WorkshopCandidate(c, 2, false)), V3))
			.as("nothing else is damaged instead").isEmpty();
	}

	@Test
	void bombardmentLeavesTheFortressWithoutIntegrity() {
		assertThat(SiegeMath.integrityAfterBombardment(200, V3)).isZero();
		SiegeRules half = new SiegeRules("t", V3.levels(), V3.progress(), 500, new SiegeRules.Bombardment(5000, 1),
				V3.repair(), Map.of());
		assertThat(SiegeMath.integrityAfterBombardment(200, half)).isEqualTo(100);
	}

	// --- repairs and projects (§5.7, Q-E8) --------------------------------------------------

	@Test
	void repairsCostThirtyPercentAndFinishAtTheEndOfTheOrderRound() {
		assertThat(SiegeMath.repairCost(40_000, V3.repair())).as("Tier 1 workshop: 12 gold").isEqualTo(12_000);
		assertThat(SiegeMath.repairCost(100_000, V3.repair())).as("level 2 fortress: 30 gold").isEqualTo(30_000);
		assertThat(SiegeMath.repairCost(33_333, V3.repair())).as("rounded up").isEqualTo(10_000);
		assertThat(SiegeMath.completesAfterRound(4, 1)).isEqualTo(4);
		assertThat(SiegeMath.completesAfterRound(4, 2)).isEqualTo(5);
	}

	static FortressWorks.State state(int level, Status status, Project project, boolean besieged, long treasury) {
		return new FortressWorks.State(ME, ME, ME, true, besieged, level, status, project, treasury, false);
	}

	@Test
	void buildingAndUpgradingFortresses() {
		FortressWorks.Quote build = FortressWorks.upgrade(state(0, null, null, false, 60_000), V3);
		assertThat(build.allowed()).isTrue();
		assertThat(build.targetLevel()).isEqualTo(1);
		assertThat(build.costMg()).isEqualTo(50_000);
		assertThat(build.durationRounds()).isEqualTo(2);

		assertThat(FortressWorks.upgrade(state(2, Status.ACTIVE, null, false, 200_000), V3).costMg()).isEqualTo(150_000);
		assertThat(FortressWorks.upgrade(state(3, Status.ACTIVE, null, false, 999_000), V3).blockers())
			.containsExactly(Blocker.MAX_LEVEL_REACHED);
		assertThat(FortressWorks.upgrade(state(1, Status.DAMAGED, null, false, 999_000), V3).blockers())
			.containsExactly(Blocker.FORTRESS_DAMAGED);
		assertThat(FortressWorks.upgrade(state(1, Status.ACTIVE, Project.UPGRADE, false, 999_000), V3).blockers())
			.containsExactly(Blocker.PROJECT_IN_PROGRESS);
		assertThat(FortressWorks.upgrade(state(1, Status.ACTIVE, null, true, 999_000), V3).blockers())
			.containsExactly(Blocker.BESIEGED);
		assertThat(FortressWorks.upgrade(state(1, Status.ACTIVE, null, false, 99_999), V3).blockers())
			.containsExactly(Blocker.INSUFFICIENT_GOLD);
	}

	@Test
	void onlyTheOwnerWhoControlsTheCityBuildsOrRepairs() {
		FortressWorks.State occupied = new FortressWorks.State(ME, ME, ENEMY, true, false, 2, Status.DAMAGED, null,
				999_000, false);
		FortressWorks.State foreign = new FortressWorks.State(ME, ENEMY, ENEMY, true, false, 2, Status.DAMAGED, null,
				999_000, false);
		FortressWorks.State waiting = new FortressWorks.State(ME, ME, ME, false, false, 2, Status.DAMAGED, null, 999_000,
				true);

		assertThat(FortressWorks.repair(occupied, V3).blockers()).containsExactly(Blocker.OCCUPIED);
		assertThat(FortressWorks.repair(foreign, V3).blockers()).containsExactly(Blocker.NOT_OWNER);
		assertThat(FortressWorks.repair(waiting, V3).blockers()).containsExactly(Blocker.NOT_YOUR_TURN,
				Blocker.IN_ARREARS);
	}

	@Test
	void repairingAFortress() {
		FortressWorks.Quote repair = FortressWorks.repair(state(2, Status.DAMAGED, null, false, 30_000), V3);
		assertThat(repair.allowed()).isTrue();
		assertThat(repair.costMg()).isEqualTo(30_000);
		assertThat(repair.durationRounds()).isEqualTo(1);
		assertThat(FortressWorks.repair(state(2, Status.ACTIVE, null, false, 99_000), V3).blockers())
			.containsExactly(Blocker.NOT_DAMAGED);
		assertThat(FortressWorks.repair(state(2, Status.DAMAGED, Project.REPAIR, false, 99_000), V3).blockers())
			.containsExactly(Blocker.PROJECT_IN_PROGRESS);
		assertThat(FortressWorks.repair(state(2, Status.DAMAGED, null, true, 99_000), V3).blockers())
			.containsExactly(Blocker.BESIEGED);
	}

	@Test
	void aDamagedFortressHasNoEffectiveLevel() {
		assertThat(FortressWorks.effectiveLevel(2, Status.ACTIVE)).isEqualTo(2);
		assertThat(FortressWorks.effectiveLevel(2, Status.DAMAGED)).isZero();
	}

	// --- control decisions ------------------------------------------------------------------

	@Test
	void unfortifiedProvincesAreOccupiedFortifiedOnesBesieged() {
		assertThat(ControlDecision.decide(ENEMY, Set.of(ME), 0, null)).isEqualTo(new Occupy(ME));
		assertThat(ControlDecision.decide(ENEMY, Set.of(ME), 1, null)).isEqualTo(new StartSiege(ME));
		assertThat(ControlDecision.decide(ME, Set.of(ME), 2, null)).as("own garrison").isEqualTo(new None());
		assertThat(ControlDecision.decide(ENEMY, Set.of(), 2, null)).isEqualTo(new None());
		assertThat(ControlDecision.decide(ENEMY, Set.of(ME, ENEMY), 2, null)).isEqualTo(new None());
	}

	@Test
	void aSiegeContinuesOnlyWhileTheBesiegerHoldsTheProvince() {
		assertThat(ControlDecision.decide(ENEMY, Set.of(ME), 2, ME)).isEqualTo(new ContinueSiege());
		assertThat(ControlDecision.decide(ENEMY, Set.of(), 2, ME)).as("withdrawn or destroyed").isEqualTo(new LiftSiege());
		assertThat(ControlDecision.decide(ENEMY, Set.of(ENEMY), 2, ME)).as("relieved").isEqualTo(new LiftSiege());
	}

	@Test
	void liberatingYourOwnOccupiedFortressNeedsASiegeToo() {
		// Ostra took Varnhold by starvation (fortress intact); Varn's army returns.
		assertThat(ControlDecision.decide(ENEMY, Set.of(ME), 2, null)).isEqualTo(new StartSiege(ME));
		// After an artillery capture the fortress is damaged: simple occupation applies.
		assertThat(ControlDecision.decide(ENEMY, Set.of(ME), FortressWorks.effectiveLevel(2, Status.DAMAGED), null))
			.isEqualTo(new Occupy(ME));
	}

}
