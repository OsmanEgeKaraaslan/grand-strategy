package com.reach.strategy.diplomacy.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Direct gold gifts between nations (Phase 10): final, one row per executed command. */
@Repository
public class GiftRepository {

	private final JdbcTemplate jdbc;

	GiftRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record GiftRow(UUID id, UUID sender, UUID recipient, long amountMg, int round, int turnNumber,
			Instant createdAt) {
	}

	public void insert(UUID gameId, GiftRow gift) {
		jdbc.update("""
				INSERT INTO game.gold_gift (id, game_id, sender_nation_id, recipient_nation_id, amount_mg, round_number,
				    turn_number, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", gift.id(), gameId, gift.sender(), gift.recipient(), gift.amountMg(), gift.round(), gift.turnNumber(),
				Timestamp.from(gift.createdAt()));
	}

	public Optional<GiftRow> find(UUID gameId, UUID giftId) {
		return list("WHERE game_id = ? AND id = ?", gameId, giftId).stream().findFirst();
	}

	/** Newest first. */
	public List<GiftRow> recent(UUID gameId, int limit) {
		return list("WHERE game_id = ? ORDER BY turn_number DESC, created_at DESC, id LIMIT " + limit, gameId);
	}

	private List<GiftRow> list(String where, Object... args) {
		return jdbc.query("""
				SELECT id, sender_nation_id, recipient_nation_id, amount_mg, round_number, turn_number, created_at
				FROM game.gold_gift
				""" + where, (rs, row) -> new GiftRow(rs.getObject("id", UUID.class),
				rs.getObject("sender_nation_id", UUID.class), rs.getObject("recipient_nation_id", UUID.class),
				rs.getLong("amount_mg"), rs.getInt("round_number"), rs.getInt("turn_number"),
				rs.getTimestamp("created_at").toInstant()), args);
	}

}
