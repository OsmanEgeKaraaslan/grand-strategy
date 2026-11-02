package com.reach.strategy.ai.rules;

import java.util.Map;

/**
 * Computer-opponent values of one ruleset version ({@code rulesets/<version>/ai.json}, v7+). Every
 * number is PROVISIONAL decision weighting, not game balance: the computer plays by the same rules
 * as everybody else and these values only steer its choices. Ratios and shares are basis points.
 */
public record AiRules(String version, Budget budget, Economy economy, Military military, Diplomacy diplomacy,
		Map<String, Personality> personalities, Map<String, String> nationPersonalities, String defaultPersonality,
		Peace peace) {

	public AiRules {
		personalities = Map.copyOf(personalities);
		nationPersonalities = nationPersonalities == null ? Map.of() : Map.copyOf(nationPersonalities);
		if (!personalities.containsKey(defaultPersonality)) {
			throw new IllegalArgumentException("Unknown default personality " + defaultPersonality);
		}
	}

	/** Bounds of one computer turn and of conference handling: the computer never loops. */
	public record Budget(int maxActionsPerTurn, int maxObservationsPerTurn, int maxFailuresPerTurn,
			int maxConferencePasses, int maxRevisionsPerConference) {
	}

	public record Economy(long reserveMg, int workshopPaybackRounds, int upgradePaybackRounds, int repairPaybackRounds,
			int minRoundsLeftToInvest, int institutionFirstRound, long institutionMinNetIncomeMg, int maxInstitutions,
			long fortressMinTreasuryMg, int maxConstructionsPerTurn) {
	}

	/**
	 * @param unitStrength rough field value per 1,000 men of each unit type (100 = infantry); used
	 * only to estimate battles — the battle engine decides them
	 * @param upkeepShareBps share of gross income the army's upkeep may take, per goal
	 */
	public record Military(Map<String, Integer> unitStrength, Map<String, Integer> upkeepShareBps,
			int minimumBattalions, int maxRecruitsPerTurn, int attackRatioBps, int riskyAttackRatioBps,
			int reinforceMinMissing, int threatRadius, int artilleryShareWhenFortifiedBps, int cavalryShareBps,
			int objectiveCapitalBonus, int objectivePopulationPerPoint) {

		public Military {
			unitStrength = Map.copyOf(unitStrength);
			upkeepShareBps = Map.copyOf(upkeepShareBps);
		}

	}

	/**
	 * @param favouredRatioBps own side's strength over the enemy side's from which a nation expects to win
	 * @param allianceRetryRounds rounds before proposing again to a nation that refused
	 */
	public record Diplomacy(int firstWarRound, int noNewWarsInLastRounds, int warRatioBps, int maxWars,
			int allyHelpExpectationBps, int threatRatioBps, int maxAlliances, int allianceRetryRounds, int honourRatioBps,
			int peaceAfterRounds, int losingScore, int winningScore, int favouredRatioBps, int endgameRounds,
			long provinceValueMg, long populationValueMgPerThousand, long tributeValueMg, int maxDemandedProvinces,
			long giftMinTreasuryMg, int giftShareBps, int giftEveryRounds) {
	}

	/**
	 * Weights that shift thresholds; every personality still fights, builds, researches and negotiates.
	 *
	 * @param institutions research institutions in order of preference
	 */
	public record Personality(int aggressionBps, int economyBps, int defenseBps, int diplomacyBps,
			java.util.List<String> institutions) {

		public Personality {
			institutions = institutions == null ? java.util.List.of() : java.util.List.copyOf(institutions);
		}

	}

	/**
	 * Peace evaluation of ruleset v8 (null before: the v7 rules apply unchanged). A nation holding enemy
	 * land wants a share of it in the treaty; the share shrinks every round the war drags on past
	 * {@code peaceAfterRounds}, and is halved when the gains are at risk or the game is about to end,
	 * so every war can still end. A favoured nation that holds nothing yet but can still take land
	 * refuses an empty peace until the war counts as a stalemate.
	 *
	 * @param keepGainsShareBps share of the value of the enemy land it holds a holder asks for at first
	 * @param concessionPerRoundBps how much that share drops per round beyond {@code peaceAfterRounds}
	 * @param gainsAtRiskRatioBps enemy strength (relative to ours) above which held land may be lost
	 * @param stalemateRounds war length after which a favoured nation without gains accepts an empty peace
	 * @param siegeNearlyDoneRounds a siege of ours this close to success counts as land about to be taken
	 */
	public record Peace(int keepGainsShareBps, int concessionPerRoundBps, int gainsAtRiskRatioBps, int stalemateRounds,
			int siegeNearlyDoneRounds) {

		public Peace {
			if (keepGainsShareBps < 0 || keepGainsShareBps > 10_000 || concessionPerRoundBps <= 0 || stalemateRounds < 1) {
				throw new IllegalArgumentException("Invalid AI peace rules");
			}
		}

	}

	public Personality personalityOf(String nationKey) {
		return personalities.get(nationPersonalities.getOrDefault(nationKey, defaultPersonality));
	}

	public String personalityName(String nationKey) {
		return nationPersonalities.getOrDefault(nationKey, defaultPersonality);
	}

}
