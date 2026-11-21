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
 * Peace proposals, signed treaties, tributes and their payments. Status changes are conditional
 * updates ({@code WHERE status = 'PENDING'}), so a proposal is settled at most once even if two
 * requests raced past the game lock; the unique war and proposal keys of a treaty back that up.
 */
@Repository
public class TreatyRepository {

	private final JdbcTemplate jdbc;

	TreatyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// --- proposals ------------------------------------------------------------------------------

	public record ProposalRow(UUID id, UUID warId, UUID proposer, UUID recipient, String status, String termsJson,
			int createdRound, int createdTurn, long createdStateVersion, Integer respondedRound, UUID counterOfId,
			Instant createdAt) {

		public boolean pending() {
			return "PENDING".equals(status);
		}

		public boolean involves(UUID nation) {
			return proposer.equals(nation) || recipient.equals(nation);
		}

	}

	static final String PROPOSAL_COLUMNS = """
			id, war_id, proposer_nation_id, recipient_nation_id, status, terms::text AS terms, created_round, created_turn,
			created_state_version, responded_round, counter_of_id, created_at
			""";

	public Optional<ProposalRow> proposal(UUID gameId, UUID proposalId) {
		return jdbc.query("SELECT " + PROPOSAL_COLUMNS + " FROM game.peace_proposal WHERE game_id = ? AND id = ?",
				TreatyRepository::proposal, gameId, proposalId).stream().findFirst();
	}

	/** Proposals the nation sent or received, newest first. */
	public List<ProposalRow> proposalsOf(UUID gameId, UUID nationId) {
		return jdbc.query("SELECT " + PROPOSAL_COLUMNS + """
				 FROM game.peace_proposal
				WHERE game_id = ? AND (proposer_nation_id = ? OR recipient_nation_id = ?)
				ORDER BY created_turn DESC, created_at DESC, id
				""", TreatyRepository::proposal, gameId, nationId, nationId);
	}

	public List<ProposalRow> pendingProposals(UUID gameId) {
		return jdbc.query("SELECT " + PROPOSAL_COLUMNS
				+ " FROM game.peace_proposal WHERE game_id = ? AND status = 'PENDING' ORDER BY created_turn, id",
				TreatyRepository::proposal, gameId);
	}

	static ProposalRow proposal(ResultSet rs, int row) throws SQLException {
		return new ProposalRow(rs.getObject("id", UUID.class), rs.getObject("war_id", UUID.class),
				rs.getObject("proposer_nation_id", UUID.class), rs.getObject("recipient_nation_id", UUID.class),
				rs.getString("status"), rs.getString("terms"), rs.getInt("created_round"), rs.getInt("created_turn"),
				rs.getLong("created_state_version"), (Integer) rs.getObject("responded_round"),
				rs.getObject("counter_of_id", UUID.class), rs.getTimestamp("created_at").toInstant());
	}

	// --- treaties -------------------------------------------------------------------------------

	public record TreatyRow(UUID id, UUID warId, UUID proposalId, int signedRound, int signedTurn, String termsJson,
			int truceUntilRound, Instant createdAt) {
	}

	public void insertTreaty(UUID gameId, TreatyRow t) {
		jdbc.update("""
				INSERT INTO game.peace_treaty (id, game_id, war_id, proposal_id, signed_round, signed_turn, terms,
				    truce_until_round, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
				""", t.id(), gameId, t.warId(), t.proposalId(), t.signedRound(), t.signedTurn(), t.termsJson(),
				t.truceUntilRound(), Timestamp.from(t.createdAt()));
	}

	/** A treaty settled by a peace conference (Phase 10): general, or the separate peace of one nation. */
	public void insertConferenceTreaty(UUID gameId, TreatyRow t, UUID conferenceId, int revision, UUID exitingNationId) {
		jdbc.update("""
				INSERT INTO game.peace_treaty (id, game_id, war_id, conference_id, revision, kind, exiting_nation_id,
				    signed_round, signed_turn, terms, truce_until_round, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
				""", t.id(), gameId, t.warId(), conferenceId, revision, exitingNationId == null ? "GENERAL" : "SEPARATE",
				exitingNationId, t.signedRound(), t.signedTurn(), t.termsJson(), t.truceUntilRound(),
				Timestamp.from(t.createdAt()));
	}

	public List<TreatyRow> treaties(UUID gameId) {
		return jdbc.query("""
				SELECT id, war_id, proposal_id, signed_round, signed_turn, terms::text AS terms, truce_until_round, created_at
				FROM game.peace_treaty WHERE game_id = ? ORDER BY signed_turn DESC, id
				""", (rs, row) -> new TreatyRow(rs.getObject("id", UUID.class), rs.getObject("war_id", UUID.class),
				rs.getObject("proposal_id", UUID.class), rs.getInt("signed_round"), rs.getInt("signed_turn"),
				rs.getString("terms"), rs.getInt("truce_until_round"), rs.getTimestamp("created_at").toInstant()), gameId);
	}

	// --- tribute --------------------------------------------------------------------------------

	public record TributeRow(UUID id, UUID treatyId, UUID payer, UUID receiver, int rateBps, int roundsTotal,
			int roundsCharged, int firstRound, long totalPaidMg, String status, String endReason, Integer endedRound) {

		public boolean active() {
			return "ACTIVE".equals(status);
		}

	}

	public record PaymentRow(UUID tributeId, int round, long grossIncomeMg, long dueMg, long paidMg) {
	}

	public List<TributeRow> tributes(UUID gameId) {
		return jdbc.query("""
				SELECT id, treaty_id, payer_nation_id, receiver_nation_id, rate_bps, rounds_total, rounds_charged,
				       first_round, total_paid_mg, status, end_reason, ended_round
				FROM game.tribute WHERE game_id = ? ORDER BY first_round, id
				""", (rs, row) -> new TributeRow(rs.getObject("id", UUID.class), rs.getObject("treaty_id", UUID.class),
				rs.getObject("payer_nation_id", UUID.class), rs.getObject("receiver_nation_id", UUID.class),
				rs.getInt("rate_bps"), rs.getInt("rounds_total"), rs.getInt("rounds_charged"), rs.getInt("first_round"),
				rs.getLong("total_paid_mg"), rs.getString("status"), rs.getString("end_reason"),
				(Integer) rs.getObject("ended_round")), gameId);
	}

	/** The payment record of one charged round; its primary key (tribute, round) forbids a second charge. */
	public void insertPayment(UUID gameId, PaymentRow p, Instant now) {
		jdbc.update("""
				INSERT INTO game.tribute_payment (game_id, tribute_id, round_number, gross_income_mg, due_mg, paid_mg,
				    created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", gameId, p.tributeId(), p.round(), p.grossIncomeMg(), p.dueMg(), p.paidMg(), Timestamp.from(now));
	}

	public List<PaymentRow> payments(UUID gameId) {
		return jdbc.query("""
				SELECT tribute_id, round_number, gross_income_mg, due_mg, paid_mg FROM game.tribute_payment
				WHERE game_id = ? ORDER BY round_number, tribute_id
				""", (rs, row) -> new PaymentRow(rs.getObject("tribute_id", UUID.class), rs.getInt("round_number"),
				rs.getLong("gross_income_mg"), rs.getLong("due_mg"), rs.getLong("paid_mg")), gameId);
	}

}
