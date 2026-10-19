package com.reach.strategy.siege.rules;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The single occupation policy (replaces the Phase 5 rule for fortified provinces, Q-M7):
 * <ul>
 * <li>battalions of nations at war never share a province — field combat resolves that first;
 * allied or neutral battalions may (Phase 9) and change nothing here;</li>
 * <li>an unfortified province where exactly one nation at war with the controller stands,
 * unopposed, passes to that nation's control at round end (simple occupation, Q-M2);</li>
 * <li>a fortified province (effective fortress level ≥ 1) needs a successful siege instead;</li>
 * <li>an active siege lasts while the besieger has battalions there and is still at war with the
 * controller, otherwise it is lifted (e.g. by a peace treaty).</li>
 * </ul>
 * Legal ownership is never changed here.
 */
public final class ControlDecision {

	private ControlDecision() {
	}

	public sealed interface Action permits None, Occupy, StartSiege, ContinueSiege, LiftSiege {
	}

	public record None() implements Action {
	}

	/** Simple occupation of an unfortified province (round end only). */
	public record Occupy(UUID nationId) implements Action {
	}

	public record StartSiege(UUID besiegerNationId) implements Action {
	}

	public record ContinueSiege() implements Action {
	}

	/** The besiegers are gone (withdrew, destroyed or beaten by a relief army) or made peace. */
	public record LiftSiege() implements Action {
	}

	/** The pre-diplomacy rule: every pair of different nations is at war (rulesets before v5). */
	public static Action decide(UUID controllerNationId, Set<UUID> nationsPresent, int effectiveFortressLevel,
			UUID activeBesieger) {
		return decide(controllerNationId, nationsPresent, effectiveFortressLevel, activeBesieger,
				(x, y) -> !x.equals(y));
	}

	/**
	 * @param activeBesieger besieging nation of the province's active siege, or null
	 * @param atWar whether two nations are at war now
	 */
	public static Action decide(UUID controllerNationId, Set<UUID> nationsPresent, int effectiveFortressLevel,
			UUID activeBesieger, BiPredicate<UUID, UUID> atWar) {
		if (activeBesieger != null) {
			boolean holds = nationsPresent.contains(activeBesieger) && atWar.test(activeBesieger, controllerNationId)
					&& nationsPresent.stream().noneMatch(n -> atWar.test(n, activeBesieger));
			return holds ? new ContinueSiege() : new LiftSiege();
		}
		List<UUID> hostile = nationsPresent.stream().filter(n -> atWar.test(n, controllerNationId)).toList();
		if (hostile.size() != 1) {
			return new None();
		}
		UUID present = hostile.getFirst();
		if (nationsPresent.stream().anyMatch(n -> atWar.test(n, present))) {
			// Defenders (or their co-belligerents) still stand here.
			return new None();
		}
		return effectiveFortressLevel >= 1 ? new StartSiege(present) : new Occupy(present);
	}

}
