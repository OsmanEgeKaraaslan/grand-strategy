package com.reach.strategy.game.api;

import java.util.Set;
import java.util.UUID;

/**
 * Events the game module publishes inside the transaction that starts or finishes a game (Phase
 * 13A). Listeners that only report (notifications) should handle them after commit.
 */
public final class GameLifecycle {

	private GameLifecycle() {
	}

	/** @param humanPlayerIds the human players seated in the game */
	public record Started(UUID gameId, Set<UUID> humanPlayerIds) {
	}

	/** The final result has been stored. */
	public record Finished(UUID gameId, Set<UUID> humanPlayerIds) {
	}

}
