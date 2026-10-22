package com.reach.strategy.social.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lobby invitations (migration V18). */
@Repository
public class InvitationStore {

	private final JdbcTemplate jdbc;

	InvitationStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Invitation(UUID id, UUID gameId, UUID inviterId, UUID inviteeId, String status, String reason,
			Instant createdAt) {
	}

	private static final String COLUMNS = "id, game_id, inviter_id, invitee_id, status, reason, created_at";

	private static Invitation row(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Invitation(rs.getObject("id", UUID.class), rs.getObject("game_id", UUID.class),
				rs.getObject("inviter_id", UUID.class), rs.getObject("invitee_id", UUID.class), rs.getString("status"),
				rs.getString("reason"), rs.getTimestamp("created_at").toInstant());
	}

	/** True if inserted; false if the invitee already has a pending invitation to this game. */
	public boolean insertPending(UUID id, UUID gameId, UUID inviter, UUID invitee, Instant now) {
		return jdbc.update("""
				INSERT INTO game.game_invitation (id, game_id, inviter_id, invitee_id, status, created_at)
				VALUES (?, ?, ?, ?, 'PENDING', ?) ON CONFLICT DO NOTHING
				""", id, gameId, inviter, invitee, Timestamp.from(now)) == 1;
	}

	public Optional<Invitation> pendingFor(UUID gameId, UUID invitee) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.game_invitation WHERE game_id = ? AND invitee_id = ? AND status = 'PENDING'",
				(rs, i) -> row(rs), gameId, invitee).stream().findFirst();
	}

	public Optional<Invitation> findForUpdate(UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.game_invitation WHERE id = ? FOR UPDATE", (rs, i) -> row(rs), id)
			.stream()
			.findFirst();
	}

	public void resolve(UUID id, String status, String reason, Instant now) {
		jdbc.update("UPDATE game.game_invitation SET status = ?, reason = ?, responded_at = ? WHERE id = ?", status, reason,
				Timestamp.from(now), id);
	}

	public int pendingForGame(UUID gameId) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM game.game_invitation WHERE game_id = ? AND status = 'PENDING'",
				Integer.class, gameId);
		return n == null ? 0 : n;
	}

	public List<Invitation> pendingTo(UUID invitee, int limit) {
		return jdbc.query("SELECT " + COLUMNS + """
				 FROM game.game_invitation WHERE invitee_id = ? AND status = 'PENDING'
				ORDER BY created_at DESC, id LIMIT ?
				""", (rs, i) -> row(rs), invitee, limit);
	}

	public List<Invitation> sentFor(UUID gameId, UUID inviter) {
		return jdbc.query("SELECT " + COLUMNS + """
				 FROM game.game_invitation WHERE game_id = ? AND inviter_id = ?
				ORDER BY created_at DESC, id
				""", (rs, i) -> row(rs), gameId, inviter);
	}

	/** Games among {@code gameIds} to which the player has ever been invited. */
	public List<UUID> gamesInvitedTo(UUID invitee, Collection<UUID> gameIds) {
		if (gameIds.isEmpty()) {
			return List.of();
		}
		return jdbc.queryForList("SELECT DISTINCT game_id FROM game.game_invitation WHERE invitee_id = ? AND game_id = ANY(?)",
				UUID.class, invitee, gameIds.toArray(UUID[]::new));
	}

	/** Withdraws pending invitations between two players in either direction (after a block). */
	public void cancelBetween(UUID a, UUID b, Instant now) {
		jdbc.update("""
				UPDATE game.game_invitation SET status = 'CANCELLED', reason = 'BLOCKED', responded_at = ?
				WHERE status = 'PENDING' AND ((inviter_id = ? AND invitee_id = ?) OR (inviter_id = ? AND invitee_id = ?))
				""", Timestamp.from(now), a, b, b, a);
	}

}
