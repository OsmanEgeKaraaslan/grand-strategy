package com.reach.strategy.military.rules;

/** The three approved battalion roles (docs/GAME_DESIGN.md §7.1). */
public enum UnitType {

	/** Cheap frontline; screens artillery. */
	INFANTRY,

	/** Expensive, strong in open terrain, flanks. */
	CAVALRY,

	/** Expensive, hard-hitting, fragile without an infantry screen. */
	ARTILLERY

}
