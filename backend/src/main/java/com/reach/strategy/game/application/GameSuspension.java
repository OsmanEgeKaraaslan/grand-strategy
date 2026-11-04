package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.reach.strategy.game.api.ComputerOpponents;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.identity.api.AccountSuspensionParticipant;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.world.api.WorldApi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * A suspended player must not stall anybody's game (Q-MOD1, APPROVED 2026-11-03). Running games: the
 * seat is handed to a new computer account and its nation becomes AI-controlled for the rest of the
 * game (where the ruleset has no computer opponents, the game is abandoned instead). Lobbies: there is
 * no "leave" yet, so the lobby is abandoned (PROPOSED). Games are locked one by one in id order.
 */
@Component
class GameSuspension implements AccountSuspensionParticipant {

	private static final Logger log = LoggerFactory.getLogger(GameSuspension.class);

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final ComputerPlayers computers;
	private final ObjectProvider<ComputerOpponents> computerOpponents;
	private final WorldApi world;
	private final TurnAdvancer advancer;

	GameSuspension(GameRepository games, GamePlayerRepository memberships, ComputerPlayers computers,
			ObjectProvider<ComputerOpponents> computerOpponents, WorldApi world, TurnAdvancer advancer) {
		this.games = games;
		this.memberships = memberships;
		this.computers = computers;
		this.computerOpponents = computerOpponents;
		this.world = world;
		this.advancer = advancer;
	}

	@Override
	public void accountSuspended(UUID playerId, Instant now) {
		List<UUID> gameIds = memberships.findByPlayerId(playerId)
			.stream()
			.map(GamePlayer::gameId)
			.distinct()
			.sorted(Comparator.naturalOrder())
			.toList();
		for (UUID gameId : gameIds) {
			Game game = games.findByIdForUpdate(gameId).orElseThrow();
			if (game.closed()) {
				continue;
			}
			if (game.status() != GameStatus.IN_PROGRESS) {
				game.abandon(now);
				log.info("Lobby {} abandoned: player {} was suspended", gameId, playerId);
				continue;
			}
			ComputerOpponents opponents = computerOpponents.getIfAvailable();
			if (opponents == null || !opponents.enabled(game.rulesetVersion())) {
				game.abandon(now);
				log.info("Game {} abandoned: player {} was suspended and ruleset {} has no computer opponents", gameId,
						playerId, game.rulesetVersion());
				continue;
			}
			GamePlayer seat = memberships.findByGameIdOrderBySeatOrder(gameId)
				.stream()
				.filter(member -> member.playerId().equals(playerId))
				.findFirst()
				.orElseThrow();
			seat.handOverToComputer(computers.create());
			memberships.saveAndFlush(seat);
			world.handOverToComputer(gameId, seat.id());
			game.touch(now);
			log.info("Game {}: seat {} of suspended player {} handed to a computer account", gameId, seat.id(), playerId);
			// With no human left the game may be decided now; otherwise the computer may be on turn.
			if (!advancer.finishIfDecided(game, now)) {
				advancer.passed(game);
			}
		}
	}

}
