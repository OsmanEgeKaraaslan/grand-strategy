package com.reach.strategy.diplomacy.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Calls to arms (Phase 10): a defender asks an ally to join its war; from ruleset v8 a declarer may also
 * invite its normal allies to join the attack. The ally honours or declines.
 */
@Repository
public class CallToArmsRepository {

	private final JdbcTemplate jdbc;

	CallToArmsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @param side the side the ally joins when it honours the call (DEFENDER, or ATTACKER for an invitation) */
	public record CallRow(UUID id, UUID warId, UUID caller, UUID ally, UUID allianceId, String status, int createdRound,
			Integer answeredRound, Instant createdAt, String side) {

		public CallRow(UUID id, UUID warId, UUID caller, UUID ally, UUID allianceId, String status, int createdRound,
				Integer answeredRound, Instant createdAt) {
			this(id, warId, caller, ally, allianceId, status, createdRound, answeredRound, createdAt, "DEFENDER");
		}

		public boolean pending() {
			return "PENDING".equals(status);
		}

		public boolean offensive() {
			return "ATTACKER".equals(side);
		}

	}

	private static final String COLUMNS = """
			id, war_id, caller_nation_id, ally_nation_id, alliance_id, status, created_round, answered_round, created_at, side
			""";

	public void insert(UUID gameId, CallRow call) {
		jdbc.update("""
				INSERT INTO game.call_to_arms (id, game_id, war_id, caller_nation_id, ally_nation_id, alliance_id, status,
				    created_round, created_at, updated_at, side)
				VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
				""", call.id(), gameId, call.warId(), call.caller(), call.ally(), call.allianceId(), call.createdRound(),
				Timestamp.from(call.createdAt()), Timestamp.from(call.createdAt()), call.side());
	}

	public Optional<CallRow> find(UUID gameId, UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.call_to_arms WHERE game_id = ? AND id = ?",
				CallToArmsRepository::row, gameId, id).stream().findFirst();
	}

	public List<CallRow> all(UUID gameId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.call_to_arms WHERE game_id = ? ORDER BY created_at, id",
				CallToArmsRepository::row, gameId);
	}

	/** PENDING → {@code status}; false if it was no longer pending. */
	public boolean close(UUID gameId, UUID id, String status, int round, Instant now) {
		return jdbc.update("""
				UPDATE game.call_to_arms SET status = ?, answered_round = ?, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = 'PENDING'
				""", status, round, Timestamp.from(now), gameId, id) == 1;
	}

	private static CallRow row(ResultSet rs, int row) throws SQLException {
		return new CallRow(rs.getObject("id", UUID.class), rs.getObject("war_id", UUID.class),
				rs.getObject("caller_nation_id", UUID.class), rs.getObject("ally_nation_id", UUID.class),
				rs.getObject("alliance_id", UUID.class), rs.getString("status"), rs.getInt("created_round"),
				(Integer) rs.getObject("answered_round"), rs.getTimestamp("created_at").toInstant(), rs.getString("side"));
	}

}
