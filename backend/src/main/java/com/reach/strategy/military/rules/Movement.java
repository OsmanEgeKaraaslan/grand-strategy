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
