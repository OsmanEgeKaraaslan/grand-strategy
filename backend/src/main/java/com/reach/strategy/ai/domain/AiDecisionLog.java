package com.reach.strategy.ai.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Why computer nations took their major decisions ({@code ai_decision}); diagnostics, not public history. */
@Repository
public class AiDecisionLog {

	private final JdbcTemplate jdbc;

	AiDecisionLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Decision(int round, UUID nationId, String kind, String summary) {
	}

	public void record(UUID gameId, int round, int turnNumber, UUID nationId, String kind, String summary,
			String detailsJson, Instant now) {
		jdbc.update("""
				INSERT INTO game.ai_decision (id, game_id, round_number, turn_number, nation_id, kind, summary, details,
				    created_at)
				VALUES (?, ?, ?, ?, ?, ?, left(?, 300), ?::jsonb, ?)
				""", UUID.randomUUID(), gameId, round, turnNumber, nationId, kind, summary, detailsJson,
				Timestamp.from(now));
	}

	public List<Decision> decisions(UUID gameId) {
		return jdbc.query("""
				SELECT round_number, nation_id, kind, summary FROM game.ai_decision WHERE game_id = ?
				ORDER BY round_number, turn_number, created_at
				""", (rs, row) -> new Decision(rs.getInt("round_number"), rs.getObject("nation_id", UUID.class),
				rs.getString("kind"), rs.getString("summary")), gameId);
	}

}
