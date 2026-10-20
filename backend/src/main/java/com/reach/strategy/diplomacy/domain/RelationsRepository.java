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
 * Diplomatic relations of a game: whether it has diplomacy at all, alliances, wars with their
 * participants and fronts, truces and war-score events. Every mutation runs inside a game command
 * or round transaction, under the game row lock; partial unique indexes back the rules up.
 */
@Repository
public class RelationsRepository {

	private final JdbcTemplate jdbc;

	RelationsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// --- game -----------------------------------------------------------------------------------

	/** The ruleset version of a game with diplomacy; empty for games of rulesets before v5. */
	public Optional<String> rulesetVersion(UUID gameId) {
		return jdbc.queryForList("SELECT ruleset_version FROM game.diplomacy_game WHERE game_id = ?", String.class, gameId)
			.stream()
			.findFirst();
	}

	public void enable(UUID gameId, String rulesetVersion, Instant now) {
		jdbc.update("INSERT INTO game.diplomacy_game (game_id, ruleset_version, created_at) VALUES (?, ?, ?)", gameId,
				rulesetVersion, Timestamp.from(now));
	}

	// --- alliances ------------------------------------------------------------------------------

	public record AllianceRow(UUID id, String kind, UUID nationA, UUID nationB, UUID proposer, String status,
			int proposedRound, Integer acceptedRound, Integer endedRound, String endReason) {

		public boolean involves(UUID nation) {
			return nationA.equals(nation) || nationB.equals(nation);
		}

		public UUID other(UUID nation) {
			return nationA.equals(nation) ? nationB : nationA;
		}

	}

	public List<AllianceRow> alliances(UUID gameId) {
		return jdbc.query("""
				SELECT id, kind, nation_a_id, nation_b_id, proposer_nation_id, status, proposed_round, accepted_round,
				       ended_round, end_reason
				FROM game.alliance WHERE game_id = ? ORDER BY proposed_round, id
				""", RelationsRepository::alliance, gameId);
	}

	public Optional<AllianceRow> alliance(UUID gameId, UUID allianceId) {
		return jdbc.query("""
				SELECT id, kind, nation_a_id, nation_b_id, proposer_nation_id, status, proposed_round, accepted_round,
				       ended_round, end_reason
				FROM game.alliance WHERE game_id = ? AND id = ?
				""", RelationsRepository::alliance, gameId, allianceId).stream().findFirst();
	}

