package com.reach.strategy.identity.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Lets the modules that hold per-player data take part in account deletion (Phase 15A) without the
 * identity module depending on them: they implement this, identity calls every implementation inside
 * the deleting transaction, after the player row is locked.
 */
public interface AccountDeletionParticipant {

	/**
	 * Why the account cannot be deleted right now, in player-facing words; empty when nothing in this
	 * module stands in the way.
	 */
	default Optional<String> blocker(UUID playerId) {
		return Optional.empty();
	}

	/** Removes or detaches this module's personal data of the player; finished matches stay intact. */
	void accountDeleted(UUID playerId, Instant now);

}
