package com.reach.strategy.game.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.reach.strategy.game.api.GameCommands;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameCommand;
import com.reach.strategy.game.domain.GameCommandRepository;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;

import com.reach.strategy.platform.read.ReadScope;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gameplay command pipeline for commands owned by other modules (e.g. construction). End turn
 * has its own service because it also checks {@code expectedTurnNumber} and advances the turn.
 */
@Service
class GameCommandService implements GameCommands {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final GameCommandRepository commands;
	private final Clock clock;

	GameCommandService(GameRepository games, GamePlayerRepository memberships, GameCommandRepository commands,
			Clock clock) {
		this.games = games;
		this.memberships = memberships;
		this.commands = commands;
		this.clock = clock;
	}

	@Override
	@Transactional
	public <T> T execute(CurrentPlayer me, UUID gameId, UUID idempotencyKey, String commandType, Handler<T> handler) {
		return run(me, gameId, idempotencyKey, commandType, handler, false);
	}

	@Override
	@Transactional
	public <T> T executeOutOfTurn(CurrentPlayer me, UUID gameId, UUID idempotencyKey, String commandType,
			Handler<T> handler) {
		return run(me, gameId, idempotencyKey, commandType, handler, true);
	}

	/**
	 * Every command runs in a writing read scope (ENGINE_DESIGN S6): state it reads twice (the world
	 * snapshot, the relations, a module's values) is loaded once, and every writer keeps the memory
	 * exact. A computer turn has opened the scope already.
	 */
	private <T> T run(CurrentPlayer me, UUID gameId, UUID idempotencyKey, String commandType, Handler<T> handler,
			boolean outOfTurn) {
		if (ReadScope.active()) {
			return runInScope(me, gameId, idempotencyKey, commandType, handler, outOfTurn);
		}
		return ReadScope.runTurn(() -> runInScope(me, gameId, idempotencyKey, commandType, handler, outOfTurn));
	}

	private <T> T runInScope(CurrentPlayer me, UUID gameId, UUID idempotencyKey, String commandType, Handler<T> handler,
			boolean outOfTurn) {
		Game game = games.findByIdForUpdate(gameId).orElseThrow(GameErrors::gameNotFound);
		GamePlayer mine = memberships.findByGameIdOrderBySeatOrder(game.id())
			.stream()
			.filter(member -> member.playerId().equals(me.id()))
			.findFirst()
			.orElseThrow(GameErrors::gameNotFound);
		if (game.status() != GameStatus.IN_PROGRESS) {
			throw GameErrors.notPlayable(game.status());
		}
		Instant now = clock.instant();
		CommandContext context = new CommandContext(game.id(), mine.id(), mine.seatOrder(), game.currentRound(),
				game.turnNumber(), game.rulesetVersion(), now);

		Optional<GameCommand> previous = commands.findByGameIdAndGamePlayerIdAndIdempotencyKey(game.id(), mine.id(),
				idempotencyKey);
		if (previous.isPresent()) {
			if (!previous.get().commandType().equals(commandType)) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
						"This Idempotency-Key was already used for a different command.");
			}
			return handler.replay(context, previous.get().resultRef());
		}
		if (mine.eliminated()) {
			throw GameErrors.eliminated();
		}
		if (!outOfTurn) {
			if (game.paused()) {
				throw GameErrors.pausedForConference();
			}
			if (game.activeSeat() != mine.seatOrder()) {
				throw new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
			}
		}

		Outcome<T> outcome = handler.execute(context);
		commands.save(new GameCommand(game.id(), mine.id(), idempotencyKey, commandType, game.turnNumber(), now)
			.withResultRef(outcome.resultRef()));
		game.touch(now);
		// A command may change the turn, the pause or the state version: a turn scope reads the membership again.
		ReadScope.forget(GameAccessService.MEMBERSHIP);
		return outcome.result();
	}

}
