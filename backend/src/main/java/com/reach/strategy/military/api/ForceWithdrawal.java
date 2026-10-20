package com.reach.strategy.military.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Moves battalions out of territory their nation may no longer enter (after a peace treaty or the
 * end of an alliance) — implemented by the military module, called by the diplomacy module inside
 * its command transaction after the diplomatic change is written.
 */
public interface ForceWithdrawal {

	/**
	 * Every battalion standing where {@link Hostility.Relations#mayEnter} no longer allows goes to
	 * the nearest province its nation controls without enemy battalions (fewest borders, then the
	 * lowest province key). Battalions are moved, never deleted or split; cohorts and movement
	 * state are kept. Sieges in every province are then re-judged.
	 *
	 * @return what moved, in battalion id order
	 */
	List<Withdrawn> withdrawWithoutAccess(UUID gameId, int round, int turnNumber, Instant now);

	/**
	 * Returns battalions to their owner's command where the owner and the commander may no longer
	 * command each other (alliance ended, peace, separate peace, elimination). Phase 10.
	 *
	 * @return the number of battalions detached
	 */
	int endInvalidAttachments(UUID gameId);

	/**
	 * Returns every battalion the nation lent or commands to its owner (the nation left a war by
	 * separate peace, so none of its soldiers may fight there any more). Phase 10.
	 *
	 * @return the number of battalions detached
	 */
	int endAttachmentsOf(UUID gameId, UUID nationId);

	/**
	 * Dissolves every battalion of an eliminated nation (Phase 10): its soldiers return as civilians to
	 * their cohorts' origin cities, whoever owns them now, so no person is lost or created; its armies
	 * are deleted and battalions attached to it go back to their owners. Battle reports keep the nation.
	 *
	 * @return the soldiers returned to cities
	 */
	int dissolveNation(UUID gameId, UUID nationId);

	record Withdrawn(UUID battalionId, UUID nationId, String fromProvinceKey, String toProvinceKey) {
	}

}
