package com.reach.strategy.game.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.reach.strategy.game.domain.GameRepository;
import com.reach.strategy.platform.web.ApiException;
import org.junit.jupiter.api.Test;

class GameCapacityTest {

	@Test
	void allowsGamesBelowTheBoundAndRefusesAtIt() {
		GameRepository games = mock(GameRepository.class);
		GameCapacity capacity = new GameCapacity(games, 3);

		when(games.countUnfinished()).thenReturn(2L);
		assertThat(capacity.available()).isTrue();
		capacity.require();

		when(games.countUnfinished()).thenReturn(3L);
		assertThat(capacity.available()).isFalse();
		assertThatThrownBy(capacity::require).isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("GAME_CAPACITY_REACHED"));
	}

}
