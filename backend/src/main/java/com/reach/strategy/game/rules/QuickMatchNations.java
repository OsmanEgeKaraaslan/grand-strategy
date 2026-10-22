package com.reach.strategy.game.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Picks the two human factions of a Quick Match (Phase 13B, docs/TURN_SYSTEM.md §9):
 * <ol>
 * <li>only nations a human may choose (playable);</li>
 * <li>both from the same starting tier — the tier with the most playable nations — so the starting
 * conditions are comparable;</li>
 * <li>preferably far apart: pairs whose capitals are at least {@link #PREFERRED_CAPITAL_DISTANCE}
 * borders apart (so the two realms do not start next to each other); if no pair is, every pair of
 * the tier qualifies;</li>
 * <li>among the qualifying pairs (sorted by key) the {@code seed} picks one, and its next bit decides
 * which player gets which faction.</li>
 * </ol>
 * Deterministic for a given seed; the caller supplies a random seed per match.
 */
public final class QuickMatchNations {

	public static final int PREFERRED_CAPITAL_DISTANCE = 4;

	private QuickMatchNations() {
	}

	public record Candidate(String key, String tier, boolean playable, String capitalProvinceKey) {
	}

	/** @param first the faction of the first player, {@code second} that of the second */
	public record Pair(String first, String second, int capitalDistance) {
	}

	/**
	 * @param neighbours province key → adjacent province keys
	 * @throws IllegalArgumentException if fewer than two playable nations share a tier
	 */
	public static Pair choose(List<Candidate> nations, Function<String, List<String>> neighbours, long seed) {
		Map<String, List<Candidate>> byTier = nations.stream()
			.filter(Candidate::playable)
			.collect(Collectors.groupingBy(Candidate::tier));
		List<Candidate> tier = byTier.values()
			.stream()
			.filter(group -> group.size() >= 2)
			.max(Comparator.<List<Candidate>>comparingInt(List::size).thenComparing(group -> group.getFirst().tier()))
			.orElseThrow(() -> new IllegalArgumentException("No two playable nations of one tier"))
			.stream()
			.sorted(Comparator.comparing(Candidate::key))
			.toList();
		List<Pair> pairs = new ArrayList<>();
		for (int i = 0; i < tier.size(); i++) {
			for (int j = i + 1; j < tier.size(); j++) {
				pairs.add(new Pair(tier.get(i).key(), tier.get(j).key(),
						distance(tier.get(i).capitalProvinceKey(), tier.get(j).capitalProvinceKey(), neighbours)));
			}
		}
		List<Pair> far = pairs.stream().filter(p -> p.capitalDistance() >= PREFERRED_CAPITAL_DISTANCE).toList();
		List<Pair> qualifying = far.isEmpty() ? pairs : far;
		Pair chosen = qualifying.get((int) Math.floorMod(seed, (long) qualifying.size()));
		boolean swap = Math.floorMod(seed / qualifying.size(), 2L) == 1;
		return swap ? new Pair(chosen.second(), chosen.first(), chosen.capitalDistance()) : chosen;
	}

	/** Borders crossed on the shortest path between two provinces (breadth-first; unreachable = max). */
	static int distance(String from, String to, Function<String, List<String>> neighbours) {
		if (from.equals(to)) {
			return 0;
		}
		Map<String, Integer> depth = new HashMap<>();
		Set<String> seen = new HashSet<>(Set.of(from));
		Deque<String> queue = new ArrayDeque<>(List.of(from));
		depth.put(from, 0);
		while (!queue.isEmpty()) {
			String current = queue.removeFirst();
			for (String next : neighbours.apply(current)) {
				if (seen.add(next)) {
					depth.put(next, depth.get(current) + 1);
					if (next.equals(to)) {
						return depth.get(next);
					}
					queue.addLast(next);
				}
			}
		}
		return Integer.MAX_VALUE;
	}

}
