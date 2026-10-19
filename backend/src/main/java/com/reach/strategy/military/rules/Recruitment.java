package com.reach.strategy.military.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Whether a nation may recruit a battalion in a city. Shared by the UI (reasons) and the command
 * (rejection), so they never disagree.
 */
public final class Recruitment {

	private Recruitment() {
	}

	public enum Blocker {

		NOT_YOUR_TURN,

		/** Only the legal owner recruits (ECONOMY §8). */
		NOT_OWNER,

		/** The owner does not control the city right now. */
		OCCUPIED,

		/** The city is under an active siege (COMBAT_AND_SIEGE §5.5). */
		BESIEGED,

		/** Q-E1b: no recruitment while in arrears. */
		IN_ARREARS,

		/** The city must keep {@code minResidualPopulation} civilians after giving a battalion. */
		INSUFFICIENT_POPULATION,

		INSUFFICIENT_GOLD

	}

	public record Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, UnitType type,
			int population, long treasuryMg, boolean inArrears, boolean besieged) {

		/** A city that is not besieged. */
		public Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, UnitType type,
				int population, long treasuryMg, boolean inArrears) {
			this(nationId, ownerNationId, controllerNationId, yourTurn, type, population, treasuryMg, inArrears, false);
		}

	}

	public static List<Blocker> blockers(Request request, MilitaryRules rules) {
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
		if (request.inArrears()) {
			blockers.add(Blocker.IN_ARREARS);
		}
		if ((long) request.population() - rules.battalionManpower() < rules.minResidualPopulation()) {
			blockers.add(Blocker.INSUFFICIENT_POPULATION);
		}
		if (request.treasuryMg() < rules.unit(request.type()).recruitCostMg()) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return List.copyOf(blockers);
	}

}
