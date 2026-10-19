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

	record Withdrawn(UUID battalionId, UUID nationId, String fromProvinceKey, String toProvinceKey) {
	}

}
