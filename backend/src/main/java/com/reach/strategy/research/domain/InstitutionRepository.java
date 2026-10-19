package com.reach.strategy.research.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.research.rules.InstitutionType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstitutionRepository extends JpaRepository<Institution, UUID> {

	List<Institution> findByGameIdOrderById(UUID gameId);

	List<Institution> findByGameIdAndCityIdOrderByType(UUID gameId, UUID cityId);

	Optional<Institution> findByGameIdAndCityIdAndType(UUID gameId, UUID cityId, InstitutionType type);

	Optional<Institution> findByGameIdAndId(UUID gameId, UUID id);

	List<Institution> findByGameIdAndStatusAndCompletesAfterRoundLessThanEqualOrderById(UUID gameId,
			Institution.Status status, int round);

}
