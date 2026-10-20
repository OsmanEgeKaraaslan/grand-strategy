package com.reach.strategy.military.rules;

/**
 * Per-battalion movement eligibility (APPROVED Q-M1, Q-M3). The global turn number grows with every
 * player turn, so "once per player turn" is an equality check on the battalion's own columns —
 * independent of any army grouping, which therefore can never reset it.
 */
public final class Movement {

	private Movement() {
	}

	public enum Eligibility {

		READY,

		/** Recruited during the current player turn: may not move or attack (it does defend). */
		RECRUITED_THIS_TURN,

		/** Already moved during the current player turn. */
		ALREADY_MOVED,

		/** Reinforced during the current player turn (PROVISIONAL tactical rule): it defends but may not move or attack. */
		REINFORCED_THIS_TURN

	}

	/**
	 * When eligibility is judged. Ruleset v6 counts per global round (a battalion moves at most once
	 * per round, whoever commands it — attached forces, Phase 10); earlier rulesets per player turn,
	 * which is the same for a battalion only ever commanded by its owner.
	 */
	public record Clock(int turnNumber, int round, boolean roundBased) {

		public static Clock turn(int turnNumber) {
			return new Clock(turnNumber, 0, false);
		}

	}

	public static Eligibility eligibility(int recruitedTurnNumber, int recruitedRound, Integer lastMovedTurnNumber,
			Integer lastMovedRound, Integer lastReinforcedTurnNumber, Integer lastReinforcedRound, Clock clock) {
		if (!clock.roundBased()) {
			return eligibility(recruitedTurnNumber, lastMovedTurnNumber, lastReinforcedTurnNumber, clock.turnNumber());
		}
		// Starting forces (recruited before round 1) are never "recruited this round".
		if (recruitedTurnNumber > 0 && recruitedRound == clock.round()) {
			return Eligibility.RECRUITED_THIS_TURN;
		}
		if (lastMovedRound != null && lastMovedRound == clock.round()) {
			return Eligibility.ALREADY_MOVED;
		}
		if (lastReinforcedRound != null && lastReinforcedRound == clock.round()) {
			return Eligibility.REINFORCED_THIS_TURN;
		}
		return Eligibility.READY;
	}

	public static Eligibility eligibility(int recruitedTurnNumber, Integer lastMovedTurnNumber,
			Integer lastReinforcedTurnNumber, int currentTurnNumber) {
		if (recruitedTurnNumber == currentTurnNumber) {
			return Eligibility.RECRUITED_THIS_TURN;
		}
		if (lastMovedTurnNumber != null && lastMovedTurnNumber == currentTurnNumber) {
			return Eligibility.ALREADY_MOVED;
		}
		if (lastReinforcedTurnNumber != null && lastReinforcedTurnNumber == currentTurnNumber) {
			return Eligibility.REINFORCED_THIS_TURN;
		}
		return Eligibility.READY;
	}

}
