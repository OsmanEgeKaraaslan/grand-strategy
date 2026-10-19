package com.reach.strategy.research.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.research.rules.InstitutionType.Pool;
import com.reach.strategy.research.rules.Research.Blocker;
import com.reach.strategy.research.rules.Research.InstitutionBlocker;
import com.reach.strategy.research.rules.ResearchRules.Track;
import org.junit.jupiter.api.Test;

/** Research points, unlocks, doctrine choices, effects and institution checks (ruleset v4 values). */
class ResearchRulesTest {

	static final ResearchRules V4 = v4();

	static ResearchRules v4() {
		ResearchRules.InstitutionRules institution = new ResearchRules.InstitutionRules(60_000, 2, 10, 1_000);
		Map<String, Integer> plus10 = Map.of("INFANTRY", 1000, "CAVALRY", 1000, "ARTILLERY", 1000);
		Map<String, Integer> plus20 = Map.of("INFANTRY", 2000, "CAVALRY", 2000, "ARTILLERY", 2000);
		return new ResearchRules("v4",
				Map.of(InstitutionType.MILITARY_ACADEMY, institution, InstitutionType.UNIVERSITY, institution,
						InstitutionType.BARRACKS, institution),
				List.of(new ResearchRules.MilitaryLevel(1, 0, Map.of()), new ResearchRules.MilitaryLevel(2, 40, plus10),
						new ResearchRules.MilitaryLevel(3, 80, plus20)),
				List.of(new ResearchRules.CivilianLevel(1, 0, null, 0), new ResearchRules.CivilianLevel(2, 40, 2, 0),
						new ResearchRules.CivilianLevel(3, 80, null, 25)),
				Map.of("MOUNTAIN_WARFARE", new ResearchRules.DoctrineRules(1, 30, 0, 5000, 0, 0), "RIVER_ENGINEERING",
						new ResearchRules.DoctrineRules(1, 30, 5000, 0, 0, 0), "SIEGE_ENGINEERING",
						new ResearchRules.DoctrineRules(1, 30, 0, 0, 5000, 0), "MILITARY_LOGISTICS",
						new ResearchRules.DoctrineRules(1, 30, 0, 0, 0, 2000)),
				1);
	}

	static final UUID ME = new UUID(0, 1);
	static final UUID ENEMY = new UUID(0, 2);

	// --- points -------------------------------------------------------------------------------

	@Test
	void eachActiveInstitutionFeedsItsOwnPoolAndTheyAddUp() {
		Map<Pool, Integer> points = Research.pointsPerRound(List.of(InstitutionType.MILITARY_ACADEMY,
				InstitutionType.MILITARY_ACADEMY, InstitutionType.UNIVERSITY), V4);

		assertThat(points).containsEntry(Pool.MILITARY, 20).containsEntry(Pool.CIVILIAN, 10).containsEntry(Pool.DOCTRINE, 0);
		assertThat(Research.pointsPerRound(List.of(), V4).values()).containsOnly(0);
	}

	@Test
	void anOccupiedCitysInstitutionsWorkForNobody() {
		assertThat(Research.produces(true, ME, ME)).isTrue();
		assertThat(Research.produces(true, ME, ENEMY)).isFalse();
		assertThat(Research.produces(false, ME, ME)).as("under construction").isFalse();
	}

	// --- technology ---------------------------------------------------------------------------

	@Test
	void technologyLevelsAreBoughtInOrderWithPointsUpToTheMaximum() {
		Research.Quote next = Research.technology(Track.MILITARY, 1, 40, true, V4);
		assertThat(next.targetLevel()).isEqualTo(2);
		assertThat(next.costPoints()).isEqualTo(40);
		assertThat(next.allowed()).isTrue();

		assertThat(Research.technology(Track.MILITARY, 1, 39, true, V4).blockers())
			.containsExactly(Blocker.INSUFFICIENT_POINTS);
		assertThat(Research.technology(Track.CIVILIAN, 2, 80, true, V4).costPoints()).isEqualTo(80);
		assertThat(Research.technology(Track.CIVILIAN, 3, 999, true, V4).blockers())
			.containsExactly(Blocker.MAX_LEVEL_REACHED);
		assertThat(Research.technology(Track.MILITARY, 1, 999, false, V4).blockers())
			.containsExactly(Blocker.NOT_YOUR_TURN);
	}

	@Test
	void oneDoctrineOfTheFirstTierAtATime() {
		assertThat(Research.doctrine("MOUNTAIN_WARFARE", Set.of(), 30, true, V4).allowed()).isTrue();
		assertThat(Research.doctrine("RIVER_ENGINEERING", Set.of("MOUNTAIN_WARFARE"), 99, true, V4).blockers())
			.containsExactly(Blocker.TIER_FULL);
		assertThat(Research.doctrine("MOUNTAIN_WARFARE", Set.of("MOUNTAIN_WARFARE"), 99, true, V4).blockers())
			.containsExactly(Blocker.ALREADY_CHOSEN);
		assertThat(Research.doctrine("SIEGE_ENGINEERING", Set.of(), 29, true, V4).blockers())
			.containsExactly(Blocker.INSUFFICIENT_POINTS);
		assertThat(Research.doctrine("BLITZKRIEG", Set.of(), 99, true, V4).blockers())
			.containsExactly(Blocker.UNKNOWN_DOCTRINE);
	}

