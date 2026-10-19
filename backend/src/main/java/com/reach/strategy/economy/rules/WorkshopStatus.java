package com.reach.strategy.economy.rules;

/** Lifecycle of a workshop (docs/COMBAT_AND_SIEGE.md §5.7). Only ACTIVE workshops employ anyone. */
public enum WorkshopStatus {

	/** A paid construction project; becomes ACTIVE at a round end (Q-E8). */
	UNDER_CONSTRUCTION,

	ACTIVE,

	/** Bombarded in an artillery siege: no capacity until repaired (Phase 7). */
	DAMAGED,

	/** A paid repair; still no capacity until it completes at a round end (Q-E8). */
	UNDER_REPAIR

}
