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

	/**
	 * @param treasuryMg counted from v6 ({@code pointsPerHundredGold})
	 * @param eliminated eliminated nations are ranked after every surviving one and cannot win
	 */
	public record NationFacts(UUID nationId, int ownedProvinces, long ownedPopulation, long treasuryMg,
			boolean eliminated) {

		public NationFacts(UUID nationId, int ownedProvinces, long ownedPopulation) {
			this(nationId, ownedProvinces, ownedPopulation, 0, false);
		}

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

	/**
	 * {@code provinces × provincePoints + floor(population / 1,000) × pointsPerThousandPopulation
	 * + floor(treasury / 100 gold) × pointsPerHundredGold} (DIPLOMACY §8; the gold part from v6).
	 */
	public static long score(NationFacts facts, DiplomacyRules.VictoryRules rules) {
		long provinces = Math.multiplyExact((long) facts.ownedProvinces(), rules.provincePoints());
		long population = Math.multiplyExact(facts.ownedPopulation() / 1000, rules.pointsPerThousandPopulation());
		long gold = Math.multiplyExact(facts.treasuryMg() / 100_000, rules.pointsPerHundredGold());
		return Math.addExact(Math.addExact(provinces, population), gold);
	}

	/**
	 * Domination (v6, Phase 10): the only nation not eliminated wins at once. Empty while two or more
	 * remain.
	 */
	public static java.util.Optional<UUID> dominator(List<NationFacts> nations) {
		List<NationFacts> alive = nations.stream().filter(n -> !n.eliminated()).toList();
		return alive.size() == 1 ? java.util.Optional.of(alive.getFirst().nationId()) : java.util.Optional.empty();
	}

	public static Outcome rank(List<NationFacts> nations, DiplomacyRules.VictoryRules rules) {
		if (nations.isEmpty()) {
			throw new IllegalArgumentException("No nations to rank");
		}
		List<NationFacts> ordered = new ArrayList<>(nations);
		ordered.sort(Comparator.comparing(NationFacts::eliminated)
			.thenComparing(Comparator.comparingLong((NationFacts n) -> score(n, rules)).reversed())
			.thenComparing(NationFacts::nationId));
		List<Ranked> standings = new ArrayList<>();
		long previous = Long.MIN_VALUE;
		int rank = 0;
		for (int i = 0; i < ordered.size(); i++) {
			NationFacts n = ordered.get(i);
			long score = score(n, rules);
			boolean newEliminatedBlock = n.eliminated() && (i == 0 || !ordered.get(i - 1).eliminated());
			if (score != previous || newEliminatedBlock) {
				rank = i + 1;
				previous = score;
			}
			standings.add(new Ranked(n.nationId(), rank, score, n.ownedProvinces(), n.ownedPopulation()));
		}
		boolean tie = standings.size() > 1 && standings.get(1).rank() == 1 && !ordered.get(1).eliminated();
		return new Outcome(tie ? null : standings.getFirst().nationId(), List.copyOf(standings));
	}

}
