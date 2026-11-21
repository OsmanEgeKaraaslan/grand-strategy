package com.reach.strategy.diplomacy.domain;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a game's {@link Relations} and writes the difference between two of them (ENGINE_DESIGN S4,
 * decision D4): rows are compared by key and only new and changed rows are written, one batched
 * statement per kind and table, in foreign-key order; within a table updates come first, so a row that
 * gives up a unique slot (a superseded proposal) does so before a new row takes it. Reads reuse the repositories' queries, so the
 * rows and their order are exactly what the rules saw before.
 */
@Repository
public class DiplomacyStore {

	private final JdbcTemplate jdbc;
	private final RelationsRepository relations;
	private final TreatyRepository treaties;
	private final CallToArmsRepository calls;
	private final ConferenceRepository conferences;

	DiplomacyStore(JdbcTemplate jdbc, RelationsRepository relations, TreatyRepository treaties,
			CallToArmsRepository calls, ConferenceRepository conferences) {
		this.jdbc = jdbc;
		this.relations = relations;
		this.treaties = treaties;
		this.calls = calls;
		this.conferences = conferences;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Relations load(UUID gameId) {
		return new Relations(relations.alliances(gameId), relations.wars(gameId), relations.participants(gameId),
				relations.activeFronts(gameId), relations.truces(gameId), treaties.tributes(gameId),
				jdbc.query("SELECT " + TreatyRepository.PROPOSAL_COLUMNS
						+ " FROM game.peace_proposal WHERE game_id = ? ORDER BY created_turn, id", TreatyRepository::proposal,
						gameId),
				calls.all(gameId), conferences.all(gameId));
	}

	/** Writes what changed from {@code before} to {@code after}; {@code now} stamps {@code updated_at}. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void persist(UUID gameId, Relations before, Relations after, Instant now) {
		if (before == after) {
			return;
		}
		Timestamp at = Timestamp.from(now);
		alliances(gameId, before, after, at);
		wars(gameId, before, after, at);
		participants(gameId, before, after);
		fronts(gameId, before, after);
		truces(gameId, before, after);
		tributes(gameId, before, after, at);
		proposals(gameId, before, after, at);
		calls(gameId, before, after, at);
		conferences(gameId, before, after, at);
	}

	private void alliances(UUID gameId, Relations before, Relations after, Timestamp at) {
		Changes<RelationsRepository.AllianceRow> c = changes(before.alliances(), after.alliances(),
				RelationsRepository.AllianceRow::id);
		batch("""
				UPDATE game.alliance SET status = ?, accepted_round = ?, ended_round = ?, end_reason = ?, updated_at = ?
				WHERE game_id = ? AND id = ?
				""", c.changed, a -> new Object[] { a.status(), a.acceptedRound(), a.endedRound(), a.endReason(), at, gameId,
				a.id() }, new int[] { Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.TIMESTAMP,
						Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.alliance (id, game_id, kind, nation_a_id, nation_b_id, proposer_nation_id, status,
				    proposed_round, accepted_round, ended_round, end_reason, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, a -> new Object[] { a.id(), gameId, a.kind(), a.nationA(), a.nationB(), a.proposer(),
				a.status(), a.proposedRound(), a.acceptedRound(), a.endedRound(), a.endReason(), at, at },
				new int[] { Types.OTHER, Types.OTHER, Types.VARCHAR, Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR,
						Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.TIMESTAMP, Types.TIMESTAMP });
	}

	private void wars(UUID gameId, Relations before, Relations after, Timestamp at) {
		Changes<RelationsRepository.WarRow> c = changes(before.wars(), after.wars(), RelationsRepository.WarRow::id);
		batch("""
				UPDATE game.war SET attacker_leader_id = ?, defender_leader_id = ?, status = ?, ended_round = ?,
				    ended_turn = ?, end_reason = ?
				WHERE game_id = ? AND id = ?
				""", c.changed, w -> new Object[] { w.attackerLeader(), w.defenderLeader(), w.status(), w.endedRound(),
				w.endedTurn(), w.endReason(), gameId, w.id() }, new int[] { Types.OTHER, Types.OTHER, Types.VARCHAR,
						Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.war (id, game_id, attacker_leader_id, defender_leader_id, status, declared_round,
				    declared_turn, ended_round, ended_turn, end_reason, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, w -> new Object[] { w.id(), gameId, w.attackerLeader(), w.defenderLeader(), w.status(),
				w.declaredRound(), w.declaredTurn(), w.endedRound(), w.endedTurn(), w.endReason(), at },
				new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR, Types.INTEGER, Types.INTEGER,
						Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.TIMESTAMP });
	}

	private void participants(UUID gameId, Relations before, Relations after) {
		Changes<RelationsRepository.ParticipantRow> c = changes(before.participants(), after.participants(),
				p -> List.of(p.warId(), p.nationId()));
		batch("""
				UPDATE game.war_participant SET left_round = ?, left_reason = ?
				WHERE game_id = ? AND war_id = ? AND nation_id = ?
				""", c.changed, p -> new Object[] { p.leftRound(), p.leftReason(), gameId, p.warId(), p.nationId() },
				new int[] { Types.INTEGER, Types.VARCHAR, Types.OTHER, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.war_participant (game_id, war_id, nation_id, side, joined_round, join_reason, left_round,
				    left_reason)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, p -> new Object[] { gameId, p.warId(), p.nationId(), p.side(), p.joinedRound(), p.joinReason(),
				p.leftRound(), p.leftReason() }, new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR,
						Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.VARCHAR });
	}

	/** Active fronts are a set: a front that disappeared is deactivated, a new one inserted. */
	private void fronts(UUID gameId, Relations before, Relations after) {
		Set<RelationsRepository.FrontRow> old = new HashSet<>(before.fronts());
		Set<RelationsRepository.FrontRow> now = new HashSet<>(after.fronts());
		List<RelationsRepository.FrontRow> ended = before.fronts().stream().filter(f -> !now.contains(f)).toList();
		List<RelationsRepository.FrontRow> opened = after.fronts().stream().filter(f -> !old.contains(f)).toList();
		batch("""
				UPDATE game.war_front SET active = false
				WHERE game_id = ? AND war_id = ? AND nation_a_id = ? AND nation_b_id = ? AND active
				""", ended, f -> new Object[] { gameId, f.warId(), f.nationA(), f.nationB() },
				new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.war_front (game_id, war_id, nation_a_id, nation_b_id, active) VALUES (?, ?, ?, ?, true)
				""", opened, f -> new Object[] { gameId, f.warId(), f.nationA(), f.nationB() },
				new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER });
	}

	private void truces(UUID gameId, Relations before, Relations after) {
		Set<RelationsRepository.TruceRow> old = new HashSet<>(before.truces());
		List<RelationsRepository.TruceRow> added = after.truces().stream().filter(t -> !old.contains(t)).toList();
		batch("""
				INSERT INTO game.truce (game_id, treaty_id, nation_a_id, nation_b_id, until_round) VALUES (?, ?, ?, ?, ?)
				""", added, t -> new Object[] { gameId, t.treatyId(), t.nationA(), t.nationB(), t.untilRound() },
				new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER, Types.INTEGER });
	}

	private void tributes(UUID gameId, Relations before, Relations after, Timestamp at) {
		Changes<TreatyRepository.TributeRow> c = changes(before.tributes(), after.tributes(),
				TreatyRepository.TributeRow::id);
		batch("""
				UPDATE game.tribute SET rounds_charged = ?, total_paid_mg = ?, status = ?, end_reason = ?, ended_round = ?
				WHERE game_id = ? AND id = ?
				""", c.changed, t -> new Object[] { t.roundsCharged(), t.totalPaidMg(), t.status(), t.endReason(),
				t.endedRound(), gameId, t.id() }, new int[] { Types.INTEGER, Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
						Types.INTEGER, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.tribute (id, game_id, treaty_id, payer_nation_id, receiver_nation_id, rate_bps,
				    rounds_total, rounds_charged, first_round, total_paid_mg, status, end_reason, ended_round, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, t -> new Object[] { t.id(), gameId, t.treatyId(), t.payer(), t.receiver(), t.rateBps(),
				t.roundsTotal(), t.roundsCharged(), t.firstRound(), t.totalPaidMg(), t.status(), t.endReason(),
				t.endedRound(), at }, new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER,
						Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
						Types.INTEGER, Types.TIMESTAMP });
	}

	private void proposals(UUID gameId, Relations before, Relations after, Timestamp at) {
		Changes<TreatyRepository.ProposalRow> c = changes(before.proposals(), after.proposals(),
				TreatyRepository.ProposalRow::id);
		batch("""
				UPDATE game.peace_proposal SET status = ?, responded_round = ?, updated_at = ? WHERE game_id = ? AND id = ?
				""", c.changed, p -> new Object[] { p.status(), p.respondedRound(), at, gameId, p.id() },
				new int[] { Types.VARCHAR, Types.INTEGER, Types.TIMESTAMP, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.peace_proposal (id, game_id, war_id, proposer_nation_id, recipient_nation_id, status, terms,
				    created_round, created_turn, created_state_version, responded_round, counter_of_id, created_at,
				    updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, p -> new Object[] { p.id(), gameId, p.warId(), p.proposer(), p.recipient(), p.status(),
				p.termsJson(), p.createdRound(), p.createdTurn(), p.createdStateVersion(), p.respondedRound(),
				p.counterOfId(), Timestamp.from(p.createdAt()), at }, new int[] { Types.OTHER, Types.OTHER, Types.OTHER,
						Types.OTHER, Types.OTHER, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.BIGINT,
						Types.INTEGER, Types.OTHER, Types.TIMESTAMP, Types.TIMESTAMP });
	}

	private void calls(UUID gameId, Relations before, Relations after, Timestamp at) {
		Changes<CallToArmsRepository.CallRow> c = changes(before.calls(), after.calls(), CallToArmsRepository.CallRow::id);
		batch("""
				UPDATE game.call_to_arms SET status = ?, answered_round = ?, updated_at = ? WHERE game_id = ? AND id = ?
				""", c.changed, x -> new Object[] { x.status(), x.answeredRound(), at, gameId, x.id() },
				new int[] { Types.VARCHAR, Types.INTEGER, Types.TIMESTAMP, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.call_to_arms (id, game_id, war_id, caller_nation_id, ally_nation_id, alliance_id, status,
				    created_round, answered_round, created_at, updated_at, side)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, x -> new Object[] { x.id(), gameId, x.warId(), x.caller(), x.ally(), x.allianceId(), x.status(),
				x.createdRound(), x.answeredRound(), Timestamp.from(x.createdAt()), at, x.side() },
				new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR,
						Types.INTEGER, Types.INTEGER, Types.TIMESTAMP, Types.TIMESTAMP, Types.VARCHAR });
	}

	private void conferences(UUID gameId, Relations before, Relations after, Timestamp at) {
		Changes<ConferenceRepository.ConferenceRow> c = changes(before.conferences(), after.conferences(),
				ConferenceRepository.ConferenceRow::id);
		batch("""
				UPDATE game.peace_conference SET status = ?, current_revision = ?, opened_at = ?, closed_at = ?,
				    updated_at = ?
				WHERE game_id = ? AND id = ?
				""", c.changed, x -> new Object[] { x.status(), x.currentRevision(), timestamp(x.openedAt()),
				timestamp(x.closedAt()), at, gameId, x.id() }, new int[] { Types.VARCHAR, Types.INTEGER, Types.TIMESTAMP,
						Types.TIMESTAMP, Types.TIMESTAMP, Types.OTHER, Types.OTHER });
		batch("""
				INSERT INTO game.peace_conference (id, game_id, war_id, kind, requester_nation_id, counterpart_nation_id,
				    status, current_revision, requested_round, opened_at, closed_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", c.added, x -> new Object[] { x.id(), gameId, x.warId(), x.kind(), x.requester(), x.counterpart(),
				x.status(), x.currentRevision(), x.requestedRound(), timestamp(x.openedAt()), timestamp(x.closedAt()),
				Timestamp.from(x.createdAt()), at }, new int[] { Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR,
						Types.OTHER, Types.OTHER, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.TIMESTAMP, Types.TIMESTAMP,
						Types.TIMESTAMP, Types.TIMESTAMP });
	}

	// --- helpers ------------------------------------------------------------------------------

	private record Changes<T>(List<T> added, List<T> changed) {
	}

	/** New rows (key not in {@code before}) and changed rows (same key, different values), in {@code after}'s order. */
	private static <T> Changes<T> changes(List<T> before, List<T> after, Function<T, Object> key) {
		Map<Object, T> old = new HashMap<>();
		before.forEach(row -> old.put(key.apply(row), row));
		List<T> added = after.stream().filter(row -> !old.containsKey(key.apply(row))).toList();
		List<T> changed = after.stream()
			.filter(row -> old.containsKey(key.apply(row)) && !Objects.equals(old.get(key.apply(row)), row))
			.toList();
		return new Changes<>(added, changed);
	}

	private <T> void batch(String sql, List<T> rows, Function<T, Object[]> values, int[] types) {
		if (rows.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(sql, rows, rows.size(), (ps, row) -> {
			Object[] v = values.apply(row);
			for (int i = 0; i < v.length; i++) {
				if (v[i] == null) {
					ps.setNull(i + 1, types[i]);
				}
				else {
					ps.setObject(i + 1, v[i], types[i]);
				}
			}
		});
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

}
