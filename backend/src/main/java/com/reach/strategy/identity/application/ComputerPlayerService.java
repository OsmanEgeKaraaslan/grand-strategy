package com.reach.strategy.identity.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computer accounts: kind COMPUTER, no Supabase user id (enforced by a check constraint), a display
 * name with a character human names cannot contain, so they never collide with human names.
 */
@Service
class ComputerPlayerService implements ComputerPlayers {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	ComputerPlayerService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID create() {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(clock.instant());
		String name = "AI#" + id.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
		jdbc.update("""
				INSERT INTO game.player (id, supabase_user_id, display_name, kind, created_at, updated_at)
				VALUES (?, NULL, ?, 'COMPUTER', ?, ?)
				""", id, name, now, now);
		return id;
	}

	@Override
	public CurrentPlayer actor(UUID computerPlayerId) {
		List<String> names = jdbc.queryForList(
				"SELECT display_name FROM game.player WHERE id = ? AND kind = 'COMPUTER' AND supabase_user_id IS NULL",
				String.class, computerPlayerId);
		if (names.isEmpty()) {
			throw new IllegalArgumentException("Not a computer account: " + computerPlayerId);
		}
		return new CurrentPlayer(computerPlayerId, names.getFirst());
	}

}
