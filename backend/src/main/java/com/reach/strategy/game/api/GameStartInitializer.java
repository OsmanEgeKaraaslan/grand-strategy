package com.reach.strategy.game.api;

import java.util.UUID;

/**
 * Hook for modules that create per-game state when a game starts (e.g. the economy creates
 * treasuries and starting workshops). Runs after the world is instantiated, inside the start
 * transaction: a failure rolls back the whole start.
 */
public interface GameStartInitializer {

	void initialize(UUID gameId, UUID mapId, String rulesetVersion);

}
