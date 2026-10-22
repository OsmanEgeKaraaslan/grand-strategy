package com.reach.strategy.social.application;

import java.time.Clock;
import java.util.UUID;

import com.reach.strategy.game.api.GameLifecycle;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells players when a game they are in starts or finishes (Phase 13A). Runs after the game's
 * transaction committed, in its own transaction: a failure here can never undo a start or a result.
 */
@Component
class GameLifecycleNotifier {

	private final Notifications notifications;
	private final Clock clock;

	GameLifecycleNotifier(Notifications notifications, Clock clock) {
		this.notifications = notifications;
		this.clock = clock;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void started(GameLifecycle.Started event) {
		for (UUID player : event.humanPlayerIds()) {
			notifications.notify(player, Notifications.Kind.GAME_STARTED, null, event.gameId(), null, clock.instant());
		}
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finished(GameLifecycle.Finished event) {
		for (UUID player : event.humanPlayerIds()) {
			notifications.notify(player, Notifications.Kind.GAME_FINISHED, null, event.gameId(), null, clock.instant());
		}
	}

}
