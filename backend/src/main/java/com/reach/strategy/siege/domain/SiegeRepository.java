package com.reach.strategy.siege.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SiegeRepository extends JpaRepository<Siege, UUID> {

	List<Siege> findByGameIdAndStatusOrderById(UUID gameId, Siege.Status status);

	Optional<Siege> findByGameIdAndProvinceStateIdAndStatus(UUID gameId, UUID provinceStateId, Siege.Status status);

	Optional<Siege> findByGameIdAndId(UUID gameId, UUID id);

	boolean existsByGameIdAndCityIdAndStatus(UUID gameId, UUID cityId, Siege.Status status);

	List<Siege> findTop20ByGameIdAndStatusNotOrderByEndedRoundDescIdAsc(UUID gameId, Siege.Status status);

}
