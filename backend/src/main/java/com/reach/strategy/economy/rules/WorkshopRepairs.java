package com.reach.strategy.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Repairing a damaged workshop (APPROVED: 30% of the cost of a new one, 1 round; values from the
 * ruleset). Shared by the UI quote and the command.
 */
public final class WorkshopRepairs {

	private WorkshopRepairs() {
	}

	public enum Blocker {

		NOT_YOUR_TURN,

		NOT_OWNER,

		OCCUPIED,

		BESIEGED,

		/** Only a DAMAGED workshop can be repaired (healthy, or already under repair). */
		NOT_DAMAGED,

		/** The game's ruleset predates repairs (its games can have no damaged workshops). */
		NOT_AVAILABLE_IN_RULESET,

		IN_ARREARS,

		INSUFFICIENT_GOLD

	}

	public record Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn,
			boolean besieged, int tier, WorkshopStatus status, long treasuryMg, boolean inArrears) {
	}

	/**
	 * {@code ceil(buildCost × costBps / 10,000)} — rounded up so no fraction of a milligold is free
	 * (Tier 1: 40 gold × 30% = 12 gold, exact).
	 */
	public static long cost(int tier, EconomyRules rules) {
		EconomyRules.WorkshopRepair repair = rules.workshopRepair();
		if (repair == null) {
			throw new IllegalStateException("Ruleset " + rules.version() + " has no workshop repairs");
		}
		return Math.ceilDiv(Math.multiplyExact(rules.tier(tier).buildCostMg(), (long) repair.costBps()), 10_000L);
	}

	public static List<Blocker> blockers(Request request, EconomyRules rules) {
		List<Blocker> blockers = new ArrayList<>();
		if (rules.workshopRepair() == null) {
			return List.of(Blocker.NOT_AVAILABLE_IN_RULESET);
		}
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
		if (request.status() != WorkshopStatus.DAMAGED) {
			blockers.add(Blocker.NOT_DAMAGED);
		}
		if (request.inArrears()) {
			blockers.add(Blocker.IN_ARREARS);
		}
		if (request.status() == WorkshopStatus.DAMAGED && request.treasuryMg() < cost(request.tier(), rules)) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return List.copyOf(blockers);
	}

}
