package com.reach.strategy.military.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only reinforcement and disband records ({@code manpower_transfer}); details are JSON. */
@Repository
public class ManpowerTransferRepository {

	private final JdbcTemplate jdbc;

	ManpowerTransferRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public enum Kind {

		REINFORCE, DISBAND

	}

	public record Row(UUID id, Kind kind, UUID battalionId, UUID nationId, int round, int turnNumber, int soldiers,
			long costMg, String detailsJson, Instant createdAt) {
	}

	public void insert(UUID gameId, Row row) {
		jdbc.update("""
				INSERT INTO game.manpower_transfer
				    (id, game_id, kind, battalion_id, nation_id, round_number, turn_number, soldiers, cost_mg, details,
				     created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
				""", row.id(), gameId, row.kind().name(), row.battalionId(), row.nationId(), row.round(),
				row.turnNumber(), row.soldiers(), row.costMg(), row.detailsJson(), Timestamp.from(row.createdAt()));
	}

	public Optional<Row> find(UUID gameId, UUID id) {
		return jdbc.query("""
				SELECT id, kind, battalion_id, nation_id, round_number, turn_number, soldiers, cost_mg,
				       details::text AS details, created_at
				FROM game.manpower_transfer WHERE game_id = ? AND id = ?
				""", (rs, n) -> new Row(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
				rs.getObject("battalion_id", UUID.class), rs.getObject("nation_id", UUID.class),
				rs.getInt("round_number"), rs.getInt("turn_number"), rs.getInt("soldiers"), rs.getLong("cost_mg"),
				rs.getString("details"), rs.getTimestamp("created_at").toInstant()), gameId, id)
			.stream()
			.findFirst();
	}

	public boolean disbanded(UUID gameId, UUID battalionId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM game.manpower_transfer WHERE game_id = ? AND battalion_id = ? AND kind = 'DISBAND'",
				Integer.class, gameId, battalionId);
		return count != null && count > 0;
	}

}
