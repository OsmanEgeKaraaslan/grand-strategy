package com.reach.strategy.social.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** In-app notifications (migration V17). Every query is scoped to the recipient. */
@Repository
public class NotificationStore {

	private final JdbcTemplate jdbc;

	NotificationStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Row(UUID id, String kind, UUID actorId, UUID gameId, UUID referenceId, Instant createdAt, boolean read) {
	}

	public void insert(UUID playerId, String kind, UUID actorId, UUID gameId, UUID referenceId, Instant now) {
		jdbc.update("""
				INSERT INTO game.notification (id, player_id, kind, actor_player_id, game_id, reference_id, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), playerId, kind, actorId, gameId, referenceId, Timestamp.from(now));
	}

	/** Keeps the newest {@code keep} notifications of a player; older ones are deleted. */
	public void prune(UUID playerId, int keep) {
		jdbc.update("""
				DELETE FROM game.notification WHERE player_id = ? AND id NOT IN (
				    SELECT id FROM game.notification WHERE player_id = ? ORDER BY created_at DESC, id LIMIT ?)
				""", playerId, playerId, keep);
	}

	public List<Row> latest(UUID playerId, int limit) {
		return jdbc.query("""
				SELECT id, kind, actor_player_id, game_id, reference_id, created_at, read_at IS NOT NULL AS read
				FROM game.notification WHERE player_id = ? ORDER BY created_at DESC, id LIMIT ?
				""", (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("kind"),
				rs.getObject("actor_player_id", UUID.class), rs.getObject("game_id", UUID.class),
				rs.getObject("reference_id", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("read")),
				playerId, limit);
	}

	public int unread(UUID playerId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM game.notification WHERE player_id = ? AND read_at IS NULL",
				Integer.class, playerId);
		return n == null ? 0 : n;
	}

	/** Marks the given notifications (only the player's own) or, with no ids, all of them read. */
	public void markRead(UUID playerId, Collection<UUID> ids, Instant now) {
		if (ids.isEmpty()) {
			jdbc.update("UPDATE game.notification SET read_at = ? WHERE player_id = ? AND read_at IS NULL", Timestamp.from(now),
					playerId);
			return;
		}
		for (UUID id : ids) {
			jdbc.update("UPDATE game.notification SET read_at = ? WHERE player_id = ? AND id = ? AND read_at IS NULL",
					Timestamp.from(now), playerId, id);
		}
	}

	/** Removes what one player caused for another (after a block), so nothing from them stays visible. */
	public void deleteFrom(UUID actorId, UUID recipientId) {
		jdbc.update("DELETE FROM game.notification WHERE actor_player_id = ? AND player_id = ?", actorId, recipientId);
	}

	/** Account deletion: the player's notifications and every notification naming them as the actor. */
	public void purge(UUID playerId) {
		jdbc.update("DELETE FROM game.notification WHERE player_id = ? OR actor_player_id = ?", playerId, playerId);
	}

}
