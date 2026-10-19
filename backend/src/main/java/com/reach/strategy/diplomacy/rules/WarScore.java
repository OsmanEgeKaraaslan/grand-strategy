package com.reach.strategy.diplomacy.rules;

/**
 * War score (docs/DIPLOMACY.md §4, PROVISIONAL Q-D2): deterministic, bounded and built only from
 * authoritative facts. Battle and siege points are fixed when the event happens (one row per
 * battle or siege, so nothing is counted twice and restarts change nothing); occupation is read
 * from the current world. Every division floors (integer arithmetic).
 */
public final class WarScore {

	private WarScore() {
	}

	public enum Side {

		ATTACKER, DEFENDER;

		public Side opponent() {
			return this == ATTACKER ? DEFENDER : ATTACKER;
		}

	}

	/**
	 * Points the winner of a battle earns: none below the minimum enemy losses (tiny battles cannot
	 * be farmed), else {@code floor(enemyLosses × pointsPerThousand / 1000)} capped per battle.
	 */
	public static int battlePoints(int enemyLosses, DiplomacyRules.WarScoreRules rules) {
		if (enemyLosses < rules.battleMinEnemyLosses()) {
			return 0;
		}
		long points = (long) enemyLosses * rules.battlePointsPerThousandLosses() / 1000;
		return (int) Math.min(points, rules.battleMaxPointsPerBattle());
	}

	public static int siegePoints(DiplomacyRules.WarScoreRules rules) {
		return rules.siegePoints();
	}

	/**
	 * One side's facts.
	 *
	 * @param battlePoints sum of this side's recorded battle points
	 * @param siegePoints sum of this side's recorded siege points
	 * @param occupiedEnemyPopulation people in cities legally owned by the other side and controlled by this one
	 * @param enemyPopulation people in all cities legally owned by the other side
	 */
	public record SideFacts(long battlePoints, long siegePoints, long occupiedEnemyPopulation, long enemyPopulation) {

		public SideFacts {
			if (battlePoints < 0 || siegePoints < 0 || occupiedEnemyPopulation < 0 || enemyPopulation < 0
					|| occupiedEnemyPopulation > enemyPopulation) {
				throw new IllegalArgumentException("Inconsistent war score facts");
			}
		}

	}

	public record SideScore(int battles, int sieges, int occupation, int total) {
	}

	/**
	 * @param net attacker total minus defender total, −maxScore…+maxScore (positive favours the attacker)
	 */
	public record Score(SideScore attacker, SideScore defender, int net) {
	}

	public static Score of(SideFacts attacker, SideFacts defender, DiplomacyRules.WarScoreRules rules) {
		SideScore a = side(attacker, rules);
		SideScore d = side(defender, rules);
		return new Score(a, d, a.total() - d.total());
	}

	static SideScore side(SideFacts facts, DiplomacyRules.WarScoreRules rules) {
		int battles = (int) Math.min(facts.battlePoints(), rules.battleCap());
		int sieges = (int) Math.min(facts.siegePoints(), rules.siegeCap());
		int occupation = facts.enemyPopulation() == 0 ? 0
				: (int) (facts.occupiedEnemyPopulation() * rules.occupationMaxPoints() / facts.enemyPopulation());
		int total = Math.min(rules.maxScore(), battles + sieges + occupation);
		return new SideScore(battles, sieges, occupation, total);
	}

}
