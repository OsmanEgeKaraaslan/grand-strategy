package com.reach.strategy.military.application;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryActions;
import com.reach.strategy.military.api.MilitaryViews;
import com.reach.strategy.military.rules.UnitType;

import org.springframework.stereotype.Component;

@Component
class MilitaryActionsAdapter implements MilitaryActions {

	private final MilitaryService military;
	private final BattalionService battalions;
	private final AttachmentService attachments;

	MilitaryActionsAdapter(MilitaryService military, BattalionService battalions, AttachmentService attachments) {
		this.military = military;
		this.battalions = battalions;
		this.attachments = attachments;
	}

	@Override
	public MilitaryViews.Overview overview(CurrentPlayer me, UUID gameId) {
		return military.overview(me, gameId);
	}

	@Override
	public MilitaryViews.BattalionDetails details(CurrentPlayer me, UUID gameId, UUID battalionId) {
		return battalions.details(me, gameId, battalionId);
	}

	@Override
	public void recruit(CurrentPlayer me, UUID gameId, UUID cityId, String unitType, UUID idempotencyKey) {
		military.recruit(me, gameId, cityId, UnitType.valueOf(unitType), idempotencyKey);
	}

	@Override
	public MilitaryViews.MoveResult move(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, String toProvinceKey,
			UUID idempotencyKey) {
		return military.move(me, gameId, battalionIds, toProvinceKey, idempotencyKey);
	}

	@Override
	public void reinforce(CurrentPlayer me, UUID gameId, UUID battalionId, int soldiers, UUID idempotencyKey) {
		battalions.reinforce(me, gameId, battalionId, soldiers, idempotencyKey);
	}

	@Override
	public void attach(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, UUID commanderNationId,
			UUID idempotencyKey) {
		attachments.attach(me, gameId, battalionIds, commanderNationId, idempotencyKey);
	}

	@Override
	public void release(CurrentPlayer me, UUID gameId, List<UUID> battalionIds, UUID idempotencyKey) {
		attachments.detach(me, gameId, battalionIds, idempotencyKey);
	}

}
