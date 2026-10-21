package com.reach.strategy.game.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The computer-controlled seats of games (Phase 11), for the in-process computer opponent. A seat
 * acts only through the command pipeline with the actor of its own computer account, which is a
 * member of exactly this one game.
 */
public interface ComputerSeats {

	/** @param playerId the seat's computer account */
	record Seat(UUID gamePlayerId, UUID playerId, int seat, boolean eliminated) {
	}

	/** The computer seat whose turn it is now, if the game is running, not paused and waiting for it. */
	record Turn(UUID gameId, Seat seat, int round, int turnNumber, String rulesetVersion) {
	}

	List<Seat> seats(UUID gameId);

	Optional<Turn> activeComputerTurn(UUID gameId);

	/** Running games that have computer seats (for recovery after a restart). */
	List<UUID> runningGamesWithComputers();

	/** Published after a commit that may hand the turn to a computer seat or end a pause. */
	record TurnPassed(UUID gameId) {
	}

}
