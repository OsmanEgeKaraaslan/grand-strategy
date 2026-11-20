package com.reach.strategy.economy.api;

import java.util.Map;
import java.util.UUID;

/**
 * What a nation's civilian technology means for the economy — implemented by the research module
 * so the economy never depends on it. Without an implementation (or for games before ruleset v4)
 * only Tier 1 is unlocked and there is no growth bonus.
 */
public interface EconomyTechnology {

	/** The highest workshop tier the nation may have (1 without civilian technology). */
	int highestWorkshopTier(UUID gameId, UUID nationId);

	/** Extra population growth per round in basis points from civilian technology. */
	int growthBonusBps(UUID gameId, UUID nationId);

	/**
	 * Both values for every nation of the game in one read, for views that cover many cities.
	 * Nations without technology are absent (Tier 1, no bonus).
	 */
	Map<UUID, Technology> ofGame(UUID gameId);

	record Technology(int highestWorkshopTier, int growthBonusBps) {
	}

}
