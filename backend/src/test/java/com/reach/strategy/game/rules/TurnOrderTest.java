package com.reach.strategy.game.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TurnOrderTest {

	@Test
	void eliminatedSeatsAreSkippedAndTheRoundStillCompletesOnce() {
		// Four humans, seat 1 eliminated.
		assertThat(TurnOrder.next(3, 0, 4, java.util.Set.of(1))).isEqualTo(new TurnOrder.Advance(false, 3, 3, 2));
		assertThat(TurnOrder.next(3, 3, 4, java.util.Set.of(1))).isEqualTo(new TurnOrder.Advance(true, 3, 4, 0));
		// Seat 0 eliminated: the next round starts with seat 1.
		assertThat(TurnOrder.next(3, 2, 3, java.util.Set.of(0))).isEqualTo(new TurnOrder.Advance(true, 3, 4, 1));
		// Trailing seats eliminated: the last active seat completes the round.
		assertThat(TurnOrder.next(5, 1, 4, java.util.Set.of(2, 3))).isEqualTo(new TurnOrder.Advance(true, 5, 6, 0));
		assertThatThrownBy(() -> TurnOrder.next(1, 0, 2, java.util.Set.of(0, 1))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void twoPlayersAlternateAndTheRoundCompletesAfterTheSecond() {
		assertThat(TurnOrder.next(1, 0, 2)).isEqualTo(new TurnOrder.Advance(false, 1, 1, 1));
		assertThat(TurnOrder.next(1, 1, 2)).isEqualTo(new TurnOrder.Advance(true, 1, 2, 0));
	}

	@Test
	void everySeatActsExactlyOncePerRoundInFixedOrder() {
		int round = 1;
		int seat = 0;
		List<String> log = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			log.add(round + ":" + seat);
			TurnOrder.Advance advance = TurnOrder.next(round, seat, 4);
			round = advance.nextRound();
			seat = advance.nextSeat();
		}
		assertThat(log).containsExactly("1:0", "1:1", "1:2", "1:3", "2:0", "2:1", "2:2", "2:3", "3:0", "3:1", "3:2", "3:3");
	}

	@ParameterizedTest
	@CsvSource({ "0, 0, 2", "1, 3, 2", "1, 2, 2", "1, -1, 2", "1, 0, 0" })
	void rejectsImpossibleStates(int round, int seat, int seats) {
		assertThatThrownBy(() -> TurnOrder.next(round, seat, seats)).isInstanceOf(IllegalArgumentException.class);
	}

}