	public void insertAlliance(UUID gameId, AllianceRow row, Instant now) {
		jdbc.update("""
				INSERT INTO game.alliance (id, game_id, kind, nation_a_id, nation_b_id, proposer_nation_id, status,
				    proposed_round, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", row.id(), gameId, row.kind(), row.nationA(), row.nationB(), row.proposer(), row.status(),
				row.proposedRound(), Timestamp.from(now), Timestamp.from(now));
	}

	/** PROPOSED → ACTIVE; false if it was no longer pending. */
	public boolean activateAlliance(UUID gameId, UUID allianceId, int round, Instant now) {
		return jdbc.update("""
				UPDATE game.alliance SET status = 'ACTIVE', accepted_round = ?, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = 'PROPOSED'
				""", round, Timestamp.from(now), gameId, allianceId) == 1;
	}

	/** Closes an open alliance (REJECTED, CANCELLED, EXPIRED, ENDED); false if it was not in {@code fromStatus}. */
	public boolean closeAlliance(UUID gameId, UUID allianceId, String fromStatus, String toStatus, String reason,
			int round, Instant now) {
		return jdbc.update("""
				UPDATE game.alliance SET status = ?, ended_round = ?, end_reason = ?, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = ?
				""", toStatus, round, reason, Timestamp.from(now), gameId, allianceId, fromStatus) == 1;
	}

	private static AllianceRow alliance(ResultSet rs, int row) throws SQLException {
		return new AllianceRow(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("nation_a_id", UUID.class),
				rs.getObject("nation_b_id", UUID.class), rs.getObject("proposer_nation_id", UUID.class),
				rs.getString("status"), rs.getInt("proposed_round"), (Integer) rs.getObject("accepted_round"),
				(Integer) rs.getObject("ended_round"), rs.getString("end_reason"));
	}

	// --- wars -----------------------------------------------------------------------------------

	public record WarRow(UUID id, UUID attackerLeader, UUID defenderLeader, String status, int declaredRound,
			int declaredTurn, Integer endedRound, Integer endedTurn, String endReason) {

		public boolean active() {
			return "ACTIVE".equals(status);
		}

	}

	/** @param leftRound round it left the war (separate peace, elimination), or null while it fights */
	public record ParticipantRow(UUID warId, UUID nationId, String side, int joinedRound, String joinReason,
			Integer leftRound, String leftReason) {

		public ParticipantRow(UUID warId, UUID nationId, String side, int joinedRound, String joinReason) {
			this(warId, nationId, side, joinedRound, joinReason, null, null);
		}

		public boolean active() {
			return leftRound == null;
		}

	}

	public List<WarRow> wars(UUID gameId) {
		return jdbc.query("""
				SELECT id, attacker_leader_id, defender_leader_id, status, declared_round, declared_turn, ended_round,
				       ended_turn, end_reason
				FROM game.war WHERE game_id = ? ORDER BY declared_turn, id
				""", RelationsRepository::war, gameId);
	}

	public Optional<WarRow> war(UUID gameId, UUID warId) {
		return jdbc.query("""
				SELECT id, attacker_leader_id, defender_leader_id, status, declared_round, declared_turn, ended_round,
				       ended_turn, end_reason
				FROM game.war WHERE game_id = ? AND id = ?
				""", RelationsRepository::war, gameId, warId).stream().findFirst();
	}

	public void insertWar(UUID gameId, WarRow war, Instant now) {
		jdbc.update("""
				INSERT INTO game.war (id, game_id, attacker_leader_id, defender_leader_id, status, declared_round,
				    declared_turn, created_at)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
				""", war.id(), gameId, war.attackerLeader(), war.defenderLeader(), war.declaredRound(), war.declaredTurn(),
				Timestamp.from(now));
	}

	/** ACTIVE → ENDED with every front of the war; false if it had already ended. */
	public boolean endWar(UUID gameId, UUID warId, int round, int turn, String reason) {
		int updated = jdbc.update("""
				UPDATE game.war SET status = 'ENDED', ended_round = ?, ended_turn = ?, end_reason = ?
				WHERE game_id = ? AND id = ? AND status = 'ACTIVE'
				""", round, turn, reason, gameId, warId);
		jdbc.update("UPDATE game.war_front SET active = false WHERE game_id = ? AND war_id = ?", gameId, warId);
		return updated == 1;
	}

	public List<ParticipantRow> participants(UUID gameId) {
		return jdbc.query("""
				SELECT p.war_id, p.nation_id, p.side, p.joined_round, p.join_reason, p.left_round, p.left_reason
				FROM game.war_participant p WHERE p.game_id = ? ORDER BY p.war_id, p.side, p.joined_round, p.nation_id
				""", (rs, row) -> new ParticipantRow(rs.getObject("war_id", UUID.class), rs.getObject("nation_id", UUID.class),
				rs.getString("side"), rs.getInt("joined_round"), rs.getString("join_reason"),
				(Integer) rs.getObject("left_round"), rs.getString("left_reason")), gameId);
	}

	public void insertParticipant(UUID gameId, ParticipantRow p) {
		jdbc.update("""
				INSERT INTO game.war_participant (game_id, war_id, nation_id, side, joined_round, join_reason)
				VALUES (?, ?, ?, ?, ?, ?)
				""", gameId, p.warId(), p.nationId(), p.side(), p.joinedRound(), p.joinReason());
	}

	/** A nation leaves a war (separate peace, elimination): its fronts in that war end. */
	public void leaveWar(UUID gameId, UUID warId, UUID nationId, int round, String reason) {
		int updated = jdbc.update("""
				UPDATE game.war_participant SET left_round = ?, left_reason = ?
				WHERE game_id = ? AND war_id = ? AND nation_id = ? AND left_round IS NULL
				""", round, reason, gameId, warId, nationId);
		if (updated != 1) {
			throw new IllegalStateException("Nation " + nationId + " is not fighting in war " + warId);
		}
		jdbc.update("""
				UPDATE game.war_front SET active = false
				WHERE game_id = ? AND war_id = ? AND active AND (nation_a_id = ? OR nation_b_id = ?)
				""", gameId, warId, nationId, nationId);
	}

	/** A new leader of one side (the previous one left the war). */
	public void setLeader(UUID gameId, UUID warId, String side, UUID nationId) {
		String column = "ATTACKER".equals(side) ? "attacker_leader_id" : "defender_leader_id";
		jdbc.update("UPDATE game.war SET " + column + " = ? WHERE game_id = ? AND id = ? AND status = 'ACTIVE'", nationId,
				gameId, warId);
	}

	/** A pair of nations now fighting each other in this war (unique among active fronts). */
	public void insertFront(UUID gameId, UUID warId, UUID a, UUID b) {
		jdbc.update("""
				INSERT INTO game.war_front (game_id, war_id, nation_a_id, nation_b_id, active) VALUES (?, ?, ?, ?, true)
				""", gameId, warId, a, b);
	}

	public record FrontRow(UUID warId, UUID nationA, UUID nationB) {
	}

	public List<FrontRow> activeFronts(UUID gameId) {
		return jdbc.query("""
				SELECT war_id, nation_a_id, nation_b_id FROM game.war_front WHERE game_id = ? AND active
				ORDER BY war_id, nation_a_id, nation_b_id
				""", (rs, row) -> new FrontRow(rs.getObject("war_id", UUID.class), rs.getObject("nation_a_id", UUID.class),
				rs.getObject("nation_b_id", UUID.class)), gameId);
	}

	private static WarRow war(ResultSet rs, int row) throws SQLException {
		return new WarRow(rs.getObject("id", UUID.class), rs.getObject("attacker_leader_id", UUID.class),
				rs.getObject("defender_leader_id", UUID.class), rs.getString("status"), rs.getInt("declared_round"),
				rs.getInt("declared_turn"), (Integer) rs.getObject("ended_round"), (Integer) rs.getObject("ended_turn"),
				rs.getString("end_reason"));
	}

	// --- truces ---------------------------------------------------------------------------------

	public record TruceRow(UUID treatyId, UUID nationA, UUID nationB, int untilRound) {
	}

	public List<TruceRow> truces(UUID gameId) {
		return jdbc.query("""
				SELECT treaty_id, nation_a_id, nation_b_id, until_round FROM game.truce WHERE game_id = ?
				ORDER BY until_round, treaty_id
				""", (rs, row) -> new TruceRow(rs.getObject("treaty_id", UUID.class), rs.getObject("nation_a_id", UUID.class),
				rs.getObject("nation_b_id", UUID.class), rs.getInt("until_round")), gameId);
	}

	public void insertTruce(UUID gameId, TruceRow truce) {
		jdbc.update("""
				INSERT INTO game.truce (game_id, treaty_id, nation_a_id, nation_b_id, until_round) VALUES (?, ?, ?, ?, ?)
				""", gameId, truce.treatyId(), truce.nationA(), truce.nationB(), truce.untilRound());
	}

	// --- war score ------------------------------------------------------------------------------

	public record ScoreEventRow(UUID warId, String side, String kind, UUID sourceId, int round, int enemyLosses,
			int points) {
	}

	/** Records a battle or siege for a war at most once; false if that source was already recorded. */
	public boolean insertScoreEvent(UUID gameId, ScoreEventRow e, Instant now) {
		return jdbc.update("""
				INSERT INTO game.war_score_event (id, game_id, war_id, side, kind, source_id, round_number, enemy_losses,
				    points, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (war_id, kind, source_id) DO NOTHING
				""", UUID.randomUUID(), gameId, e.warId(), e.side(), e.kind(), e.sourceId(), e.round(), e.enemyLosses(),
				e.points(), Timestamp.from(now)) == 1;
	}

	public List<ScoreEventRow> scoreEvents(UUID gameId) {
		return jdbc.query("""
				SELECT war_id, side, kind, source_id, round_number, enemy_losses, points
				FROM game.war_score_event WHERE game_id = ? ORDER BY round_number, created_at, id
				""", (rs, row) -> new ScoreEventRow(rs.getObject("war_id", UUID.class), rs.getString("side"),
				rs.getString("kind"), rs.getObject("source_id", UUID.class), rs.getInt("round_number"),
				rs.getInt("enemy_losses"), rs.getInt("points")), gameId);
	}

}
