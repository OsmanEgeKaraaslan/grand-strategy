package com.reach.strategy.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Lets the modules that hold per-player state react when an administrator suspends an account
 * (Q-MOD1), as {@link AccountDeletionParticipant} does for deletion: identity calls every
 * implementation inside the suspending transaction, after the player row is locked.
 */
public interface AccountSuspensionParticipant {

	/**
	 * Stops whatever of this module would otherwise wait for the suspended player (lobbies, seats in
	 * running games, invitations, searches). Unsuspending does not undo it.
	 */
	void accountSuspended(UUID playerId, Instant now);

}
