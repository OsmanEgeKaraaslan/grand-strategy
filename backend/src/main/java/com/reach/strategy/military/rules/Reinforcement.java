package com.reach.strategy.military.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Refilling a damaged battalion from the civilians of the city where it stands (rules accepted
 * for implementation 2026-10-19, Q-R1 option A). Shared by the UI quote and the command, so they
 * never disagree.
 */
public final class Reinforcement {

	private Reinforcement() {
	}

	public enum Blocker {

		NOT_YOUR_TURN,

		AT_FULL_STRENGTH,

		/** More soldiers requested than the battalion lacks. */
		EXCEEDS_MAXIMUM_MANPOWER,

		/** This battalion already received the per-turn maximum. */
		TURN_LIMIT_REACHED,

		/** The battalion's province city is not legally owned by its nation. */
		NOT_OWNED_CITY,

		/** Owned, but controlled by another nation. */
		OCCUPIED,

		/** The city is under an active siege. */
		BESIEGED,

		IN_ARREARS,

		/** The city must keep {@code minResidualPopulation} civilians, as for recruitment. */
		INSUFFICIENT_POPULATION,

		INSUFFICIENT_GOLD

	}

	/**
	 * @param reinforcedThisTurn soldiers this battalion already received during the current player turn
	 * @param population civilians of the city in the battalion's province
	 */
	public record Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, UnitType type,
			int manpower, int maxManpower, int reinforcedThisTurn, int population, long treasuryMg, boolean inArrears,
			boolean besieged) {

		/** A city that is not besieged. */
		public Request(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, UnitType type,
				int manpower, int maxManpower, int reinforcedThisTurn, int population, long treasuryMg,
				boolean inArrears) {
			this(nationId, ownerNationId, controllerNationId, yourTurn, type, manpower, maxManpower, reinforcedThisTurn,
					population, treasuryMg, inArrears, false);
		}

	}

	/**
	 * @param missing soldiers the battalion lacks
	 * @param maxSoldiers the most it can receive right now (0 if blocked)
	 * @param costOfMaxMg price of {@code maxSoldiers}
	 * @param blockers why nothing can be added now; empty if {@code maxSoldiers > 0}
	 */
	public record Quote(int missing, int turnAllowance, int maxSoldiers, long costOfMaxMg, List<Blocker> blockers) {
	}

	/**
	 * Gold for {@code soldiers} replacements: the unit's recruitment cost pro rata, rounded up
	 * (PROPOSED, docs/ECONOMY.md §8a) so splitting a reinforcement can never be cheaper. With the
	 * current rulesets the division is exact (10 / 25 / 40 mg per soldier).
	 */
	public static long cost(UnitType type, int soldiers, MilitaryRules rules) {
		long numerator = Math.multiplyExact(rules.unit(type).recruitCostMg(), (long) soldiers);
		return Math.ceilDiv(numerator, (long) rules.battalionManpower());
	}

	public static Quote quote(Request request, MilitaryRules rules) {
		int missing = request.maxManpower() - request.manpower();
		int allowance = Math.max(0, rules.reinforcementPerTurn() - request.reinforcedThisTurn());
		int byPopulation = Math.max(0, request.population() - rules.minResidualPopulation());
		long perThousand = rules.unit(request.type()).recruitCostMg();
		int byGold = perThousand == 0 ? Integer.MAX_VALUE
				: (int) Math.min(Integer.MAX_VALUE, request.treasuryMg() * rules.battalionManpower() / perThousand);
		int max = Math.min(Math.min(missing, allowance), Math.min(byPopulation, byGold));

		List<Blocker> blockers = new ArrayList<>();
		if (!request.yourTurn()) {
			blockers.add(Blocker.NOT_YOUR_TURN);
		}
		if (missing == 0) {
			blockers.add(Blocker.AT_FULL_STRENGTH);
		}
		else if (allowance == 0) {
			blockers.add(Blocker.TURN_LIMIT_REACHED);
		}
		if (!request.nationId().equals(request.ownerNationId())) {
			blockers.add(Blocker.NOT_OWNED_CITY);
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
		if (missing > 0 && allowance > 0) {
			if (byPopulation == 0) {
				blockers.add(Blocker.INSUFFICIENT_POPULATION);
			}
			if (byGold == 0) {
				blockers.add(Blocker.INSUFFICIENT_GOLD);
			}
		}
		if (!blockers.isEmpty()) {
			max = 0;
		}
		return new Quote(missing, allowance, max, max == 0 ? 0 : cost(request.type(), max, rules), List.copyOf(blockers));
	}

	/**
	 * Why exactly {@code soldiers} replacements cannot be added; empty when they can.
	 */
	public static List<Blocker> blockers(Request request, int soldiers, MilitaryRules rules) {
		Quote quote = quote(request, rules);
		List<Blocker> blockers = new ArrayList<>(quote.blockers());
		if (!blockers.isEmpty()) {
			return List.copyOf(blockers);
		}
		if (soldiers > quote.missing()) {
			blockers.add(Blocker.EXCEEDS_MAXIMUM_MANPOWER);
		}
		else if (soldiers > quote.turnAllowance()) {
			blockers.add(Blocker.TURN_LIMIT_REACHED);
		}
		if ((long) request.population() - soldiers < rules.minResidualPopulation()) {
			blockers.add(Blocker.INSUFFICIENT_POPULATION);
		}
		if (request.treasuryMg() < cost(request.type(), soldiers, rules)) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return List.copyOf(blockers);
	}

}
