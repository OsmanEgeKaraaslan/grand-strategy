package com.reach.strategy.military.rules;

import static com.reach.strategy.military.rules.MilitaryRulesFixture.V1;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.military.rules.Battle.Fighter;
import com.reach.strategy.military.rules.Battle.Input;
import com.reach.strategy.military.rules.Battle.Result;
import com.reach.strategy.military.rules.Battle.Side;
import org.junit.jupiter.api.Test;

/** Golden tests: the worked examples of docs/COMBAT_AND_SIEGE.md §3 (provisional coefficients). */
class BattleTest {

	private static int nextId = 1;

	static List<Fighter> army(int infantry, int cavalry, int artillery) {
		List<Fighter> fighters = new ArrayList<>();
		add(fighters, UnitType.INFANTRY, infantry);
		add(fighters, UnitType.CAVALRY, cavalry);
		add(fighters, UnitType.ARTILLERY, artillery);
		return fighters;
	}

	private static void add(List<Fighter> fighters, UnitType type, int count) {
		for (int i = 0; i < count; i++) {
			fighters.add(new Fighter(new UUID(0, nextId++), type, 1000));
		}
	}

	static int lostOfType(Result result, List<Fighter> side, UnitType type) {
		return side.stream().filter(f -> f.type() == type).mapToInt(f -> result.losses().get(f.id())).sum();
	}

	static int lostAfterPhase(Result result, int phase, Side side) {
		Battle.Phase p = result.phases().get(phase - 1);
		return side == Side.ATTACKER ? result.attackerInitial() - p.attacker().manpowerAfter()
				: result.defenderInitial() - p.defender().manpowerAfter();
	}

	@Test
	void example1_combinedArmsBeatsInfantryOnPlains() {
		List<Fighter> a = army(6, 2, 2);
		List<Fighter> b = army(8, 2, 0);
		Result r = Battle.resolve(new Input(a, b, false, false), V1);

		assertThat(lostAfterPhase(r, 1, Side.ATTACKER)).isEqualTo(412);
		assertThat(lostAfterPhase(r, 1, Side.DEFENDER)).isEqualTo(490);
		assertThat(lostAfterPhase(r, 2, Side.ATTACKER)).isEqualTo(803);
		assertThat(lostAfterPhase(r, 2, Side.DEFENDER)).isEqualTo(961);
		assertThat(r.attackerLosses()).isEqualTo(1175);
		assertThat(r.defenderLosses()).isEqualTo(1414);
		assertThat(lostOfType(r, a, UnitType.INFANTRY)).isEqualTo(734);
		assertThat(lostOfType(r, a, UnitType.CAVALRY)).isEqualTo(321);
		assertThat(lostOfType(r, a, UnitType.ARTILLERY)).isEqualTo(120);
		assertThat(lostOfType(r, b, UnitType.INFANTRY)).isEqualTo(1066);
		assertThat(lostOfType(r, b, UnitType.CAVALRY)).isEqualTo(348);
		assertThat(r.phases()).hasSize(3);
		assertThat(r.broken()).isNull();
		assertThat(r.winner()).isEqualTo(Side.ATTACKER);
	}

	@Test
	void example2_riverAndMountainsFavourTheDefender() {
		Result r = Battle.resolve(new Input(army(6, 2, 2), army(8, 2, 0), true, true), V1);

		assertThat(r.phases().get(0).attacker().terrainMultiplier()).isEqualByComparingTo("0.52");
		assertThat(r.phases().get(0).attacker().damage()).isEqualByComparingTo("6.5936");
		assertThat(r.phases().get(0).defender().damage()).isEqualByComparingTo("9.68");
		assertThat(lostAfterPhase(r, 1, Side.ATTACKER)).isEqualTo(368);
		assertThat(lostAfterPhase(r, 1, Side.DEFENDER)).isEqualTo(233);
		assertThat(r.attackerLosses()).isEqualTo(1078);
		assertThat(r.defenderLosses()).isEqualTo(678);
		assertThat(r.winner()).isEqualTo(Side.DEFENDER);
	}

	@Test
	void example2b_overwhelmingTheDefendedPosition() {
		Result r = Battle.resolve(new Input(army(10, 2, 4), army(8, 2, 0), true, true), V1);

		assertThat(r.attackerLosses()).isEqualTo(1057);
		assertThat(r.defenderLosses()).isEqualTo(1176);
		assertThat(r.winner()).isEqualTo(Side.ATTACKER);
	}

