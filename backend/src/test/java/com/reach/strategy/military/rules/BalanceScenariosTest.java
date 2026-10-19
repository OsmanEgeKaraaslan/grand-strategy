package com.reach.strategy.military.rules;

import static com.reach.strategy.military.rules.MilitaryRulesFixture.V1;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.military.rules.Battle.Fighter;
import com.reach.strategy.military.rules.Battle.Input;
import com.reach.strategy.military.rules.Battle.Result;
import com.reach.strategy.military.rules.Battle.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The deterministic balance scenarios of docs/BALANCE_ANALYSIS.md (provisional coefficients,
 * rulesets v1/v2). Each row is golden: if a coefficient changes, the document is regenerated
 * together with this table.
 */
class BalanceScenariosTest {

	private static int nextId = 1;

	/** {@code "4I2C1A@650"}: counts per type, optional manpower per battalion (default 1000). */
	static List<Fighter> army(String spec) {
		String[] parts = spec.split("@");
		int men = parts.length > 1 ? Integer.parseInt(parts[1]) : 1000;
		List<Fighter> fighters = new ArrayList<>();
		String counts = parts[0];
		int number = 0;
		for (char ch : counts.toCharArray()) {
			if (Character.isDigit(ch)) {
				number = number * 10 + (ch - '0');
				continue;
			}
			UnitType type = switch (ch) {
				case 'I' -> UnitType.INFANTRY;
				case 'C' -> UnitType.CAVALRY;
				case 'A' -> UnitType.ARTILLERY;
				default -> throw new IllegalArgumentException(spec);
			};
			for (int i = 0; i < number; i++) {
				fighters.add(new Fighter(new UUID(0, nextId++), type, men));
			}
			number = 0;
		}
		return fighters;
	}

	@ParameterizedTest(name = "{0} attacks {1} ({2}): losses {3} / {4}, {5} wins")
	@CsvSource({
			// infantry vs infantry, terrain
			"5I, 5I, PLAINS, 482, 482, DEFENDER",
			"5I, 5I, RIVER, 485, 385, DEFENDER",
			"5I, 5I, MOUNTAINS, 488, 313, DEFENDER",
			// infantry vs cavalry
			"4C, 4I, PLAINS, 500, 694, ATTACKER",
			"4I, 4C, PLAINS, 694, 500, DEFENDER",
			"4C, 4I, MOUNTAINS, 522, 207, DEFENDER",
			// infantry vs artillery
			"4I, 4A, PLAINS, 367, 928, ATTACKER",
			"4A, 4I, PLAINS, 928, 367, DEFENDER",
			// balanced vs artillery-heavy
			"4I4A, 8I, PLAINS, 957, 1333, ATTACKER",
			"2I6A, 8I, PLAINS, 1561, 1035, DEFENDER",
			"6I2C2A, 2I1C7A, PLAINS, 1482, 2776, ATTACKER",
			// cavalry-heavy attacks
			"2I6C, 8I, PLAINS, 940, 1294, ATTACKER",
			"2I6C, 8I, MOUNTAINS, 977, 438, DEFENDER",
			// small mountain defenders vs larger attackers
			"6I, 4I, MOUNTAINS, 386, 381, ATTACKER",
			"8I, 4I, MOUNTAINS, 381, 510, ATTACKER",
			"12I, 4I, MOUNTAINS, 373, 771, ATTACKER",
			// river crossing
			"6I, 4I, RIVER, 383, 469, ATTACKER",
			// damaged vs full-strength battalions (manpower, not battalion count, matters)
			"4I, 8I@500, PLAINS, 385, 385, DEFENDER",
			"4I, 4I@300, PLAINS, 106, 395, ATTACKER",
			"4I@300, 1I, PLAINS, 95, 115, ATTACKER",
			// frontline collapse
			"3I3A, 8I, PLAINS, 980, 983, DEFENDER",
			"1I4A, 5I, PLAINS, 1021, 609, DEFENDER",
			// equal cost (120 gold)
			"12I, 3A, PLAINS, 100, 960, ATTACKER",
			"2I4C, 12I, PLAINS, 1426, 827, DEFENDER" })
	void scenario(String attacker, String defender, String terrain, int attackerLosses, int defenderLosses,
			Side winner) {
		Result r = Battle.resolve(new Input(army(attacker), army(defender), terrain.equals("RIVER"),
				terrain.equals("MOUNTAINS")), V1);

		assertThat(r.attackerLosses()).isEqualTo(attackerLosses);
		assertThat(r.defenderLosses()).isEqualTo(defenderLosses);
		assertThat(r.winner()).isEqualTo(winner);
	}

