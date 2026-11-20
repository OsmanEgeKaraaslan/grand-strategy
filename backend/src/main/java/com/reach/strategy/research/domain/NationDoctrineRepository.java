package com.reach.strategy.research.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NationDoctrineRepository extends JpaRepository<NationDoctrine, UUID> {

	List<NationDoctrine> findByGameIdAndNationIdOrderByDoctrineKey(UUID gameId, UUID nationId);

	List<NationDoctrine> findByGameIdOrderByNationIdAscDoctrineKeyAsc(UUID gameId);

}
