package com.reach.strategy.economy.api;

import java.util.Map;
import java.util.UUID;

/**
 * Something that costs upkeep every global round (battalions now; fortresses later). The economy
 * charges the sum of all sources in its round-end settlement (income first, then upkeep, Q-E1)
 * and shows it in the treasury projection. Implementations must be deterministic and read only
 * the current state, so destroyed units stop costing upkeep immediately.
 */
public interface UpkeepSource {

	/** Upkeep per nation id in milligold for the next round end; nations without upkeep may be absent. */
	Map<UUID, Long> upkeepByNation(UUID gameId, String rulesetVersion);

}
