package com.reach.strategy.research.api;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/**
 * Research commands and read models for the computer opponent (Phase 11). Institution types and
 * tracks are their names (MILITARY_ACADEMY, UNIVERSITY, BARRACKS; MILITARY, CIVILIAN).
 */
public interface ResearchActions {

	ResearchViews.Overview overview(CurrentPlayer me, UUID gameId);

	ResearchViews.CityInstitutions city(CurrentPlayer me, UUID gameId, UUID cityId);

	void buildInstitution(CurrentPlayer me, UUID gameId, UUID cityId, String type, UUID idempotencyKey);

	void unlockTechnology(CurrentPlayer me, UUID gameId, String track, int targetLevel, UUID idempotencyKey);

	void unlockDoctrine(CurrentPlayer me, UUID gameId, String doctrineKey, UUID idempotencyKey);

}
