package com.reach.strategy.economy.application;

import java.util.UUID;

import com.reach.strategy.economy.api.EconomyActions;
import com.reach.strategy.economy.api.EconomyViews;
import com.reach.strategy.identity.api.CurrentPlayer;

import org.springframework.stereotype.Component;

@Component
class EconomyActionsAdapter implements EconomyActions {

	private final EconomyService economy;

	EconomyActionsAdapter(EconomyService economy) {
		this.economy = economy;
	}

	@Override
	public EconomyViews.Overview overview(CurrentPlayer me, UUID gameId) {
		return economy.overview(me, gameId);
	}

	@Override
	public void buildWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, int tier, UUID idempotencyKey) {
		economy.buildWorkshop(me, gameId, cityId, tier, idempotencyKey);
	}

	@Override
	public void upgradeWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, UUID buildingId, UUID idempotencyKey) {
		economy.upgradeWorkshop(me, gameId, cityId, buildingId, idempotencyKey);
	}

	@Override
	public void repairWorkshop(CurrentPlayer me, UUID gameId, UUID cityId, UUID buildingId, UUID idempotencyKey) {
		economy.repairWorkshop(me, gameId, cityId, buildingId, idempotencyKey);
	}

}
