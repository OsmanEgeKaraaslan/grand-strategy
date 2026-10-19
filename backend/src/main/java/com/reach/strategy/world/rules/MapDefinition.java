package com.reach.strategy.world.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The topology of a map template: provinces, the adjacency graph and starting ownership.
 * Pure data for the rules engine — SVG geometry lives elsewhere ({@code MapGeometry}).
 */
public record MapDefinition(UUID id, String key, int version, String name, int maxPlayers, List<Province> provinces,
		List<Adjacency> adjacencies, List<Nation> nations) {

	public MapDefinition {
		provinces = List.copyOf(provinces);
		adjacencies = List.copyOf(adjacencies);
		nations = List.copyOf(nations);
	}

	public enum Terrain {

		PLAINS, MOUNTAINS

	}

	public enum Crossing {

		NONE, RIVER

	}

	public record Province(UUID id, String key, String name, Terrain terrain, String cityName, int startPopulation) {
	}

	/** Undirected edge between two province keys. */
	public record Adjacency(String a, String b, Crossing crossing) {

		public boolean touches(String provinceKey) {
			return a.equals(provinceKey) || b.equals(provinceKey);
		}

		public String other(String provinceKey) {
			return a.equals(provinceKey) ? b : a;
		}

	}

	/** A playable nation with its capital and starting provinces (legal owner and controller). */
	public record Nation(UUID id, String key, String name, String color, String capitalProvinceKey,
			List<String> startProvinceKeys) {

		public Nation {
			startProvinceKeys = List.copyOf(startProvinceKeys);
		}

	}

	public Set<String> neighbours(String provinceKey) {
		return adjacencies.stream()
			.filter(edge -> edge.touches(provinceKey))
			.map(edge -> edge.other(provinceKey))
			.collect(Collectors.toUnmodifiableSet());
	}

	/** Starting nation key per province key. */
	public Map<String, String> startingOwners() {
		return nations.stream()
			.flatMap(nation -> nation.startProvinceKeys().stream().map(province -> Map.entry(province, nation.key())))
			.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}
