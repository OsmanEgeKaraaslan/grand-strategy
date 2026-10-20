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
 * Peace conferences (Phase 10): the conference, its immutable draft revisions and the approval
 * decisions, each tied to one revision. Status changes are conditional updates, and the primary key
 * (conference, revision, nation) makes approving twice impossible.
 */
@Repository
public class ConferenceRepository {

	private final JdbcTemplate jdbc;

	ConferenceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record ConferenceRow(UUID id, UUID warId, String kind, UUID requester, UUID counterpart, String status,
			int currentRevision, int requestedRound, Instant openedAt, Instant closedAt, Instant createdAt) {

		public boolean open() {
			return "REQUESTED".equals(status) || "ACTIVE".equals(status);
		}

		public boolean active() {
			return "ACTIVE".equals(status);
		}

		public boolean separate() {
			return "SEPARATE".equals(kind);
		}

		public boolean principal(UUID nation) {
			return requester.equals(nation) || counterpart.equals(nation);
		}

	}

	/** @param requiredJson the nations whose approval this revision needs (JSON array of ids) */
	public record RevisionRow(int revision, UUID author, String termsJson, String requiredJson, Instant createdAt) {
	}

	public record DecisionRow(int revision, UUID nationId, String decision, Instant decidedAt) {
	}

	private static final String COLUMNS = """
			id, war_id, kind, requester_nation_id, counterpart_nation_id, status, current_revision, requested_round,
			opened_at, closed_at, created_at
			""";

	public void insert(UUID gameId, ConferenceRow c) {
		jdbc.update("""
				INSERT INTO game.peace_conference (id, game_id, war_id, kind, requester_nation_id, counterpart_nation_id,
				    status, requested_round, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?)
				""", c.id(), gameId, c.warId(), c.kind(), c.requester(), c.counterpart(), c.requestedRound(),
				Timestamp.from(c.createdAt()), Timestamp.from(c.createdAt()));
	}

	public Optional<ConferenceRow> find(UUID gameId, UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.peace_conference WHERE game_id = ? AND id = ?",
				ConferenceRepository::row, gameId, id).stream().findFirst();
	}

	public List<ConferenceRow> all(UUID gameId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.peace_conference WHERE game_id = ? ORDER BY created_at DESC, id",
				ConferenceRepository::row, gameId);
	}

	/** REQUESTED → ACTIVE; false if it was not requested any more. */
	public boolean open(UUID gameId, UUID id, Instant now) {
		return jdbc.update("""
				UPDATE game.peace_conference SET status = 'ACTIVE', opened_at = ?, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = 'REQUESTED'
				""", Timestamp.from(now), Timestamp.from(now), gameId, id) == 1;
	}

	/** An open conference ends (REJECTED, CANCELLED, CONCLUDED); false if it was not in {@code fromStatus}. */
	public boolean close(UUID gameId, UUID id, String fromStatus, String toStatus, Instant now) {
		return jdbc.update("""
				UPDATE game.peace_conference SET status = ?, closed_at = ?, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = ?
				""", toStatus, Timestamp.from(now), Timestamp.from(now), gameId, id, fromStatus) == 1;
	}

	/**
	 * Stores the next revision; only succeeds while the conference is active and still at
	 * {@code expectedRevision}, so two simultaneous edits cannot both become "the next" revision.
	 */
	public boolean addRevision(UUID gameId, UUID conferenceId, int expectedRevision, RevisionRow revision) {
		int updated = jdbc.update("""
				UPDATE game.peace_conference SET current_revision = current_revision + 1, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = 'ACTIVE' AND current_revision = ?
				""", Timestamp.from(revision.createdAt()), gameId, conferenceId, expectedRevision);
		if (updated != 1) {
			return false;
		}
		jdbc.update("""
				INSERT INTO game.conference_revision (game_id, conference_id, revision, author_nation_id, terms, required,
				    created_at)
				VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
				""", gameId, conferenceId, revision.revision(), revision.author(), revision.termsJson(), revision.requiredJson(),
				Timestamp.from(revision.createdAt()));
		return true;
	}

	public List<RevisionRow> revisions(UUID conferenceId) {
		return jdbc.query("""
				SELECT revision, author_nation_id, terms::text AS terms, required::text AS required, created_at
				FROM game.conference_revision WHERE conference_id = ? ORDER BY revision
				""", (rs, row) -> new RevisionRow(rs.getInt("revision"), rs.getObject("author_nation_id", UUID.class),
				rs.getString("terms"), rs.getString("required"), rs.getTimestamp("created_at").toInstant()), conferenceId);
	}

	/** Records one nation's decision on one revision; false if it had already decided on it. */
	public boolean decide(UUID gameId, UUID conferenceId, DecisionRow decision) {
		return jdbc.update("""
				INSERT INTO game.conference_decision (game_id, conference_id, revision, nation_id, decision, decided_at)
				VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (conference_id, revision, nation_id) DO NOTHING
				""", gameId, conferenceId, decision.revision(), decision.nationId(), decision.decision(),
				Timestamp.from(decision.decidedAt())) == 1;
	}

	public List<DecisionRow> decisions(UUID conferenceId, int revision) {
		return jdbc.query("""
				SELECT revision, nation_id, decision, decided_at FROM game.conference_decision
				WHERE conference_id = ? AND revision = ? ORDER BY decided_at, nation_id
				""", (rs, row) -> new DecisionRow(rs.getInt("revision"), rs.getObject("nation_id", UUID.class),
				rs.getString("decision"), rs.getTimestamp("decided_at").toInstant()), conferenceId, revision);
	}

	private static ConferenceRow row(ResultSet rs, int row) throws SQLException {
		Timestamp opened = rs.getTimestamp("opened_at");
		Timestamp closed = rs.getTimestamp("closed_at");
		return new ConferenceRow(rs.getObject("id", UUID.class), rs.getObject("war_id", UUID.class), rs.getString("kind"),
				rs.getObject("requester_nation_id", UUID.class), rs.getObject("counterpart_nation_id", UUID.class),
				rs.getString("status"), rs.getInt("current_revision"), rs.getInt("requested_round"),
				opened == null ? null : opened.toInstant(), closed == null ? null : closed.toInstant(),
				rs.getTimestamp("created_at").toInstant());
	}

}
