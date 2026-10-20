package com.reach.strategy.world.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.world.rules.MapDefinition.Adjacency;
import com.reach.strategy.world.rules.MapDefinition.Crossing;
import com.reach.strategy.world.rules.MapDefinition.Nation;
import com.reach.strategy.world.rules.MapDefinition.Province;
import com.reach.strategy.world.rules.MapDefinition.Terrain;
import org.junit.jupiter.api.Test;

/**
 * Adjacency must follow shared land borders: irregular outlines, any number of neighbours, corners
 * that only touch, and the minimum border length (Phase 10).
 */
class MapGeometryTest {

	// An irregular, L-shaped west province, a narrow east strip, a south province touching both, and
	// an island-like north-east province that only touches the strip at one corner.
	static final Map<String, String> OUTLINES = Map.of(
			"west", "M0 0 L40 0 L40 30 L25 35 L20 60 L0 60 Z",
			"east", "M40 0 L60 0 L60 60 L20 60 L25 35 L40 30 Z",
			"south", "M0 60 L20 60 L60 60 L60 90 L0 90 Z",
			"corner", "M60 -30 L90 -30 L90 0 L60 0 Z");

	static MapDefinition map(List<Adjacency> edges, Integer minBorder) {
		List<Province> provinces = new ArrayList<>();
		for (String key : List.of("corner", "east", "south", "west")) {
			provinces.add(new Province(UUID.randomUUID(), key, key, Terrain.PLAINS, key, 100));
		}
		Nation a = new Nation(UUID.randomUUID(), "a", "A", "#000000", "west", List.of("west", "south"));
		Nation b = new Nation(UUID.randomUUID(), "b", "B", "#ffffff", "east", List.of("east", "corner"));
		return new MapDefinition(UUID.randomUUID(), "test", 1, "Test", 2, provinces, edges, List.of(a, b), minBorder);
	}

	@Test
	void sharedBordersAreMeasuredFromIdenticalSegments() {
		List<String> problems = new ArrayList<>();
		Map<MapGeometry.Pair, Double> shared = MapGeometry.sharedBorders(OUTLINES, problems);
		assertThat(problems).isEmpty();
		assertThat(shared.get(MapGeometry.Pair.of("west", "east"))).isCloseTo(30 + Math.hypot(15, 5) + Math.hypot(5, 25),
				org.assertj.core.data.Offset.offset(0.001));
		assertThat(shared.get(MapGeometry.Pair.of("south", "west"))).isEqualTo(20.0);
		assertThat(shared.get(MapGeometry.Pair.of("south", "east"))).isEqualTo(40.0);
		assertThat(shared).as("a touching corner is not a border").doesNotContainKey(MapGeometry.Pair.of("corner", "east"));
	}

	@Test
	void adjacencyMustMatchTheGeometry() {
		List<Adjacency> correct = List.of(new Adjacency("east", "west", Crossing.RIVER), new Adjacency("south", "west", Crossing.NONE),
				new Adjacency("east", "south", Crossing.NONE));
		assertThat(MapGeometry.validate(map(correct, 16), OUTLINES)).isEmpty();

		List<Adjacency> cornerOnly = new ArrayList<>(correct);
		cornerOnly.add(new Adjacency("corner", "east", Crossing.NONE));
		assertThat(MapGeometry.validate(map(cornerOnly, 16), OUTLINES))
			.anyMatch(p -> p.startsWith("Adjacent provinces without a land border"));

		List<Adjacency> missing = List.of(new Adjacency("east", "west", Crossing.NONE));
		assertThat(MapGeometry.validate(map(missing, 16), OUTLINES)).hasSize(2)
			.allMatch(p -> p.startsWith("Land border without adjacency"));

		// With a 25-unit threshold the 20-unit south–west border no longer counts.
		List<Adjacency> longOnly = List.of(new Adjacency("east", "west", Crossing.NONE),
				new Adjacency("east", "south", Crossing.NONE));
		assertThat(MapGeometry.validate(map(longOnly, 25), OUTLINES)).isEmpty();
	}

	@Test
	void mapsWithoutAThresholdAreNotCheckedAgainstGeometry() {
		assertThat(MapGeometry.validate(map(List.of(new Adjacency("corner", "west", Crossing.NONE)), null), OUTLINES)).isEmpty();
	}

	@Test
	void structuralValidationNeedsEnoughPlayableNations() {
		List<Adjacency> edges = List.of(new Adjacency("east", "west", Crossing.NONE), new Adjacency("south", "west", Crossing.NONE),
				new Adjacency("corner", "east", Crossing.NONE));
		MapDefinition base = map(edges, null);
		Nation npc = new Nation(base.nations().get(1).id(), "b", "B", "#ffffff", "east", List.of("east", "corner"),
				MapDefinition.Tier.MAJOR, false);
		MapDefinition onePlayable = new MapDefinition(base.id(), "test", 1, "Test", 2, base.provinces(), edges,
				List.of(base.nations().get(0), npc), null);
		assertThat(MapValidator.validate(base)).isEmpty();
		assertThat(MapValidator.validate(onePlayable)).anyMatch(p -> p.contains("playable"));
	}

}
