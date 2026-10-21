package com.reach.strategy.identity.api;

import java.util.UUID;

/**
 * Accounts for computer-controlled seats (Phase 11). A computer player has no Supabase identity, so
 * no authenticated request can ever resolve to it; the only way to act as one is an actor obtained
 * here, and only for a computer account. Human accounts can never be obtained this way.
 */
public interface ComputerPlayers {

	/** Creates a new computer account (one per computer seat) and returns its id. */
	UUID create();

	/**
	 * The actor of a computer account, for the in-process computer opponent only.
	 *
	 * @throws IllegalArgumentException if the id is not a computer account
	 */
	CurrentPlayer actor(UUID computerPlayerId);

}
