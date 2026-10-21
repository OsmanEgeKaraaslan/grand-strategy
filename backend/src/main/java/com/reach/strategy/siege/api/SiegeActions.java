package com.reach.strategy.siege.api;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/** Fortification commands and read models for the computer opponent (Phase 11). */
public interface SiegeActions {

	SiegeViews.Overview overview(CurrentPlayer me, UUID gameId);

	SiegeViews.ProvinceFortification province(CurrentPlayer me, UUID gameId, String provinceKey);

	void buildFortress(CurrentPlayer me, UUID gameId, UUID cityId, int targetLevel, UUID idempotencyKey);

	void repairFortress(CurrentPlayer me, UUID gameId, UUID cityId, UUID idempotencyKey);

}
