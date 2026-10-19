package com.reach.strategy.economy.api;

import java.util.List;
import java.util.UUID;

/** Workshop access for siege bombardment (COMBAT_AND_SIEGE §5.6). */
public interface WorkshopsApi {

	/** Workshops of a city in building id order. */
	List<WorkshopInfo> workshops(UUID gameId, UUID cityId);

	/**
	 * Marks an ACTIVE workshop DAMAGED inside the caller's round transaction. The record stays;
	 * nobody is killed — its workers simply farm until it is repaired.
	 */
	void damage(UUID gameId, UUID buildingId);

	/** @param status the economy's workshop status name (ACTIVE, DAMAGED, UNDER_REPAIR, UNDER_CONSTRUCTION) */
	record WorkshopInfo(UUID buildingId, int tier, String status) {
	}

}
