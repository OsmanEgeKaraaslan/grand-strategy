package com.reach.strategy.game.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.game.api.GameLifecycle;
import com.reach.strategy.game.api.VictoryCondition;
import com.reach.strategy.game.domain.Game;
import com.reach.strategy.game.domain.GamePlayer;
import com.reach.strategy.game.domain.GamePlayerRepository;
import com.reach.strategy.game.domain.GameResultRepository;
import com.reach.strategy.game.rules.TurnOrder;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Passing the turn and ending games, shared by "end turn" and by resuming play after a peace
 * conference (Phase 10). Always runs inside the caller's transaction with the game row locked.
 */
@Component
class TurnAdvancer {

	private final GamePlayerRepository memberships;
	private final RoundProcessor roundProcessor;
	private final List<VictoryCondition> victory;
	private final GameResultRepository results;
	private final WorldApi world;
	private final JsonMapper jsonMapper;
	private final ApplicationEventPublisher events;

	TurnAdvancer(GamePlayerRepository memberships, RoundProcessor roundProcessor, List<VictoryCondition> victory,
			GameResultRepository results, WorldApi world, JsonMapper jsonMapper, ApplicationEventPublisher events) {
		this.events = events;
		this.memberships = memberships;
		this.roundProcessor = roundProcessor;
		this.victory = List.copyOf(victory);
		this.results = results;
		this.world = world;
		this.jsonMapper = jsonMapper;
	}

	/** @param finished the round end met the victory condition: the game finished instead of advancing */
	record Advanced(TurnOrder.Advance advance, boolean finished) {
	}

	/**
	 * Passes the turn to the next seat that still takes turns; after the last one the global round
	 * completes exactly once and the victory condition is asked.
	 */
	Advanced advance(Game game, Instant now) {
		List<GamePlayer> seats = memberships.findByGameIdOrderBySeatOrder(game.id());
		TurnOrder.Advance advance = game.nextTurn(skippedSeats(game), seats.size());
		if (advance.roundCompleted()) {
			roundProcessor.completeRound(game.id(), advance.endedRound(), game.rulesetVersion(), now);
			Optional<VictoryCondition.Outcome> outcome = victory.isEmpty() ? Optional.empty()
					: victory.getFirst().evaluate(game.id(), advance.endedRound(), game.rulesetVersion());
			if (outcome.isPresent()) {
				finish(game, advance.endedRound(), outcome.get(), now);
				return new Advanced(advance, true);
			}
		}
		game.applyTurnAdvance(advance, now);
		// Delivered after commit: a computer seat may now be on turn (Phase 11).
		events.publishEvent(new ComputerSeats.TurnPassed(game.id()));
		return new Advanced(advance, false);
	}

	/** Announces (after commit) that play may continue, e.g. at a computer seat after a pause. */
	void passed(Game game) {
		events.publishEvent(new ComputerSeats.TurnPassed(game.id()));
	}

	/** Ends the game now if the victory condition decides so outside a round end. */
	boolean finishIfDecided(Game game, Instant now) {
		if (victory.isEmpty() || game.finished()) {
			return false;
		}
		Optional<VictoryCondition.Outcome> outcome = victory.getFirst()
			.evaluateNow(game.id(), game.currentRound(), game.rulesetVersion());
		outcome.ifPresent(o -> finish(game, game.currentRound(), o, now));
		return outcome.isPresent();
	}

	/** Seats of players whose nation was eliminated: they take no more turns (Phase 10). */
	Set<Integer> skippedSeats(Game game) {
		return memberships.findByGameIdOrderBySeatOrder(game.id())
			.stream()
			.filter(GamePlayer::eliminated)
			.map(GamePlayer::seatOrder)
			.collect(Collectors.toSet());
	}

	/**
	 * Completes the game once: FINISHED status and the stored result commit together with the change
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
			.filter(nation -> nation.gamePlayerId() != null)
			.collect(Collectors.toMap(WorldApi.NationState::id, WorldApi.NationState::gamePlayerId));
		List<GameResultRepository.Standing> standings = outcome.standings()
			.stream()
			.map(s -> new GameResultRepository.Standing(s.nationId(),
					playerByGamePlayer.get(gamePlayerByNation.get(s.nationId())), s.rank(), s.score(), s.ownedProvinces(),
					s.ownedPopulation(), s.treasuryMg()))
			.toList();
		results.insert(new GameResultRepository.Result(game.id(), round, outcome.reason(), outcome.winnerNationId(),
				game.rulesetVersion(), jsonMapper.writeValueAsString(new TreeMap<>(outcome.statistics())), now),
				standings);
		events.publishEvent(new GameLifecycle.Finished(game.id(), memberships.findByGameIdOrderBySeatOrder(game.id())
			.stream()
			.filter(m -> !m.computer())
			.map(GamePlayer::playerId)
			.collect(Collectors.toSet())));
	}

}
