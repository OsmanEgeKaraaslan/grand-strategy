package com.reach.strategy.game.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GamePlayerRepository extends JpaRepository<GamePlayer, UUID> {

	boolean existsByGameIdAndPlayerId(UUID gameId, UUID playerId);

	List<GamePlayer> findByGameIdOrderBySeatOrder(UUID gameId);

	List<GamePlayer> findByPlayerId(UUID playerId);

	List<GamePlayer> findByGameIdIn(java.util.Collection<UUID> gameIds);

}
