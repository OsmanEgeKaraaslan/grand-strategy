package com.reach.strategy.ai.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The computer's decision making: pure functions from an {@link AiState} to a bounded, ordered list
 * of intended actions. No randomness, no clock, no I/O — equal state and rules give equal plans.
 * Actions whose signature is in {@code failed} (refused earlier this turn) are never planned again.
 */
public final class AiBrain {

	private AiBrain() {
	}

	/**
	 * @param goal the strategic goal chosen for this turn (DEVELOP, BUILDUP, EXPAND, DEFEND, RECOVER)
	 */
	public record TurnPlan(String goal, List<AiAction> actions) {

		public TurnPlan {
			actions = List.copyOf(actions);
		}

	}

	/**
	 * Everything the nation wants to do on its own turn, in execution order: diplomacy that changes
	 * the situation first (answers, peace, war), then spending — army first when at war or preparing
	 * for one, economy first otherwise — research, quiet moves, reinforcements, and attacks last
	 * (each attack is followed by a fresh look at the board).
	 */
	public static TurnPlan planTurn(AiState state, AiRules rules, Set<String> failed) {
		Strategy.Assessment a = Strategy.assess(state, rules);
		List<AiAction> actions = new ArrayList<>();
		Optional<Statecraft.Target> target = state.diplomacy().available() ? Statecraft.warTarget(state, rules, a)
				: Optional.empty();
		if (state.diplomacy().available()) {
			actions.addAll(Statecraft.calls(state, rules, a, failed));
			actions.addAll(Statecraft.alliances(state, rules, a, target, failed));
			actions.addAll(Statecraft.peace(state, rules, failed));
		}
		Development.Budget budget = new Development.Budget(Strategy.spendable(state, rules));
		boolean military = Strategy.DEFEND.equals(a.goal()) || Strategy.EXPAND.equals(a.goal())
				|| Strategy.BUILDUP.equals(a.goal()) && target.isPresent();
		if (military) {
			actions.addAll(Military.recruit(state, rules, a, budget, failed));
			actions.addAll(Development.fortress(state, rules, a, budget, failed));
			actions.addAll(Development.economy(state, rules, a, budget, failed));
		}
		else {
			actions.addAll(Development.economy(state, rules, a, budget, failed));
			actions.addAll(Development.institutions(state, rules, budget, failed));
			actions.addAll(Development.fortress(state, rules, a, budget, failed));
			actions.addAll(Military.recruit(state, rules, a, budget, failed));
		}
		actions.addAll(Development.research(state, rules, a, failed));
		if (state.diplomacy().available()) {
			actions.addAll(Statecraft.gifts(state, rules, a, failed));
		}
		Military.Moves moves = Military.move(state, rules, a, target.map(Statecraft.Target::nation).orElse(null), failed);
		actions.addAll(moves.quiet());
		actions.addAll(Military.reinforce(state, rules, moves.moving(), budget, failed));
		actions.addAll(moves.attacks());
		// A declaration changes everything: issued last, after the army has moved into position.
		if (state.diplomacy().available()) {
			actions.addAll(Statecraft.declareWar(state, rules, a, target, failed));
		}
		return new TurnPlan(a.goal(), actions.stream().filter(x -> !failed.contains(x.signature())).toList());
	}

	/** Conference business the nation must or wants to handle now, in or out of its turn. */
	public static List<AiAction> planNegotiation(AiState state, AiRules rules, Set<String> failed) {
		if (!state.diplomacy().available() || !state.diplomacy().features().peaceConferences()) {
			return List.of();
		}
		return Statecraft.negotiate(state, rules, failed);
	}

}
