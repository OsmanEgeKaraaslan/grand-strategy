package com.reach.strategy.military.domain;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.reach.strategy.military.rules.Cohorts;
import com.reach.strategy.military.rules.Forces;
import com.reach.strategy.military.rules.UnitType;

import com.reach.strategy.platform.read.ReadScope;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a game's {@link Forces} in three queries and writes the difference between two of them
 * (ENGINE_DESIGN S2c, decision D4): rows are compared by id and only inserted, changed and removed
 * rows are written, one batched statement per kind and table. Runs inside the caller's transaction.
 */
@Repository
public class MilitaryStore {

	private static final String UNIT_COLUMNS = """
			id, nation_id, unit_type, manpower, max_manpower, origin_city_id, province_state_id, army_id, \
			equipment_level, recruitment_cost_mg, recruited_round, recruited_turn_number, last_moved_turn_number, \
			last_reinforced_turn_number, last_moved_round, last_reinforced_round, commander_nation_id, \
			reinforced_this_turn, created_at""";

	private static final String UPSERT_UNIT = "INSERT INTO game.battalion (game_id, " + UNIT_COLUMNS + """
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET manpower = excluded.manpower, province_state_id = excluded.province_state_id,
			    army_id = excluded.army_id, equipment_level = excluded.equipment_level,
			    last_moved_turn_number = excluded.last_moved_turn_number,
			    last_reinforced_turn_number = excluded.last_reinforced_turn_number,
			    last_moved_round = excluded.last_moved_round, last_reinforced_round = excluded.last_reinforced_round,
			    commander_nation_id = excluded.commander_nation_id, reinforced_this_turn = excluded.reinforced_this_turn
			""";

	/** The read-scope name of a game's forces. */
	private static final String FORCES = "military.forces";

	private final JdbcTemplate jdbc;

	MilitaryStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The whole military state of a game, for a command that changes it. */
	@Transactional(propagation = Propagation.MANDATORY)
	public Forces load(UUID gameId) {
		// Inside a turn scope (ENGINE_DESIGN S6) one load serves the whole computer turn; persist keeps it exact.
		return ReadScope.remember(List.of(FORCES, gameId), () -> loadFromDatabase(gameId));
	}

	private Forces loadFromDatabase(UUID gameId) {
		List<Forces.Unit> units = queryUnits(gameId);
		List<Forces.Formation> formations = queryFormations(gameId);
		Map<UUID, List<Cohorts.Cohort>> cohorts = new HashMap<>();
		jdbc.query("""
				SELECT battalion_id, origin_city_id, soldiers FROM game.battalion_cohort
				WHERE game_id = ? ORDER BY battalion_id, origin_city_id
				""", (ResultSet rs) -> {
			cohorts.computeIfAbsent(rs.getObject("battalion_id", UUID.class), id -> new ArrayList<>())
				.add(new Cohorts.Cohort(rs.getObject("origin_city_id", UUID.class), rs.getInt("soldiers")));
		}, gameId);
		return Forces.of(units, formations, cohorts);
	}

	/** Every battalion of the game in id order (for views and other modules: no cohorts, no armies). */
	@Transactional(readOnly = true)
	public List<Forces.Unit> units(UUID gameId) {
		Forces remembered = ReadScope.peek(List.of(FORCES, gameId));
		return remembered != null ? remembered.units() : queryUnits(gameId);
	}

	private List<Forces.Unit> queryUnits(UUID gameId) {
		return jdbc.query("SELECT " + UNIT_COLUMNS + " FROM game.battalion WHERE game_id = ? ORDER BY id",
				MilitaryStore::unit, gameId);
	}

	@Transactional(readOnly = true)
	public java.util.Optional<Forces.Unit> unit(UUID gameId, UUID unitId) {
		return jdbc.query("SELECT " + UNIT_COLUMNS + " FROM game.battalion WHERE game_id = ? AND id = ?",
				MilitaryStore::unit, gameId, unitId)
			.stream()
			.findFirst();
	}

