package com.reach.strategy.diplomacy.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The public diplomatic history of a game (never contains private proposals). */
@Repository
public class DiplomacyEventLog {

	private final JdbcTemplate jdbc;

	DiplomacyEventLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Event(UUID id, int round, int turnNumber, String kind, UUID actorNationId, UUID targetNationId,
			UUID warId, String detailsJson, Instant createdAt) {
	}

	public void record(UUID gameId, int round, int turnNumber, String kind, UUID actor, UUID target, UUID warId,
			String detailsJson, Instant now) {
		jdbc.update("""
				INSERT INTO game.diplomacy_event (id, game_id, round_number, turn_number, kind, actor_nation_id,
				    target_nation_id, war_id, details, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
				""", UUID.randomUUID(), gameId, round, turnNumber, kind, actor, target, warId, detailsJson,
				Timestamp.from(now));
	}

	/** Newest first. */
	public List<Event> recent(UUID gameId, int limit) {
		return jdbc.query("""
				SELECT id, round_number, turn_number, kind, actor_nation_id, target_nation_id, war_id, details::text AS details,
				       created_at
				FROM game.diplomacy_event WHERE game_id = ?
				ORDER BY round_number DESC, created_at DESC, id DESC LIMIT ?
				""", (rs, row) -> new Event(rs.getObject("id", UUID.class), rs.getInt("round_number"),
				rs.getInt("turn_number"), rs.getString("kind"), rs.getObject("actor_nation_id", UUID.class),
				rs.getObject("target_nation_id", UUID.class), rs.getObject("war_id", UUID.class),
				rs.getString("details"), rs.getTimestamp("created_at").toInstant()), gameId, limit);
	}

	public int count(UUID gameId, String kind) {
		Integer n = jdbc.queryForObject("SELECT count(*) FROM game.diplomacy_event WHERE game_id = ? AND kind = ?",
				Integer.class, gameId, kind);
		return n == null ? 0 : n;
	}

}
