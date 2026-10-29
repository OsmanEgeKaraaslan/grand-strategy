package com.reach.strategy.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.reach.strategy.game.api.ComputerSeats;
import org.junit.jupiter.api.Test;

/** Phase 15B: triggers for one game never pile up, and the queue of waiting games is bounded. */
class AiTriggersTest {

	@Test
	void repeatedTriggersOfAWaitingGameQueueASingleRun() throws Exception {
		AiTurnRunner runner = mock(AiTurnRunner.class);
		CountDownLatch release = new CountDownLatch(1);
		UUID busy = UUID.randomUUID();
		UUID waiting = UUID.randomUUID();
		doAnswer(invocation -> {
			if (busy.equals(invocation.getArgument(0))) {
				release.await(5, TimeUnit.SECONDS);
			}
			return null;
		}).when(runner).run(any());
		AiTriggers triggers = new AiTriggers(runner, mock(ComputerSeats.class), "async", false, 1, 10, 1000);

		triggers.dispatch(busy);
		verify(runner, timeout(2000)).run(busy);
		for (int i = 0; i < 50; i++) {
			triggers.dispatch(waiting);
		}
		assertThat(triggers.queuedGames()).isEqualTo(1);

		release.countDown();
		verify(runner, timeout(2000).times(1)).run(waiting);
		triggers.stop();
	}

	@Test
	void aFullQueueDropsTriggersForTheRecoverySweepInsteadOfGrowing() throws Exception {
		AiTurnRunner runner = mock(AiTurnRunner.class);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).when(runner).run(any());
		AiTriggers triggers = new AiTriggers(runner, mock(ComputerSeats.class), "async", false, 1, 2, 1000);

		UUID first = UUID.randomUUID();
		triggers.dispatch(first);
		verify(runner, timeout(2000)).run(first);
		for (int i = 0; i < 20; i++) {
			triggers.dispatch(UUID.randomUUID());
		}
		assertThat(triggers.queuedGames()).isEqualTo(2);

		release.countDown();
		verify(runner, timeout(2000).times(3)).run(any());
		triggers.stop();
		verify(runner, times(3)).run(any());
	}

}
