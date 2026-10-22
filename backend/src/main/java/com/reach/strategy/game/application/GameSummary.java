package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameStatus;

/**
 * One row of "my games" (Phase 13A: enough to resume a game from the dashboard). Deliberately
 * without the invitation code.
 *
 * @param yourNation the caller's faction, null before choosing one
 * @param yourTurn the game runs, is not paused and the caller's seat is on turn
 * @param humanPlayers seated human players
 * @param computerPlayers computer seats (added at the start)
 */
public record GameSummary(UUID id, String name, GameStatus status, int playerCount, int maxPlayers, int yourSeat,
		boolean youAreHost, Instant createdAt, String mapName, Nation yourNation, Integer currentRound, boolean yourTurn,
		boolean paused, boolean youAreEliminated, int humanPlayers, int computerPlayers, Instant lastActivityAt) {

	public record Nation(String key, String name, String color) {
	}

	static GameSummary of(Game game, int mySeat, UUID myPlayerId, String mapName, Nation yourNation, boolean eliminated,
			int humans, int computers) {
		boolean running = game.status() == GameStatus.IN_PROGRESS;
		boolean yourTurn = running && !game.paused() && !eliminated && game.activeSeat() != null && game.activeSeat() == mySeat;
		return new GameSummary(game.id(), game.name(), game.status(), game.playerCount(), game.maxPlayers(), mySeat,
				game.hostPlayerId().equals(myPlayerId), game.createdAt(), mapName, yourNation, game.currentRound(), yourTurn,
				game.paused(), eliminated, humans, computers, game.updatedAt());
	}

}
