package com.reach.strategy.diplomacy.rules;

/**
 * Diplomacy, war score, peace-term and victory values of one ruleset version
 * ({@code rulesets/<version>/diplomacy.json}, v5+). Every number is PROVISIONAL balance
 * (docs/DIPLOMACY.md); the record validates itself so a malformed file stops the application from
 * starting. Games of older rulesets have no diplomacy: every pair of nations is permanently at war
 * and no game ends (the pre-diplomacy MVP rule, GAME_DESIGN §11.1).
 *
 * @param truceRounds rounds after a peace treaty during which its former enemies cannot declare war
 * on each other (Q-D1)
 * @param proposalExpiryRounds a pending alliance or peace proposal expires at the end of round
 * {@code createdRound + proposalExpiryRounds}
 * @param maxPeaceTerms upper bound of terms in one peace proposal
 */
public record DiplomacyRules(String version, int truceRounds, int proposalExpiryRounds, int maxPeaceTerms,
		MilitaryAccess militaryAccess, WarScoreRules warScore, TributeRules tribute,
		PopulationTransferRules populationTransfer, ReparationRules reparations, VictoryRules victory) {

	public DiplomacyRules {
		if (version == null || version.isBlank()) {
			throw invalid(version, "version");
		}
		if (truceRounds < 0 || proposalExpiryRounds < 1 || maxPeaceTerms < 1) {
			throw invalid(version, "truce, expiry and term limits");
		}
		if (militaryAccess == null || warScore == null || tribute == null || populationTransfer == null
				|| reparations == null || victory == null) {
			throw invalid(version, "every section is required");
		}
		warScore.check(version);
		if (tribute.rateBps() < 1 || tribute.rateBps() > 10_000 || tribute.rounds() < 1) {
			throw invalid(version, "tribute");
		}
		PopulationTransferRules p = populationTransfer;
		if (p.minPeople() < 1 || p.maxPeople() < p.minPeople() || p.maxShareBps() < 1 || p.maxShareBps() > 10_000
				|| p.minResidualPopulation() < 0) {
			throw invalid(version, "population transfer");
		}
		if (reparations.maxMg() < 1) {
			throw invalid(version, "reparations");
		}
		if (victory.maxRounds() < 1 || victory.provincePoints() < 0 || victory.pointsPerThousandPopulation() < 0) {
			throw invalid(version, "victory");
		}
	}

	static IllegalArgumentException invalid(String version, String what) {
		return new IllegalArgumentException("Invalid diplomacy ruleset " + version + ": " + what);
	}

	/**
	 * Whether an alliance lets the partner's battalions enter one's territory. Nations fighting on the
	 * same side of an active war always have access to each other's territory when
	 * {@code coBelligerents} is set.
	 */
	public record MilitaryAccess(boolean normalAlliance, boolean defensiveAlliance, boolean coBelligerents) {

		public boolean grantedBy(Diplomacy.AllianceKind kind) {
			return kind == Diplomacy.AllianceKind.NORMAL ? normalAlliance : defensiveAlliance;
		}

	}

	/**
	 * War score per side, 0…{@code maxScore} (docs/DIPLOMACY.md §4). A won battle scores only when the
	 * enemy lost at least {@code battleMinEnemyLosses} soldiers (no farming with tiny battles):
	 * {@code floor(enemyLosses × battlePointsPerThousandLosses / 1000)}, at most
	 * {@code battleMaxPointsPerBattle}; all battles together at most {@code battleCap}. Each siege won
	 * scores {@code siegePoints}, together at most {@code siegeCap}. Occupation scores
	 * {@code floor(occupied enemy population × occupationMaxPoints / enemy population)}.
	 */
	public record WarScoreRules(int maxScore, int battleMinEnemyLosses, int battlePointsPerThousandLosses,
			int battleMaxPointsPerBattle, int battleCap, int siegePoints, int siegeCap, int occupationMaxPoints) {

		void check(String version) {
			if (maxScore < 1 || battleMinEnemyLosses < 1 || battlePointsPerThousandLosses < 0
					|| battleMaxPointsPerBattle < 0 || battleCap < 0 || siegePoints < 0 || siegeCap < 0
					|| occupationMaxPoints < 0) {
				throw invalid(version, "war score");
			}
		}

	}

	/** Tribute: {@code rateBps} of the payer's gross income, once per global round, for {@code rounds} rounds. */
	public record TributeRules(int rateBps, int rounds) {
	}

	/**
	 * Population transfer per term: {@code minPeople…maxPeople} (Q-D4 PROPOSED 1,000–2,000), all
	 * terms from one city together at most {@code maxShareBps} of its population, and the source city
	 * keeps at least {@code minResidualPopulation} people.
	 */
	public record PopulationTransferRules(int minPeople, int maxPeople, int maxShareBps, int minResidualPopulation) {
	}

	/** Upper bound of one reparations term (validated against the payer's treasury as well). */
	public record ReparationRules(long maxMg) {
	}

	/**
	 * PROVISIONAL Q-T2: the game ends after round {@code maxRounds}; a nation scores
	 * {@code provincePoints} per legally owned province plus {@code pointsPerThousandPopulation} per
	 * full 1,000 people in its legally owned cities.
	 */
	public record VictoryRules(int maxRounds, int provincePoints, int pointsPerThousandPopulation) {
	}

}
