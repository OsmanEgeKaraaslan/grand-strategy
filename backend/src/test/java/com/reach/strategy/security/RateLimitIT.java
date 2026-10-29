package com.reach.strategy.security;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 15B against the full application: per-account budgets answer 429 with Retry-After, policies
 * are counted per account (a forwarded-for header changes nothing), oversized bodies get 413, the
 * number of unfinished games per player is bounded, and API responses carry the security headers.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.rate-limits.enabled=true",
		"strategy.rate-limits.policies.player-search.capacity=3", "strategy.rate-limits.policies.player-search.window=PT1H",
		"strategy.rate-limits.policies.game-join.capacity=2", "strategy.rate-limits.policies.game-join.window=PT1H",
		"strategy.rate-limits.policies.game-commands.capacity=4", "strategy.rate-limits.policies.game-commands.window=PT1H",
		"strategy.game.max-unfinished-games=3", "strategy.web.max-request-body=2KB" })
class RateLimitIT {

	@Autowired
	MockMvc mockMvc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	private MvcResult search(String token, String forwardedFor) throws Exception {
		return mockMvc.perform(get("/api/v1/players/search?q=abc").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("X-Forwarded-For", forwardedFor)).andReturn();
	}

	@Test
	void anExhaustedBudgetAnswers429WithRetryAfterForThatAccountOnly() throws Exception {
		String token = ApiDriver.newPlayer();
		for (int i = 0; i < 3; i++) {
			assertThat(status(search(token, "203.0.113." + i))).isEqualTo(200);
		}
		// A different claimed client address does not open a new budget.
		MvcResult limited = search(token, "198.51.100.7");
		assertThat(status(limited)).isEqualTo(429);
		assertThat((String) read(limited, "$.code")).isEqualTo("RATE_LIMITED");
		assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
		assertThat((String) read(limited, "$.requestId")).isNotBlank();

		// Other endpoints (global budget only) and other accounts are unaffected.
		assertThat(status(api.getJson(token, "/friends"))).isEqualTo(200);
		assertThat(status(search(ApiDriver.newPlayer(), "198.51.100.7"))).isEqualTo(200);
	}

	@Test
	void guessingInvitationCodesIsThrottled() throws Exception {
		String guesser = ApiDriver.newPlayer();
		assertThat(status(api.join(guesser, "AAAAAAAA"))).isEqualTo(404);
		assertThat(status(api.join(guesser, "BBBBBBBB"))).isEqualTo(404);
		assertThat(status(api.join(guesser, "CCCCCCCC"))).isEqualTo(429);
	}

	@Test
	void gameCommandsHaveTheirOwnBudgetWhileReadsKeepWorking() throws Exception {
		ApiDriver.Lobby game = api.startedGame("Throttled " + UUID.randomUUID().toString().substring(0, 6));
		String city = api.cityId(game.host(), game.gameId(), "varnhold");
		int last = 0;
		for (int i = 0; i < 5; i++) {
			last = status(api.buildWorkshop(game.host(), game.gameId(), city, 1, UUID.randomUUID()));
		}
		assertThat(last).isEqualTo(429);
		assertThat(status(api.state(game.host(), game.gameId()))).isEqualTo(200);
	}

	@Test
	void aPlayerCanHoldOnlySoManyUnfinishedGames() throws Exception {
		String host = ApiDriver.newPlayer();
		for (int i = 0; i < 3; i++) {
			assertThat(status(api.createGame(host, "Lobby " + i))).isEqualTo(201);
		}
		MvcResult refused = api.createGame(host, "One too many");
		assertThat(status(refused) + " " + read(refused, "$.code")).isEqualTo("409 TOO_MANY_UNFINISHED_GAMES");

		String other = ApiDriver.newPlayer();
		String invite = read(api.createGame(other, "Open lobby"), "$.inviteCode");
		assertThat((String) read(api.join(host, invite), "$.code")).isEqualTo("TOO_MANY_UNFINISHED_GAMES");
	}

	@Test
	void oversizedBodiesAreRefusedBeforeTheyAreRead() throws Exception {
		String token = ApiDriver.newPlayer();
		String huge = "{\"name\":\"" + "x".repeat(4096) + "\"}";
		MvcResult result = mockMvc.perform(post("/api/v1/games").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(huge)).andReturn();
		assertThat(status(result)).isEqualTo(413);
		assertThat(body(result)).contains("PAYLOAD_TOO_LARGE");
	}

	@Test
	void apiResponsesCarryProtectiveHeaders() throws Exception {
		MvcResult result = api.getJson(ApiDriver.newPlayer(), "/me");
		assertThat(result.getResponse().getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
		assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
		assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

}