	/** Every army of the game in id order. */
	@Transactional(readOnly = true)
	public List<Forces.Formation> formations(UUID gameId) {
		Forces remembered = ReadScope.peek(List.of(FORCES, gameId));
		return remembered != null ? remembered.formations() : queryFormations(gameId);
	}

	private List<Forces.Formation> queryFormations(UUID gameId) {
		return jdbc.query("SELECT id, nation_id, name, created_at FROM game.army WHERE game_id = ? ORDER BY id",
				MilitaryStore::formation, gameId);
	}

	/** The cohorts of one battalion, ordered by origin city id. */
	@Transactional(readOnly = true)
	public List<Cohorts.Cohort> cohorts(UUID gameId, UUID unitId) {
		return jdbc.query("""
				SELECT origin_city_id, soldiers FROM game.battalion_cohort
				WHERE game_id = ? AND battalion_id = ? ORDER BY origin_city_id
				""", (rs, n) -> new Cohorts.Cohort(rs.getObject("origin_city_id", UUID.class), rs.getInt("soldiers")),
				gameId, unitId);
	}

	/**
	 * Writes what changed from {@code before} to {@code after}. Order respects the foreign keys: new
	 * armies, then battalions, then cohorts, then removed battalions (their cohorts cascade), then
	 * removed armies.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void persist(UUID gameId, Forces before, Forces after) {
		ReadScope.replace(List.of(FORCES, gameId), before, after);
		List<Forces.Formation> newFormations = after.formations()
			.stream()
			.filter(f -> before.formation(f.id()).isEmpty())
			.toList();
		if (!newFormations.isEmpty()) {
			jdbc.batchUpdate("INSERT INTO game.army (id, game_id, nation_id, name, created_at) VALUES (?, ?, ?, ?, ?)",
					newFormations, newFormations.size(), (ps, f) -> {
						ps.setObject(1, f.id());
						ps.setObject(2, gameId);
						ps.setObject(3, f.nationId());
						ps.setString(4, f.name());
						ps.setTimestamp(5, Timestamp.from(f.createdAt()));
					});
		}

		List<Forces.Unit> changed = after.units()
			.stream()
			.filter(u -> !u.equals(before.unit(u.id()).orElse(null)))
			.toList();
		if (!changed.isEmpty()) {
			jdbc.batchUpdate(UPSERT_UNIT, changed, changed.size(), (ps, u) -> bind(ps, gameId, u));
		}

		persistCohorts(gameId, before, after, changed);

		List<UUID> removed = before.units()
			.stream()
			.map(Forces.Unit::id)
			.filter(id -> after.unit(id).isEmpty())
			.toList();
		if (!removed.isEmpty()) {
			jdbc.batchUpdate("DELETE FROM game.battalion WHERE game_id = ? AND id = ?", removed, removed.size(),
					(ps, id) -> {
						ps.setObject(1, gameId);
						ps.setObject(2, id);
					});
		}

		List<UUID> removedFormations = before.formations()
			.stream()
			.map(Forces.Formation::id)
			.filter(id -> after.formation(id).isEmpty())
			.toList();
		if (!removedFormations.isEmpty()) {
			jdbc.batchUpdate("DELETE FROM game.army WHERE game_id = ? AND id = ?", removedFormations,
					removedFormations.size(), (ps, id) -> {
						ps.setObject(1, gameId);
						ps.setObject(2, id);
					});
		}
	}

	/** Cohorts change only together with a battalion's manpower, so only changed battalions are compared. */
	private void persistCohorts(UUID gameId, Forces before, Forces after, List<Forces.Unit> changed) {
		List<Object[]> inserts = new ArrayList<>();
		List<Object[]> updates = new ArrayList<>();
		List<Object[]> deletes = new ArrayList<>();
		for (Forces.Unit unit : changed) {
			Map<UUID, Integer> old = new LinkedHashMap<>();
			if (before.unit(unit.id()).isPresent()) {
				before.cohortsOf(unit.id()).forEach(c -> old.put(c.originCityId(), c.soldiers()));
			}
			for (Cohorts.Cohort cohort : after.cohortsOf(unit.id())) {
				Integer was = old.remove(cohort.originCityId());
				if (was == null) {
					inserts.add(new Object[] { UUID.randomUUID(), gameId, unit.id(), cohort.originCityId(), cohort.soldiers() });
				}
				else if (!Objects.equals(was, cohort.soldiers())) {
					updates.add(new Object[] { cohort.soldiers(), gameId, unit.id(), cohort.originCityId() });
				}
			}
			old.keySet().forEach(city -> deletes.add(new Object[] { gameId, unit.id(), city }));
		}
		if (!deletes.isEmpty()) {
			jdbc.batchUpdate("DELETE FROM game.battalion_cohort WHERE game_id = ? AND battalion_id = ? AND origin_city_id = ?",
					deletes);
		}
		if (!updates.isEmpty()) {
			jdbc.batchUpdate(
					"UPDATE game.battalion_cohort SET soldiers = ? WHERE game_id = ? AND battalion_id = ? AND origin_city_id = ?",
					updates);
		}
		if (!inserts.isEmpty()) {
			jdbc.batchUpdate(
					"INSERT INTO game.battalion_cohort (id, game_id, battalion_id, origin_city_id, soldiers) VALUES (?, ?, ?, ?, ?)",
					inserts);
		}
	}

