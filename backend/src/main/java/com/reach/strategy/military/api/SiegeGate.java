package com.reach.strategy.military.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The siege module's view of a province, for military commands (implemented by the siege module,
 * so the military module never depends on it). Without an implementation nothing is ever besieged.
 */
public interface SiegeGate {

	/** Whether the city of this province is under an active siege. */
	boolean besieged(UUID gameId, UUID provinceStateId);

	/**
	 * A command changed which battalions stand in these provinces (move, battle, retreat). Called in
	 * the command's transaction after the battalions are written: sieges start when a hostile force
	 * holds a fortified province alone, and are lifted when the besiegers are gone.
	 */
	void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, int round, int turnNumber, Instant now);

}
