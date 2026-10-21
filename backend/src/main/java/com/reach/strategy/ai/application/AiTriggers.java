package com.reach.strategy.ai.application;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.reach.strategy.diplomacy.api.ConferenceActivity;
import com.reach.strategy.game.api.ComputerSeats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PreDestroy;

/**
 * When computers act: after every commit that passes a turn or ends a pause, after every conference
 * change, and periodically for recovery after a restart (a game whose computer seat is on turn but
 * nobody plays it). {@code strategy.ai.execution=inline} plays in the committing thread (tests and
 * deterministic runs); {@code async} (default) on a small worker pool so a human's request returns
 * at once.
 */
@Configuration
@EnableScheduling
class AiTriggers {

	private static final Logger log = LoggerFactory.getLogger(AiTriggers.class);

	private static final String WORKER = "computer-opponent";

	private final AiTurnRunner runner;
	private final ComputerSeats seats;
	private final boolean inline;
	private final boolean manual;
	private final boolean recovery;
	private final ExecutorService workers;

	AiTriggers(AiTurnRunner runner, ComputerSeats seats, @Value("${strategy.ai.execution:async}") String execution,
			@Value("${strategy.ai.recovery-enabled:true}") boolean recovery,
			@Value("${strategy.ai.worker-threads:2}") int workerThreads) {
		this.runner = runner;
		this.seats = seats;
		this.inline = "inline".equals(execution);
		// manual: nothing plays by itself (tests drive the runner directly).
		this.manual = "manual".equals(execution);
		this.recovery = recovery;
		// Inline still plays on a worker thread (and waits): computer transactions never share the
		// persistence context of the request that triggered them.
		this.workers = manual ? null : Executors.newFixedThreadPool(inline ? 1 : workerThreads, r -> {
			Thread thread = new Thread(r, WORKER);
			thread.setDaemon(true);
			return thread;
		});
	}

	@TransactionalEventListener(fallbackExecution = true)
	void turnPassed(ComputerSeats.TurnPassed event) {
		dispatch(event.gameId());
	}

	@TransactionalEventListener(fallbackExecution = true)
	void conferenceChanged(ConferenceActivity event) {
		dispatch(event.gameId());
	}

	@Scheduled(initialDelayString = "${strategy.ai.recovery-interval-ms:15000}",
			fixedDelayString = "${strategy.ai.recovery-interval-ms:15000}")
	void recover() {
		if (!recovery) {
			return;
		}
		try {
			seats.runningGamesWithComputers().forEach(this::dispatch);
		}
		catch (RuntimeException e) {
			log.warn("Computer recovery sweep failed", e);
		}
	}

	void dispatch(UUID gameId) {
		if (manual) {
			return;
		}
		if (WORKER.equals(Thread.currentThread().getName())) {
			// Raised by a computer's own command: the running loop picks it up (never wait on ourselves).
			runner.run(gameId);
		}
		else if (inline) {
			try {
				workers.submit(() -> runner.run(gameId)).get();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			catch (java.util.concurrent.ExecutionException e) {
				log.error("Computer opponent failed in game {}", gameId, e.getCause());
			}
		}
		else {
			workers.execute(() -> runner.run(gameId));
		}
	}

	@PreDestroy
	void stop() {
		if (workers != null) {
			workers.shutdownNow();
		}
	}

}
