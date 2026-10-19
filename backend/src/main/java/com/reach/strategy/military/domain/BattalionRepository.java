package com.reach.strategy.military.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BattalionRepository extends JpaRepository<Battalion, UUID> {

	List<Battalion> findByGameIdOrderById(UUID gameId);

	List<Battalion> findByGameIdAndProvinceStateIdOrderById(UUID gameId, UUID provinceStateId);

	List<Battalion> findByGameIdAndIdIn(UUID gameId, Collection<UUID> ids);

	List<Battalion> findByGameIdAndArmyId(UUID gameId, UUID armyId);

	long countByGameIdAndArmyId(UUID gameId, UUID armyId);

}
