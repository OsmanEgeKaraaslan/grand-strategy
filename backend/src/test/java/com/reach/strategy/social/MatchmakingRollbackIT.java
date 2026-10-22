package com.reach.strategy.social;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A failure while creating the matched game rolls the whole pairing back (no game, no MATCHED ticket). */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.social.matchmaking.map-key=no-such-map" })
class MatchmakingRollbackIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void aFailedGameCreationLeavesNoPartialMatch() throws Exception {
		ApiDriver api = new ApiDriver(mockMvc);
		jdbc.update("UPDATE game.matchmaking_ticket SET status = 'EXPIRED', ended_at = now() WHERE status = 'WAITING'");
		Account a = api.newAccount("rollback_a");
		Account b = api.newAccount("rollback_b");
		int gamesBefore = jdbc.queryForObject("SELECT count(*) FROM game.game", Integer.class);
		api.postJson(a.token(), "/matchmaking/quick/join", "{}");

		assertThat(status(api.postJson(b.token(), "/matchmaking/quick/join", "{}"))).isEqualTo(500);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game", Integer.class)).isEqualTo(gamesBefore);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.matchmaking_ticket WHERE status = 'MATCHED' AND player_id IN (?::uuid, ?::uuid)",
				Integer.class, a.id(), b.id())).isZero();
		assertThat((String) read(api.getJson(a.token(), "/matchmaking/quick"), "$.state")).isEqualTo("SEARCHING");
		assertThat((String) read(api.getJson(b.token(), "/matchmaking/quick"), "$.state")).as("b's join was rolled back")
			.isEqualTo("NONE");
		jdbc.update("UPDATE game.matchmaking_ticket SET status = 'EXPIRED', ended_at = now() WHERE status = 'WAITING'");
	}

}
