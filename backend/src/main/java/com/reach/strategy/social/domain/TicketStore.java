package com.reach.strategy.social.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Matchmaking tickets (migration V19). */
@Repository
public class TicketStore {

	private final JdbcTemplate jdbc;

	TicketStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Ticket(UUID id, UUID playerId, String queue, String status, Instant createdAt, Instant heartbeatAt,
			UUID matchedGameId, UUID opponentId, Instant matchedAt, Instant acknowledgedAt) {
	}

	private static final String COLUMNS = """
			id, player_id, queue, status, created_at, heartbeat_at, matched_game_id, opponent_player_id, matched_at,
			acknowledged_at""";

	private static Ticket row(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Ticket(rs.getObject("id", UUID.class), rs.getObject("player_id", UUID.class), rs.getString("queue"),
				rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("heartbeat_at").toInstant(),
				rs.getObject("matched_game_id", UUID.class), rs.getObject("opponent_player_id", UUID.class),
				instant(rs.getTimestamp("matched_at")), instant(rs.getTimestamp("acknowledged_at")));
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	/**
	 * Serializes every matchmaking change of one queue across transactions — and across backend
	 * instances, since the lock lives in PostgreSQL. Released at commit or rollback.
	 */
	public void lockQueue(String queue) {
		jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtext('matchmaking:' || ?))", Integer.class, queue);
	}

	public Optional<Ticket> waiting(UUID playerId, String queue) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.matchmaking_ticket WHERE player_id = ? AND queue = ? AND status = 'WAITING'",
				(rs, i) -> row(rs), playerId, queue).stream().findFirst();
	}

	/** The player's latest ticket of the queue in any state. */
	public Optional<Ticket> latest(UUID playerId, String queue) {
		return jdbc.query("SELECT " + COLUMNS + """
				 FROM game.matchmaking_ticket WHERE player_id = ? AND queue = ? ORDER BY created_at DESC, id LIMIT 1
				""", (rs, i) -> row(rs), playerId, queue).stream().findFirst();
	}

	public void insertWaiting(UUID id, UUID playerId, String queue, Instant now) {
		jdbc.update("""
				INSERT INTO game.matchmaking_ticket (id, player_id, queue, status, created_at, heartbeat_at)
				VALUES (?, ?, ?, 'WAITING', ?, ?)
				""", id, playerId, queue, Timestamp.from(now), Timestamp.from(now));
	}

	public void heartbeat(UUID ticketId, Instant now) {
		jdbc.update("UPDATE game.matchmaking_ticket SET heartbeat_at = ? WHERE id = ? AND status = 'WAITING'", Timestamp.from(now),
				ticketId);
	}

	/** WAITING tickets without a heartbeat since {@code staleBefore} become EXPIRED. */
	public void expireStale(String queue, Instant staleBefore, Instant now) {
		jdbc.update("""
				UPDATE game.matchmaking_ticket SET status = 'EXPIRED', ended_at = ?
				WHERE queue = ? AND status = 'WAITING' AND heartbeat_at < ?
				""", Timestamp.from(now), queue, Timestamp.from(staleBefore));
	}

	/**
	 * The longest-waiting other live ticket of the queue whose player has no block with this one.
	 * Only called under the queue lock, so no other transaction can take it meanwhile.
	 */
	public Optional<Ticket> oldestOpponent(UUID playerId, String queue, Instant liveSince) {
		return jdbc.query("SELECT " + COLUMNS + """
				 FROM game.matchmaking_ticket t
				WHERE t.queue = ? AND t.status = 'WAITING' AND t.player_id <> ? AND t.heartbeat_at >= ?
				  AND NOT EXISTS (SELECT 1 FROM game.player_block b
				                  WHERE (b.blocker_id = t.player_id AND b.blocked_id = ?) OR (b.blocker_id = ? AND b.blocked_id = t.player_id))
				ORDER BY t.created_at, t.id
				LIMIT 1
				""", (rs, i) -> row(rs), queue, playerId, Timestamp.from(liveSince), playerId, playerId).stream().findFirst();
	}

	public void markMatched(UUID ticketId, UUID gameId, UUID opponentId, Instant now) {
		int updated = jdbc.update("""
				UPDATE game.matchmaking_ticket SET status = 'MATCHED', matched_game_id = ?, opponent_player_id = ?, matched_at = ?,
				    ended_at = ?
				WHERE id = ? AND status = 'WAITING'
				""", gameId, opponentId, Timestamp.from(now), Timestamp.from(now), ticketId);
		if (updated != 1) {
			throw new IllegalStateException("Ticket " + ticketId + " was not waiting");
		}
	}

	public boolean cancel(UUID ticketId, Instant now) {
		return jdbc.update("UPDATE game.matchmaking_ticket SET status = 'CANCELLED', ended_at = ? WHERE id = ? AND status = 'WAITING'",
				Timestamp.from(now), ticketId) == 1;
	}

	public void acknowledge(UUID ticketId, Instant now) {
		jdbc.update("UPDATE game.matchmaking_ticket SET acknowledged_at = ? WHERE id = ? AND status = 'MATCHED' AND acknowledged_at IS NULL",
				Timestamp.from(now), ticketId);
	}

	/** Account deletion: a Quick Match search still waiting ends. */
	public void cancelWaitingOf(UUID playerId, Instant now) {
		jdbc.update("UPDATE game.matchmaking_ticket SET status = 'CANCELLED', ended_at = ? WHERE player_id = ? AND status = 'WAITING'",
				Timestamp.from(now), playerId);
	}

}