	@Test
	void manpowerNotBattalionCountDecides() {
		Result full = Battle.resolve(new Input(army("4I"), army("4I"), false, false), V1);
		Result halves = Battle.resolve(new Input(army("4I"), army("8I@500"), false, false), V1);

		assertThat(halves.attackerLosses()).isEqualTo(full.attackerLosses());
		assertThat(halves.defenderLosses()).isEqualTo(full.defenderLosses());
	}

	@Test
	void artilleryLosesItsScreenDuringTheBattle() {
		Result r = Battle.resolve(new Input(army("3I3A"), army("8I"), false, false), V1);

		BigDecimal p1 = r.phases().get(0).attacker().protection();
		BigDecimal p2 = r.phases().get(1).attacker().protection();
		BigDecimal p3 = r.phases().get(2).attacker().protection();
		assertThat(p1).isEqualByComparingTo("1");
		assertThat(p2).isLessThan(p1);
		assertThat(p3).isLessThan(p2);
		assertThat(r.phases().get(2).attacker().artilleryEffectiveness()).isLessThan(BigDecimal.ONE);
	}

	@Test
	void cavalryFlankIsCappedAndDisappearsInMountains() {
		Result plains = Battle.resolve(new Input(army("8C"), army("8I"), false, false), V1);
		Result mountains = Battle.resolve(new Input(army("8C"), army("8I"), false, true), V1);
		Result outnumbered = Battle.resolve(new Input(army("2C"), army("8I"), false, false), V1);

		assertThat(plains.phases().get(0).attacker().flankBonus()).isEqualByComparingTo("0.30");
		assertThat(mountains.phases().get(0).attacker().flankBonus()).isEqualByComparingTo("0");
		// surplus 2 vs half the enemy (4): half the maximum
		assertThat(outnumbered.phases().get(0).attacker().flankBonus()).isEqualByComparingTo("0.15");
	}

	@Test
	void riverAndMountainPenaltiesCombineMultiplicatively() {
		Result both = Battle.resolve(new Input(army("5I"), army("5I"), true, true), V1);

		assertThat(both.phases().get(0).attacker().terrainMultiplier()).isEqualByComparingTo("0.52");
		assertThat(both.phases().get(0).defender().terrainMultiplier()).as("the defender is never penalised")
			.isEqualByComparingTo("1");
	}

	@Test
	void winnersAlsoBleed() {
		for (String[] matchup : new String[][] { { "4I4A", "8I" }, { "2I6C", "8I" }, { "6I", "4I" }, { "12I", "4I" } }) {
			Result r = Battle.resolve(new Input(army(matchup[0]), army(matchup[1]), false, false), V1);
			assertThat(r.attackerLosses()).as(matchup[0] + " vs " + matchup[1]).isPositive();
			assertThat(r.defenderLosses()).isPositive();
		}
	}

	@Test
	void whenBothSidesBreakTheReportSaysSoAndTheLowerLossRatioWins() {
		// A deliberately lethal coefficient (×15 casualties) makes both armies break in phase 1:
		// the attacker loses 2,000 of 5,000 (40%), the defender 2,500 of 4,000 (62.5%).
		MilitaryRules.CombatRules c = V1.combat();
		MilitaryRules lethal = new MilitaryRules("test", 1000, 1000, V1.units(),
				new MilitaryRules.CombatRules(c.maxPhases(), new BigDecimal("600"), c.breakThresholdBps(),
						c.riverAttackMultiplier(), c.mountainAttackMultiplier(), c.screenPerArtillery(),
						c.artilleryMinEffectiveness(), c.artilleryProtectedExposure(),
						c.artilleryUnprotectedExtraExposure(), c.cavalryMountainFactor(), c.flankMaxBonus(),
						c.flankEnemyShare()),
				V1.startingForces(), 500);

		Result r = Battle.resolve(new Input(army("5I"), army("4I"), false, false), lethal);

		assertThat(r.phases()).hasSize(1);
		assertThat(r.attackerBroke()).isTrue();
		assertThat(r.defenderBroke()).isTrue();
		assertThat(r.winner()).isEqualTo(Side.ATTACKER);
		assertThat(r.broken()).isEqualTo(Side.DEFENDER);
	}

}
