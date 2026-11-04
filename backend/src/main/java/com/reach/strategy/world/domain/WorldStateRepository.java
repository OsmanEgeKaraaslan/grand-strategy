package com.reach.strategy.world.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-game world rows (nation, province_state, city). Phase 3 only creates and reads them, so
 * plain JDBC batch statements are enough; later phases that mutate them (economy, war) can map
 * them as JPA aggregates. Runs inside the caller's transaction.
 */
@Repository
public class WorldStateRepository {

	private final JdbcTemplate jdbc;

	WorldStateRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param gamePlayerId null for an NPC-controlled nation
	 * @param control HUMAN, AI (a computer seat, Phase 11) or NPC
	 */
	public record NationRow(UUID id, UUID gameId, UUID nationTemplateId, UUID gamePlayerId, String name, String color,
			Integer eliminatedRound, String control) {

		public NationRow(UUID id, UUID gameId, UUID nationTemplateId, UUID gamePlayerId, String name, String color) {
			this(id, gameId, nationTemplateId, gamePlayerId, name, color, null, gamePlayerId == null ? "NPC" : "HUMAN");
		}

	}

	public record ProvinceRow(UUID id, UUID gameId, UUID provinceTemplateId, UUID ownerNationId, UUID controllerNationId) {
	}

	public record CityRow(UUID id, UUID gameId, UUID provinceStateId, String name, int population) {
	}

	/** Snapshot row: one province of one game with its template key and city. */
	public record ProvinceSnapshotRow(String provinceKey, UUID provinceStateId, UUID ownerNationId, UUID controllerNationId, UUID cityId,
			String cityName, int population) {
	}

