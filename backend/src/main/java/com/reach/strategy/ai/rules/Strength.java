package com.reach.strategy.ai.rules;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Rough battle strength for the computer's estimates. The battle engine decides real battles; these
 * numbers only rank options. Strength is "infantry-equivalent men": manpower × the unit type's weight
 * (ai.json {@code unitStrength}, 100 = infantry) / 100, and an attack is scaled by the attacker's own
 * river and mountain multipliers (with its doctrines), exactly the terrain effects a human sees in
 * the move preview. Integer arithmetic throughout.
 */
public final class Strength {

	private Strength() {
	}

	public static long of(AiState.Unit unit, AiRules rules) {
		return (long) unit.manpower() * rules.military().unitStrength().getOrDefault(unit.type(), 100) / 100;
	}

	public static long of(Collection<AiState.Unit> units, AiRules rules) {
		long total = 0;
		for (AiState.Unit unit : units) {
			total += of(unit, rules);
		}
		return total;
	}

	public static long ofNation(AiState state, UUID nation, AiRules rules) {
		return of(state.unitsOf(nation), rules);
	}

	/** Battalions in a province that would fight the deciding nation there (at war with it). */
	public static List<AiState.Unit> hostileIn(AiState state, String province) {
		return state.unitsIn(province).stream().filter(u -> state.atWarWith(u.nation())).toList();
	}

	/** Battalions in a province that fight on the deciding nation's side (own, allied, co-belligerent). */
	public static List<AiState.Unit> friendlyIn(AiState state, String province) {
		var friends = state.friends();
		return state.unitsIn(province)
			.stream()
			.filter(u -> u.nation().equals(state.me()) || friends.contains(u.nation()))
			.toList();
	}

	/** Attack strength of {@code attackers} moving from {@code from} into {@code to}. */
	public static long attack(AiState state, Collection<AiState.Unit> attackers, String from, String to, AiRules rules) {
		long power = of(attackers, rules);
		AiState.Province origin = state.provinces().get(from);
		AiState.Province target = state.provinces().get(to);
		if (origin != null && origin.riverTo().contains(to)) {
			power = power * state.combat().riverAttackBps() / 10_000;
		}
		if (target != null && target.mountains()) {
			power = power * state.combat().mountainAttackBps() / 10_000;
		}
		return power;
	}

	/** Attack ÷ defence in basis points; "very large" against an empty province. */
	public static long ratioBps(long attack, long defence) {
		if (defence <= 0) {
			return 1_000_000;
		}
		return attack * 10_000 / defence;
	}

	/** Strength of battalions at war with the deciding nation within {@code radius} steps of a province. */
	public static long threatAround(AiState state, String province, int radius, AiRules rules) {
		long threat = 0;
		for (String key : Paths.within(state, province, radius)) {
			threat += of(hostileIn(state, key), rules);
		}
		return threat;
	}

}
