package com.reach.strategy.world.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.world.rules.MapDefinition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads immutable map templates. Plain JDBC into records: templates are read-only reference data
 * and never mutated through JPA.
 */
@Repository
public class MapTemplateReader {

	private final JdbcTemplate jdbc;

	MapTemplateReader(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<UUID> allMapIds() {
		return jdbc.queryForList("SELECT id FROM game.map_template ORDER BY key, version", UUID.class);
	}

	/** The highest published version of a map. */
	public Optional<UUID> findLatestId(String key) {
		return jdbc.queryForList("SELECT id FROM game.map_template WHERE key = ? ORDER BY version DESC LIMIT 1", UUID.class,
				key)
			.stream()
			.findFirst();
	}

	public Optional<UUID> findId(String key, int version) {
		return jdbc.queryForList("SELECT id FROM game.map_template WHERE key = ? AND version = ?", UUID.class, key, version)
			.stream()
			.findFirst();
	}

	public Optional<MapTemplate> load(UUID mapId) {
		List<Header> headers = jdbc.query(
				"SELECT id, key, version, name, view_box, max_players, min_border_length FROM game.map_template WHERE id = ?",
				(rs, row) -> new Header(rs.getObject("id", UUID.class), rs.getString("key"), rs.getInt("version"),
						rs.getString("name"), rs.getString("view_box"), rs.getInt("max_players"),
						(Integer) rs.getObject("min_border_length")),
				mapId);
		if (headers.isEmpty()) {
			return Optional.empty();
		}
		Header header = headers.getFirst();

		List<MapDefinition.Province> provinces = new ArrayList<>();
		Map<String, MapTemplate.ProvinceShape> shapes = new LinkedHashMap<>();
		Map<UUID, String> keysById = new LinkedHashMap<>();
		jdbc.query("""
				SELECT id, key, name, terrain, svg_path, label_x, label_y, city_name, start_population, city_x, city_y
				FROM game.province_template WHERE map_template_id = ? ORDER BY key
				""", rs -> {
			String key = rs.getString("key");
			UUID id = rs.getObject("id", UUID.class);
			keysById.put(id, key);
			provinces.add(new MapDefinition.Province(id, key, rs.getString("name"),
					MapDefinition.Terrain.valueOf(rs.getString("terrain")), rs.getString("city_name"),
					rs.getInt("start_population")));
			Integer cityX = (Integer) rs.getObject("city_x");
			Integer cityY = (Integer) rs.getObject("city_y");
			shapes.put(key, new MapTemplate.ProvinceShape(rs.getString("svg_path"), rs.getInt("label_x"), rs.getInt("label_y"),
					cityX == null ? rs.getInt("label_x") : cityX, cityY == null ? rs.getInt("label_y") : cityY));
		}, mapId);

		List<MapDefinition.Adjacency> adjacencies = new ArrayList<>();
		List<MapTemplate.RiverShape> rivers = new ArrayList<>();
		jdbc.query("""
				SELECT province_a_id, province_b_id, crossing, river_svg_path
				FROM game.province_adjacency WHERE map_template_id = ?
				""", rs -> {
			String a = keysById.get(rs.getObject("province_a_id", UUID.class));
			String b = keysById.get(rs.getObject("province_b_id", UUID.class));
			MapDefinition.Crossing crossing = MapDefinition.Crossing.valueOf(rs.getString("crossing"));
			adjacencies.add(a.compareTo(b) < 0 ? new MapDefinition.Adjacency(a, b, crossing)
					: new MapDefinition.Adjacency(b, a, crossing));
			if (crossing == MapDefinition.Crossing.RIVER) {
				rivers.add(new MapTemplate.RiverShape(a, b, rs.getString("river_svg_path")));
			}
		}, mapId);
		adjacencies.sort((x, y) -> (x.a() + x.b()).compareTo(y.a() + y.b()));

		Map<UUID, List<String>> startProvinces = new LinkedHashMap<>();
		jdbc.query("""
				SELECT nation_template_id, province_template_id FROM game.nation_start_province
				WHERE map_template_id = ?
				""", rs -> {
			startProvinces.computeIfAbsent(rs.getObject("nation_template_id", UUID.class), id -> new ArrayList<>())
				.add(keysById.get(rs.getObject("province_template_id", UUID.class)));
		}, mapId);

		List<MapDefinition.Nation> nations = jdbc.query("""
				SELECT id, key, name, color, capital_province_id, tier, playable FROM game.nation_template
				WHERE map_template_id = ? ORDER BY key
				""", (rs, row) -> {
			UUID id = rs.getObject("id", UUID.class);
			List<String> owned = new ArrayList<>(startProvinces.getOrDefault(id, List.of()));
			owned.sort(String::compareTo);
			return new MapDefinition.Nation(id, rs.getString("key"), rs.getString("name"), rs.getString("color"),
					keysById.get(rs.getObject("capital_province_id", UUID.class)), owned,
					MapDefinition.Tier.valueOf(rs.getString("tier")), rs.getBoolean("playable"));
		}, mapId);

		MapDefinition definition = new MapDefinition(header.id(), header.key(), header.version(), header.name(),
				header.maxPlayers(), provinces, adjacencies, nations, header.minBorderLength());
		return Optional.of(new MapTemplate(definition, new MapTemplate.Geometry(header.viewBox(), shapes, rivers)));
	}

	private record Header(UUID id, String key, int version, String name, String viewBox, int maxPlayers,
			Integer minBorderLength) {
	}

}
