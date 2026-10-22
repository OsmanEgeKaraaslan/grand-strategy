package com.reach.strategy.game.api;

import java.util.List;
import java.util.UUID;

/**
 * Creates and starts a Quick Match game (Phase 13B) through the same lobby, nation and start code
 * as a private game: the first player hosts, the factions are assigned by the game module
 * ({@code game.rules.QuickMatchNations}), and every other nation gets a computer seat. Runs in the
 * caller's transaction, so a failure anywhere leaves no partial game.
 */
public interface MatchCreation {

	/**
	 * @param mapKey the map (its latest version)
	 * @param playerIds the human players in seat order (exactly two)
	 * @param seed picks the faction pair and who gets which
	 */
	CreatedMatch createQuickMatch(String mapKey, List<UUID> playerIds, long seed);

	record CreatedMatch(UUID gameId, String gameName, String mapName, String rulesetVersion, List<Assignment> assignments) {
	}

	record Assignment(UUID playerId, String nationKey, String nationName, String nationColor) {
	}

}
