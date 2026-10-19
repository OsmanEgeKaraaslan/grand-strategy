package com.reach.strategy.world.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of a map template. Returns every problem found (empty list = valid) so a
 * broken map is reported completely, not one error at a time.
 * <p>
 * Rules: unique province and nation keys; adjacency only between existing, distinct provinces,
 * without duplicates; a connected graph; every province starts owned by exactly one nation (no
 * neutral land in the MVP, Q-W4); every nation owns at least one province including its capital;
 * at least two nations and enough nations for the map's player count.
 */
public final class MapValidator {

	private MapValidator() {
	}

	public static List<String> validate(MapDefinition map) {
		List<String> problems = new ArrayList<>();
		Set<String> provinces = new HashSet<>();
		for (MapDefinition.Province province : map.provinces()) {
			if (!provinces.add(province.key())) {
				problems.add("Duplicate province key: " + province.key());
			}
			if (province.startPopulation() < 0) {
				problems.add("Negative start population: " + province.key());
			}
		}
		if (provinces.isEmpty()) {
			problems.add("Map has no provinces");
		}

		Set<String> edges = new HashSet<>();
		for (MapDefinition.Adjacency edge : map.adjacencies()) {
			if (edge.a().equals(edge.b())) {
				problems.add("Province adjacent to itself: " + edge.a());
			}
			if (!provinces.contains(edge.a()) || !provinces.contains(edge.b())) {
				problems.add("Adjacency references unknown province: " + edge.a() + " - " + edge.b());
			}
			String canonical = edge.a().compareTo(edge.b()) < 0 ? edge.a() + "|" + edge.b() : edge.b() + "|" + edge.a();
			if (!edges.add(canonical)) {
				problems.add("Duplicate adjacency: " + edge.a() + " - " + edge.b());
			}
		}
		if (!provinces.isEmpty() && !isConnected(map, provinces)) {
			problems.add("Province graph is not connected");
		}

		Map<String, String> owners = new HashMap<>();
		Set<String> nationKeys = new HashSet<>();
		for (MapDefinition.Nation nation : map.nations()) {
			if (!nationKeys.add(nation.key())) {
				problems.add("Duplicate nation key: " + nation.key());
			}
			if (nation.startProvinceKeys().isEmpty()) {
				problems.add("Nation owns no province: " + nation.key());
			}
			if (!nation.startProvinceKeys().contains(nation.capitalProvinceKey())) {
				problems.add("Capital of " + nation.key() + " is not one of its provinces: " + nation.capitalProvinceKey());
			}
			for (String province : nation.startProvinceKeys()) {
				if (!provinces.contains(province)) {
					problems.add("Nation " + nation.key() + " owns unknown province: " + province);
				}
				String previous = owners.put(province, nation.key());
				if (previous != null) {
					problems.add("Province " + province + " starts owned by both " + previous + " and " + nation.key());
				}
			}
		}
		for (String province : provinces) {
			if (!owners.containsKey(province)) {
				problems.add("Province has no starting owner (neutral land is not supported yet): " + province);
			}
		}
		if (map.nations().size() < 2) {
			problems.add("A map needs at least two nations");
		}
		if (map.nations().size() < map.maxPlayers()) {
			problems.add("Map supports " + map.maxPlayers() + " players but has only " + map.nations().size() + " nations");
		}
		return List.copyOf(problems);
	}

	private static boolean isConnected(MapDefinition map, Set<String> provinces) {
		String start = provinces.iterator().next();
		Set<String> seen = new HashSet<>(Set.of(start));
		Deque<String> queue = new ArrayDeque<>(List.of(start));
		while (!queue.isEmpty()) {
			for (String next : map.neighbours(queue.poll())) {
				if (provinces.contains(next) && seen.add(next)) {
					queue.add(next);
				}
			}
		}
		return seen.size() == provinces.size();
	}

}
