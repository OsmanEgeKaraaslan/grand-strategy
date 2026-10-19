package com.reach.strategy.military.rules;

import static com.reach.strategy.military.rules.MilitaryRulesFixture.V1;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.military.rules.Battle.Fighter;
import com.reach.strategy.military.rules.Battle.Input;
import com.reach.strategy.military.rules.Battle.Result;
import com.reach.strategy.military.rules.Battle.SideModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Technology and doctrine modifiers in the battle engine and in upkeep (Phase 8, PROVISIONAL values). */
class ModifiersTest {

	static final Map<UnitType, Integer> PLUS_10 = Map.of(UnitType.INFANTRY, 1000, UnitType.CAVALRY, 1000,
			UnitType.ARTILLERY, 1000);

	static final SideModifiers MILITARY_2 = new SideModifiers(PLUS_10, 0, 0);

	private static int next = 1;

	static List<Fighter> of(UnitType type, int count, int men) {
		List<Fighter> list = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			list.add(new Fighter(new UUID(0, next++), type, men));
		}
		return list;
	}

	@Test
	void neutralModifiersChangeNothing() {
		List<Fighter> a = BattleTest.army(6, 2, 2);
		List<Fighter> b = BattleTest.army(8, 2, 0);
		Result plain = Battle.resolve(new Input(a, b, false, false), V1);
		Result neutral = Battle.resolve(new Input(a, b, false, false, SideModifiers.NONE, SideModifiers.NONE), V1);

		assertThat(neutral.losses()).isEqualTo(plain.losses());
		assertThat(neutral.attackerLosses()).isEqualTo(1175);
	}

	@ParameterizedTest
	@EnumSource(UnitType.class)
	void militaryTechnologyRaisesEveryUnitTypesDamageByItsBonus(UnitType type) {
		List<Fighter> attackers = of(type, 3, 1000);
		List<Fighter> defenders = of(UnitType.INFANTRY, 6, 1000);
		Result base = Battle.resolve(new Input(attackers, defenders, false, false), V1);
		Result teched = Battle.resolve(new Input(attackers, defenders, false, false, MILITARY_2, null), V1);

		assertThat(teched.phases().getFirst().attacker().damage())
			.isEqualByComparingTo(base.phases().getFirst().attacker().damage().multiply(new java.math.BigDecimal("1.1")));
		assertThat(teched.defenderLosses()).isGreaterThan(base.defenderLosses());
		// The soldiers themselves are unchanged.
		assertThat(teched.attackerInitial()).isEqualTo(3000);
	}

	@Test
	void technologyOnlyHelpsTheUnitTypesItNames() {
		SideModifiers gunsOnly = new SideModifiers(Map.of(UnitType.ARTILLERY, 2000), 0, 0);
		List<Fighter> infantry = of(UnitType.INFANTRY, 4, 1000);
		List<Fighter> defenders = of(UnitType.INFANTRY, 4, 1000);

		Result r = Battle.resolve(new Input(infantry, defenders, false, false, gunsOnly, null), V1);
		Result base = Battle.resolve(new Input(infantry, defenders, false, false), V1);
		assertThat(r.losses()).isEqualTo(base.losses());
	}

	@Test
	void technologyHelpsTheDefenderToo() {
		List<Fighter> a = of(UnitType.INFANTRY, 5, 1000);
		List<Fighter> d = of(UnitType.INFANTRY, 5, 1000);
		Result base = Battle.resolve(new Input(a, d, false, false), V1);
		Result teched = Battle.resolve(new Input(a, d, false, false, null, MILITARY_2), V1);

		assertThat(teched.attackerLosses()).isGreaterThan(base.attackerLosses());
	}

	@Test
	void mountainWarfareHalvesTheMountainPenalty() {
		SideModifiers mountain = new SideModifiers(Map.of(), 0, 5000);
		Result r = Battle.resolve(new Input(of(UnitType.INFANTRY, 5, 1000), of(UnitType.INFANTRY, 5, 1000), false, true,
				mountain, null), V1);

		// −35% halved to −17.5%: × 0.825
		assertThat(r.phases().getFirst().attacker().terrainMultiplier()).isEqualByComparingTo("0.825");
		assertThat(r.phases().getFirst().defender().terrainMultiplier()).isEqualByComparingTo("1");
	}

	@Test
	void riverEngineeringHalvesTheRiverPenaltyAndStacksWithMountains() {
		SideModifiers river = new SideModifiers(Map.of(), 5000, 0);
		Result r = Battle.resolve(new Input(of(UnitType.INFANTRY, 5, 1000), of(UnitType.INFANTRY, 5, 1000), true, true,
				river, null), V1);

		// river 0.90 × mountains 0.65
		assertThat(r.phases().getFirst().attacker().terrainMultiplier()).isEqualByComparingTo("0.585");
	}

	@Test
	void aDefendersDoctrineDoesNotAffectTheAttackersPenalty() {
		SideModifiers river = new SideModifiers(Map.of(), 5000, 5000);
		Result r = Battle.resolve(new Input(of(UnitType.INFANTRY, 5, 1000), of(UnitType.INFANTRY, 5, 1000), true, false,
				null, river), V1);

		assertThat(r.phases().getFirst().attacker().terrainMultiplier()).isEqualByComparingTo("0.80");
	}

	@Test
	void logisticsReducesProportionalUpkeepWithFlooring() {
		assertThat(Upkeep.of(UnitType.INFANTRY, 1000, 1000, V1, 2000)).isEqualTo(400);
		assertThat(Upkeep.of(UnitType.INFANTRY, 650, 1000, V1, 2000)).as("floor(325 × 0.8)").isEqualTo(260);
		assertThat(Upkeep.of(UnitType.CAVALRY, 333, 1000, V1, 2000)).as("floor(499 × 0.8)").isEqualTo(399);
		assertThat(Upkeep.of(UnitType.ARTILLERY, 1000, 1000, V1, 0)).isEqualTo(2000);
	}

}
