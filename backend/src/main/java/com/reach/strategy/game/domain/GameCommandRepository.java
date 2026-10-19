package com.reach.strategy.game.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GameCommandRepository extends JpaRepository<GameCommand, UUID> {

	Optional<GameCommand> findByGameIdAndGamePlayerIdAndIdempotencyKey(UUID gameId, UUID gamePlayerId,
			UUID idempotencyKey);

}
