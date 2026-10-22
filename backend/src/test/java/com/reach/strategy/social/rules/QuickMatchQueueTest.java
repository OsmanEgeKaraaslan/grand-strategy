package com.reach.strategy.social.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.reach.strategy.social.rules.QuickMatchQueue.Join;
import org.junit.jupiter.api.Test;

class QuickMatchQueueTest {

	private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
	private static final Duration TIMEOUT = Duration.ofSeconds(90);

	@Test
	void aReconnectingPlayerRecoversTheirMatchInsteadOfSearchingAgain() {
		assertThat(QuickMatchQueue.decideJoin(true, false, 9, 5)).isEqualTo(Join.RECOVER_MATCH);
	}

	@Test
	void aRepeatedJoinContinuesTheSameSearch() {
		assertThat(QuickMatchQueue.decideJoin(false, true, 9, 5)).isEqualTo(Join.CONTINUE_SEARCH);
	}

	@Test
	void newSearchesAreLimitedByUnfinishedGames() {
		assertThat(QuickMatchQueue.decideJoin(false, false, 5, 5)).isEqualTo(Join.REFUSE_TOO_MANY_GAMES);
		assertThat(QuickMatchQueue.decideJoin(false, false, 4, 5)).isEqualTo(Join.START_SEARCH);
	}

	@Test
	void aSearchWithoutHeartbeatsExpires() {
		assertThat(QuickMatchQueue.live(NOW.minusSeconds(90), NOW, TIMEOUT)).isTrue();
		assertThat(QuickMatchQueue.live(NOW.minusSeconds(91), NOW, TIMEOUT)).isFalse();
		assertThat(QuickMatchQueue.displayedState("WAITING", NOW.minusSeconds(10), false, NOW, TIMEOUT)).isEqualTo("SEARCHING");
		assertThat(QuickMatchQueue.displayedState("WAITING", NOW.minusSeconds(200), false, NOW, TIMEOUT)).isEqualTo("EXPIRED");
	}

	@Test
	void anOpenedMatchNoLongerBlocksTheQueueView() {
		assertThat(QuickMatchQueue.displayedState("MATCHED", NOW, false, NOW, TIMEOUT)).isEqualTo("MATCHED");
		assertThat(QuickMatchQueue.displayedState("MATCHED", NOW, true, NOW, TIMEOUT)).isEqualTo("NONE");
		assertThat(QuickMatchQueue.displayedState("CANCELLED", NOW, false, NOW, TIMEOUT)).isEqualTo("CANCELLED");
	}

}
