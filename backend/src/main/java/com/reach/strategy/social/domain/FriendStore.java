package com.reach.strategy.social.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.social.rules.FriendRequestRules.Status;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Friend requests, friendships and blocks (tables of migration V17). Friendship rows store the
 * smaller player id first; callers pass the two players in any order.
 */
@Repository
public class FriendStore {

	private final JdbcTemplate jdbc;

	FriendStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Request(UUID id, UUID senderId, UUID recipientId, Status status, Instant createdAt) {
	}

	public record Friend(UUID playerId, Instant since) {
	}

	private static Request request(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Request(rs.getObject("id", UUID.class), rs.getObject("sender_id", UUID.class),
				rs.getObject("recipient_id", UUID.class), Status.valueOf(rs.getString("status")),
				rs.getTimestamp("created_at").toInstant());
	}

	/** Inserts a PENDING request unless one is already pending for the pair; true if inserted. */
	public boolean insertPending(UUID id, UUID sender, UUID recipient, Instant now) {
		return jdbc.update("""
				INSERT INTO game.friend_request (id, sender_id, recipient_id, status, created_at)
				VALUES (?, ?, ?, 'PENDING', ?)
				ON CONFLICT DO NOTHING
				""", id, sender, recipient, Timestamp.from(now)) == 1;
	}

	/** The pending request between two players, either direction, locked for the caller's transaction. */
	public Optional<Request> pendingBetweenForUpdate(UUID a, UUID b) {
		return jdbc.query("""
				SELECT id, sender_id, recipient_id, status, created_at FROM game.friend_request
				WHERE status = 'PENDING' AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
				FOR UPDATE
				""", (rs, i) -> request(rs), a, b, b, a).stream().findFirst();
	}

	public Optional<Request> findForUpdate(UUID requestId) {
		return jdbc.query("""
				SELECT id, sender_id, recipient_id, status, created_at FROM game.friend_request WHERE id = ? FOR UPDATE
				""", (rs, i) -> request(rs), requestId).stream().findFirst();
	}

	public void setStatus(UUID requestId, Status status, Instant now) {
		jdbc.update("UPDATE game.friend_request SET status = ?, responded_at = ? WHERE id = ?", status.name(),
				Timestamp.from(now), requestId);
	}

	/** Cancels whatever is pending between two players (used when one blocks the other). */
	public void cancelPendingBetween(UUID a, UUID b, Instant now) {
		jdbc.update("""
				UPDATE game.friend_request SET status = 'CANCELLED', responded_at = ?
				WHERE status = 'PENDING' AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
				""", Timestamp.from(now), a, b, b, a);
	}

	public List<Request> pendingFor(UUID playerId) {
		return jdbc.query("""
				SELECT id, sender_id, recipient_id, status, created_at FROM game.friend_request
				WHERE status = 'PENDING' AND (sender_id = ? OR recipient_id = ?)
				ORDER BY created_at DESC, id
				""", (rs, i) -> request(rs), playerId, playerId);
	}

	public int pendingSentBy(UUID playerId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM game.friend_request WHERE status = 'PENDING' AND sender_id = ?",
				Integer.class, playerId);
		return n == null ? 0 : n;
	}

	/** Creates the friendship once; a second attempt for the same pair is a no-op. */
	public void befriend(UUID a, UUID b, Instant now) {
		// PostgreSQL orders the pair: Java's UUID.compareTo (signed halves) disagrees with uuid ordering.
		jdbc.update("""
				INSERT INTO game.friendship (player_low_id, player_high_id, created_at)
				VALUES (least(?::uuid, ?::uuid), greatest(?::uuid, ?::uuid), ?)
				ON CONFLICT DO NOTHING
				""", a, b, a, b, Timestamp.from(now));
	}

	public boolean unfriend(UUID a, UUID b) {
		return jdbc.update("""
				DELETE FROM game.friendship WHERE (player_low_id = ? AND player_high_id = ?) OR (player_low_id = ? AND player_high_id = ?)
				""", a, b, b, a) > 0;
	}

	public boolean areFriends(UUID a, UUID b) {
		Integer n = jdbc.queryForObject("""
				SELECT count(*) FROM game.friendship
				WHERE (player_low_id = ? AND player_high_id = ?) OR (player_low_id = ? AND player_high_id = ?)
				""", Integer.class, a, b, b, a);
		return n != null && n > 0;
	}

	public List<Friend> friendsOf(UUID playerId) {
		return jdbc.query("""
				SELECT CASE WHEN player_low_id = ? THEN player_high_id ELSE player_low_id END AS friend_id, created_at
				FROM game.friendship WHERE player_low_id = ? OR player_high_id = ?
				ORDER BY created_at DESC
				""", (rs, i) -> new Friend(rs.getObject("friend_id", UUID.class), rs.getTimestamp("created_at").toInstant()),
				playerId, playerId, playerId);
	}

	public int friendCount(UUID playerId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM game.friendship WHERE player_low_id = ? OR player_high_id = ?",
				Integer.class, playerId, playerId);
		return n == null ? 0 : n;
	}

	public void block(UUID blocker, UUID blocked, Instant now) {
		jdbc.update("INSERT INTO game.player_block (blocker_id, blocked_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
				blocker, blocked, Timestamp.from(now));
	}

	public boolean unblock(UUID blocker, UUID blocked) {
		return jdbc.update("DELETE FROM game.player_block WHERE blocker_id = ? AND blocked_id = ?", blocker, blocked) > 0;
	}

	/** Either player blocked the other. */
	public boolean blockedEitherWay(UUID a, UUID b) {
		Integer n = jdbc.queryForObject("""
				SELECT count(*) FROM game.player_block
				WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)
				""", Integer.class, a, b, b, a);
		return n != null && n > 0;
	}

	public List<UUID> blockedBy(UUID blocker) {
		return jdbc.queryForList("SELECT blocked_id FROM game.player_block WHERE blocker_id = ? ORDER BY created_at DESC",
				UUID.class, blocker);
	}

	/** Account deletion: the player's requests, friendships and blocks, in both directions. */
	public void purge(UUID playerId) {
		jdbc.update("DELETE FROM game.friend_request WHERE sender_id = ? OR recipient_id = ?", playerId, playerId);
		jdbc.update("DELETE FROM game.friendship WHERE player_low_id = ? OR player_high_id = ?", playerId, playerId);
		jdbc.update("DELETE FROM game.player_block WHERE blocker_id = ? OR blocked_id = ?", playerId, playerId);
	}

}
