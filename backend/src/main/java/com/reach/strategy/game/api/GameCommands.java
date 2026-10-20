package com.reach.strategy.game.api;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/**
 * The gameplay command pipeline (docs/TURN_SYSTEM.md §4) for commands owned by other modules:
 * game row lock → membership → idempotency → game in progress → active player → handler →
 * record the command and bump the state version — one transaction, all or nothing.
 */
public interface GameCommands {

	/**
	 * Runs a command for the authenticated caller. A repeated {@code idempotencyKey} does not run the
	 * handler again: {@link Handler#replay} rebuilds the response from the recorded result instead.
	 */
	<T> T execute(CurrentPlayer me, UUID gameId, UUID idempotencyKey, String commandType, Handler<T> handler);

	/**
	 * Like {@link #execute}, but for actions that do not follow turn order (Phase 10: peace
	 * conference actions): lock, membership, idempotency and an in-progress game are still required;
	 * the caller need not be the active player, and the command is allowed while play is paused for
	 * a conference. Eliminated players can never act.
	 */
	<T> T executeOutOfTurn(CurrentPlayer me, UUID gameId, UUID idempotencyKey, String commandType, Handler<T> handler);

	interface Handler<T> {

		/** Validates and applies the command. Throwing rolls everything back. */
		Outcome<T> execute(CommandContext context);

		/** Response for a retry of an already executed command. */
		T replay(CommandContext context, UUID resultRef);

	}

	/** @param resultRef id of what the command produced (e.g. the new building), stored for replays */
	record Outcome<T>(T result, UUID resultRef) {
	}

	/** The verified actor and the game's turn state, under the game lock. */
	record CommandContext(UUID gameId, UUID gamePlayerId, int seat, int round, int turnNumber, String rulesetVersion,
			Instant now) {
	}

}
