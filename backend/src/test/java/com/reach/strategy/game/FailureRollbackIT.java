package com.reach.strategy.game;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.game.application.RoundProcessor;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.world.application.WorldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Failures in the middle of a transaction leave no trace: the real work runs first (rows are
 * written), then an injected exception forces the rollback.
 */
@PostgresIntegrationTest
class FailureRollbackIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	WorldService world;

	@MockitoSpyBean
	RoundProcessor roundProcessor;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	@AfterEach
	void restoreSpies() {
		reset(spyBehind(world), spyBehind(roundProcessor));
	}

	@Test
	void aFailedWorldInitializationRollsBackTheWholeStart() throws Exception {
		Lobby lobby = api.readyLobby("Doomed start");
		api.chooseNation(lobby.host(), lobby.gameId(), lobby.firstNationId());
		api.chooseNation(lobby.guest(), lobby.gameId(), lobby.secondNationId());
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after the world rows were written");
		}).when(spyBehind(world)).instantiate(any(), any(), any(), any());

		MvcResult failed = api.start(lobby.host(), lobby.gameId());

		assertThat(status(failed)).isEqualTo(500);
		assertThat((String) read(failed, "$.code")).isEqualTo("INTERNAL_ERROR");
		assertThat((String) read(api.lobby(lobby.host(), lobby.gameId()), "$.status")).isEqualTo("READY");
		assertThat(count("game.nation", lobby.gameId())).isZero();
		assertThat(count("game.province_state", lobby.gameId())).isZero();
		assertThat(count("game.city", lobby.gameId())).isZero();

		reset(spyBehind(world));
		assertThat(status(api.start(lobby.host(), lobby.gameId()))).as("a retry succeeds").isEqualTo(200);
		assertThat(count("game.province_state", lobby.gameId())).isEqualTo(12);
	}

	@Test
	void aFailedRoundEndRollsBackTheEndOfTurn() throws Exception {
		Lobby game = api.startedGame("Doomed round");
		api.endTurn(game.host(), game.gameId(), 1, UUID.randomUUID());
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure in a round phase");
		}).when(spyBehind(roundProcessor)).completeRound(any(), anyInt(), any(), any());
		UUID key = UUID.randomUUID();

		MvcResult failed = api.endTurn(game.guest(), game.gameId(), 2, key);

		assertThat(status(failed)).isEqualTo(500);
		MvcResult state = api.state(game.guest(), game.gameId());
		assertThat((Integer) read(state, "$.round")).isEqualTo(1);
		assertThat((Integer) read(state, "$.activeSeat")).as("still the guest's turn").isEqualTo(1);
		assertThat(count("game.round_resolution", game.gameId())).isZero();
		assertThat(count("game.economy_round_report", game.gameId())).as("no nation was paid").isZero();
		assertThat(jdbc.queryForList("SELECT treasury_mg FROM game.nation_economy WHERE game_id = ?::uuid", Long.class,
				game.gameId())).as("every treasury unchanged").containsOnly(50_000L);
		assertThat(count("game.game_command", game.gameId())).as("only the host's command").isEqualTo(1);

		reset(spyBehind(roundProcessor));
		MvcResult retry = api.endTurn(game.guest(), game.gameId(), 2, key);
		assertThat(status(retry)).isEqualTo(200);
		assertThat((Boolean) read(retry, "$.replayed")).as("the failed attempt left no command behind").isFalse();
		assertThat((Integer) read(retry, "$.round")).isEqualTo(2);
		assertThat(count("game.round_resolution", game.gameId())).isEqualTo(1);
		assertThat(count("game.economy_round_report", game.gameId())).as("every nation paid exactly once").isEqualTo(2);
	}

	/** The beans are transactional proxies; stubbing must address the Mockito spy behind them. */
	private static <T> T spyBehind(T proxy) {
		return AopTestUtils.getUltimateTargetObject(proxy);
	}

	private Integer count(String table, String gameId) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE game_id = ?::uuid", Integer.class, gameId);
	}

}
