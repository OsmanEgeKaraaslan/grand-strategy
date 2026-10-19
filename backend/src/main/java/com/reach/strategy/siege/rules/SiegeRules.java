package com.reach.strategy.siege.rules;

import java.util.Map;
import java.util.TreeMap;

/**
 * Siege and fortification values of one ruleset version ({@code rulesets/<version>/siege.json}).
 * Amounts are milligold, rates in basis points (1/10,000). Every value is PROVISIONAL balance
 * (Q-S1, Q-S4, Q-S5, Q-E7); change it by adding a ruleset version, never in code.
 *
 * @param levels per fortress level 1..n: build cost (from the level below), duration, upkeep, integrity
 * @param startingFortresses fortress level per province key at game start (new games only)
 */
public record SiegeRules(String version, Map<Integer, FortressLevel> levels, Progress progress, int attritionBps,
		Bombardment bombardment, Repair repair, Map<String, Integer> startingFortresses) {

	public SiegeRules {
		if (levels == null || levels.isEmpty() || progress == null || bombardment == null || repair == null
				|| attritionBps < 0 || attritionBps > 10_000) {
			throw new IllegalArgumentException("Invalid siege ruleset " + version);
		}
		for (int level = 1; level <= levels.size(); level++) {
			if (!levels.containsKey(level)) {
				throw new IllegalArgumentException("Siege ruleset " + version + " must define levels 1.." + levels.size());
			}
		}
		levels = Map.copyOf(new TreeMap<>(levels));
		startingFortresses = startingFortresses == null ? Map.of() : Map.copyOf(startingFortresses);
		int maxLevel = levels.size();
		startingFortresses.forEach((key, level) -> {
			if (level < 1 || level > maxLevel) {
				throw new IllegalArgumentException("Invalid starting fortress " + key + " = " + level);
			}
		});
	}

	public int maxLevel() {
		return levels.size();
	}

	public FortressLevel level(int level) {
		FortressLevel rules = levels.get(level);
		if (rules == null) {
			throw new IllegalArgumentException("Unknown fortress level " + level);
		}
		return rules;
	}

	/**
	 * @param buildCostMg cost of raising the fortress to this level (from level − 1; 50 gold × n)
	 * @param buildDurationRounds rounds the project takes (Q-E8 timing)
	 * @param upkeepMg round upkeep while ACTIVE at this level (1 gold × n)
	 * @param maxIntegrity integrity of an intact fortress at this level (display and damage state)
	 */
	public record FortressLevel(long buildCostMg, int buildDurationRounds, long upkeepMg, int maxIntegrity) {

		public FortressLevel {
			if (buildCostMg < 0 || buildDurationRounds < 1 || upkeepMg < 0 || maxIntegrity <= 0) {
				throw new IllegalArgumentException("Invalid fortress level");
			}
		}

	}

	/**
	 * Q-S1 progress points: required {@code (requiredBase + requiredPerLevel × F) × pointsPerUnit};
	 * per round {@code basePerRound + floor(artilleryPerBattalion × artilleryManpower / battalionManpower)}.
	 */
	public record Progress(int requiredBase, int requiredPerLevel, int pointsPerUnit, int basePerRound,
			int artilleryPerBattalion, int battalionManpower) {

		public Progress {
			if (requiredBase < 1 || requiredPerLevel < 0 || pointsPerUnit < 1 || basePerRound < 1
					|| artilleryPerBattalion < 0 || battalionManpower < 1) {
				throw new IllegalArgumentException("Invalid siege progress rules");
			}
		}

	}

	/**
	 * What a successful artillery siege destroys (Q-S2, Q-S3).
	 *
	 * @param fortressIntegrityLossBps share of the fortress's integrity lost (10,000 = all; the fortress is DAMAGED)
	 * @param workshopsDamaged how many ACTIVE workshops are damaged (1)
	 */
	public record Bombardment(int fortressIntegrityLossBps, int workshopsDamaged) {

		public Bombardment {
			if (fortressIntegrityLossBps < 0 || fortressIntegrityLossBps > 10_000 || workshopsDamaged < 0) {
				throw new IllegalArgumentException("Invalid bombardment rules");
			}
		}

	}

	/** @param costBps share of the building's construction cost a repair costs (APPROVED 30% for workshops) */
	public record Repair(int costBps, int durationRounds) {

		public Repair {
			if (costBps < 0 || durationRounds < 1) {
				throw new IllegalArgumentException("Invalid repair rules");
			}
		}

	}

}
