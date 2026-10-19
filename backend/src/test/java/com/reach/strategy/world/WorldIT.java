package com.reach.strategy.world;

import static com.reach.strategy.support.ApiDriver.newPlayer;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.world.api.WorldApi;
import com.reach.strategy.world.application.WorldService;
import com.reach.strategy.world.domain.MapTemplate;
import com.reach.strategy.world.rules.MapDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The seeded map template as stored in PostgreSQL, its validation and its API. */
@PostgresIntegrationTest
class WorldIT {

	@Autowired
	WorldService world;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MockMvc mockMvc;

	ApiDriver api;

	MapDefinition map;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		map = world.template(world.defaultMapId()).definition();
	}

	@Test
	void everyStoredMapPassesValidation() {
		assertThat(world.validateAllMaps()).isEmpty();
	}

	@Test
	void theSunderedReachHasTwoNationsWithSixProvincesEachAndNoNeutralLand() {
		assertThat(map.key()).isEqualTo("sundered-reach");
		assertThat(map.provinces()).hasSize(12);
		assertThat(map.nations()).extracting(MapDefinition.Nation::key).containsExactlyInAnyOrder("varn", "ostra");
		assertThat(map.nations()).allSatisfy(nation -> assertThat(nation.startProvinceKeys()).hasSize(6));
		Map<String, String> owners = map.startingOwners();
		assertThat(owners).hasSize(12);
		assertThat(owners.get("varnhold")).isEqualTo("varn");
		assertThat(owners.get("ostrava")).isEqualTo("ostra");
	}

	@Test
	void adjacencyGraphMatchesTheDrawnMap() {
		assertThat(map.adjacencies()).hasSize(17);
		assertThat(map.neighbours("elmsford")).containsExactlyInAnyOrder("greyspire", "varnhold", "sablewater", "fenwick");
		assertThat(map.neighbours("harrowmere")).containsExactlyInAnyOrder("greyspire", "varnhold");
		for (MapDefinition.Province province : map.provinces()) {
			for (String neighbour : map.neighbours(province.key())) {
				assertThat(map.neighbours(neighbour)).as("symmetric adjacency").contains(province.key());
			}
		}
		assertThat(map.adjacencies()).filteredOn(edge -> edge.crossing() == MapDefinition.Crossing.RIVER)
			.extracting(edge -> edge.a() + "-" + edge.b())
			.containsExactlyInAnyOrder("elmsford-sablewater", "fenwick-marrowfield");
	}

	@Test
	void riversNeverTouchMountainsOnThisMap() {
		List<String> mountains = map.provinces()
			.stream()
			.filter(province -> province.terrain() == MapDefinition.Terrain.MOUNTAINS)
			.map(MapDefinition.Province::key)
			.toList();
		assertThat(mountains).containsExactlyInAnyOrder("greyspire", "kaldric");
		assertThat(map.adjacencies()).filteredOn(edge -> edge.crossing() == MapDefinition.Crossing.RIVER)
			.noneMatch(edge -> mountains.contains(edge.a()) || mountains.contains(edge.b()));
	}

	@Test
	void geometryCoversEveryProvinceAndStaysOutOfTheTopology() {
		MapTemplate template = world.template(map.id());
		assertThat(template.geometry().provinces().keySet())
			.containsExactlyInAnyOrderElementsOf(map.provinces().stream().map(MapDefinition.Province::key).toList());
		assertThat(template.geometry().rivers()).hasSize(2);
	}

	@Test
	void mapEndpointServesTheTemplateToSignedInPlayers() throws Exception {
		MvcResult result = api.map(newPlayer(), map.id().toString());

		assertThat(status(result)).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("max-age");
		List<String> keys = read(result, "$.provinces[*].key");
		assertThat(keys).hasSize(12);
		List<String> paths = read(result, "$.provinces[*].svgPath");
		assertThat(paths).allMatch(path -> path.startsWith("M") && path.endsWith("Z"));
		assertThat(status(api.map(newPlayer(), UUID.randomUUID().toString()))).isEqualTo(404);
	}

	@Test
	void mapSummaryListsTheSelectableNations() {
		WorldApi.MapSummary summary = world.mapSummary(map.id());
		assertThat(summary.maxPlayers()).isEqualTo(2);
		assertThat(summary.nations()).extracting(WorldApi.NationOption::provinceCount).containsOnly(6);
	}

	@Test
	void databaseRejectsMalformedTemplates() {
		UUID mapId = map.id();
		UUID a = map.provinces().get(0).id();
		UUID b = map.provinces().get(1).id();
		// PostgreSQL orders uuids as unsigned bytes, i.e. like their canonical strings — not like
		// UUID.compareTo, which compares signed longs.
		boolean aFirst = a.toString().compareTo(b.toString()) < 0;
		UUID low = aFirst ? a : b;
		UUID high = aFirst ? b : a;

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO game.province_adjacency (map_template_id, province_a_id, province_b_id, crossing) VALUES (?, ?, ?, 'NONE')",
				mapId, high, low))
			.as("edges are stored once, ordered")
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO game.province_adjacency (map_template_id, province_a_id, province_b_id, crossing) VALUES (?, ?, ?, 'RIVER')",
				mapId, low, high))
			.as("a river edge needs its drawing")
			.isInstanceOf(DataIntegrityViolationException.class);
		UUID otherNation = map.nations().stream().filter(n -> !n.startProvinceKeys().contains(map.provinces().get(0).key()))
			.findFirst().orElseThrow().id();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO game.nation_start_province (map_template_id, nation_template_id, province_template_id) VALUES (?, ?, ?)",
				mapId, otherNation, a))
			.as("a province starts with at most one owner")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}
