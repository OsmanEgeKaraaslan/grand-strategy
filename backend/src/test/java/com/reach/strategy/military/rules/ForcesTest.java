package com.reach.strategy.military.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.military.rules.Cohorts.Cohort;
import org.junit.jupiter.api.Test;

/** The immutable military state (ENGINE_DESIGN S2c): transitions, invariants and ordering. */
class ForcesTest {

	static final UUID ME = new UUID(0, 1);
	static final UUID ALLY = new UUID(0, 2);
	static final UUID CITY_A = new UUID(0, 10);
	static final UUID CITY_B = new UUID(0, 11);
	static final UUID HERE = new UUID(0, 20);
	static final UUID THERE = new UUID(0, 21);
	static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
	static final Movement.Clock ROUND_3 = new Movement.Clock(7, 3, true);

	static Forces.Unit unit(long id) {
		return Forces.Unit.raised(new UUID(0, id), ME, UnitType.INFANTRY, 1000, CITY_A, HERE, 10_000, 1, 1, NOW);
	}

	@Test
	void aRaisedBattalionHasOneCohortFromItsOriginCity() {
		Forces forces = Forces.empty().raise(unit(100));

		assertThat(forces.units()).hasSize(1);
		assertThat(forces.cohortsOf(new UUID(0, 100))).containsExactly(new Cohort(CITY_A, 1000));
		assertThat(Forces.empty().units()).isEmpty();
	}

	@Test
	void changesNeverTouchTheOriginalAndKeepUnchangedBattalionsIdentical() {
		Forces before = Forces.empty().raise(unit(100)).raise(unit(101));
		Forces.Unit untouched = before.unit(new UUID(0, 101)).orElseThrow();

		Forces after = before.update(before.unit(new UUID(0, 100)).orElseThrow().movedTo(THERE, ROUND_3));

		assertThat(before.unit(new UUID(0, 100)).orElseThrow().provinceStateId()).isEqualTo(HERE);
		assertThat(after.unit(new UUID(0, 100)).orElseThrow().provinceStateId()).isEqualTo(THERE);
		assertThat(after.unit(new UUID(0, 101)).orElseThrow()).isSameAs(untouched);
	}

	@Test
	void movingSpendsTheRoundAndTurnReinforcingSpendsMovementAndCountsTowardTheTurnLimit() {
		Forces.Unit moved = unit(100).movedTo(THERE, ROUND_3);
		assertThat(moved.lastMovedTurnNumber()).isEqualTo(7);
		assertThat(moved.lastMovedRound()).isEqualTo(3);
		assertThat(moved.eligibility(ROUND_3)).isEqualTo(Movement.Eligibility.ALREADY_MOVED);

		Forces forces = Forces.empty().raise(unit(100)).suffer(new UUID(0, 100), 400);
		forces = forces.reinforce(new UUID(0, 100), CITY_B, 150, ROUND_3);
		forces = forces.reinforce(new UUID(0, 100), CITY_B, 50, ROUND_3);
		Forces.Unit reinforced = forces.unit(new UUID(0, 100)).orElseThrow();

		assertThat(reinforced.manpower()).isEqualTo(800);
		assertThat(reinforced.reinforcedInTurn(7)).isEqualTo(200);
		assertThat(reinforced.reinforcedInTurn(8)).isZero();
		assertThat(reinforced.eligibility(ROUND_3)).isEqualTo(Movement.Eligibility.REINFORCED_THIS_TURN);
		assertThat(forces.cohortsOf(new UUID(0, 100))).containsExactly(new Cohort(CITY_A, 600), new Cohort(CITY_B, 200));
	}

	@Test
	void lossesComeFromTheCohortsProportionallyAndAWipedOutBattalionIsRemoved() {
		Forces forces = Forces.empty().raise(unit(100)).suffer(new UUID(0, 100), 500);
		forces = forces.reinforce(new UUID(0, 100), CITY_B, 500, Movement.Clock.turn(2));

		Forces hit = forces.suffer(new UUID(0, 100), 100);
		assertThat(hit.unit(new UUID(0, 100)).orElseThrow().manpower()).isEqualTo(900);
		assertThat(hit.cohortsOf(new UUID(0, 100))).containsExactly(new Cohort(CITY_A, 450), new Cohort(CITY_B, 450));
		assertThat(forces.suffer(new UUID(0, 100), 0)).isSameAs(forces);

		Forces wiped = forces.suffer(new UUID(0, 100), 1000);
		assertThat(wiped.unit(new UUID(0, 100))).isEmpty();
		assertThatThrownBy(() -> wiped.cohortsOf(new UUID(0, 100))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void updateRefusesUnknownBattalionsAndChangedManpower() {
		Forces forces = Forces.empty().raise(unit(100));

		assertThatThrownBy(() -> forces.update(unit(999))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> forces.update(unit(100).afterLosses(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> forces.raise(unit(100))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void storedCohortsMustAddUpToTheManpower() {
		assertThatThrownBy(() -> Forces.of(List.of(unit(100)), List.of(),
				Map.of(new UUID(0, 100), List.of(new Cohort(CITY_A, 999)))))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> Forces.of(List.of(unit(100)), List.of(), Map.of()))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void battalionsLeavingWithoutTheRestOfTheirArmyLeaveItAndEmptyArmiesAreRemoved() {
		UUID army = new UUID(0, 500);
		Forces forces = Forces.empty()
			.form(new Forces.Formation(army, ME, "First", NOW))
			.raise(unit(100))
			.raise(unit(101));
		forces = forces.update(forces.units().stream().map(u -> u.inArmy(army)).toList());

		List<Forces.Unit> one = forces.leavingSplitArmies(List.of(forces.unit(new UUID(0, 100)).orElseThrow()));
		assertThat(one.getFirst().armyId()).isNull();
		List<Forces.Unit> both = forces.leavingSplitArmies(forces.units());
		assertThat(both).allMatch(u -> army.equals(u.armyId()));

		assertThat(forces.withoutEmptyFormations(Set.of(army)).formation(army)).isPresent();
		assertThatThrownBy(() -> Forces.empty().form(new Forces.Formation(army, ME, "A", NOW))
			.raise(unit(100).inArmy(army))
			.disband(army)).isInstanceOf(IllegalArgumentException.class);
		Forces emptied = forces.update(forces.units().stream().map(u -> u.inArmy(null)).toList());
		assertThat(emptied.withoutEmptyFormations(Set.of(army)).formations()).isEmpty();
	}

	@Test
	void attachingLeavesTheArmyAndDetachingRestoresTheOwnersCommand() {
		Forces.Unit attached = unit(100).inArmy(new UUID(0, 500)).attachedTo(ALLY);
		assertThat(attached.commander()).isEqualTo(ALLY);
		assertThat(attached.armyId()).isNull();
		assertThat(attached.detached().commander()).isEqualTo(ME);
	}

	@Test
	void iterationFollowsTheDatabaseOrderOfUuids() {
		List<UUID> ids = new ArrayList<>();
		for (long i = 0; i < 50; i++) {
			ids.add(new UUID(i * 0x1234_5678_9ABC_DEFL, -i * 31));
		}
		ids.add(new UUID(Long.MIN_VALUE, 0));
		ids.add(new UUID(-1, -1));
		Forces forces = Forces.empty();
		for (UUID id : ids) {
			forces = forces.raise(Forces.Unit.raised(id, ME, UnitType.CAVALRY, 600, CITY_A, HERE, 0, 0, 0, NOW));
		}

		List<UUID> expected = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
		assertThat(forces.units().stream().map(Forces.Unit::id).toList()).isEqualTo(expected);
	}

}
