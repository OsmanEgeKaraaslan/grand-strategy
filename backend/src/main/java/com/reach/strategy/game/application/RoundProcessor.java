package com.reach.strategy.game.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.game.api.RoundPhase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completes a global round exactly once (docs/TURN_SYSTEM.md §5). The first statement inserts the
 * {@code round_resolution} guard row: processing the same round twice violates its primary key and
 * rolls the whole transaction back, even if some future code path skipped the game lock. Then
 * every phase runs in {@link RoundPhase.Step} order within the same transaction.
 */
public class RoundProcessor {

	private final JdbcTemplate jdbc;
	private final List<RoundPhase> phases;

	RoundProcessor(JdbcTemplate jdbc, List<RoundPhase> phases) {
		this.jdbc = jdbc;
		this.phases = List.copyOf(phases);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void completeRound(UUID gameId, int round, String rulesetVersion, Instant now) {
		jdbc.update("INSERT INTO game.round_resolution (game_id, round_number, completed_at) VALUES (?, ?, ?)", gameId,
				round, Timestamp.from(now));
		RoundPhase.RoundContext context = new RoundPhase.RoundContext(gameId, round, rulesetVersion, now);
		for (RoundPhase phase : phases) {
			phase.apply(context);
		}
	}

	/** The phases in execution order (for diagnostics and tests). */
	public List<RoundPhase.Step> steps() {
		return phases.stream().map(RoundPhase::step).toList();
	}

}
