package com.reach.strategy.ai.domain;

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
 * Progress of computer turns ({@code ai_turn}). {@link #recordAction} runs in the same transaction
 * as the command it counts and only succeeds from the expected count, so a committed command and its
 * progress are never separated and two runners can never both issue the n-th action of a turn.
 */
@Repository
public class AiTurnRepository {

	private static final String COLUMNS = """
			game_id, turn_number, round_number, game_player_id, nation_id, status, goal, actions, failures, observations,
			failed::text AS failed, last_error, duration_ms
			""";

	private final JdbcTemplate jdbc;

	AiTurnRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @param failed signatures of actions the server refused this turn (JSON array) */
	public record TurnRow(UUID gameId, int turnNumber, int round, UUID gamePlayerId, UUID nationId, String status,
			String goal, int actions, int failures, int observations, String failed, String lastError,
			Long durationMs) {

		public boolean done() {
			return "DONE".equals(status);
		}

	}

	/** Creates the row of a computer turn if it does not exist yet (restart-safe). */
	public TurnRow begin(UUID gameId, int turnNumber, int round, UUID gamePlayerId, UUID nationId, Instant now) {
		jdbc.update("""
				INSERT INTO game.ai_turn (game_id, turn_number, round_number, game_player_id, nation_id, status, started_at)
				VALUES (?, ?, ?, ?, ?, 'RUNNING', ?)
				ON CONFLICT (game_id, turn_number) DO NOTHING
				""", gameId, turnNumber, round, gamePlayerId, nationId, Timestamp.from(now));
		return find(gameId, turnNumber).orElseThrow();
	}

	public Optional<TurnRow> find(UUID gameId, int turnNumber) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.ai_turn WHERE game_id = ? AND turn_number = ?",
				AiTurnRepository::row, gameId, turnNumber).stream().findFirst();
	}

	public List<TurnRow> turns(UUID gameId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.ai_turn WHERE game_id = ? ORDER BY turn_number",
				AiTurnRepository::row, gameId);
	}

	/** Counts the n-th action of the turn; false if it was already counted (another runner). */
	public boolean recordAction(UUID gameId, int turnNumber, int expectedActions) {
		return jdbc.update("""
				UPDATE game.ai_turn SET actions = actions + 1
				WHERE game_id = ? AND turn_number = ? AND actions = ? AND status = 'RUNNING'
				""", gameId, turnNumber, expectedActions) == 1;
	}

	public void recordFailure(UUID gameId, int turnNumber, String signature, String error) {
		jdbc.update("""
				UPDATE game.ai_turn SET failures = failures + 1, failed = failed || jsonb_build_array(?::text),
				    last_error = left(?, 300)
				WHERE game_id = ? AND turn_number = ?
				""", signature, error, gameId, turnNumber);
	}

	public void recordObservation(UUID gameId, int turnNumber, String goal) {
		jdbc.update("UPDATE game.ai_turn SET observations = observations + 1, goal = ? WHERE game_id = ? AND turn_number = ?",
				goal, gameId, turnNumber);
	}

	/** RUNNING → DONE, in the transaction that ends the computer's turn. */
	public void finish(UUID gameId, int turnNumber, Instant now) {
		jdbc.update("""
				UPDATE game.ai_turn SET status = 'DONE', finished_at = ?,
				    duration_ms = (EXTRACT(EPOCH FROM (?::timestamptz - started_at)) * 1000)::bigint
				WHERE game_id = ? AND turn_number = ? AND status = 'RUNNING'
				""", Timestamp.from(now), Timestamp.from(now), gameId, turnNumber);
	}

	private static TurnRow row(ResultSet rs, int row) throws SQLException {
		return new TurnRow(rs.getObject("game_id", UUID.class), rs.getInt("turn_number"), rs.getInt("round_number"),
				rs.getObject("game_player_id", UUID.class), rs.getObject("nation_id", UUID.class), rs.getString("status"),
				rs.getString("goal"), rs.getInt("actions"), rs.getInt("failures"), rs.getInt("observations"),
				rs.getString("failed"), rs.getString("last_error"), (Long) rs.getObject("duration_ms"));
	}

}
