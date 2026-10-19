package com.reach.strategy.game.domain;

/**
 * Lifecycle status of a game (docs/TURN_SYSTEM.md §3.1). ABANDONED arrives once stalled-game
 * handling (Q-T3) is decided.
 */
public enum GameStatus {

	/** At least one slot is free; players can join with the invitation code. */
	WAITING_FOR_PLAYERS,

	/** Every slot is taken. Nobody else can join. */
	READY,

	/** Started: nations are locked and players take sequential turns. */
	IN_PROGRESS,

	/**
	 * Completed by the ruleset's victory condition (Phase 9, PROVISIONAL Q-T2). Final: the state stays
	 * readable, every gameplay command is refused.
	 */
	FINISHED

}