	public boolean exists(UUID gameId) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM game.nation WHERE game_id = ?", Integer.class, gameId);
		return count != null && count > 0;
	}

	public void insertNations(List<NationRow> nations) {
		jdbc.batchUpdate("""
				INSERT INTO game.nation (id, game_id, nation_template_id, game_player_id, name, color, control)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", nations, nations.size(), (ps, n) -> {
			ps.setObject(1, n.id());
			ps.setObject(2, n.gameId());
			ps.setObject(3, n.nationTemplateId());
			ps.setObject(4, n.gamePlayerId(), java.sql.Types.OTHER);
			ps.setString(5, n.name());
			ps.setString(6, n.color());
			ps.setString(7, n.control());
		});
	}

	public void insertProvinces(List<ProvinceRow> provinces) {
		jdbc.batchUpdate("""
				INSERT INTO game.province_state (id, game_id, province_template_id, owner_nation_id, controller_nation_id)
				VALUES (?, ?, ?, ?, ?)
				""", provinces, provinces.size(), (ps, p) -> {
			ps.setObject(1, p.id());
			ps.setObject(2, p.gameId());
			ps.setObject(3, p.provinceTemplateId());
			ps.setObject(4, p.ownerNationId());
			ps.setObject(5, p.controllerNationId());
		});
	}

	public void insertCities(List<CityRow> cities) {
		jdbc.batchUpdate("""
				INSERT INTO game.city (id, game_id, province_state_id, name, population)
				VALUES (?, ?, ?, ?, ?)
				""", cities, cities.size(), (ps, c) -> {
			ps.setObject(1, c.id());
			ps.setObject(2, c.gameId());
			ps.setObject(3, c.provinceStateId());
			ps.setString(4, c.name());
			ps.setInt(5, c.population());
		});
	}

	public List<NationRow> nations(UUID gameId) {
		return jdbc.query("""
				SELECT id, game_id, nation_template_id, game_player_id, name, color, eliminated_round, control
				FROM game.nation WHERE game_id = ? ORDER BY name
				""", (rs, row) -> new NationRow(rs.getObject("id", UUID.class), rs.getObject("game_id", UUID.class),
				rs.getObject("nation_template_id", UUID.class), rs.getObject("game_player_id", UUID.class),
				rs.getString("name"), rs.getString("color"), (Integer) rs.getObject("eliminated_round"),
				rs.getString("control")), gameId);
	}

	public void handOverToComputer(UUID gameId, UUID gamePlayerId) {
		int updated = jdbc.update(
				"UPDATE game.nation SET control = 'AI' WHERE game_id = ? AND game_player_id = ? AND control = 'HUMAN'",
				gameId, gamePlayerId);
		if (updated != 1) {
			throw new IllegalStateException("No human nation of seat " + gamePlayerId + " in game " + gameId);
		}
	}

	public void markEliminated(UUID gameId, UUID nationId, int round) {
		int updated = jdbc.update(
				"UPDATE game.nation SET eliminated_round = ? WHERE game_id = ? AND id = ? AND eliminated_round IS NULL", round,
				gameId, nationId);
		if (updated != 1) {
			throw new IllegalStateException("Nation " + nationId + " of game " + gameId + " is unknown or already eliminated");
		}
	}

	/** The map a game is played on (via its nations' templates); empty before the start. */
	public java.util.Optional<UUID> mapIdOf(UUID gameId) {
		return jdbc.queryForList("""
				SELECT DISTINCT nt.map_template_id FROM game.nation n
				JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE n.game_id = ?
				""", UUID.class, gameId).stream().findFirst();
	}

	/** Adds delta to a city's population; the CHECK constraint rejects a negative result. */
	public int changePopulation(UUID gameId, UUID cityId, int delta) {
		List<Integer> updated = jdbc.queryForList("""
				UPDATE game.city SET population = population + ?
				WHERE game_id = ? AND id = ? AND population + ? >= 0
				RETURNING population
				""", Integer.class, delta, gameId, cityId, delta);
		if (updated.isEmpty()) {
			throw new IllegalStateException("City " + cityId + " of game " + gameId + " cannot change population by " + delta);
		}
		return updated.getFirst();
	}

	public void setController(UUID gameId, String provinceKey, UUID controllerNationId) {
		int updated = jdbc.update("""
				UPDATE game.province_state ps SET controller_nation_id = ?
				FROM game.province_template pt
				WHERE ps.game_id = ? AND pt.id = ps.province_template_id AND pt.key = ?
				""", controllerNationId, gameId, provinceKey);
		if (updated != 1) {
			throw new IllegalStateException("Province " + provinceKey + " of game " + gameId + " not found");
		}
	}

	/** Legal transfer by peace treaty: the new owner also takes control. Only from the expected owner. */
	public void transferOwnership(UUID gameId, String provinceKey, UUID fromNationId, UUID toNationId) {
		int updated = jdbc.update("""
				UPDATE game.province_state ps SET owner_nation_id = ?, controller_nation_id = ?
				FROM game.province_template pt
				WHERE ps.game_id = ? AND pt.id = ps.province_template_id AND pt.key = ? AND ps.owner_nation_id = ?
				""", toNationId, toNationId, gameId, provinceKey, fromNationId);
		if (updated != 1) {
			throw new IllegalStateException("Province " + provinceKey + " of game " + gameId + " is not owned by " + fromNationId);
		}
	}

	public List<ProvinceSnapshotRow> provinces(UUID gameId) {
		return jdbc.query("""
				SELECT pt.key, ps.id AS province_state_id, ps.owner_nation_id, ps.controller_nation_id, c.id AS city_id,
				       c.name AS city_name, c.population
				FROM game.province_state ps
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.city c ON c.game_id = ps.game_id AND c.province_state_id = ps.id
				WHERE ps.game_id = ?
				ORDER BY pt.key
				""", (rs, row) -> new ProvinceSnapshotRow(rs.getString("key"),
				rs.getObject("province_state_id", UUID.class), rs.getObject("owner_nation_id", UUID.class),
				rs.getObject("controller_nation_id", UUID.class), rs.getObject("city_id", UUID.class),
				rs.getString("city_name"), rs.getInt("population")),
				gameId);
	}

}
