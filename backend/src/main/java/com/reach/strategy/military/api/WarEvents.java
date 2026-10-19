package com.reach.strategy.military.api;

import java.util.UUID;

/**
 * Facts of the fighting that count towards war score — implemented by the diplomacy module. The
 * military and siege modules report each battle and each successful siege once, inside the
 * transaction that produced it; the implementation records it at most once per source id.
 * Without an implementation nothing is recorded.
 */
public interface WarEvents {

	void battleFought(UUID gameId, Battle battle);

	void siegeWon(UUID gameId, SiegeWon siege);

	/**
	 * @param attackerWon whether the attacker won the field
	 * @param attackerLosses soldiers the attacker lost
	 * @param defenderLosses soldiers the defender lost
	 */
	record Battle(UUID battleId, int round, int turnNumber, UUID attackerNationId, UUID defenderNationId,
			boolean attackerWon, int attackerLosses, int defenderLosses) {
	}

	record SiegeWon(UUID siegeId, int round, UUID besiegerNationId, UUID defenderNationId, UUID cityId) {
	}

}
