package com.reach.strategy.research.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only research records: points per round end, and technology or doctrine purchases. */
@Repository
public class ResearchLogRepository {

	private final JdbcTemplate jdbc;

	ResearchLogRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** The primary key (game, round, nation) makes paying research twice for one round impossible. */
	public void insertRound(UUID gameId, int round, UUID nationId, int military, int civilian, int doctrine) {
		jdbc.update("""
				INSERT INTO game.research_round_report (game_id, round_number, nation_id, military_gained,
				    civilian_gained, doctrine_gained)
				VALUES (?, ?, ?, ?, ?, ?)
				""", gameId, round, nationId, military, civilian, doctrine);
	}

	public record Unlock(UUID id, UUID nationId, String kind, String subject, Integer level, int costPoints, int round) {
	}

	public void insertUnlock(UUID gameId, Unlock unlock, Instant now) {
		jdbc.update("""
				INSERT INTO game.research_unlock (id, game_id, nation_id, kind, subject, level, cost_points, round_number,
				    created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", unlock.id(), gameId, unlock.nationId(), unlock.kind(), unlock.subject(), unlock.level(),
				unlock.costPoints(), unlock.round(), Timestamp.from(now));
	}

	public Optional<Unlock> findUnlock(UUID gameId, UUID id) {
		return jdbc.query("""
				SELECT id, nation_id, kind, subject, level, cost_points, round_number FROM game.research_unlock
				WHERE game_id = ? AND id = ?
				""", (rs, n) -> new Unlock(rs.getObject("id", UUID.class), rs.getObject("nation_id", UUID.class),
				rs.getString("kind"), rs.getString("subject"), (Integer) rs.getObject("level"), rs.getInt("cost_points"),
				rs.getInt("round_number")), gameId, id)
			.stream()
			.findFirst();
	}

	/** Points a nation gained at its most recent round end, or null before the first. */
	public int[] lastRound(UUID gameId, UUID nationId) {
		return jdbc.query("""
				SELECT military_gained, civilian_gained, doctrine_gained FROM game.research_round_report
				WHERE game_id = ? AND nation_id = ? ORDER BY round_number DESC LIMIT 1
				""", (rs, n) -> new int[] { rs.getInt(1), rs.getInt(2), rs.getInt(3) }, gameId, nationId)
			.stream()
			.findFirst()
			.orElse(null);
	}

}
