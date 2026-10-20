package com.reach.strategy.game.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Game-level state changes other modules trigger from inside their own command transaction (the game
 * row is already locked by the command pipeline). Phase 10: peace conferences pause and resume normal
 * play, eliminated players stop taking turns, and a game can end immediately (domination).
 */
public interface GameControl {

	/** Pauses normal play for an active peace conference; round, active seat and turn number are kept. */
	void pauseForConference(UUID gameId, UUID conferenceId, Instant now);

	/**
	 * Resumes play after the conference. If the active player was eliminated meanwhile, the turn passes
	 * to the next player who still takes turns (completing the round if it was the last seat).
	 */
	void resumeAfterConference(UUID gameId, UUID conferenceId, Instant now);

	/** The human player of an eliminated nation takes no more turns. */
	void eliminatePlayer(UUID gameId, UUID gamePlayerId, int round);

	/**
	 * Ends the game now if the victory condition says so outside a round end (domination, or no human
	 * player left). Stores the result once.
	 *
	 * @return whether the game finished
	 */
	boolean finishIfDecided(UUID gameId, Instant now);

}
