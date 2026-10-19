package com.reach.strategy.siege.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fortress construction, upgrades and repairs (PROPOSED, ECONOMY §5): who may order them, what
 * they cost and when they finish. Shared by the UI quote and the command.
 */
public final class FortressWorks {

	private FortressWorks() {
	}

	public enum Status {

		ACTIVE,

		/** Bombarded: effective level 0 (Q-S4) until repaired. */
		DAMAGED

	}

	public enum Project {

		/** Building level 1 or raising to the next level. */
		UPGRADE,

		REPAIR

	}

	/** Effective level for sieges: a damaged fortress offers no resistance (Q-S4). */
	public static int effectiveLevel(int level, Status status) {
		return status == Status.ACTIVE ? level : 0;
	}

	public enum Blocker {

		NOT_YOUR_TURN,

		NOT_OWNER,

		OCCUPIED,

		BESIEGED,

		/** Another fortress project is already running in this city. */
		PROJECT_IN_PROGRESS,

		/** Upgrade: the fortress must be repaired first. Repair: nothing to repair. */
		FORTRESS_DAMAGED,

		NOT_DAMAGED,

		MAX_LEVEL_REACHED,

		/** The game's ruleset has no fortifications (games created before Phase 7). */
		NOT_AVAILABLE_IN_RULESET,

		IN_ARREARS,

		INSUFFICIENT_GOLD

	}

	/**
	 * @param currentLevel 0 when the city has no fortress yet
	 * @param status null when the city has no fortress
	 * @param project the running project, or null
	 */
	public record State(UUID nationId, UUID ownerNationId, UUID controllerNationId, boolean yourTurn, boolean besieged,
			int currentLevel, Status status, Project project, long treasuryMg, boolean inArrears) {
	}

	/** @param targetLevel the level an upgrade produces; costMg what it would cost now */
	public record Quote(int targetLevel, long costMg, int durationRounds, boolean allowed, List<Blocker> blockers) {
	}

	public static Quote upgrade(State state, SiegeRules rules) {
		List<Blocker> blockers = common(state);
		int target = state.currentLevel() + 1;
		if (state.status() == Status.DAMAGED) {
			blockers.add(Blocker.FORTRESS_DAMAGED);
		}
		if (target > rules.maxLevel()) {
			blockers.add(Blocker.MAX_LEVEL_REACHED);
			return new Quote(state.currentLevel(), 0, 0, false, List.copyOf(blockers));
		}
		SiegeRules.FortressLevel level = rules.level(target);
		if (state.treasuryMg() < level.buildCostMg()) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return new Quote(target, level.buildCostMg(), level.buildDurationRounds(), blockers.isEmpty(),
				List.copyOf(blockers));
	}

	public static Quote repair(State state, SiegeRules rules) {
		List<Blocker> blockers = common(state);
		if (state.status() != Status.DAMAGED) {
			blockers.add(Blocker.NOT_DAMAGED);
			return new Quote(state.currentLevel(), 0, 0, false, List.copyOf(blockers));
		}
		long cost = SiegeMath.repairCost(rules.level(state.currentLevel()).buildCostMg(), rules.repair());
		if (state.treasuryMg() < cost) {
			blockers.add(Blocker.INSUFFICIENT_GOLD);
		}
		return new Quote(state.currentLevel(), cost, rules.repair().durationRounds(), blockers.isEmpty(),
				List.copyOf(blockers));
	}

	private static List<Blocker> common(State state) {
		List<Blocker> blockers = new ArrayList<>();
		if (!state.yourTurn()) {
			blockers.add(Blocker.NOT_YOUR_TURN);
		}
		if (!state.nationId().equals(state.ownerNationId())) {
			blockers.add(Blocker.NOT_OWNER);
		}
		else if (!state.nationId().equals(state.controllerNationId())) {
			blockers.add(Blocker.OCCUPIED);
		}
		if (state.besieged()) {
			blockers.add(Blocker.BESIEGED);
		}
		if (state.project() != null) {
			blockers.add(Blocker.PROJECT_IN_PROGRESS);
		}
		if (state.inArrears()) {
			blockers.add(Blocker.IN_ARREARS);
		}
		return blockers;
	}

}
