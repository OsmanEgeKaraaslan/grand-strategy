package com.reach.strategy.game.application;

import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.platform.web.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A server-wide bound on unfinished games (lobbies and running games), so that many accounts cannot
 * together create more games than one server can run — every Quick Match brings eight computer
 * nations whose turns share the worker threads (docs/SECURITY_AUDIT.md §7, P1). The per-account bound
 * is {@code strategy.game.max-unfinished-games}; finished games never count.
 */
@Component
public class GameCapacity {

	private final GameRepository games;
	private final long maxUnfinishedGames;

	GameCapacity(GameRepository games, @Value("${strategy.game.max-unfinished-games-total:200}") long maxUnfinishedGames) {
		this.games = games;
		this.maxUnfinishedGames = maxUnfinishedGames;
	}

	public boolean available() {
		return games.countUnfinished() < maxUnfinishedGames;
	}

	/** Refuses a new game while the server is full; 503 so clients know to try again later. */
	public void require() {
		if (!available()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_CAPACITY_REACHED",
					"The server is running as many games as it can right now. Please try again later.");
		}
	}

}