	// --- effects ------------------------------------------------------------------------------

	@Test
	void effectsFollowTheLevelReachedAndTheChosenDoctrines() {
		Research.Effects start = Research.effects(1, 1, List.of(), V4);
		assertThat(start.damageBonusBps()).isEmpty();
		assertThat(start.highestUnlockedWorkshopTier()).isEqualTo(1);

		Research.Effects advanced = Research.effects(3, 3, List.of("MILITARY_LOGISTICS"), V4);
		assertThat(advanced.damageBonusBps()).containsEntry("INFANTRY", 2000).containsEntry("ARTILLERY", 2000);
		assertThat(advanced.highestUnlockedWorkshopTier()).isEqualTo(2);
		assertThat(advanced.growthBonusBps()).isEqualTo(25);
		assertThat(advanced.militaryUpkeepReductionBps()).isEqualTo(2000);
		assertThat(advanced.riverPenaltyReductionBps()).isZero();

		assertThat(Research.effects(1, 2, List.of("SIEGE_ENGINEERING"), V4).siegeArtilleryBonusBps()).isEqualTo(5000);
		assertThat(Research.civilianLevelUnlocking(2, V4)).isEqualTo(2);
	}

	// --- institutions -------------------------------------------------------------------------

	static Research.InstitutionRequest request(boolean besieged, boolean exists, long treasury) {
		return new Research.InstitutionRequest(ME, ME, ME, true, besieged, exists, treasury, false);
	}

	@Test
	void institutionsNeedTheActiveOwnerWhoControlsAnUnbesiegedCity() {
		assertThat(Research.institutionBlockers(InstitutionType.UNIVERSITY, request(false, false, 60_000), V4)).isEmpty();
		assertThat(Research.institutionBlockers(InstitutionType.UNIVERSITY, request(true, false, 60_000), V4))
			.containsExactly(InstitutionBlocker.BESIEGED);
		assertThat(Research.institutionBlockers(InstitutionType.UNIVERSITY, request(false, true, 60_000), V4))
			.containsExactly(InstitutionBlocker.ALREADY_EXISTS);
		assertThat(Research.institutionBlockers(InstitutionType.BARRACKS, request(false, false, 59_999), V4))
			.containsExactly(InstitutionBlocker.INSUFFICIENT_GOLD);
		assertThat(Research.institutionBlockers(InstitutionType.BARRACKS,
				new Research.InstitutionRequest(ME, ENEMY, ENEMY, false, false, false, 99_000, true), V4))
			.containsExactly(InstitutionBlocker.NOT_YOUR_TURN, InstitutionBlocker.NOT_OWNER, InstitutionBlocker.IN_ARREARS);
		assertThat(Research.institutionBlockers(InstitutionType.BARRACKS,
				new Research.InstitutionRequest(ME, ME, ENEMY, true, false, false, 99_000, false), V4))
			.containsExactly(InstitutionBlocker.OCCUPIED);
	}

	@Test
	void anInstitutionOrderedInRoundNFirstProducesInRoundNPlusD() {
		assertThat(Research.completesAfterRound(3, 2)).isEqualTo(4);
		assertThat(Research.completesAfterRound(3, 1)).isEqualTo(3);
	}

	// --- validation ---------------------------------------------------------------------------

	@Test
	void malformedRulesetsAreRejected() {
		ResearchRules.InstitutionRules institution = new ResearchRules.InstitutionRules(1, 1, 1, 1);
		Map<InstitutionType, ResearchRules.InstitutionRules> all = Map.of(InstitutionType.MILITARY_ACADEMY, institution,
				InstitutionType.UNIVERSITY, institution, InstitutionType.BARRACKS, institution);
		var civilian = List.of(new ResearchRules.CivilianLevel(1, 0, null, 0));
		var doctrines = Map.of("X", new ResearchRules.DoctrineRules(1, 1, 0, 0, 0, 0));

		assertThatThrownBy(() -> new ResearchRules("bad", all,
				List.of(new ResearchRules.MilitaryLevel(1, 0, Map.of()), new ResearchRules.MilitaryLevel(3, 10, Map.of())),
				civilian, doctrines, 1)).hasMessageContaining("in order");
		assertThatThrownBy(() -> new ResearchRules("bad", all,
				List.of(new ResearchRules.MilitaryLevel(1, 0, Map.of("DRAGONS", 100))), civilian, doctrines, 1))
			.hasMessageContaining("unknown unit type");
		assertThatThrownBy(() -> new ResearchRules("bad", all,
				List.of(new ResearchRules.MilitaryLevel(1, 0, Map.of()), new ResearchRules.MilitaryLevel(2, 0, Map.of())),
				civilian, doctrines, 1)).hasMessageContaining("cost points");
		assertThatThrownBy(() -> new ResearchRules("bad", Map.of(InstitutionType.UNIVERSITY, institution),
				List.of(new ResearchRules.MilitaryLevel(1, 0, Map.of())), civilian, doctrines, 1))
			.hasMessageContaining("institution");
		assertThatThrownBy(() -> new ResearchRules.DoctrineRules(1, 10, 10_001, 0, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
