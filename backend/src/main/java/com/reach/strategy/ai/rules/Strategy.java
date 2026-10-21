package com.reach.strategy.ai.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Strategic goal of one turn and the personality-adjusted thresholds the planners use. Goals are
 * re-evaluated every turn, so a nation switches from development to war and back as the board
 * changes. Values in basis points; integer arithmetic.
 */
public final class Strategy {

	public static final String DEVELOP = "DEVELOP";
	public static final String BUILDUP = "BUILDUP";
	public static final String EXPAND = "EXPAND";
	public static final String DEFEND = "DEFEND";
	public static final String RECOVER = "RECOVER";

	private Strategy() {
	}

	/** Facts the goal is chosen from (also used by the other planners). */
	public record Assessment(String goal, long myPower, long localThreat, long strongestNeighbourPower,
			UUID strongestNeighbour, boolean atWar, int occupiedProvinces, boolean capitalThreatened,
			Set<UUID> neighbours) {
	}

	public static Assessment assess(AiState state, AiRules rules) {
		AiRules.Personality p = rules.personalityOf(state.meKey());
		long myPower = Strength.ofNation(state, state.me(), rules);
		Set<UUID> neighbours = neighbours(state, state.me());
		long strongest = 0;
		UUID strongestNation = null;
		for (UUID n : neighbours.stream().sorted().toList()) {
			long power = Strength.ofNation(state, n, rules);
			if (power > strongest) {
				strongest = power;
				strongestNation = n;
			}
		}
		int radius = rules.military().threatRadius();
		long threat = 0;
		for (AiState.Province own : state.ownedBy(state.me())) {
			threat = Math.max(threat, Strength.threatAround(state, own.key(), radius, rules));
		}
		boolean atWar = state.diplomacy().wars().stream().anyMatch(w -> w.sideOf(state.me()).isPresent());
		int occupied = (int) state.ownedBy(state.me()).stream().filter(pr -> !pr.controller().equals(state.me())).count();
		String capital = state.self().capitalKey();
		boolean capitalThreatened = capital != null && state.provinces().containsKey(capital)
				&& Strength.threatAround(state, capital, 1, rules) > 0;

		AiState.Economy e = state.economy();
		String goal;
		if (e.inArrears() || (e.netIncomeMg() < 0 && e.treasuryMg() < reserve(rules, p))) {
			goal = RECOVER;
		}
		else if (atWar && (occupied > 0 || capitalThreatened
				|| threat * 10_000 > myPower * (10_000L + p.defenseBps() / 2))) {
			goal = DEFEND;
		}
		else if (atWar) {
			goal = EXPAND;
		}
		else if (strongest * 10_000 > myPower * (rules.diplomacy().threatRatioBps() - p.defenseBps() / 4L)) {
			goal = BUILDUP;
		}
		else if (p.aggressionBps() > 0 && state.round() >= rules.diplomacy().firstWarRound() - 1) {
			goal = BUILDUP;
		}
		else {
			goal = DEVELOP;
		}
		return new Assessment(goal, myPower, threat, strongest, strongestNation, atWar, occupied, capitalThreatened,
				neighbours);
	}

	/** Nations owning or controlling a province adjacent to one the given nation owns or controls. */
	public static Set<UUID> neighbours(AiState state, UUID nation) {
		Set<UUID> result = new HashSet<>();
		for (AiState.Province p : state.provinces().values()) {
			if (!p.owner().equals(nation) && !p.controller().equals(nation)) {
				continue;
			}
			for (String key : p.neighbours()) {
				AiState.Province n = state.provinces().get(key);
				if (n == null) {
					continue;
				}
				if (!n.owner().equals(nation)) {
					result.add(n.owner());
				}
				if (!n.controller().equals(nation)) {
					result.add(n.controller());
				}
			}
		}
		result.removeIf(n -> state.nations().get(n) == null || state.nations().get(n).eliminated());
		return result;
	}

	// --- personality-adjusted thresholds --------------------------------------------------------

	public static long reserve(AiRules rules, AiRules.Personality p) {
		return rules.economy().reserveMg() * (10_000 + p.economyBps() / 2) / 10_000;
	}

	public static int attackRatio(AiRules rules, AiRules.Personality p) {
		return rules.military().attackRatioBps() - p.aggressionBps() / 4 + p.defenseBps() / 8;
	}

	public static int upkeepShare(AiRules rules, AiRules.Personality p, String goal) {
		int base = rules.military().upkeepShareBps().getOrDefault(goal, 3000);
		int adjusted = base + p.aggressionBps() / 6 + p.defenseBps() / 8 - p.economyBps() / 8;
		return Math.max(1000, Math.min(9000, adjusted));
	}

	public static int warRatio(AiRules rules, AiRules.Personality p) {
		return rules.diplomacy().warRatioBps() - p.aggressionBps() / 3 + p.defenseBps() / 6;
	}

	/** Treasury not needed as a reserve. */
	public static long spendable(AiState state, AiRules rules) {
		return Math.max(0, state.economy().treasuryMg() - reserve(rules, rules.personalityOf(state.meKey())));
	}

	static List<String> institutionPreference(AiRules rules, String nationKey) {
		return rules.personalityOf(nationKey).institutions();
	}

}
