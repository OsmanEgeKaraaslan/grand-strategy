package com.reach.strategy.research;

import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.research.domain.ResearchLogRepository;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A failure while paying research rolls back the whole round end: no points, no income, no growth. */
@PostgresIntegrationTest
@TestPropertySource(properties = "strategy.ruleset.current-version=v4")
class ResearchRollbackIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	ResearchLogRepository log;

	ApiDriver api;

	MilitaryScenario world;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		world = new MilitaryScenario(api, jdbc);
	}

	@AfterEach
	void restore() {
		reset(spyBehind(log));
	}

	@Test
	void aFailedResearchRoundLeavesNothingBehind() throws Exception {
		Lobby game = world.startedGame();
		world.activeInstitution(game, "varnhold", "UNIVERSITY");
		world.endTurn(game, game.host(), 1);
		long treasury = world.treasury(game, "varn");
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after research was paid");
		}).when(spyBehind(log)).insertRound(any(), anyInt(), any(), anyInt(), anyInt(), anyInt());
		UUID key = UUID.randomUUID();

		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, key))).isEqualTo(500);

		assertThat(world.research(game, "varn")).containsEntry("civilian_points", 0);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_000);
		assertThat(world.treasury(game, "varn")).isEqualTo(treasury);
		assertThat(world.count("game.research_round_report", game)).isZero();
		assertThat(world.count("game.economy_round_report", game)).isZero();
		assertThat(world.count("game.population_growth", game)).isZero();

		reset(spyBehind(log));
		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, key))).isEqualTo(200);
		assertThat(world.research(game, "varn")).containsEntry("civilian_points", 10);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_040);
	}

	private static <T> T spyBehind(T proxy) {
		return AopTestUtils.getUltimateTargetObject(proxy);
	}

}
