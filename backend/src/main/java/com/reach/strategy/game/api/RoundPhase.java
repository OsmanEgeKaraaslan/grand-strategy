package com.reach.strategy.game.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of global round processing (docs/TURN_SYSTEM.md §5). Feature modules implement it;
 * the game module runs every phase inside the transaction of the final end-turn, ordered by
 * {@link Step} — the single, explicit definition of the round-end order (never bean ordering).
 * A phase that throws rolls back the whole end of turn, including every other phase's writes.
 */
public interface RoundPhase {

	Step step();

	void apply(RoundContext context);

	/**
	 * Canonical round-end order. Each step has at most one implementation; steps without one are
	 * skipped. Later phases slot in without reordering existing ones.
	 */
	enum Step {

		/**
		 * Control: simple occupation of unfortified provinces (Phase 5) and sieges — attrition,
		 * progress, capture and bombardment (Phase 7). Before income, so income uses the controller
		 * and population as they stand after this round's fighting.
		 */
		SIEGES,

		/** Income, upkeep and treasury settlement incl. arrears (Phase 4). */
		ECONOMY,

		/**
		 * Treaty tribute (Phase 9): a share of this round's gross income, paid from the treasury after
		 * its settlement, so tribute never creates arrears and never more than the treasury holds.
		 */
		TRIBUTE,

		/**
		 * Research points from institutions ACTIVE before this round's completions (Phase 8): an
		 * institution finishing this round produces from the next one.
		 */
		RESEARCH,

		/** Civilian growth after income: new people first earn in the next round (Phase 8). */
		GROWTH,

		/** Workshop construction, repair and upgrade completions — after income (Q-E8, Phases 4, 7, 8). */
		CONSTRUCTION,

		/** Fortress construction, upgrade and repair completions — after income and sieges (Q-E8, Phase 7). */
		FORTIFICATIONS,

		/** Research institution completions — after research, so they produce from the next round (Phase 8). */
		INSTITUTIONS,

		/** Expiry of unanswered alliance and peace proposals (Phase 9) — last, after every other change. */
		DIPLOMACY

	}

	/**
	 * @param round the round being completed
	 * @param rulesetVersion the game's ruleset (balance values are looked up by it)
	 */
	record RoundContext(UUID gameId, int round, String rulesetVersion, Instant now) {
	}

}
