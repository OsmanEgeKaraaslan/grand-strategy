package com.reach.strategy.military.rules;

import static com.reach.strategy.military.rules.MilitaryRulesFixture.V1;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.military.rules.Battle.Fighter;
import com.reach.strategy.military.rules.Battle.Input;
import com.reach.strategy.military.rules.Battle.Result;
import com.reach.strategy.military.rules.Battle.SideModifiers;
import com.reach.strategy.military.rules.Movement.Clock;
import com.reach.strategy.military.rules.Movement.Eligibility;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 coalition battles (COMBAT_AND_SIEGE §3.6): every battalion fights with its own nation's
 * modifiers, a side of one nation fights exactly as before, losses are booked per battalion; and
 * round-based movement eligibility for ruleset v6.
 */
class CoalitionBattleTest {

	private static int nextId = 1;

	private static final SideModifiers INFANTRY_PLUS_50 = new SideModifiers(Map.of(UnitType.INFANTRY, 5_000), 0, 0);

	private static List<Fighter> infantry(int count, SideModifiers modifiers) {
		List<Fighter> fighters = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			fighters.add(new Fighter(new UUID(0, nextId++), UnitType.INFANTRY, 1000, modifiers));
		}
		return fighters;
	}

	private static List<Fighter> concat(List<Fighter> a, List<Fighter> b) {
		List<Fighter> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
	}

	@Test
	void aSideOfOneNationFightsExactlyAsWithSideModifiers() {
		List<Fighter> perFighter = infantry(6, INFANTRY_PLUS_50);
		List<Fighter> defenders = infantry(6, null);
		Result coalition = Battle.resolve(new Input(perFighter, defenders, false, false), V1);
		List<Fighter> plain = perFighter.stream().map(f -> new Fighter(f.id(), f.type(), f.manpower())).toList();
		Result classic = Battle.resolve(new Input(plain, defenders, false, false, INFANTRY_PLUS_50, SideModifiers.NONE),
				V1);
		assertThat(coalition.attackerLosses()).isEqualTo(classic.attackerLosses());
		assertThat(coalition.defenderLosses()).isEqualTo(classic.defenderLosses());
		assertThat(coalition.losses()).isEqualTo(classic.losses());
		assertThat(coalition.winner()).isEqualTo(classic.winner());
	}

	@Test
	void twoNationsWithoutModifiersFightLikeOne() {
		List<Fighter> a = concat(infantry(3, SideModifiers.NONE), infantry(3, SideModifiers.NONE));
		List<Fighter> b = infantry(6, null);
		Result split = Battle.resolve(new Input(a, b, true, false), V1);
		Result whole = Battle.resolve(new Input(a.stream().map(f -> new Fighter(f.id(), f.type(), f.manpower())).toList(),
				b, true, false), V1);
		assertThat(split.losses()).isEqualTo(whole.losses());
	}

	@Test
	void eachNationKeepsItsOwnTechnology() {
		List<Fighter> defenders = infantry(8, null);
		Result none = Battle.resolve(
				new Input(concat(infantry(3, SideModifiers.NONE), infantry(3, SideModifiers.NONE)), defenders, false, false),
				V1);
		Result mixed = Battle.resolve(
				new Input(concat(infantry(3, INFANTRY_PLUS_50), infantry(3, SideModifiers.NONE)), defenders, false, false),
				V1);
		Result both = Battle.resolve(
				new Input(concat(infantry(3, INFANTRY_PLUS_50), infantry(3, INFANTRY_PLUS_50)), defenders, false, false), V1);
		// One ally's technology helps the coalition, but does not give the other ally's men its bonus.
		assertThat(mixed.defenderLosses()).isGreaterThan(none.defenderLosses());
		assertThat(mixed.defenderLosses()).isLessThan(both.defenderLosses());
	}

	@Test
	void everyLossIsBookedOnABattalionOfTheRightSide() {
		List<Fighter> a = concat(infantry(2, INFANTRY_PLUS_50), infantry(4, SideModifiers.NONE));
		List<Fighter> b = infantry(5, null);
		Result r = Battle.resolve(new Input(a, b, false, true), V1);
		assertThat(a.stream().mapToInt(f -> r.losses().get(f.id())).sum()).isEqualTo(r.attackerLosses());
		assertThat(b.stream().mapToInt(f -> r.losses().get(f.id())).sum()).isEqualTo(r.defenderLosses());
		assertThat(r.losses().values()).allMatch(l -> l >= 0 && l <= 1000);
	}

	@Test
	void theResultDoesNotDependOnTheOrderOfTheFighters() {
		List<Fighter> a = concat(infantry(2, INFANTRY_PLUS_50), infantry(4, SideModifiers.NONE));
		List<Fighter> b = infantry(5, null);
		List<Fighter> reversed = new ArrayList<>(a);
		java.util.Collections.reverse(reversed);
		assertThat(Battle.resolve(new Input(reversed, b, false, false), V1).losses())
			.isEqualTo(Battle.resolve(new Input(a, b, false, false), V1).losses());
	}

	// --- movement per global round (v6) ----------------------------------------------------------

	@Test
	void inRoundBasedMovementABattalionMovesOncePerRoundWhoeverCommandsIt() {
		Clock round3turn7 = new Clock(7, 3, true);
		Clock round3turn8 = new Clock(8, 3, true);
		Clock round4 = new Clock(10, 4, true);
		// Moved by its commander in turn 7 of round 3: not again in turn 8 (its owner's turn) of round 3.
		assertThat(Movement.eligibility(0, 0, 7, 3, null, null, round3turn8)).isEqualTo(Eligibility.ALREADY_MOVED);
		assertThat(Movement.eligibility(0, 0, 7, 3, null, null, round4)).isEqualTo(Eligibility.READY);
		// Recruited in round 3: not before round 4; starting forces always ready.
		assertThat(Movement.eligibility(7, 3, null, null, null, null, round3turn8))
			.isEqualTo(Eligibility.RECRUITED_THIS_TURN);
		assertThat(Movement.eligibility(0, 0, null, null, null, null, new Clock(1, 0, true))).isEqualTo(Eligibility.READY);
		assertThat(Movement.eligibility(0, 0, null, null, 7, 3, round3turn7)).isEqualTo(Eligibility.REINFORCED_THIS_TURN);
	}

	@Test
	void turnBasedMovementIsUnchangedForOlderRulesets() {
		assertThat(Movement.eligibility(0, 0, 7, 3, null, null, Clock.turn(8))).isEqualTo(Eligibility.READY);
		assertThat(Movement.eligibility(0, 0, 8, 3, null, null, Clock.turn(8))).isEqualTo(Eligibility.ALREADY_MOVED);
	}

}
