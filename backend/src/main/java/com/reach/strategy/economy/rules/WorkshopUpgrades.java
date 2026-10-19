package com.reach.strategy.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Upgrading a Tier 1 workshop to Tier 2 (ECONOMY §5, PROPOSED 60 gold, 1 round; needs the civilian
 * technology that unlocks Tier 2). The workshop keeps its identity and its slot, and keeps producing
 * at Tier 1 until the upgrade completes (Q-E3 PROPOSED default).
 */
public final class WorkshopUpgrades {

	private WorkshopUpgrades() {
	}

	public enum Blocker {

		NOT_YOUR_TURN,

		NOT_OWNER,

		OCCUPIED,

		BESIEGED,

		/** The nation lacks the civilian technology for the target tier. */
		TECHNOLOGY_REQUIRED,

		/** Only an ACTIVE workshop of the source tier without another project can be upgraded. */
		NOT_ELIGIBLE,

		NOT_AVAILABLE_IN_RULESET,

		IN_ARREARS,

		INSUFFICIENT_GOLD

	}

	/**
	 * @param unlockedTier the highest workshop tier the nation's technology allows
	 * @param upgrading the workshop already has an upgrade running
	 */
	public record Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn,
			boolean besieged, int tier, WorkshopStatus status, boolean upgrading, int unlockedTier, long treasuryMg,
			boolean inArrears) {
	}

	public static List<Blocker> blockers(Request request, EconomyRules rules) {
		EconomyRules.WorkshopUpgrade upgrade = rules.workshopUpgrade();
		if (upgrade == null) {
			return List.of(Blocker.NOT_AVAILABLE_IN_RULESET);
		}
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
		if (request.unlockedTier() < upgrade.toTier()) {
			blockers.add(Blocker.TECHNOLOGY_REQUIRED);
		}
		if (request.status() != WorkshopStatus.ACTIVE || request.tier() != upgrade.fromTier() || request.upgrading()) {
			blockers.add(Blocker.NOT_ELIGIBLE);
		}
		if (request.inArrears()) {
			blockers.add(Blocker.IN_ARREARS);
		}
		if (request.treasuryMg() < upgrade.costMg()) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return List.copyOf(blockers);
	}

}
