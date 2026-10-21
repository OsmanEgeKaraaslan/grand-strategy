package com.reach.strategy.military.api;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;

/**
 * Military commands and read models for callers outside the web layer — the computer opponent
 * (Phase 11). Unit types are their names (INFANTRY, CAVALRY, ARTILLERY).
 */
public interface MilitaryActions {

	MilitaryViews.Overview overview(CurrentPlayer me, UUID gameId);

	MilitaryViews.BattalionDetails details(CurrentPlayer me, UUID gameId, UUID battalionId);

	void recruit(CurrentPlayer me, UUID gameId, UUID cityId, String unitType, UUID idempotencyKey);

	MilitaryViews.MoveResult move(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, String toProvinceKey,
			UUID idempotencyKey);

	void reinforce(CurrentPlayer me, UUID gameId, UUID battalionId, int soldiers, UUID idempotencyKey);

	void attach(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, UUID commanderNationId, UUID idempotencyKey);

	void release(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, UUID idempotencyKey);

}
