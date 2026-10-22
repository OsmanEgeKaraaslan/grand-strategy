package com.reach.strategy.social.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public match statistics from a player's completed matches (Phase 12A, docs/DATA_MODEL.md §3.2).
 * Pure: the caller supplies the stored results; nothing here reads unfinished games.
 * <ul>
 * <li>completed = wins + losses + draws (every completed match has exactly one outcome);</li>
 * <li>win rate in basis points = wins × 10 000 / completed, rounded down (integer division), absent
 * without completed matches;</li>
 * <li>favourite faction = the faction played most often; ties go to more wins, then to the faction
 * key in alphabetical order, so the answer is deterministic.</li>
 * </ul>
 */
public final class MatchStatistics {

	private MatchStatistics() {
	}

	public enum Outcome {
		WIN, LOSS, DRAW
	}

	public record Match(Outcome outcome, String factionKey, String factionName) {
	}

	public record FactionRecord(String key, String name, int played, int wins) {
	}

	public record Summary(int completed, int wins, int losses, int draws, Integer winRateBps,
			Optional<FactionRecord> favouriteFaction, List<FactionRecord> factions) {
	}

	public static Summary of(List<Match> matches) {
		int wins = 0;
		int losses = 0;
		int draws = 0;
		Map<String, int[]> byFaction = new LinkedHashMap<>();
		Map<String, String> names = new LinkedHashMap<>();
		for (Match match : matches) {
			switch (match.outcome()) {
				case WIN -> wins++;
				case LOSS -> losses++;
				case DRAW -> draws++;
			}
			int[] counts = byFaction.computeIfAbsent(match.factionKey(), k -> new int[2]);
			counts[0]++;
			if (match.outcome() == Outcome.WIN) {
				counts[1]++;
			}
			names.putIfAbsent(match.factionKey(), match.factionName());
		}
		List<FactionRecord> factions = new ArrayList<>();
		byFaction.forEach((key, counts) -> factions.add(new FactionRecord(key, names.get(key), counts[0], counts[1])));
		factions.sort(Comparator.comparingInt(FactionRecord::played)
			.reversed()
			.thenComparing(Comparator.comparingInt(FactionRecord::wins).reversed())
			.thenComparing(FactionRecord::key));
		int completed = wins + losses + draws;
		Integer winRate = completed == 0 ? null : (int) ((long) wins * 10_000 / completed);
		return new Summary(completed, wins, losses, draws, winRate, factions.stream().findFirst(), List.copyOf(factions));
	}

}
