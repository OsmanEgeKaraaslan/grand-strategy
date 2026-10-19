package com.reach.strategy.economy.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.economy.rules.WorkshopStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, UUID> {

	List<Building> findByGameIdOrderById(UUID gameId);

	Optional<Building> findByGameIdAndId(UUID gameId, UUID id);

	long countByGameIdAndCityId(UUID gameId, UUID cityId);

	List<Building> findByGameIdAndStatusAndCompletesAfterRoundLessThanEqualOrderById(UUID gameId, WorkshopStatus status,
			int round);

	List<Building> findByGameIdAndStatusAndRepairCompletesAfterRoundLessThanEqualOrderById(UUID gameId,
			WorkshopStatus status, int round);

	List<Building> findByGameIdAndCityIdOrderById(UUID gameId, UUID cityId);

	List<Building> findByGameIdAndUpgradeCompletesAfterRoundLessThanEqualOrderById(UUID gameId, int round);

}
