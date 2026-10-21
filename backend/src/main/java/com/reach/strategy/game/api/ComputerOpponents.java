package com.reach.strategy.game.api;

/**
 * Whether games of a ruleset get computer-controlled seats for the nations nobody chose (Phase 11).
 * Implemented by the computer-opponent module; without an implementation no game has any.
 */
public interface ComputerOpponents {

	boolean enabled(String rulesetVersion);

}
