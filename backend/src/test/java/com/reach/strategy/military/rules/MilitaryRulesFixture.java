package com.reach.strategy.military.rules;

import java.math.BigDecimal;
import java.util.Map;

/** The provisional v1/v2 values (rulesets/v1/military.json), built in code for pure tests. */
final class MilitaryRulesFixture {

	static final MilitaryRules V1 = new MilitaryRules("v1", 1000, 1000,
			Map.of(UnitType.INFANTRY, unit(10_000, 500, "1.0", "1.2", "1.0"), UnitType.CAVALRY,
					unit(25_000, 1500, "1.4", "0.9", "0.5"), UnitType.ARTILLERY, unit(40_000, 2000, "2.5", "0.5", "0.0")),
			new MilitaryRules.CombatRules(3, new BigDecimal("40"), 3000, new BigDecimal("0.80"), new BigDecimal("0.65"),
					new BigDecimal("1.0"), new BigDecimal("0.4"), new BigDecimal("0.2"), new BigDecimal("1.3"),
					new BigDecimal("0.6"), new BigDecimal("0.30"), new BigDecimal("0.5")),
			Map.of(), 500);

	private MilitaryRulesFixture() {
	}

	private static MilitaryRules.UnitRules unit(long cost, long upkeep, String attack, String defence, String screen) {
		return new MilitaryRules.UnitRules(cost, upkeep, new BigDecimal(attack), new BigDecimal(defence),
				new BigDecimal(screen));
	}

}
