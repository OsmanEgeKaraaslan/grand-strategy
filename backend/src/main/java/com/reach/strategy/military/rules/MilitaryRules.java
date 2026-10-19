package com.reach.strategy.military.rules;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Military balance values of one ruleset version ({@code rulesets/<version>/military.json}).
 * Amounts are milligold. Every value is PROVISIONAL balance (Q-E7, Q-C1): change it by adding
 * a ruleset version, never in code.
 *
 * @param battalionManpower soldiers in a full battalion, also the civilians recruitment takes (1000)
 * @param minResidualPopulation civilians that must remain in the city after recruiting (ECONOMY §8)
 * @param startingForces battalions each nation receives in its capital at game start (Q-W3)
 * @param reinforcementPerTurn replacement soldiers one battalion may receive per player turn (500)
 */
public record MilitaryRules(String version, int battalionManpower, int minResidualPopulation,
		Map<UnitType, UnitRules> units, CombatRules combat, Map<UnitType, Integer> startingForces,
		int reinforcementPerTurn) {

	public MilitaryRules {
		if (battalionManpower <= 0 || minResidualPopulation < 0 || reinforcementPerTurn <= 0 || units == null
				|| combat == null
				|| units.size() != UnitType.values().length) {
			throw new IllegalArgumentException("Invalid military ruleset " + version);
		}
		units = Map.copyOf(new EnumMap<>(units));
		startingForces = startingForces == null ? Map.of() : Map.copyOf(startingForces);
	}

	public UnitRules unit(UnitType type) {
		return units.get(type);
	}

	/**
	 * @param recruitCostMg price of a new full battalion
	 * @param upkeepMg round upkeep of a full battalion (scaled by manpower, see {@link Upkeep})
	 * @param attack damage per 1000 soldiers
	 * @param defence divisor of casualties: higher means fewer losses
	 * @param screen how much of the artillery screen 1000 soldiers provide
	 */
	public record UnitRules(long recruitCostMg, long upkeepMg, BigDecimal attack, BigDecimal defence, BigDecimal screen) {

		public UnitRules {
			if (recruitCostMg < 0 || upkeepMg < 0 || attack.signum() < 0 || defence.signum() <= 0 || screen.signum() < 0) {
				throw new IllegalArgumentException("Invalid unit rules");
			}
		}

	}

	/**
	 * Coefficients of the provisional battle model (docs/COMBAT_AND_SIEGE.md §2). The river (0.80)
	 * and mountain (0.65) attacker multipliers are APPROVED; everything else is PROPOSED (Q-C1).
	 */
	public record CombatRules(int maxPhases, BigDecimal lossPerDamage, int breakThresholdBps,
			BigDecimal riverAttackMultiplier, BigDecimal mountainAttackMultiplier, BigDecimal screenPerArtillery,
			BigDecimal artilleryMinEffectiveness, BigDecimal artilleryProtectedExposure,
			BigDecimal artilleryUnprotectedExtraExposure, BigDecimal cavalryMountainFactor, BigDecimal flankMaxBonus,
			BigDecimal flankEnemyShare) {

		public CombatRules {
			if (maxPhases < 1 || breakThresholdBps <= 0 || breakThresholdBps > 10_000 || lossPerDamage.signum() <= 0
					|| screenPerArtillery.signum() <= 0 || flankEnemyShare.signum() <= 0) {
				throw new IllegalArgumentException("Invalid combat rules");
			}
		}

	}

}
