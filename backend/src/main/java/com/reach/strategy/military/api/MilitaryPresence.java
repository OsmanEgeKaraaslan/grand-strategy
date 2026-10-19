package com.reach.strategy.military.api;

import java.util.List;
import java.util.UUID;

/**
 * Where every battalion of a game stands, from authoritative state — what the siege module uses to
 * decide who besieges whom and how much artillery takes part. Never from client input.
 */
public interface MilitaryPresence {

	/** All battalions of the game in battalion id order. */
	List<Presence> presence(UUID gameId);

	record Presence(UUID battalionId, UUID nationId, UUID provinceStateId, String unitType, int manpower) {

		public boolean artillery() {
			return "ARTILLERY".equals(unitType);
		}

	}

}
