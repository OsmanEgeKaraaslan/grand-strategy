package com.reach.strategy.ai.rules;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Graph search over the authoritative adjacency of the map. Movement is one adjacent province per
 * battalion and round, so paths only choose the next step; weights express how costly a step is to
 * fight through (a river crossing and mountains each add one), never extra movement. Ties are broken
 * by province key, so every search is deterministic.
 */
public final class Paths {

	/** Cost of one step; +1 across a river, +1 into mountains. */
	static final int STEP = 2;

	private Paths() {
	}

	/** Provinces within {@code radius} steps (including the start), ignoring access. */
	public static Set<String> within(AiState state, String start, int radius) {
		Set<String> seen = new LinkedHashSet<>();
		Deque<String> frontier = new ArrayDeque<>();
		Map<String, Integer> depth = new HashMap<>();
		frontier.add(start);
		depth.put(start, 0);
		seen.add(start);
		while (!frontier.isEmpty()) {
			String current = frontier.poll();
			int d = depth.get(current);
			if (d == radius) {
				continue;
			}
			for (String next : neighbours(state, current)) {
				if (seen.add(next)) {
					depth.put(next, d + 1);
					frontier.add(next);
				}
			}
		}
		return seen;
	}

	/** Unweighted step distance from {@code start} to every reachable province (BFS). */
	public static Map<String, Integer> steps(AiState state, String start, Predicate<String> passable) {
		Map<String, Integer> distance = new HashMap<>();
		Deque<String> frontier = new ArrayDeque<>();
		distance.put(start, 0);
		frontier.add(start);
		while (!frontier.isEmpty()) {
			String current = frontier.poll();
			for (String next : neighbours(state, current)) {
				if (!distance.containsKey(next) && passable.test(next)) {
					distance.put(next, distance.get(current) + 1);
					frontier.add(next);
				}
			}
		}
		return distance;
	}

	/** The first step of a cheapest path from {@code start} to any of {@code goals} (Dijkstra). */
	public static Optional<Route> route(AiState state, String start, Collection<String> goals, Predicate<String> passable) {
		if (goals.isEmpty()) {
			return Optional.empty();
		}
		Set<String> goalSet = Set.copyOf(goals);
		Map<String, Integer> cost = new HashMap<>();
		Map<String, String> firstStep = new HashMap<>();
		PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingInt(Node::cost).thenComparing(Node::key));
		cost.put(start, 0);
		queue.add(new Node(start, 0));
		while (!queue.isEmpty()) {
			Node node = queue.poll();
			if (node.cost() > cost.getOrDefault(node.key(), Integer.MAX_VALUE)) {
				continue;
			}
			if (goalSet.contains(node.key()) && !node.key().equals(start)) {
				return Optional.of(new Route(firstStep.get(node.key()), node.key(), node.cost()));
			}
			for (String next : neighbours(state, node.key())) {
				if (!passable.test(next)) {
					continue;
				}
				int c = node.cost() + stepCost(state, node.key(), next);
				if (c < cost.getOrDefault(next, Integer.MAX_VALUE)) {
					cost.put(next, c);
					firstStep.put(next, node.key().equals(start) ? next : firstStep.get(node.key()));
					queue.add(new Node(next, c));
				}
			}
		}
		return Optional.empty();
	}

	public static int stepCost(AiState state, String from, String to) {
		AiState.Province origin = state.provinces().get(from);
		AiState.Province target = state.provinces().get(to);
		int cost = STEP;
		if (origin != null && origin.riverTo().contains(to)) {
			cost++;
		}
		if (target != null && target.mountains()) {
			cost++;
		}
		return cost;
	}

	/** @param next the province to move to now; @param goal where the path ends */
	public record Route(String next, String goal, int cost) {
	}

	private record Node(String key, int cost) {
	}

	private static List<String> neighbours(AiState state, String key) {
		AiState.Province province = state.provinces().get(key);
		return province == null ? List.of() : province.neighbours();
	}

}
