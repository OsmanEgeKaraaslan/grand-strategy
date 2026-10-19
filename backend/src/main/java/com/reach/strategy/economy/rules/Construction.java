package com.reach.strategy.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Whether a nation may order a workshop in a city, and when it would be finished. The same check
 * drives the UI (disabled buttons with reasons) and the command (rejection), so they never disagree.
 */
public final class Construction {

	private Construction() {
	}

	/** Reasons a construction is not allowed, in the order they are reported. */
	public enum Blocker {

		NOT_YOUR_TURN,

		/** The nation is not the city's legal owner (e.g. it only occupies it). */
		NOT_OWNER,

		/** The nation owns the city but another nation controls it. */
		OCCUPIED,

		/** The city is under an active siege (Phase 7). */
		BESIEGED,

		/** This tier cannot be built yet (Tier 2 needs civilian technology). */
		TIER_LOCKED,

		/** The city already has the maximum number of workshops, projects included (Q-E2). */
		WORKSHOP_LIMIT_REACHED,

		/** Q-E1b: a nation in arrears cannot construct. */
		IN_ARREARS,

		INSUFFICIENT_GOLD

	}

	/**
	 * @param existingWorkshops all workshops of the city, any status (projects reserve a slot)
	 * @param besieged the city is under an active siege
	 */
	public record Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, int tier,
			int existingWorkshops, long treasuryMg, boolean inArrears, boolean besieged) {

		/** A city that is not besieged. */
		public Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, int tier,
				int existingWorkshops, long treasuryMg, boolean inArrears) {
			this(nationId, ownerNationId, controllerNationId, yourTurn, tier, existingWorkshops, treasuryMg, inArrears,
					false);
		}

	}

	public static List<Blocker> blockers(Request request, EconomyRules rules) {
		List<Blocker> blockers = new ArrayList<>();
		if (!request.yourTurn()) {
			blockers.add(Blocker.NOT_YOUR_TURN);
		}
		if (!request.nationId().equals(request.ownerNationId())) {
			blockers.add(Blocker.NOT_OWNER);
		}
		else if (!request.nationId().equals(request.controllerNationId())) {
			blockers.add(Blocker.OCCUPIED);
		}
		if (request.besieged()) {
			blockers.add(Blocker.BESIEGED);
		}
		EconomyRules.WorkshopTier tier = rules.workshopTiers().get(request.tier());
		if (tier == null || !tier.constructible()) {
			blockers.add(Blocker.TIER_LOCKED);
		}
		if (request.existingWorkshops() >= rules.maxWorkshopsPerCity()) {
			blockers.add(Blocker.WORKSHOP_LIMIT_REACHED);
		}
		if (request.inArrears()) {
			blockers.add(Blocker.IN_ARREARS);
		}
		if (tier != null && request.treasuryMg() < tier.buildCostMg()) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return List.copyOf(blockers);
	}

	/**
	 * Q-E8: a project of duration d ordered in round N becomes ACTIVE at the end of round N+d−1,
	 * after that round's income, and first produces income in round N+d.
	 */
	public static int completesAfterRound(int orderedRound, int durationRounds) {
		if (orderedRound < 1 || durationRounds < 1) {
			throw new IllegalArgumentException("Invalid construction timing");
		}
		return orderedRound + durationRounds - 1;
	}

	/** First round whose income includes the building. */
	public static int operationalFromRound(int completesAfterRound) {
		return completesAfterRound + 1;
	}

}
