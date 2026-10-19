package com.reach.strategy.game.api;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/** Read-only membership checks for other modules' queries. */
public interface GameAccess {

	/**
	 * The caller's membership in a game.
	 *
	 * @throws com.reach.strategy.platform.web.ApiException 404 {@code GAME_NOT_FOUND} if the game
	 * does not exist or the caller is not a member
	 */
	Membership membership(CurrentPlayer me, UUID gameId);

	/**
	 * @param inProgress whether the game is running (started and not finished)
	 * @param yourTurn whether the caller is the active player right now
	 * @param round current round (0 before the start)
	 * @param turnNumber global player-turn counter (movement eligibility is keyed on it)
	 * @param finished whether the game has finished (read-only from then on)
	 */
	record Membership(UUID gameId, UUID gamePlayerId, int seat, boolean inProgress, boolean yourTurn, int round,
			int turnNumber, String rulesetVersion, long stateVersion, boolean finished) {

		/** Started at some point: running or finished — its state is readable. */
		public boolean started() {
			return inProgress || finished;
		}

	}

}
