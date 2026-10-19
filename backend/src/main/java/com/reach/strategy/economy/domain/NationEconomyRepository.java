package com.reach.strategy.economy.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NationEconomyRepository extends JpaRepository<NationEconomy, UUID> {

	/** Ordered by nation id so round processing is deterministic. */
	List<NationEconomy> findByGameIdOrderByNationId(UUID gameId);

}
