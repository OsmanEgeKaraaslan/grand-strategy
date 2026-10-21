package com.reach.strategy.economy.api;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/**
 * The economy's commands and read model for callers outside its web layer — the computer opponent
 * (Phase 11). Same services, same rules, same command pipeline as the REST endpoints.
 */
public interface EconomyActions {

	EconomyViews.Overview overview(CurrentPlayer me, UUID gameId);

	void buildWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, int tier, UUID idempotencyKey);

	void upgradeWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, UUID buildingId, UUID idempotencyKey);

	void repairWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, UUID buildingId, UUID idempotencyKey);

}
