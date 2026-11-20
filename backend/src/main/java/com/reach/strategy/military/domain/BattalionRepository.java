package com.reach.strategy.military.domain;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.military.rules.UnitType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Battalions ({@code battalion}) in plain JDBC (Phase 8, ENGINE_DESIGN S2b). A change is written when
 * {@code save} is called; there is no persistence context, so a mutated battalion that is not saved
 * is not written. The {@code flush} methods remain for callers and do nothing.
 */
@Repository
public class BattalionRepository {

	private static final String COLUMNS = """
			id, game_id, nation_id, unit_type, manpower, max_manpower, origin_city_id, province_state_id, army_id, \
			equipment_level, recruitment_cost_mg, recruited_round, recruited_turn_number, last_moved_turn_number, \
			last_reinforced_turn_number, last_moved_round, last_reinforced_round, commander_nation_id, \
			reinforced_this_turn, created_at""";

	private static final String UPSERT = "INSERT INTO game.battalion (" + COLUMNS + """
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET manpower = excluded.manpower, province_state_id = excluded.province_state_id,
			    army_id = excluded.army_id, equipment_level = excluded.equipment_level,
			    last_moved_turn_number = excluded.last_moved_turn_number,
			    last_reinforced_turn_number = excluded.last_reinforced_turn_number,
			    last_moved_round = excluded.last_moved_round, last_reinforced_round = excluded.last_reinforced_round,
			    commander_nation_id = excluded.commander_nation_id, reinforced_this_turn = excluded.reinforced_this_turn
			""";

	private static final String DELETE = "DELETE FROM game.battalion WHERE game_id = ? AND id = ?";

	private final JdbcTemplate jdbc;

	BattalionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Battalion> findByGameIdOrderById(UUID gameId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.battalion WHERE game_id = ? ORDER BY id",
				BattalionRepository::row, gameId);
	}

	public List<Battalion> findByGameIdAndProvinceStateIdOrderById(UUID gameId, UUID provinceStateId) {
		return jdbc.query(
				"SELECT " + COLUMNS + " FROM game.battalion WHERE game_id = ? AND province_state_id = ? ORDER BY id",
				BattalionRepository::row, gameId, provinceStateId);
	}

	public List<Battalion> findByGameIdAndIdIn(UUID gameId, Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return new ArrayList<>();
		}
		return jdbc.query("SELECT " + COLUMNS + " FROM game.battalion WHERE game_id = ? AND id = ANY (?) ORDER BY id",
				ps -> {
					ps.setObject(1, gameId);
					ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
				}, BattalionRepository::row);
	}

	public List<Battalion> findByGameIdAndArmyId(UUID gameId, UUID armyId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.battalion WHERE game_id = ? AND army_id = ? ORDER BY id",
				BattalionRepository::row, gameId, armyId);
	}

	public long countByGameIdAndArmyId(UUID gameId, UUID armyId) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM game.battalion WHERE game_id = ? AND army_id = ?",
				Long.class, gameId, armyId);
		return count == null ? 0 : count;
	}

	public Optional<Battalion> findById(UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.battalion WHERE id = ?", BattalionRepository::row, id)
			.stream()
			.findFirst();
	}

	/** Inserts a new battalion or writes the changeable fields of an existing one. */
	public Battalion save(Battalion battalion) {
		jdbc.update(UPSERT, ps -> bind(ps, battalion));
		return battalion;
	}

	public Battalion saveAndFlush(Battalion battalion) {
		return save(battalion);
	}

	/** All of them in one batched statement. */
	public <B extends Battalion> List<B> saveAll(Collection<B> list) {
		if (!list.isEmpty()) {
			List<B> rows = List.copyOf(list);
			jdbc.batchUpdate(UPSERT, rows, rows.size(), BattalionRepository::bind);
		}
		return new ArrayList<>(list);
	}

	public <B extends Battalion> List<B> saveAllAndFlush(Collection<B> list) {
		return saveAll(list);
	}

	/** Deletes the battalion; its cohorts go with it (ON DELETE CASCADE). */
	public void delete(Battalion battalion) {
		jdbc.update(DELETE, battalion.gameId(), battalion.id());
	}

	public void deleteAll(Collection<? extends Battalion> list) {
		if (list.isEmpty()) {
			return;
		}
		List<Battalion> rows = List.copyOf(list);
		jdbc.batchUpdate(DELETE, rows, rows.size(), (ps, b) -> {
			ps.setObject(1, b.gameId());
			ps.setObject(2, b.id());
		});
	}

	/** Nothing to do: every save is already written. */
	public void flush() {
	}

	private static void bind(PreparedStatement ps, Battalion b) throws SQLException {
		ps.setObject(1, b.id());
		ps.setObject(2, b.gameId());
		ps.setObject(3, b.nationId());
		ps.setString(4, b.unitType().name());
		ps.setInt(5, b.manpower());
		ps.setInt(6, b.maxManpower());
		ps.setObject(7, b.originCityId());
		ps.setObject(8, b.provinceStateId());
		ps.setObject(9, b.armyId(), Types.OTHER);
		ps.setInt(10, b.equipmentLevel());
		ps.setLong(11, b.recruitmentCostMg());
		ps.setInt(12, b.recruitedRound());
		ps.setInt(13, b.recruitedTurnNumber());
		ps.setObject(14, b.lastMovedTurnNumber(), Types.INTEGER);
		ps.setObject(15, b.lastReinforcedTurnNumber(), Types.INTEGER);
		ps.setObject(16, b.lastMovedRound(), Types.INTEGER);
		ps.setObject(17, b.lastReinforcedRound(), Types.INTEGER);
		ps.setObject(18, b.commanderNationId(), Types.OTHER);
		ps.setInt(19, b.reinforcedThisTurn());
		ps.setTimestamp(20, Timestamp.from(b.createdAt()));
	}

	private static Battalion row(ResultSet rs, int n) throws SQLException {
		return Battalion.restore(rs.getObject("id", UUID.class), rs.getObject("game_id", UUID.class),
				rs.getObject("nation_id", UUID.class), UnitType.valueOf(rs.getString("unit_type")), rs.getInt("manpower"),
				rs.getInt("max_manpower"), rs.getObject("origin_city_id", UUID.class),
				rs.getObject("province_state_id", UUID.class), rs.getObject("army_id", UUID.class),
				rs.getInt("equipment_level"), rs.getLong("recruitment_cost_mg"), rs.getInt("recruited_round"),
				rs.getInt("recruited_turn_number"), rs.getObject("last_moved_turn_number", Integer.class),
				rs.getObject("last_reinforced_turn_number", Integer.class), rs.getObject("last_moved_round", Integer.class),
				rs.getObject("last_reinforced_round", Integer.class), rs.getObject("commander_nation_id", UUID.class),
				rs.getInt("reinforced_this_turn"), rs.getTimestamp("created_at").toInstant());
	}

}
