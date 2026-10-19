package com.reach.strategy.research.rules;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Research values of one ruleset version ({@code rulesets/<version>/research.json}, v4+). Every
 * number is PROVISIONAL balance (Q-X1); effects are declarative typed values that the rules engine
 * applies — nothing in a ruleset is ever executed. The record validates itself so a malformed file
 * stops the application from starting instead of silently playing with wrong values.
 *
 * @param militaryLevels level 1 (the starting level, cost 0) up to the maximum, in order
 * @param civilianLevels idem for civilian technology
 * @param doctrines doctrine key → its tier, cost and modifiers
 * @param doctrinesPerTier how many doctrines a nation may choose in each tier (PROVISIONAL: 1)
 */
public record ResearchRules(String version, Map<InstitutionType, InstitutionRules> institutions,
		List<MilitaryLevel> militaryLevels, List<CivilianLevel> civilianLevels, Map<String, DoctrineRules> doctrines,
		int doctrinesPerTier) {

	/** Unit types a military level may modify (the military module's unit types, by name). */
	public static final Set<String> UNIT_TYPES = Set.of("INFANTRY", "CAVALRY", "ARTILLERY");

	public ResearchRules {
		if (institutions == null || institutions.size() != InstitutionType.values().length) {
			throw invalid(version, "every institution type needs rules");
		}
		institutions = Map.copyOf(new EnumMap<>(institutions));
		militaryLevels = checkedLevels(version, "military", militaryLevels, MilitaryLevel::level, MilitaryLevel::costPoints);
		civilianLevels = checkedLevels(version, "civilian", civilianLevels, CivilianLevel::level, CivilianLevel::costPoints);
		int previousBonus = 0;
		for (MilitaryLevel level : militaryLevels) {
			if (!UNIT_TYPES.containsAll(level.damageBonusBps().keySet())) {
				throw invalid(version, "unknown unit type in military level " + level.level());
			}
			int min = level.damageBonusBps().values().stream().mapToInt(Integer::intValue).min().orElse(0);
			if (min < previousBonus) {
				throw invalid(version, "military bonuses must not decrease with the level");
			}
			previousBonus = level.damageBonusBps().values().stream().mapToInt(Integer::intValue).max().orElse(0);
		}
		if (doctrines == null || doctrines.isEmpty() || doctrinesPerTier < 1) {
			throw invalid(version, "doctrines");
		}
		doctrines = Map.copyOf(new TreeMap<>(doctrines));
	}

	private static <T> List<T> checkedLevels(String version, String track, List<T> levels,
			java.util.function.ToIntFunction<T> level, java.util.function.ToIntFunction<T> cost) {
		if (levels == null || levels.isEmpty()) {
			throw invalid(version, track + " levels");
		}
		for (int i = 0; i < levels.size(); i++) {
			if (level.applyAsInt(levels.get(i)) != i + 1) {
				throw invalid(version, track + " levels must be 1, 2, 3, … in order");
			}
			if ((i == 0 && cost.applyAsInt(levels.get(i)) != 0) || (i > 0 && cost.applyAsInt(levels.get(i)) <= 0)) {
				throw invalid(version, track + " level 1 is free (the start), later levels cost points");
			}
		}
		return List.copyOf(levels);
	}

	private static IllegalArgumentException invalid(String version, String what) {
		return new IllegalArgumentException("Invalid research ruleset " + version + ": " + what);
	}

	public InstitutionRules institution(InstitutionType type) {
		return institutions.get(type);
	}

	public int maxLevel(Track track) {
		return track == Track.MILITARY ? militaryLevels.size() : civilianLevels.size();
	}

	public int levelCost(Track track, int level) {
		return track == Track.MILITARY ? militaryLevels.get(level - 1).costPoints()
				: civilianLevels.get(level - 1).costPoints();
	}

	public MilitaryLevel military(int level) {
		return militaryLevels.get(level - 1);
	}

	public CivilianLevel civilian(int level) {
		return civilianLevels.get(level - 1);
	}

	/** The two technology tracks; doctrines are chosen, not levelled. */
	public enum Track {

		MILITARY, CIVILIAN

	}

	/**
	 * @param pointsPerRound research points an ACTIVE institution adds to its pool every round end
	 * @param upkeepMg gold per round end while ACTIVE and its city is owned and controlled
	 */
	public record InstitutionRules(long buildCostMg, int buildDurationRounds, int pointsPerRound, long upkeepMg) {

		public InstitutionRules {
			if (buildCostMg < 0 || buildDurationRounds < 1 || pointsPerRound < 0 || upkeepMg < 0) {
				throw new IllegalArgumentException("Invalid institution rules");
			}
		}

	}

	/**
	 * @param damageBonusBps total extra damage per unit type at this level (not cumulative with lower
	 * levels: the value replaces them)
	 */
	public record MilitaryLevel(int level, int costPoints, Map<String, Integer> damageBonusBps) {

		public MilitaryLevel {
			damageBonusBps = damageBonusBps == null ? Map.of() : Map.copyOf(damageBonusBps);
			if (damageBonusBps.values().stream().anyMatch(b -> b < 0 || b > 100_000)) {
				throw new IllegalArgumentException("Invalid military level bonus");
			}
		}

	}

	/**
	 * @param unlocksWorkshopTier a workshop tier this level unlocks (Tier 2 at level 2), or null
	 * @param growthBonusBps extra population growth per round at this level and above
	 */
	public record CivilianLevel(int level, int costPoints, Integer unlocksWorkshopTier, int growthBonusBps) {

		public CivilianLevel {
			if (growthBonusBps < 0 || growthBonusBps > 10_000) {
				throw new IllegalArgumentException("Invalid civilian level");
			}
		}

	}

	/** Every modifier a doctrine may carry; unused ones are 0. All in basis points. */
	public record DoctrineRules(int tier, int costPoints, int riverPenaltyReductionBps, int mountainPenaltyReductionBps,
			int siegeArtilleryBonusBps, int militaryUpkeepReductionBps) {

		public DoctrineRules {
			if (tier < 1 || costPoints <= 0 || out(riverPenaltyReductionBps) || out(mountainPenaltyReductionBps)
					|| siegeArtilleryBonusBps < 0 || siegeArtilleryBonusBps > 100_000 || out(militaryUpkeepReductionBps)) {
				throw new IllegalArgumentException("Invalid doctrine");
			}
		}

		private static boolean out(int bps) {
			return bps < 0 || bps > 10_000;
		}

	}

}
