package com.reach.strategy.military.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Voluntary disbanding (APPROVED, GAME_DESIGN §7.6): the owner, on its turn, in a province its own
 * nation controls — allies' territory does not count. Survivors go home per cohort ({@link Cohorts}).
 */
public final class Disbanding {

	private Disbanding() {
	}

	public enum Blocker {

		NOT_YOUR_TURN,

		/** The province is controlled by another nation. */
		NOT_FRIENDLY_TERRITORY,

		/** The province is under an active siege (PROVISIONAL Q-S11). */
		BESIEGED

	}

	public static List<Blocker> blockers(UUID nationId, UUID provinceControllerId, boolean yourTurn,
			boolean besieged) {
		List<Blocker> blockers = new ArrayList<>();
		if (!yourTurn) {
			blockers.add(Blocker.NOT_YOUR_TURN);
		}
		if (!nationId.equals(provinceControllerId)) {
			blockers.add(Blocker.NOT_FRIENDLY_TERRITORY);
		}
		if (besieged) {
			blockers.add(Blocker.BESIEGED);
		}
		return List.copyOf(blockers);
	}

}
