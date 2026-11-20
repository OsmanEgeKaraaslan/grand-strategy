package com.reach.strategy.military.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.world.api.WorldApi;

/**
 * The siege module's view of a province, for military commands (implemented by the siege module,
 * so the military module never depends on it). Without an implementation nothing is ever besieged.
 */
public interface SiegeGate {

	/** Whether the city of this province is under an active siege. */
	boolean besieged(UUID gameId, UUID provinceStateId);

	/** Every province of the game whose city is under an active siege, in one query. */
	Set<UUID> besiegedProvinces(UUID gameId);

	/**
	 * A command changed which battalions stand in these provinces (move, battle, retreat). Called in
	 * the command's transaction after the battalions are written: sieges start when a hostile force
	 * holds a fortified province alone, and are lifted when the besiegers are gone.
	 */
	void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, int round, int turnNumber, Instant now);

	/**
	 * The same, reusing the world snapshot and diplomatic relations the command has already read. Only
	 * for commands that cannot have changed them (a plain move: control changes at the round end, and
	 * no battle was fought that could end a war or eliminate a nation).
	 */
	default void presenceChanged(UUID gameId, Set<UUID> provinceStateIds, int round, int turnNumber, Instant now,
			WorldApi.WorldSnapshot snapshot, Hostility.Relations relations) {
		presenceChanged(gameId, provinceStateIds, round, turnNumber, now);
	}

}
