package com.reach.strategy.military.rules;

/**
 * Battalion upkeep per global round (PROVISIONAL rule, docs/ECONOMY.md §4): proportional to
 * remaining manpower, {@code floor(fullUpkeep × manpower / maxManpower)} milligold. A 650-man
 * infantry battalion costs floor(500 × 650 / 1000) = 325 mg. Destroyed battalions no longer exist
 * and therefore cost nothing.
 */
public final class Upkeep {

	private Upkeep() {
	}

	public static long of(UnitType type, int manpower, int maxManpower, MilitaryRules rules) {
		if (manpower < 0 || maxManpower <= 0 || manpower > maxManpower) {
			throw new IllegalArgumentException("Invalid manpower " + manpower + "/" + maxManpower);
		}
		// floor toward zero of non-negative values (docs/ECONOMY.md §1.3)
		return Math.multiplyExact(rules.unit(type).upkeepMg(), (long) manpower) / maxManpower;
	}

	/**
	 * With a doctrine reduction (Military Logistics, PROVISIONAL): {@code floor(upkeep × (10,000 − bps) / 10,000)}
	 * of the proportional upkeep above — exact integers, floored like every upkeep amount.
	 */
	public static long of(UnitType type, int manpower, int maxManpower, MilitaryRules rules, int reductionBps) {
		if (reductionBps < 0 || reductionBps > 10_000) {
			throw new IllegalArgumentException("Invalid upkeep reduction " + reductionBps);
		}
		return Math.multiplyExact(of(type, manpower, maxManpower, rules), 10_000L - reductionBps) / 10_000;
	}

}
