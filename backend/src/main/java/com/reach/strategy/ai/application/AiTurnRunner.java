package com.reach.strategy.ai.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.reach.strategy.ai.domain.AiDecisionLog;
import com.reach.strategy.ai.domain.AiTurnRepository;
import com.reach.strategy.ai.rules.AiAction;
import com.reach.strategy.ai.rules.AiBrain;
import com.reach.strategy.ai.rules.AiRules;
import com.reach.strategy.ai.rules.AiState;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.game.api.GameAccess;
import com.reach.strategy.game.api.TurnControl;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.world.api.WorldApi;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plays the turns of computer seats (Phase 11, docs/AI.md §2). A computer seat holds its place in
 * the sequential turn order like a human; when the turn passes to it, this runner observes the board,
 * asks the pure {@link AiBrain} for a plan and issues the actions one by one through the normal
 * command pipeline, then ends the turn — all with the seat's own computer account.
 * <p>
 * Reliability: every action runs in its own transaction together with the increment of the turn's
 * action counter ({@code ai_turn}); its idempotency key is derived from (game, turn, counter). A crash
 * at any point therefore loses at most an uncommitted action, and a restarted runner continues from
 * the committed counter. A refused action is recorded and never planned again in the same turn.
 * Budgets bound actions, observations and failures, so a turn always ends. Within one JVM a game is
 * played by at most one thread; across JVMs the game row lock, the counter check and the turn number
 * check of "end turn" keep effects exactly-once.
 */
@Component
class AiTurnRunner {

	private static final Logger log = LoggerFactory.getLogger(AiTurnRunner.class);

	/** Upper bound of computer turns played in one run (a full round of up to ten seats, twice). */
	private static final int MAX_TURNS_PER_RUN = 24;

	private static final int MAX_RERUNS = 8;

	private final ComputerSeats seats;
	private final ComputerPlayers computers;
	private final TurnControl turns;
	private final GameAccess gameAccess;
	private final WorldApi world;
	private final AiRulesets rulesets;
	private final AiObserver observer;
	private final AiExecutor executor;
	private final AiNegotiator negotiator;
	private final AiTurnRepository progress;
	private final AiDecisionLog decisions;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	private final TransactionTemplate writes;
	private final TransactionTemplate reads;
	private final Set<UUID> running = ConcurrentHashMap.newKeySet();
	private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

	AiTurnRunner(ComputerSeats seats, ComputerPlayers computers, TurnControl turns, GameAccess gameAccess,
			WorldApi world, AiRulesets rulesets, AiObserver observer, AiExecutor executor, AiNegotiator negotiator,
			AiTurnRepository progress, AiDecisionLog decisions, JsonMapper jsonMapper, Clock clock,
			PlatformTransactionManager transactions) {
		this.seats = seats;
		this.computers = computers;
		this.turns = turns;
		this.gameAccess = gameAccess;
		this.world = world;
		this.rulesets = rulesets;
		this.observer = observer;
		this.executor = executor;
		this.negotiator = negotiator;
		this.progress = progress;
		this.decisions = decisions;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		// Always new transactions: the runner may be invoked from an after-commit callback.
		this.writes = new TransactionTemplate(transactions);
		this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.reads = new TransactionTemplate(transactions);
		this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.reads.setReadOnly(true);
	}

	/** Plays every computer business of the game that is due now; returns when a human must act. */
	void run(UUID gameId) {
		if (!running.add(gameId)) {
			dirty.add(gameId);
			return;
		}
		try {
			int reruns = 0;
			do {
				dirty.remove(gameId);
				List<ComputerSeats.Seat> computerSeats = read(() -> seats.seats(gameId));
				if (computerSeats.isEmpty()) {
					return;
				}
				for (int i = 0; i < MAX_TURNS_PER_RUN; i++) {
					negotiator.negotiate(gameId, computerSeats);
					Optional<ComputerSeats.Turn> turn = read(() -> seats.activeComputerTurn(gameId));
					if (turn.isEmpty() || !playTurn(turn.get())) {
						break;
					}
				}
			}
			while (dirty.contains(gameId) && ++reruns < MAX_RERUNS);
		}
		catch (RuntimeException unexpected) {
			// Never propagate into the caller's request: the recovery poller retries later.
			log.error("Computer opponent failed in game {}", gameId, unexpected);
		}
		finally {
			running.remove(gameId);
		}
	}

	/** @return true when the turn was ended (the next seat may be a computer too) */
	private boolean playTurn(ComputerSeats.Turn turn) {
		UUID gameId = turn.gameId();
		CurrentPlayer actor = computers.actor(turn.seat().playerId());
		Optional<AiRules> rules = rulesets.find(turn.rulesetVersion());
		UUID nationId = read(() -> world.snapshot(gameId)
			.nations()
			.stream()
			.filter(n -> turn.seat().gamePlayerId().equals(n.gamePlayerId()))
			.findFirst()
			.orElseThrow()
			.id());
		Instant started = clock.instant();
		AiTurnRepository.TurnRow row = write(() -> progress.begin(gameId, turn.turnNumber(), turn.round(),
				turn.seat().gamePlayerId(), nationId, started));
		MDC.put("gameId", gameId.toString());
		try {
			if (rules.isPresent()) {
				if (!act(turn, actor, nationId, rules.get(), row)) {
					return false;
				}
			}
			return endTurn(turn, actor);
		}
		finally {
			MDC.remove("gameId");
		}
	}

