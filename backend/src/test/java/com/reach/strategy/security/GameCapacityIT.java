package com.reach.strategy.security;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SECURITY_AUDIT §7 P1: a server-wide bound on unfinished games. With room for one game (the test
 * database may already hold others), a new lobby or Quick Match search is refused with 503 once the
 * server is full, whichever account asks.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = "strategy.game.max-unfinished-games-total=1")
class GameCapacityIT {

	@Autowired
	MockMvc mockMvc;

	@Test
	void newGamesAndSearchesAreRefusedWhileTheServerIsFull() throws Exception {
		ApiDriver api = new ApiDriver(mockMvc);
		MvcResult refused = null;
		// At most one lobby fits; every further one, from fresh accounts, is refused.
		for (int i = 0; i < 3 && refused == null; i++) {
			MvcResult created = api.createGame(ApiDriver.newPlayer(), "Capacity " + i);
			if (status(created) != 201) {
				refused = created;
			}
		}
		assertThat(refused).isNotNull();
		assertThat(status(refused) + " " + read(refused, "$.code")).isEqualTo("503 GAME_CAPACITY_REACHED");

		MvcResult search = api.postJson(ApiDriver.newPlayer(), "/matchmaking/quick/join", "{}");
		assertThat(status(search) + " " + read(search, "$.code")).isEqualTo("503 GAME_CAPACITY_REACHED");
	}

}
