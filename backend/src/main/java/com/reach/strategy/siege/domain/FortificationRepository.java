package com.reach.strategy.siege.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FortificationRepository extends JpaRepository<Fortification, UUID> {

	List<Fortification> findByGameIdOrderById(UUID gameId);

	Optional<Fortification> findByGameIdAndCityId(UUID gameId, UUID cityId);

	List<Fortification> findByGameIdAndProjectCompletesAfterRoundLessThanEqualOrderById(UUID gameId, int round);

}
