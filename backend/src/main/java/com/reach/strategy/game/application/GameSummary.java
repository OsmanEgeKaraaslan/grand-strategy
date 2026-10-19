package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameStatus;

/** One row of "my games". Deliberately without the invitation code. */
public record GameSummary(UUID id, String name, GameStatus status, int playerCount, int maxPlayers, int yourSeat,
		boolean youAreHost, Instant createdAt) {

	static GameSummary of(Game game, int mySeat, UUID myPlayerId) {
		return new GameSummary(game.id(), game.name(), game.status(), game.playerCount(), game.maxPlayers(), mySeat,
				game.hostPlayerId().equals(myPlayerId), game.createdAt());
	}

}