	private static void bind(PreparedStatement ps, UUID gameId, Forces.Unit u) throws SQLException {
		ps.setObject(1, gameId);
		ps.setObject(2, u.id());
		ps.setObject(3, u.nationId());
		ps.setString(4, u.unitType().name());
		ps.setInt(5, u.manpower());
		ps.setInt(6, u.maxManpower());
		ps.setObject(7, u.originCityId());
		ps.setObject(8, u.provinceStateId());
		ps.setObject(9, u.armyId(), Types.OTHER);
		ps.setInt(10, u.equipmentLevel());
		ps.setLong(11, u.recruitmentCostMg());
		ps.setInt(12, u.recruitedRound());
		ps.setInt(13, u.recruitedTurnNumber());
		ps.setObject(14, u.lastMovedTurnNumber(), Types.INTEGER);
		ps.setObject(15, u.lastReinforcedTurnNumber(), Types.INTEGER);
		ps.setObject(16, u.lastMovedRound(), Types.INTEGER);
		ps.setObject(17, u.lastReinforcedRound(), Types.INTEGER);
		ps.setObject(18, u.commanderNationId(), Types.OTHER);
		ps.setInt(19, u.reinforcedThisTurn());
		ps.setTimestamp(20, Timestamp.from(u.createdAt()));
	}

	private static Forces.Formation formation(ResultSet rs, int n) throws SQLException {
		return new Forces.Formation(rs.getObject("id", UUID.class), rs.getObject("nation_id", UUID.class),
				rs.getString("name"), rs.getTimestamp("created_at").toInstant());
	}

	private static Forces.Unit unit(ResultSet rs, int n) throws SQLException {
		return new Forces.Unit(rs.getObject("id", UUID.class), rs.getObject("nation_id", UUID.class),
				UnitType.valueOf(rs.getString("unit_type")), rs.getInt("manpower"), rs.getInt("max_manpower"),
				rs.getObject("origin_city_id", UUID.class), rs.getObject("province_state_id", UUID.class),
				rs.getObject("army_id", UUID.class), rs.getInt("equipment_level"), rs.getLong("recruitment_cost_mg"),
				rs.getInt("recruited_round"), rs.getInt("recruited_turn_number"),
				rs.getObject("last_moved_turn_number", Integer.class),
				rs.getObject("last_reinforced_turn_number", Integer.class), rs.getObject("last_moved_round", Integer.class),
				rs.getObject("last_reinforced_round", Integer.class), rs.getObject("commander_nation_id", UUID.class),
				rs.getInt("reinforced_this_turn"), rs.getTimestamp("created_at").toInstant());
	}

}
