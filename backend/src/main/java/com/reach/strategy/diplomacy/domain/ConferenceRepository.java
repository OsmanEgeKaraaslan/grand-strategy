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

	public Optional<ConferenceRow> find(UUID gameId, UUID id) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.peace_conference WHERE game_id = ? AND id = ?",
				ConferenceRepository::row, gameId, id).stream().findFirst();
	}

	/** Whether any conference of the game is still requested or running (one indexed query). */
	public boolean anyOpen(UUID gameId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM game.peace_conference WHERE game_id = ? AND status IN ('REQUESTED', 'ACTIVE'))",
				Boolean.class, gameId));
	}

	public List<ConferenceRow> all(UUID gameId) {
		return jdbc.query("SELECT " + COLUMNS + " FROM game.peace_conference WHERE game_id = ? ORDER BY created_at DESC, id",
				ConferenceRepository::row, gameId);
	}

	/** The record of a revision whose conference row was already advanced (see {@code Relations#revise}). */
	public void insertRevision(UUID gameId, UUID conferenceId, RevisionRow revision) {
		jdbc.update("""
				INSERT INTO game.conference_revision (game_id, conference_id, revision, author_nation_id, terms, required,
				    created_at)
				VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
				""", gameId, conferenceId, revision.revision(), revision.author(), revision.termsJson(), revision.requiredJson(),
				Timestamp.from(revision.createdAt()));
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
