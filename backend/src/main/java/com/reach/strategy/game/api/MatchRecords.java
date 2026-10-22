package com.reach.strategy.game.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Completed matches of one player, read from the stored final results (Phase 12A). Only games
 * whose result row exists count: unfinished games never appear, and the result's primary key on
 * the game makes a second, double-counted result impossible. Games finished before results were
 * stored (rulesets without a victory condition) have no result and are therefore absent.
 */
public interface MatchRecords {

	/** Newest first. */
	List<CompletedMatch> completedMatches(UUID playerId);

	/**
	 * @param outcome WIN (the player's nation won), LOSS (another nation — human or computer — won) or
	 * DRAW (the game ended in a draw)
	 */
	record CompletedMatch(UUID gameId, String gameName, String mapName, Instant finishedAt, int finishedRound,
			Outcome outcome, int rank, String factionKey, String factionName) {
	}

	enum Outcome {
		WIN, LOSS, DRAW
	}

}
