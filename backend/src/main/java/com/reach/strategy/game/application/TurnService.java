package com.reach.strategy.game.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.api.VictoryCondition;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GameCommand;
import com.reach.strategy.game.domain.GameCommandRepository;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.game.domain.GameResultRepository;
import com.reach.strategy.game.domain.GameStatus;
import com.reach.strategy.game.rules.TurnOrder;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sequential turns (docs/TURN_SYSTEM.md §4): the gameplay command pipeline for "end turn".
 * <ol>
 * <li>Lock the game row — all commands of one game are serialized.</li>
 * <li>Membership (404 otherwise).</li>
 * <li>Idempotency: a repeated Idempotency-Key replays the outcome instead of acting again.</li>
 * <li>Game in progress, caller is the active player, {@code expectedTurnNumber} still current.</li>
 * <li>Advance; after the last seat, complete the round exactly once via {@link RoundProcessor}; if the
 * ruleset's {@link VictoryCondition} is met, the game finishes instead of advancing.</li>
 * <li>Record the command. Everything commits or rolls back together.</li>
 * </ol>
 */
@Service
public class TurnService {

	private final GameRepository games;
	private final GamePlayerRepository memberships;
	private final GameCommandRepository commands;
	private final RoundProcessor roundProcessor;
	private final Clock clock;
	private final List<VictoryCondition> victory;
	private final GameResultRepository results;
	private final WorldApi world;
	private final JsonMapper jsonMapper;

	TurnService(GameRepository games, GamePlayerRepository memberships, GameCommandRepository commands,
			RoundProcessor roundProcessor, Clock clock, List<VictoryCondition> victory, GameResultRepository results,
			WorldApi world, JsonMapper jsonMapper) {
		this.victory = List.copyOf(victory);
		this.results = results;
		this.world = world;
		this.jsonMapper = jsonMapper;
		this.games = games;
		this.memberships = memberships;
		this.commands = commands;
		this.roundProcessor = roundProcessor;
		this.clock = clock;
	}

	@Transactional
	public TurnResult endTurn(CurrentPlayer me, UUID gameId, UUID idempotencyKey, int expectedTurnNumber) {
		Game game = games.findByIdForUpdate(gameId).orElseThrow(GameErrors::gameNotFound);
		GamePlayer mine = memberOf(game, me);

		if (commands.findByGameIdAndGamePlayerIdAndIdempotencyKey(game.id(), mine.id(), idempotencyKey).isPresent()) {
			return TurnResult.of(game, false, true);
		}
		if (game.status() != GameStatus.IN_PROGRESS) {
			throw GameErrors.notPlayable(game.status());
		}
		if (game.activeSeat() != mine.seatOrder()) {
			throw new ApiException(HttpStatus.CONFLICT, "NOT_ACTIVE_PLAYER", "It is not your turn.");
		}
		if (game.turnNumber() != expectedTurnNumber) {
			throw new ApiException(HttpStatus.CONFLICT, "TURN_CHANGED",
					"The turn has changed since you last loaded the game. Refresh and try again.");
		}

		Instant now = clock.instant();
		int endedTurn = game.turnNumber();
		TurnOrder.Advance advance = game.nextTurn();
		if (advance.roundCompleted()) {
			roundProcessor.completeRound(game.id(), advance.endedRound(), game.rulesetVersion(), now);
			Optional<VictoryCondition.Outcome> outcome = victory.isEmpty() ? Optional.empty()
					: victory.getFirst().evaluate(game.id(), advance.endedRound(), game.rulesetVersion());
			if (outcome.isPresent()) {
				finish(game, advance.endedRound(), outcome.get(), now);
				commands.save(new GameCommand(game.id(), mine.id(), idempotencyKey, GameCommand.END_TURN, endedTurn, now));
				return TurnResult.of(game, true, false);
			}
		}
		game.applyTurnAdvance(advance, now);
		commands.save(new GameCommand(game.id(), mine.id(), idempotencyKey, GameCommand.END_TURN, endedTurn, now));
		return TurnResult.of(game, advance.roundCompleted(), false);
	}

	/**
	 * Completes the game once: FINISHED status and the stored result commit together with the round
	 * that met the condition. A second attempt fails on the status check and the result's primary key.
	 */
	private void finish(Game game, int round, VictoryCondition.Outcome outcome, Instant now) {
		game.finish(now);
		Map<UUID, UUID> playerByGamePlayer = memberships.findByGameIdOrderBySeatOrder(game.id())
			.stream()
			.collect(Collectors.toMap(GamePlayer::id, GamePlayer::playerId));
		Map<UUID, UUID> gamePlayerByNation = world.snapshot(game.id())
			.nations()
			.stream()
			.collect(Collectors.toMap(WorldApi.NationState::id, WorldApi.NationState::gamePlayerId));
		List<GameResultRepository.Standing> standings = outcome.standings()
			.stream()
			.map(s -> new GameResultRepository.Standing(s.nationId(),
					playerByGamePlayer.get(gamePlayerByNation.get(s.nationId())), s.rank(), s.score(), s.ownedProvinces(),
					s.ownedPopulation(), s.treasuryMg()))
			.toList();
		results.insert(new GameResultRepository.Result(game.id(), round, outcome.reason(), outcome.winnerNationId(),
				game.rulesetVersion(), jsonMapper.writeValueAsString(new java.util.TreeMap<>(outcome.statistics())), now),
				standings);
	}

	private GamePlayer memberOf(Game game, CurrentPlayer me) {
		List<GamePlayer> members = memberships.findByGameIdOrderBySeatOrder(game.id());
		return members.stream()
			.filter(member -> member.playerId().equals(me.id()))
			.findFirst()
			.orElseThrow(GameErrors::gameNotFound);
	}

	/**
	 * Turn state after the command.
	 *
	 * @param roundCompleted whether this end of turn completed a global round
	 * @param replayed whether this was a retry of an already executed request (no new effect)
	 * @param finished whether the game has finished (this end of turn completed its last round)
	 */
	public record TurnResult(int round, int activeSeat, int turnNumber, long stateVersion, boolean roundCompleted,
			boolean replayed, boolean finished) {

		static TurnResult of(Game game, boolean roundCompleted, boolean replayed) {
			return new TurnResult(game.currentRound(), game.activeSeat(), game.turnNumber(), game.stateVersion(),
					roundCompleted, replayed, game.finished());
		}

	}

}