	@Test
	void example3_unprotectedArtilleryLoses() {
		Result r = Battle.resolve(new Input(army(0, 0, 6), army(6, 0, 0), false, false), V1);

		assertThat(r.phases().get(0).attacker().protection()).isEqualByComparingTo("0");
		assertThat(r.phases().get(0).attacker().artilleryEffectiveness()).isEqualByComparingTo("0.4");
		assertThat(r.phases().get(0).attacker().damage()).isEqualByComparingTo("6.0");
		assertThat(lostAfterPhase(r, 1, Side.ATTACKER)).isEqualTo(480);
		assertThat(lostAfterPhase(r, 1, Side.DEFENDER)).isEqualTo(200);
		assertThat(r.attackerLosses()).isEqualTo(1393);
		assertThat(r.defenderLosses()).isEqualTo(552);
		assertThat(r.winner()).isEqualTo(Side.DEFENDER);
	}

	@Test
	void example3b_protectedArtilleryWinsAndIsExposedAsTheScreenThins() {
		Result r = Battle.resolve(new Input(army(3, 0, 3), army(6, 0, 0), false, false), V1);

		assertThat(r.phases().get(0).attacker().damage()).isEqualByComparingTo("10.5");
		assertThat(r.attackerLosses()).isEqualTo(717);
		assertThat(r.defenderLosses()).isEqualTo(1000);
		assertThat(r.winner()).isEqualTo(Side.ATTACKER);
		assertThat(r.phases().get(1).attacker().protection()).isLessThan(r.phases().get(0).attacker().protection());
		assertThat(r.phases().get(0).attacker().lossesByType().get(UnitType.ARTILLERY)).isEqualTo(80);
		assertThat(r.phases().get(1).attacker().lossesByType().get(UnitType.ARTILLERY)).isEqualTo(89);
		assertThat(r.phases().get(2).attacker().lossesByType().get(UnitType.ARTILLERY)).isEqualTo(93);
	}

	@Test
	void example4_cavalryFlanksOnPlains() {
		Result r = Battle.resolve(new Input(army(4, 4, 0), army(7, 1, 0), false, false), V1);

		assertThat(r.phases().get(0).attacker().flankBonus()).isEqualByComparingTo("0.225");
		assertThat(r.phases().get(0).attacker().damage()).isEqualByComparingTo("11.76");
		assertThat(r.phases().get(0).defender().damage()).isEqualByComparingTo("8.4");
		assertThat(r.attackerLosses()).isEqualTo(926);
		assertThat(r.defenderLosses()).isEqualTo(1174);
		assertThat(r.winner()).isEqualTo(Side.ATTACKER);
	}

	@Test
	void example4_cavalryCannotFlankInMountains() {
		Result r = Battle.resolve(new Input(army(4, 4, 0), army(7, 1, 0), false, true), V1);

		assertThat(r.phases().get(0).attacker().flankBonus()).isEqualByComparingTo("0");
		assertThat(r.attackerLosses()).isEqualTo(892);
		assertThat(r.defenderLosses()).isEqualTo(477);
		assertThat(r.winner()).isEqualTo(Side.DEFENDER);
	}

	@Test
	void example5_defenderBreaksAfterPhaseTwo() {
		Result r = Battle.resolve(new Input(army(10, 4, 4), army(4, 0, 0), false, false), V1);

		assertThat(r.phases().get(0).attacker().damage()).isEqualByComparingTo("33.28");
		assertThat(lostAfterPhase(r, 1, Side.DEFENDER)).isEqualTo(1109);
		assertThat(lostAfterPhase(r, 1, Side.ATTACKER)).isEqualTo(155);
		assertThat(r.phases()).hasSize(2);
		assertThat(r.defenderLosses()).isEqualTo(2209);
		assertThat(r.attackerLosses()).isEqualTo(266);
		assertThat(r.broken()).isEqualTo(Side.DEFENDER);
		assertThat(r.winner()).isEqualTo(Side.ATTACKER);
	}

	@Test
	void riverAloneWeakensTheAttacker() {
		Result plains = Battle.resolve(new Input(army(5, 0, 0), army(5, 0, 0), false, false), V1);
		Result river = Battle.resolve(new Input(army(5, 0, 0), army(5, 0, 0), true, false), V1);

		assertThat(river.phases().get(0).attacker().terrainMultiplier()).isEqualByComparingTo("0.80");
		assertThat(river.defenderLosses()).isLessThan(plains.defenderLosses());
		// Mirror armies on open ground: equal losses, and the tie goes to the defender.
		assertThat(plains.attackerLosses()).isEqualTo(plains.defenderLosses());
		assertThat(plains.winner()).isEqualTo(Side.DEFENDER);
	}

