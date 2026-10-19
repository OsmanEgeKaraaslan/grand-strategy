package com.reach.strategy.research.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NationResearchRepository extends JpaRepository<NationResearch, UUID> {

	List<NationResearch> findByGameIdOrderByNationId(UUID gameId);

}
