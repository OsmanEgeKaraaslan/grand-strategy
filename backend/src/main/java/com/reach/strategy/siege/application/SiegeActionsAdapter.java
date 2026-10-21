package com.reach.strategy.siege.application;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.siege.api.SiegeActions;
import com.reach.strategy.siege.api.SiegeViews;

import org.springframework.stereotype.Component;

@Component
class SiegeActionsAdapter implements SiegeActions {

	private final SiegeService sieges;

	SiegeActionsAdapter(SiegeService sieges) {
		this.sieges = sieges;
	}

	@Override
	public SiegeViews.Overview overview(CurrentPlayer me, UUID gameId) {
		return sieges.overview(me, gameId);
	}

	@Override
	public SiegeViews.ProvinceFortification province(CurrentPlayer me, UUID gameId, String provinceKey) {
		return sieges.province(me, gameId, provinceKey);
	}

	@Override
	public void buildFortress(CurrentPlayer me, UUID gameId, UUID cityId, int targetLevel, UUID idempotencyKey) {
		sieges.upgrade(me, gameId, cityId, targetLevel, idempotencyKey);
	}

	@Override
	public void repairFortress(CurrentPlayer me, UUID gameId, UUID cityId, UUID idempotencyKey) {
		sieges.repair(me, gameId, cityId, idempotencyKey);
	}

}