	@Test
	void unprotectedArtilleryDoesNotBeatCheaperInfantry() {
		// 2 guns (80 gold) against 3 infantry (30 gold)
		Result r = Battle.resolve(new Input(army(0, 0, 2), army(3, 0, 0), false, false), V1);

		assertThat(r.winner()).isEqualTo(Side.DEFENDER);
	}

	@Test
	void partialBattalionsCountByManpowerAndLossesSplitProportionally() {
		UUID big = new UUID(0, 9001);
		UUID small = new UUID(0, 9002);
		List<Fighter> attackers = List.of(new Fighter(big, UnitType.INFANTRY, 1000),
				new Fighter(small, UnitType.INFANTRY, 500));
		Result r = Battle.resolve(new Input(attackers, army(2, 0, 0), false, false), V1);

		int bigLost = r.losses().get(big);
		int smallLost = r.losses().get(small);
		assertThat(bigLost + smallLost).isEqualTo(r.attackerLosses());
		assertThat(Math.abs(bigLost - 2 * smallLost)).isLessThanOrEqualTo(3);
		assertThat(r.phases().get(0).attacker().damage()).isEqualByComparingTo("1.5");
	}

	@Test
	void lossesNeverExceedManpowerAndNoOneIsCreated() {
		List<Fighter> weak = List.of(new Fighter(new UUID(0, 9100), UnitType.ARTILLERY, 30));
		List<Fighter> strong = army(10, 4, 0);
		Result r = Battle.resolve(new Input(weak, strong, false, false), V1);

		assertThat(r.survivors(weak.get(0))).isZero();
		strong.forEach(f -> assertThat(r.survivors(f)).isBetween(0, 1000));
		assertThat(r.winner()).isEqualTo(Side.DEFENDER);
	}

	/**
	 * Invariants over every small army composition (jqwik is not a project dependency, so an
	 * exhaustive grid stands in for property-based generation): losses stay within manpower, add
	 * up, nobody is created, and resolving twice gives the same result.
	 */
	@Test
	void invariantsHoldForEverySmallComposition() {
		for (int ai = 0; ai <= 3; ai++) {
			for (int ac = 0; ac <= 2; ac++) {
				for (int aa = 0; aa <= 3; aa++) {
					for (int di = 0; di <= 3; di++) {
						for (int da = 0; da <= 2; da++) {
							if (ai + ac + aa == 0 || di + da == 0) {
								continue;
							}
							for (boolean mountains : new boolean[] { false, true }) {
								List<Fighter> a = army(ai, ac, aa);
								List<Fighter> d = army(di, 1, da);
								Input input = new Input(a, d, !mountains, mountains);
								Result r = Battle.resolve(input, V1);
								int lostA = a.stream().mapToInt(f -> r.losses().get(f.id())).sum();
								int lostD = d.stream().mapToInt(f -> r.losses().get(f.id())).sum();
								assertThat(lostA).isEqualTo(r.attackerLosses()).isBetween(0, r.attackerInitial());
								assertThat(lostD).isEqualTo(r.defenderLosses()).isBetween(0, r.defenderInitial());
								a.forEach(f -> assertThat(r.survivors(f)).isBetween(0, f.manpower()));
								d.forEach(f -> assertThat(r.survivors(f)).isBetween(0, f.manpower()));
								assertThat(r.phases()).hasSizeBetween(1, 3);
								Result again = Battle.resolve(input, V1);
								assertThat(again.losses()).isEqualTo(r.losses());
								assertThat(again.winner()).isEqualTo(r.winner());
							}
						}
					}
				}
			}
		}
	}

	@Test
	void isDeterministicRegardlessOfInputOrder() {
		List<Fighter> a = army(3, 1, 1);
		List<Fighter> b = army(4, 0, 0);
		Result first = Battle.resolve(new Input(a, b, false, false), V1);
		Result reversed = Battle.resolve(new Input(a.reversed(), b.reversed(), false, false), V1);

		assertThat(reversed.losses()).containsExactlyInAnyOrderEntriesOf(first.losses());
		assertThat(reversed.winner()).isEqualTo(first.winner());
	}

}
