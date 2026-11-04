package com.reach.strategy.game.domain;

/**
 * Lifecycle status of a game (docs/TURN_SYSTEM.md §3.1).
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
	FINISHED,

	/**
	 * Closed by an administrator (Q-T3 operator action, Q-MOD1), from any status before FINISHED. Final:
	 * no result is stored, a started game stays readable, every command is refused.
	 */
	ABANDONED

}
