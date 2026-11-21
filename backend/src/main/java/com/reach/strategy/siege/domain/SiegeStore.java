package com.reach.strategy.siege.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.siege.rules.FortressWorks;
import com.reach.strategy.siege.rules.Strongholds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fortresses ({@code fortification}) and sieges ({@code siege}) as {@link Strongholds} values, in
 * plain JDBC (ENGINE_DESIGN S3, decision D4). {@link #persist} compares two of them by id and writes
 * only new and changed rows, one batched statement per table.
 */
@Repository
public class SiegeStore {

	private static final String FORTRESSES = """
			SELECT id, city_id, level, status, integrity, max_integrity, project, project_target_level,
			       project_ordered_round, project_completes_after_round, project_cost_mg, ruleset_version, created_at
			FROM game.fortification
			""";

	private static final String SIEGES = """
			SELECT id, province_state_id, city_id, besieger_nation_id, defender_nation_id, fortress_level,
			       required_progress, progress, status, started_round, started_turn_number, last_processed_round,
			       ended_round, end_reason, artillery_siege, damaged_building_id, population_lost, created_at
			FROM game.siege
			""";

	private final JdbcTemplate jdbc;

	SiegeStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public List<Strongholds.Fortress> fortresses(UUID gameId) {
		return jdbc.query(FORTRESSES + "WHERE game_id = ? ORDER BY id", SiegeStore::fortress, gameId);
	}

	@Transactional(readOnly = true)
	public Optional<Strongholds.Fortress> fortressOfCity(UUID gameId, UUID cityId) {
		return jdbc.query(FORTRESSES + "WHERE game_id = ? AND city_id = ?", SiegeStore::fortress, gameId, cityId)
			.stream()
			.findFirst();
	}

	@Transactional(readOnly = true)
	public Optional<Strongholds.Fortress> fortress(UUID gameId, UUID id) {
		return jdbc.query(FORTRESSES + "WHERE game_id = ? AND id = ?", SiegeStore::fortress, gameId, id)
			.stream()
			.findFirst();
	}

	/** The game's active sieges in id order. */
	@Transactional(readOnly = true)
	public List<Strongholds.Siege> activeSieges(UUID gameId) {
		return jdbc.query(SIEGES + "WHERE game_id = ? AND status = 'ACTIVE' ORDER BY id", SiegeStore::siege, gameId);
	}

	/** The most recently ended sieges, newest first. */
	@Transactional(readOnly = true)
	public List<Strongholds.Siege> endedSieges(UUID gameId, int limit) {
		return jdbc.query(SIEGES + "WHERE game_id = ? AND status <> 'ACTIVE' ORDER BY ended_round DESC, id ASC LIMIT ?",
				SiegeStore::siege, gameId, limit);
	}

	@Transactional(readOnly = true)
	public Optional<Strongholds.Siege> siege(UUID gameId, UUID id) {
		return jdbc.query(SIEGES + "WHERE game_id = ? AND id = ?", SiegeStore::siege, gameId, id).stream().findFirst();
	}

	/** Writes the fortresses and sieges that are new or different in {@code after}. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void persist(UUID gameId, Strongholds before, Strongholds after) {
		List<Strongholds.Fortress> fortresses = after.fortresses()
			.stream()
			.filter(f -> !f.equals(before.fortress(f.id()).orElse(null)))
			.toList();
		if (!fortresses.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO game.fortification (id, game_id, city_id, level, status, integrity, max_integrity, project,
					    project_target_level, project_ordered_round, project_completes_after_round, project_cost_mg,
					    ruleset_version, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (id) DO UPDATE SET level = excluded.level, status = excluded.status,
					    integrity = excluded.integrity, max_integrity = excluded.max_integrity, project = excluded.project,
					    project_target_level = excluded.project_target_level,
					    project_ordered_round = excluded.project_ordered_round,
					    project_completes_after_round = excluded.project_completes_after_round,
					    project_cost_mg = excluded.project_cost_mg
					""", fortresses, fortresses.size(), (ps, f) -> {
				ps.setObject(1, f.id());
				ps.setObject(2, gameId);
				ps.setObject(3, f.cityId());
				ps.setInt(4, f.level());
				ps.setString(5, f.status().name());
				ps.setInt(6, f.integrity());
				ps.setInt(7, f.maxIntegrity());
				ps.setString(8, f.project() == null ? null : f.project().name());
				ps.setObject(9, f.projectTargetLevel(), Types.INTEGER);
				ps.setObject(10, f.projectOrderedRound(), Types.INTEGER);
				ps.setObject(11, f.projectCompletesAfterRound(), Types.INTEGER);
				ps.setLong(12, f.projectCostMg());
				ps.setString(13, f.rulesetVersion());
				ps.setTimestamp(14, Timestamp.from(f.createdAt()));
			});
		}
		List<Strongholds.Siege> sieges = after.sieges()
			.stream()
			.filter(s -> !s.equals(before.siege(s.id()).orElse(null)))
			.toList();
		if (!sieges.isEmpty()) {
			jdbc.batchUpdate("""
					INSERT INTO game.siege (id, game_id, province_state_id, city_id, besieger_nation_id, defender_nation_id,
					    fortress_level, required_progress, progress, status, started_round, started_turn_number,
					    last_processed_round, ended_round, end_reason, artillery_siege, damaged_building_id, population_lost,
					    created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (id) DO UPDATE SET progress = excluded.progress, status = excluded.status,
					    last_processed_round = excluded.last_processed_round, ended_round = excluded.ended_round,
					    end_reason = excluded.end_reason, artillery_siege = excluded.artillery_siege,
					    damaged_building_id = excluded.damaged_building_id, population_lost = excluded.population_lost
					""", sieges, sieges.size(), (ps, s) -> {
				ps.setObject(1, s.id());
				ps.setObject(2, gameId);
				ps.setObject(3, s.provinceStateId());
				ps.setObject(4, s.cityId());
				ps.setObject(5, s.besiegerNationId());
				ps.setObject(6, s.defenderNationId());
				ps.setInt(7, s.fortressLevel());
				ps.setInt(8, s.requiredProgress());
				ps.setInt(9, s.progress());
				ps.setString(10, s.status().name());
				ps.setInt(11, s.startedRound());
				ps.setInt(12, s.startedTurnNumber());
				ps.setObject(13, s.lastProcessedRound(), Types.INTEGER);
				ps.setObject(14, s.endedRound(), Types.INTEGER);
				ps.setString(15, s.endReason() == null ? null : s.endReason().name());
				ps.setObject(16, s.artillerySiege(), Types.BOOLEAN);
				ps.setObject(17, s.damagedBuildingId(), Types.OTHER);
				ps.setInt(18, s.populationLost());
				ps.setTimestamp(19, Timestamp.from(s.createdAt()));
			});
		}
	}

	private static Strongholds.Fortress fortress(ResultSet rs, int n) throws SQLException {
		String project = rs.getString("project");
		return new Strongholds.Fortress(rs.getObject("id", UUID.class), rs.getObject("city_id", UUID.class),
				rs.getInt("level"), FortressWorks.Status.valueOf(rs.getString("status")), rs.getInt("integrity"),
				rs.getInt("max_integrity"), project == null ? null : FortressWorks.Project.valueOf(project),
				rs.getObject("project_target_level", Integer.class), rs.getObject("project_ordered_round", Integer.class),
				rs.getObject("project_completes_after_round", Integer.class), rs.getLong("project_cost_mg"),
				rs.getString("ruleset_version"), rs.getTimestamp("created_at").toInstant());
	}

	private static Strongholds.Siege siege(ResultSet rs, int n) throws SQLException {
		String reason = rs.getString("end_reason");
		return new Strongholds.Siege(rs.getObject("id", UUID.class), rs.getObject("province_state_id", UUID.class),
				rs.getObject("city_id", UUID.class), rs.getObject("besieger_nation_id", UUID.class),
				rs.getObject("defender_nation_id", UUID.class), rs.getInt("fortress_level"), rs.getInt("required_progress"),
				rs.getInt("progress"), Strongholds.Siege.Status.valueOf(rs.getString("status")), rs.getInt("started_round"),
				rs.getInt("started_turn_number"), rs.getObject("last_processed_round", Integer.class),
				rs.getObject("ended_round", Integer.class),
				reason == null ? null : Strongholds.Siege.EndReason.valueOf(reason),
				rs.getObject("artillery_siege", Boolean.class), rs.getObject("damaged_building_id", UUID.class),
				rs.getInt("population_lost"), rs.getTimestamp("created_at").toInstant());
	}

}