	/** @return false when the game is paused for a conference involving humans: the turn waits */
	private boolean act(ComputerSeats.Turn turn, CurrentPlayer actor, UUID nationId, AiRules rules,
			AiTurnRepository.TurnRow row) {
		UUID gameId = turn.gameId();
		AiRules.Budget budget = rules.budget();
		Set<String> failed = new HashSet<>(jsonMapper.readValue(row.failed(), new TypeReference<List<String>>() {
		}));
		int actions = row.actions();
		int failures = row.failures();
		int observations = row.observations();
		while (actions < budget.maxActionsPerTurn() && failures < budget.maxFailuresPerTurn()
				&& observations < budget.maxObservationsPerTurn()) {
			if (paused(actor, gameId)) {
				negotiator.negotiate(gameId, read(() -> seats.seats(gameId)));
				if (paused(actor, gameId)) {
					return false;
				}
			}
			long t0 = System.nanoTime();
			AiState state = read(() -> observer.observe(actor, gameId, turn.round(), turn.turnNumber()));
			AiBrain.TurnPlan plan = AiBrain.planTurn(state, rules, failed);
			observations++;
			String goal = plan.goal();
			write(() -> {
				progress.recordObservation(gameId, turn.turnNumber(), goal);
				return null;
			});
			log.debug("Computer {} plans {} actions (goal {}) in {} µs", state.meKey(), plan.actions().size(), goal,
					(System.nanoTime() - t0) / 1000);
			boolean progressed = false;
			for (AiAction action : plan.actions()) {
				if (failed.contains(action.signature())) {
					continue;
				}
				if (actions >= budget.maxActionsPerTurn() || failures >= budget.maxFailuresPerTurn()) {
					break;
				}
				int sequence = actions;
				UUID key = key(gameId + ":" + turn.turnNumber() + ":" + sequence);
				try {
					write(() -> {
						executor.execute(actor, gameId, action, key);
						if (!progress.recordAction(gameId, turn.turnNumber(), sequence)) {
							throw new ConcurrentTurnException();
						}
						if (action.major()) {
							decisions.record(gameId, turn.round(), turn.turnNumber(), nationId, kind(action), action.reason(),
									details(action, goal), clock.instant());
						}
						return null;
					});
					actions++;
					progressed = true;
					log.info("Computer {} round {}: {} — {}", state.meKey(), turn.round(), kind(action), action.reason());
				}
				catch (ConcurrentTurnException another) {
					log.info("Computer turn {} of game {} is played elsewhere", turn.turnNumber(), gameId);
					return false;
				}
				catch (ApiException refused) {
					failed.add(action.signature());
					failures++;
					String error = refused.code() + ": " + refused.getMessage();
					write(() -> {
						progress.recordFailure(gameId, turn.turnNumber(), action.signature(), error);
						return null;
					});
					log.info("Computer {} round {}: {} refused ({})", state.meKey(), turn.round(), action.signature(), error);
				}
				catch (RuntimeException bug) {
					failed.add(action.signature());
					failures++;
					write(() -> {
						progress.recordFailure(gameId, turn.turnNumber(), action.signature(),
								"INTERNAL: " + bug.getClass().getSimpleName());
						return null;
					});
					log.warn("Computer {} action {} failed", state.meKey(), action.signature(), bug);
				}
				if (action.reobserve()) {
					break;
				}
			}
			if (!progressed) {
				break;
			}
		}
		return true;
	}

	private boolean endTurn(ComputerSeats.Turn turn, CurrentPlayer actor) {
		UUID gameId = turn.gameId();
		try {
			write(() -> {
				turns.endTurn(actor, gameId, key(gameId + ":" + turn.turnNumber() + ":end"), turn.turnNumber());
				progress.finish(gameId, turn.turnNumber(), clock.instant());
				return null;
			});
			return true;
		}
		catch (ApiException alreadyMovedOn) {
			// TURN_CHANGED / NOT_ACTIVE_PLAYER: another runner ended it; GAME_PAUSED: wait for the conference.
			log.info("Computer turn {} of game {} not ended: {}", turn.turnNumber(), gameId, alreadyMovedOn.code());
			return false;
		}
	}

	private boolean paused(CurrentPlayer actor, UUID gameId) {
		return read(() -> gameAccess.membership(actor, gameId).paused());
	}

	static UUID key(String seed) {
		return UUID.nameUUIDFromBytes(("ai:" + seed).getBytes(StandardCharsets.UTF_8));
	}

	static String kind(AiAction action) {
		return action.signature().substring(0, action.signature().indexOf(':'));
	}

	private String details(AiAction action, String goal) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("goal", goal);
		details.put("action", action.signature());
		return jsonMapper.writeValueAsString(details);
	}

	private <T> T read(Supplier<T> work) {
		return reads.execute(status -> work.get());
	}

	private <T> T write(Supplier<T> work) {
		return writes.execute(status -> work.get());
	}

	/** Another runner counted this action first: it is playing the turn. */
	private static final class ConcurrentTurnException extends RuntimeException {

		ConcurrentTurnException() {
			super(null, null, false, false);
		}

	}

}
