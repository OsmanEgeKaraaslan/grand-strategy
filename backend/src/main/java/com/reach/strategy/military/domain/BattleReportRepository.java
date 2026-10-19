package com.reach.strategy.military.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only battle reports; the detailed numbers are a JSON document written by the service. */
@Repository
public class BattleReportRepository {

	private static final String COLUMNS = """
			id, round_number, turn_number, province_state_id, attacker_nation_id, defender_nation_id, winner,
			ruleset_version, report::text AS report, created_at""";

	private final JdbcTemplate jdbc;

	BattleReportRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Row(UUID id, int round, int turnNumber, UUID provinceStateId, UUID attackerNationId,
			UUID defenderNationId, String winner, String rulesetVersion, String reportJson, Instant createdAt) {
	}

	public void insert(UUID gameId, Row row) {
		jdbc.update("""
				INSERT INTO game.battle_report
				    (id, game_id, round_number, turn_number, province_state_id, attacker_nation_id, defender_nation_id,
				     winner, ruleset_version, report, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
				""", row.id(), gameId, row.round(), row.turnNumber(), row.provinceStateId(), row.attackerNationId(),
				row.defenderNationId(), row.winner(), row.rulesetVersion(), row.reportJson(),
				Timestamp.from(row.createdAt()));
	}

	/** Newest first; the id breaks ties of battles recorded in the same instant. */
	public List<Row> recent(UUID gameId, int limit) {
		return jdbc.query("SELECT " + COLUMNS + """

				FROM game.battle_report
				WHERE game_id = ?
				ORDER BY created_at DESC, turn_number DESC, id
				LIMIT ?
				""", BattleReportRepository::map, gameId, limit);
	}

	public Optional<Row> find(UUID gameId, UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.battle_report WHERE game_id = ? AND id = ?",
				BattleReportRepository::map, gameId, id)
			.stream()
			.findFirst();
	}

	public long count(UUID gameId) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM game.battle_report WHERE game_id = ?", Long.class,
				gameId);
		return count == null ? 0 : count;
	}

	private static Row map(ResultSet rs, int row) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getInt("round_number"), rs.getInt("turn_number"),
				rs.getObject("province_state_id", UUID.class), rs.getObject("attacker_nation_id", UUID.class),
				rs.getObject("defender_nation_id", UUID.class), rs.getString("winner"), rs.getString("ruleset_version"),
				rs.getString("report"), rs.getTimestamp("created_at").toInstant());
	}

}
