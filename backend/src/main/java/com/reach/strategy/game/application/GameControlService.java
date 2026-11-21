package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.UUID;

import com.reach.strategy.game.api.GameControl;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;

import com.reach.strategy.platform.read.ReadScope;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pausing for peace conferences, eliminating players and ending games early (Phase 10). Called from
 * inside other modules' command transactions, where the command pipeline already holds the game row
 * lock; the locked entity is the one the persistence context returns here.
 */
@Service
class GameControlService implements GameControl {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final TurnAdvancer advancer;

	GameControlService(GameRepository games, GamePlayerRepository memberships, TurnAdvancer advancer) {
		this.games = games;
		this.memberships = memberships;
		this.advancer = advancer;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void pauseForConference(UUID gameId, UUID conferenceId, Instant now) {
		ReadScope.forget(GameAccessService.MEMBERSHIP);
		game(gameId).pause(conferenceId, now);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void resumeAfterConference(UUID gameId, UUID conferenceId, Instant now) {
		ReadScope.forget(GameAccessService.MEMBERSHIP);
		Game game = game(gameId);
		if (game.finished()) {
			return;
		}
		game.resume(conferenceId, now);
		advancer.passed(game);
		// The active player may have been eliminated by the treaty: the turn passes on.
		if (advancer.skippedSeats(game).contains(game.activeSeat())) {
			advancer.advance(game, now);
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void eliminatePlayer(UUID gameId, UUID gamePlayerId, int round) {
		ReadScope.forget(GameAccessService.MEMBERSHIP);
		GamePlayer player = memberships.findByGameIdOrderBySeatOrder(gameId)
			.stream()
			.filter(member -> member.id().equals(gamePlayerId))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("No player " + gamePlayerId + " in game " + gameId));
		player.eliminate(round);
		memberships.saveAndFlush(player);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean finishIfDecided(UUID gameId, Instant now) {
		ReadScope.forget(GameAccessService.MEMBERSHIP);
		return advancer.finishIfDecided(game(gameId), now);
	}

	private Game game(UUID gameId) {
		return games.findById(gameId).orElseThrow(() -> new IllegalStateException("Unknown game " + gameId));
	}

}
