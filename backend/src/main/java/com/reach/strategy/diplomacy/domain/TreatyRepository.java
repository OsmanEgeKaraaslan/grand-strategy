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

	private static final String PROPOSAL_COLUMNS = """
			id, war_id, proposer_nation_id, recipient_nation_id, status, terms::text AS terms, created_round, created_turn,
			created_state_version, responded_round, counter_of_id, created_at
			""";

	public void insertProposal(UUID gameId, ProposalRow p, Instant now) {
		jdbc.update("""
				INSERT INTO game.peace_proposal (id, game_id, war_id, proposer_nation_id, recipient_nation_id, status, terms,
				    created_round, created_turn, created_state_version, counter_of_id, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'PENDING', ?::jsonb, ?, ?, ?, ?, ?, ?)
				""", p.id(), gameId, p.warId(), p.proposer(), p.recipient(), p.termsJson(), p.createdRound(),
				p.createdTurn(), p.createdStateVersion(), p.counterOfId(), Timestamp.from(now), Timestamp.from(now));
	}

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

	/** PENDING → {@code status}; false if it was no longer pending. */
	public boolean closeProposal(UUID gameId, UUID proposalId, String status, int round, Instant now) {
		return jdbc.update("""
				UPDATE game.peace_proposal SET status = ?, responded_round = ?, updated_at = ?
				WHERE game_id = ? AND id = ? AND status = 'PENDING'
				""", status, round, Timestamp.from(now), gameId, proposalId) == 1;
	}

	private static ProposalRow proposal(ResultSet rs, int row) throws SQLException {
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

	public void insertTribute(UUID gameId, TributeRow t, Instant now) {
		jdbc.update("""
				INSERT INTO game.tribute (id, game_id, treaty_id, payer_nation_id, receiver_nation_id, rate_bps,
				    rounds_total, first_round, status, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
				""", t.id(), gameId, t.treatyId(), t.payer(), t.receiver(), t.rateBps(), t.roundsTotal(), t.firstRound(),
				Timestamp.from(now));
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

	/**
	 * Charges one round: the payment row's primary key (tribute, round) forbids a second charge; the
	 * tribute completes with its last round.
	 */
	public void recordPayment(UUID gameId, PaymentRow p, boolean last, Instant now) {
		jdbc.update("""
				INSERT INTO game.tribute_payment (game_id, tribute_id, round_number, gross_income_mg, due_mg, paid_mg,
				    created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", gameId, p.tributeId(), p.round(), p.grossIncomeMg(), p.dueMg(), p.paidMg(), Timestamp.from(now));
		int updated = jdbc.update("""
				UPDATE game.tribute SET rounds_charged = rounds_charged + 1, total_paid_mg = total_paid_mg + ?,
				    status = CASE WHEN ? THEN 'COMPLETED' ELSE status END,
				    end_reason = CASE WHEN ? THEN 'COMPLETED' ELSE end_reason END,
				    ended_round = CASE WHEN ? THEN ? ELSE ended_round END
				WHERE game_id = ? AND id = ? AND status = 'ACTIVE'
				""", p.paidMg(), last, last, last, p.round(), gameId, p.tributeId());
		if (updated != 1) {
			throw new IllegalStateException("Tribute " + p.tributeId() + " is not active");
		}
	}

	/** ACTIVE → CANCELLED (e.g. the two nations are at war again); false if it was not active. */
	public boolean cancelTribute(UUID gameId, UUID tributeId, String reason, int round) {
		return jdbc.update("""
				UPDATE game.tribute SET status = 'CANCELLED', end_reason = ?, ended_round = ?
				WHERE game_id = ? AND id = ? AND status = 'ACTIVE'
				""", reason, round, gameId, tributeId) == 1;
	}

	public List<PaymentRow> payments(UUID gameId) {
		return jdbc.query("""
				SELECT tribute_id, round_number, gross_income_mg, due_mg, paid_mg FROM game.tribute_payment
				WHERE game_id = ? ORDER BY round_number, tribute_id
				""", (rs, row) -> new PaymentRow(rs.getObject("tribute_id", UUID.class), rs.getInt("round_number"),
				rs.getLong("gross_income_mg"), rs.getLong("due_mg"), rs.getLong("paid_mg")), gameId);
	}

}
