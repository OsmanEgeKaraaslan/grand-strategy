package com.reach.strategy.world.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.world.rules.MapDefinition.Adjacency;
import com.reach.strategy.world.rules.MapDefinition.Crossing;
import com.reach.strategy.world.rules.MapDefinition.Nation;
import com.reach.strategy.world.rules.MapDefinition.Province;
import com.reach.strategy.world.rules.MapDefinition.Terrain;
import org.junit.jupiter.api.Test;

class MapValidatorTest {

	/** a - b - c - d in a line; north owns a,b; south owns c,d. */
	static MapDefinition valid() {
		return map(List.of(province("a"), province("b"), province("c"), province("d")),
				List.of(edge("a", "b"), edge("b", "c"), edge("c", "d")),
				List.of(nation("north", "a", "a", "b"), nation("south", "d", "c", "d")));
	}

	@Test
	void aWellFormedMapHasNoProblems() {
		assertThat(MapValidator.validate(valid())).isEmpty();
	}

	@Test
	void neighboursAreSymmetric() {
		MapDefinition map = valid();
		assertThat(map.neighbours("b")).containsExactlyInAnyOrder("a", "c");
		assertThat(map.neighbours("a")).containsExactly("b");
		for (Province province : map.provinces()) {
			for (String neighbour : map.neighbours(province.key())) {
				assertThat(map.neighbours(neighbour)).contains(province.key());
			}
		}
	}

	@Test
	void detectsAdjacencyProblems() {
		MapDefinition base = valid();
		List<Adjacency> edges = new ArrayList<>(base.adjacencies());
		edges.add(edge("a", "a"));
		edges.add(edge("b", "a"));
		edges.add(edge("d", "zz"));

		assertThat(MapValidator.validate(withEdges(base, edges))).anyMatch(p -> p.contains("adjacent to itself"))
			.anyMatch(p -> p.contains("Duplicate adjacency"))
			.anyMatch(p -> p.contains("unknown province"));
	}

	@Test
	void detectsADisconnectedGraph() {
		MapDefinition base = valid();
		assertThat(MapValidator.validate(withEdges(base, List.of(edge("a", "b"), edge("c", "d")))))
			.contains("Province graph is not connected");
	}

	@Test
	void detectsNeutralAndDoublyOwnedProvinces() {
		MapDefinition base = valid();
		List<Nation> nations = List.of(nation("north", "a", "a", "b", "c"), nation("south", "c", "c"));

		assertThat(MapValidator.validate(withNations(base, nations)))
			.anyMatch(p -> p.contains("starts owned by both north and south"))
			.anyMatch(p -> p.contains("no starting owner") && p.contains(": d"));
	}

	@Test
	void detectsCapitalsOutsideTheNationAndTooFewNations() {
		MapDefinition base = valid();
		List<Nation> nations = List.of(nation("north", "d", "a", "b", "c", "d"));

		assertThat(MapValidator.validate(withNations(base, nations)))
			.anyMatch(p -> p.contains("at least two nations"))
			.anyMatch(p -> p.contains("supports 2 players but has only 1"));
		assertThat(MapValidator.validate(withNations(base, List.of(nation("north", "c", "a", "b"), nation("south", "d", "c", "d")))))
			.anyMatch(p -> p.contains("Capital of north"));
	}

	@Test
	void detectsDuplicateKeys() {
		MapDefinition base = valid();
		List<Province> provinces = new ArrayList<>(base.provinces());
		provinces.add(province("a"));
		MapDefinition duplicated = new MapDefinition(base.id(), base.key(), 1, base.name(), 2, provinces,
				base.adjacencies(), base.nations());

		assertThat(MapValidator.validate(duplicated)).contains("Duplicate province key: a");
	}

	private static MapDefinition map(List<Province> provinces, List<Adjacency> edges, List<Nation> nations) {
		return new MapDefinition(UUID.randomUUID(), "test", 1, "Test", 2, provinces, edges, nations);
	}

	private static MapDefinition withEdges(MapDefinition base, List<Adjacency> edges) {
		return map(base.provinces(), edges, base.nations());
	}

	private static MapDefinition withNations(MapDefinition base, List<Nation> nations) {
		return map(base.provinces(), base.adjacencies(), nations);
	}

	private static Province province(String key) {
		return new Province(UUID.randomUUID(), key, key.toUpperCase(), Terrain.PLAINS, key + "-city", 1000);
	}

	private static Adjacency edge(String a, String b) {
		return new Adjacency(a, b, Crossing.NONE);
	}

	private static Nation nation(String key, String capital, String... provinces) {
		return new Nation(UUID.randomUUID(), key, key, "#000000", capital, List.of(provinces));
	}

}
