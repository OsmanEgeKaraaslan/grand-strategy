package com.reach.strategy.military.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BattalionCohortRepository extends JpaRepository<BattalionCohort, UUID> {

	List<BattalionCohort> findByGameIdAndBattalionIdOrderByOriginCityId(UUID gameId, UUID battalionId);

	List<BattalionCohort> findByGameIdAndBattalionIdIn(UUID gameId, Collection<UUID> battalionIds);

	List<BattalionCohort> findByGameId(UUID gameId);

}
