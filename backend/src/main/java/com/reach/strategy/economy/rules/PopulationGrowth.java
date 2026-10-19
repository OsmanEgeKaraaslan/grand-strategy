package com.reach.strategy.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Civilian population growth (PROVISIONAL, Q-E6): once per global round, after income, an eligible
 * city gains {@code floor(population × (baseBps + techBonusBps) / 10,000)} people (ECONOMY §1.3
 * percentage rounding). Exact integer arithmetic; flooring never creates more people than the
 * rate allows, and an overflow fails loudly instead of wrapping.
 */
public final class PopulationGrowth {

	private PopulationGrowth() {
	}

	public enum Blocker {

		/** The game's ruleset has no growth (games created before v4). */
		NOT_AVAILABLE_IN_RULESET,

		/** Owner and controller differ: an occupied city does not grow. */
		OCCUPIED,

		/** Siege attrition applies instead (5% per round); never both. */
		BESIEGED

	}

	public static List<Blocker> blockers(UUID ownerNationId, UUID controllerNationId, boolean besieged,
			EconomyRules rules) {
		if (rules.populationGrowth() == null) {
			return List.of(Blocker.NOT_AVAILABLE_IN_RULESET);
		}
		List<Blocker> blockers = new ArrayList<>();
		if (!ownerNationId.equals(controllerNationId)) {
			blockers.add(Blocker.OCCUPIED);
		}
		if (besieged) {
			blockers.add(Blocker.BESIEGED);
		}
		return List.copyOf(blockers);
	}

	/** People added this round to an eligible city. */
	public static int growth(int population, int bonusBps, EconomyRules rules) {
		if (population < 0 || bonusBps < 0) {
			throw new IllegalArgumentException("Negative population or bonus");
		}
		if (rules.populationGrowth() == null) {
			return 0;
		}
		long rate = (long) rules.populationGrowth().baseBps() + bonusBps;
		long added = (long) population * rate / 10_000L;
		// The city row is an int: refuse to grow past it rather than overflow.
		Math.toIntExact((long) population + added);
		return (int) added;
	}

}
