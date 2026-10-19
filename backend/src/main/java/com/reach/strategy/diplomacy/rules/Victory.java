package com.reach.strategy.diplomacy.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * PROVISIONAL Q-T2 match completion (docs/DIPLOMACY.md §8): the game ends after the last round of
 * the ruleset; nations are ranked by a score that rewards legally owned territory above all.
 * Occupation does not count — only ownership, which changes solely through peace treaties. A tie
 * for the highest score is a draw. No nation is ever eliminated.
 */
public final class Victory {

	private Victory() {
	}

	public static boolean due(int completedRound, DiplomacyRules.VictoryRules rules) {
		return completedRound >= rules.maxRounds();
	}

	public record NationFacts(UUID nationId, int ownedProvinces, long ownedPopulation) {
	}

	/** @param rank 1 = best; nations with equal scores share a rank */
	public record Ranked(UUID nationId, int rank, long score, int ownedProvinces, long ownedPopulation) {
	}

	/** @param winnerNationId null for a draw */
	public record Outcome(UUID winnerNationId, List<Ranked> standings) {

		public boolean draw() {
			return winnerNationId == null;
		}

	}

	/** {@code provinces × provincePoints + floor(population / 1,000) × pointsPerThousandPopulation} (DIPLOMACY §8). */
	public static long score(NationFacts facts, DiplomacyRules.VictoryRules rules) {
		long provinces = Math.multiplyExact((long) facts.ownedProvinces(), rules.provincePoints());
		long population = Math.multiplyExact(facts.ownedPopulation() / 1000, rules.pointsPerThousandPopulation());
		return Math.addExact(provinces, population);
	}

	public static Outcome rank(List<NationFacts> nations, DiplomacyRules.VictoryRules rules) {
		if (nations.isEmpty()) {
			throw new IllegalArgumentException("No nations to rank");
		}
		List<NationFacts> ordered = new ArrayList<>(nations);
		ordered.sort(Comparator.comparingLong((NationFacts n) -> score(n, rules))
			.reversed()
			.thenComparing(NationFacts::nationId));
		List<Ranked> standings = new ArrayList<>();
		long previous = Long.MIN_VALUE;
		int rank = 0;
		for (int i = 0; i < ordered.size(); i++) {
			NationFacts n = ordered.get(i);
			long score = score(n, rules);
			if (score != previous) {
				rank = i + 1;
				previous = score;
			}
			standings.add(new Ranked(n.nationId(), rank, score, n.ownedProvinces(), n.ownedPopulation()));
		}
		boolean tie = standings.size() > 1 && standings.get(1).rank() == 1;
		return new Outcome(tie ? null : standings.getFirst().nationId(), List.copyOf(standings));
	}

}
