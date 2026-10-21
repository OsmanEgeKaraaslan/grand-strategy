package com.reach.strategy.research.application;

import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.research.api.ResearchActions;
import com.reach.strategy.research.api.ResearchViews;
import com.reach.strategy.research.rules.InstitutionType;
import com.reach.strategy.research.rules.ResearchRules;

import org.springframework.stereotype.Component;

@Component
class ResearchActionsAdapter implements ResearchActions {

	private final ResearchService research;

	ResearchActionsAdapter(ResearchService research) {
		this.research = research;
	}

	@Override
	public ResearchViews.Overview overview(CurrentPlayer me, UUID gameId) {
		return research.overview(me, gameId);
	}

	@Override
	public ResearchViews.CityInstitutions city(CurrentPlayer me, UUID gameId, UUID cityId) {
		return research.city(me, gameId, cityId);
	}

	@Override
	public void buildInstitution(CurrentPlayer me, UUID gameId, UUID cityId, String type, UUID idempotencyKey) {
		research.buildInstitution(me, gameId, cityId, InstitutionType.valueOf(type), idempotencyKey);
	}

	@Override
	public void unlockTechnology(CurrentPlayer me, UUID gameId, String track, int targetLevel, UUID idempotencyKey) {
		research.unlockTechnology(me, gameId, ResearchRules.Track.valueOf(track), targetLevel, idempotencyKey);
	}

	@Override
	public void unlockDoctrine(CurrentPlayer me, UUID gameId, String doctrineKey, UUID idempotencyKey) {
		research.unlockDoctrine(me, gameId, doctrineKey, idempotencyKey);
	}

}
