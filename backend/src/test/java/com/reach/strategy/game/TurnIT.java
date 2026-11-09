package com.reach.strategy.game;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Sequential turns against PostgreSQL: order, authorization, idempotency and races. */
@PostgresIntegrationTest
class TurnIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	@Test
	void hostGoesFirstThenGuestThenTheNextRoundStartsWithTheHost() throws Exception {
		Lobby game = api.startedGame("Order");

		MvcResult hostEnds = api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());
		assertThat(status(hostEnds)).isEqualTo(200);
		assertThat((Integer) read(hostEnds, "$.activeSeat")).isEqualTo(1);
		assertThat((Integer) read(hostEnds, "$.round")).isEqualTo(1);
		assertThat((Boolean) read(hostEnds, "$.roundCompleted")).isFalse();
		assertThat(rounds(game)).isZero();

		MvcResult guestEnds = api.endTurn(game.guest(), game.gameId(), 2, UUID.randomUUID());
		assertThat((Integer) read(guestEnds, "$.activeSeat")).isZero();
		assertThat((Integer) read(guestEnds, "$.round")).isEqualTo(2);
		assertThat((Integer) read(guestEnds, "$.turnNumber")).isEqualTo(3);
		assertThat((Boolean) read(guestEnds, "$.roundCompleted")).isTrue();
		assertThat(rounds(game)).isEqualTo(1);

		MvcResult state = api.state(game.host(), game.gameId());
		assertThat((Boolean) read(state, "$.yourTurn")).isTrue();
		assertThat((Integer) read(state, "$.round")).isEqualTo(2);
	}

	@Test
	void inactivePlayersCannotEndTheTurn() throws Exception {
		Lobby game = api.startedGame("Patience");

		MvcResult early = api.endTurn(game.guest(), game.gameId(), 1, UUID.randomUUID());

		assertThat(status(early)).isEqualTo(409);
		assertThat((String) read(early, "$.code")).isEqualTo("NOT_ACTIVE_PLAYER");
		assertThat((Integer) read(api.state(game.host(), game.gameId()), "$.turnNumber")).isEqualTo(1);
	}

	@Test
	void aRetryWithTheSameKeyIsReplayedNotExecutedAgain() throws Exception {
		Lobby game = api.startedGame("Retry");
		UUID key = UUID.randomUUID();

		MvcResult first = api.endTurn(game.host(), game.gameId(), 1, key);
		MvcResult retry = api.endTurn(game.host(), game.gameId(), 1, key);

		assertThat(status(retry)).isEqualTo(200);
		assertThat((Boolean) read(first, "$.replayed")).isFalse();
		assertThat((Boolean) read(retry, "$.replayed")).isTrue();
		assertThat((Integer) read(retry, "$.turnNumber")).isEqualTo(2);
		assertThat((Integer) read(retry, "$.activeSeat")).as("still the guest's turn").isEqualTo(1);
		assertThat(commands(game)).isEqualTo(1);
	}

	@Test
	void aStaleTurnNumberIsRejected() throws Exception {
		Lobby game = api.startedGame("Stale");
		api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());
		api.endTurn(game.guest(), game.gameId(), 2, UUID.randomUUID());

		// The host double-clicked in round 1 with a new key: it must not end the round-2 turn.
		MvcResult stale = api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());

		assertThat(status(stale)).isEqualTo(409);
		assertThat((String) read(stale, "$.code")).isEqualTo("TURN_CHANGED");
		assertThat((Integer) read(api.state(game.host(), game.gameId()), "$.turnNumber")).isEqualTo(3);
	}

	@Test
	void theIdempotencyKeyIsRequired() throws Exception {
		Lobby game = api.startedGame("Keyless");

		MvcResult keyless = api.endTurn(game.host(), game.gameId(), 1, null);

		assertThat(status(keyless)).isEqualTo(400);
		assertThat((String) read(keyless, "$.code")).isEqualTo("IDEMPOTENCY_KEY_MISSING");
	}

	@Test
	void lobbiesHaveNoTurns() throws Exception {
		Lobby lobby = api.readyLobby("No turns yet");

		MvcResult result = api.endTurn(lobby.host(), lobby.gameId(), 0, UUID.randomUUID());

		assertThat(status(result)).isEqualTo(409);
		assertThat((String) read(result, "$.code")).isEqualTo("GAME_NOT_IN_PROGRESS");
		assertThat(status(api.state(lobby.host(), lobby.gameId()))).isEqualTo(409);
	}

	@Test
	void concurrentEndTurnRequestsWithDifferentKeysAdvanceExactlyOnce() throws Exception {
		Lobby game = api.startedGame("Double click");

		List<Integer> statuses = Concurrently.run(8,
				i -> status(api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID())));

		assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
		assertThat((Integer) read(api.state(game.host(), game.gameId()), "$.turnNumber")).isEqualTo(2);
		assertThat(commands(game)).isEqualTo(1);
	}

	@Test
	void concurrentRetriesWithTheSameKeyExecuteOnce() throws Exception {
		Lobby game = api.startedGame("Same key");
		UUID key = UUID.randomUUID();

		List<MvcResult> results = Concurrently.run(8, i -> api.endTurn(game.host(), game.gameId(), 1, key));

		assertThat(results).allSatisfy(result -> assertThat(status(result)).isEqualTo(200));
		assertThat(results).filteredOn(result -> !(Boolean) readUnchecked(result, "$.replayed")).hasSize(1);
		assertThat(commands(game)).isEqualTo(1);
	}

	@Test
	void theFinalTurnOfARoundCompletesItExactlyOnceUnderConcurrency() throws Exception {
		Lobby game = api.startedGame("Round end");
		api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());

		List<Integer> statuses = Concurrently.run(8,
				i -> status(api.endTurn(game.guest(), game.gameId(), 2, UUID.randomUUID())));

		assertThat(statuses).containsOnlyOnce(200);
		assertThat(rounds(game)).isEqualTo(1);
		MvcResult state = api.state(game.host(), game.gameId());
		assertThat((Integer) read(state, "$.round")).isEqualTo(2);
		assertThat((Integer) read(state, "$.activeSeat")).isZero();
	}

	@Test
	void severalRoundsInARow() throws Exception {
		Lobby game = api.startedGame("Marathon");
		int turn = 1;
		for (int round = 1; round <= 5; round++) {
			assertThat(status(api.endTurn(game.host(), game.gameId(), turn++, UUID.randomUUID()))).isEqualTo(200);
			assertThat(status(api.endTurn(game.guest(), game.gameId(), turn++, UUID.randomUUID()))).isEqualTo(200);
		}
		assertThat(rounds(game)).isEqualTo(5);
		assertThat(jdbc.queryForList("SELECT round_number FROM game.round_resolution WHERE game_id = ?::uuid ORDER BY 1",
				Integer.class, game.gameId())).containsExactly(1, 2, 3, 4, 5);
		assertThat((Integer) read(api.state(game.guest(), game.gameId()), "$.round")).isEqualTo(6);
	}

	@Test
	void unchangedStateIsAnswered304() throws Exception {
		Lobby game = api.startedGame("Polling");
		MvcResult first = api.state(game.host(), game.gameId());
		String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
		assertThat(etag).isNotBlank();

		MvcResult again = mockMvc.perform(get("/api/v1/games/" + game.gameId() + "/state")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + game.guest())
			.header(HttpHeaders.IF_NONE_MATCH, etag)).andReturn();
		assertThat(status(again)).isEqualTo(304);

		api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());
		MvcResult changed = mockMvc.perform(get("/api/v1/games/" + game.gameId() + "/state")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + game.guest())
			.header(HttpHeaders.IF_NONE_MATCH, etag)).andReturn();
		assertThat(status(changed)).isEqualTo(200);
	}

	@Test
	void aMatchingEtagDoesNotLetOutsidersSeeTheGame() throws Exception {
		Lobby game = api.startedGame("Polling privacy");
		String etag = api.state(game.host(), game.gameId()).getResponse().getHeader(HttpHeaders.ETAG);

		// The cheap version check runs before the full read model; it must refuse non-members the same way.
		MvcResult outsider = mockMvc.perform(get("/api/v1/games/" + game.gameId() + "/state")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ApiDriver.newPlayer())
			.header(HttpHeaders.IF_NONE_MATCH, etag)).andReturn();
		assertThat(status(outsider)).isEqualTo(404);
	}

	private Integer rounds(Lobby game) {
		return jdbc.queryForObject("SELECT count(*) FROM game.round_resolution WHERE game_id = ?::uuid", Integer.class,
				game.gameId());
	}

	private Integer commands(Lobby game) {
		return jdbc.queryForObject("SELECT count(*) FROM game.game_command WHERE game_id = ?::uuid", Integer.class,
				game.gameId());
	}

	private static Object readUnchecked(MvcResult result, String path) {
		try {
			return read(result, path);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
