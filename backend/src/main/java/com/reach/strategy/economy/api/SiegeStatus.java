package com.reach.strategy.economy.api;

import java.util.Set;
import java.util.UUID;

/**
 * Whether a city is under an active siege — implemented by the siege module so the economy can
 * refuse construction and repairs there without depending on it. Without an implementation (no
 * siege module) no city is besieged.
 */
public interface SiegeStatus {

	boolean cityBesieged(UUID gameId, UUID cityId);

	/** Every city of the game under an active siege, in one query (views that list many cities). */
	Set<UUID> besiegedCities(UUID gameId);

}
