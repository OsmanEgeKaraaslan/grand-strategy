package com.reach.strategy.game.application;

import java.util.UUID;

import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.identity.api.CurrentPlayer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GameAccessService implements GameAccess {

	private final GameRepository games;
	private final GamePlayerRepository memberships;

	GameAccessService(GameRepository games, GamePlayerRepository memberships) {
		this.games = games;
		this.memberships = memberships;
	}

	@Override
	@Transactional(readOnly = true)
	public Membership membership(CurrentPlayer me, UUID gameId) {
		Game game = games.findById(gameId).orElseThrow(GameErrors::gameNotFound);
		GamePlayer mine = memberships.findByGameIdOrderBySeatOrder(gameId)
			.stream()
			.filter(member -> member.playerId().equals(me.id()))
			.findFirst()
			.orElseThrow(GameErrors::gameNotFound);
		boolean inProgress = game.status() == GameStatus.IN_PROGRESS;
		// An abandoned game that had started is read-only like a finished one (Q-MOD1).
		boolean finished = game.finished() || (game.closed() && game.startedAt() != null);
		// Nobody has a normal turn while a peace conference pauses play or once eliminated.
		boolean yourTurn = inProgress && !game.paused() && !mine.eliminated() && game.activeSeat() == mine.seatOrder();
		return new Membership(game.id(), mine.id(), mine.seatOrder(), inProgress, yourTurn,
				inProgress || finished ? game.currentRound() : 0, game.turnNumber(), game.rulesetVersion(),
				game.stateVersion(), finished, game.paused(), mine.eliminated());
	}

}
