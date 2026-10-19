package com.reach.strategy.military.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArmyRepository extends JpaRepository<Army, UUID> {

	List<Army> findByGameIdOrderById(UUID gameId);

	Optional<Army> findByGameIdAndId(UUID gameId, UUID id);

}
