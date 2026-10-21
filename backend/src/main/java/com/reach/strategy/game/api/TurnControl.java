package com.reach.strategy.game.api;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/** Ending one's turn, for callers outside the web layer (the computer opponent, Phase 11). */
public interface TurnControl {

	/** Same rules as {@code POST /turn/end}: active player, expected turn number, idempotency key. */
	void endTurn(CurrentPlayer me, UUID gameId, UUID idempotencyKey, int expectedTurnNumber);

}
