package com.reach.strategy.economy.application;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.economy.api.WorkshopsApi;
import com.reach.strategy.economy.domain.Building;
import com.reach.strategy.economy.domain.BuildingRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Workshop reads and bombardment damage for the siege module. */
@Service
class WorkshopAccess implements WorkshopsApi {

	private final BuildingRepository buildings;

	WorkshopAccess(BuildingRepository buildings) {
		this.buildings = buildings;
	}

	@Override
	@Transactional(readOnly = true)
	public List<WorkshopInfo> workshops(UUID gameId, UUID cityId) {
		return buildings.findByGameIdAndCityIdOrderById(gameId, cityId)
			.stream()
			.map(b -> new WorkshopInfo(b.id(), b.tier(), b.status().name()))
			.toList();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void damage(UUID gameId, UUID buildingId) {
		Building building = buildings.findByGameIdAndId(gameId, buildingId)
			.orElseThrow(() -> new IllegalStateException("Building " + buildingId + " is not in game " + gameId));
		building.damage();
		buildings.saveAndFlush(building);
	}

}
