package com.reach.strategy.siege;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.siege.domain.SiegeRoundRepository;
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
import org.springframework.test.web.servlet.MvcResult;

/** A failure in siege processing rolls back the whole end of the round: no attrition, no progress, no income. */
@PostgresIntegrationTest
// Phase 7 rules: ruleset v3 (fortresses and sieges, no research and no population growth).
@TestPropertySource(properties = "strategy.ruleset.current-version=v3")
class SiegeRollbackIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	SiegeRoundRepository siegeRounds;

	ApiDriver api;

	MilitaryScenario world;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		world = new MilitaryScenario(api, jdbc);
	}

	@AfterEach
	void restore() {
		reset(spyBehind(siegeRounds));
	}

	@Test
	void aFailedSiegeRoundLeavesTheWholeRoundUnresolved() throws Exception {
		Lobby game = world.startedGame();
		String besieger = world.place(game, "varn", "elmsford", "INFANTRY", 1_000);
		api.move(game.host(), game.gameId(), List.of(besieger), "sablewater", UUID.randomUUID());
		world.endTurn(game, game.host(), 1);
		long treasury = world.treasury(game, "ostra");
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("simulated failure after the siege round was written");
		}).when(spyBehind(siegeRounds)).insert(any(), any(), any());
		UUID key = UUID.randomUUID();

		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, key))).isEqualTo(500);

		assertThat(world.population(game, "sablewater")).isEqualTo(8_000);
		assertThat(world.sieges(game).getFirst().get("progress")).isEqualTo(0);
		assertThat(world.count("game.siege_round", game)).isZero();
		assertThat(world.count("game.economy_round_report", game)).isZero();
		assertThat(world.count("game.round_resolution", game)).isZero();
		assertThat(world.treasury(game, "ostra")).isEqualTo(treasury);

		reset(spyBehind(siegeRounds));
		MvcResult retry = api.endTurn(game.guest(), game.gameId(), 2, key);
		assertThat(status(retry)).isEqualTo(200);
		assertThat((Boolean) read(retry, "$.roundCompleted")).isTrue();
		assertThat(world.population(game, "sablewater")).isEqualTo(7_600);
		assertThat(world.count("game.siege_round", game)).isEqualTo(1);
	}

	private static <T> T spyBehind(T proxy) {
		return AopTestUtils.getUltimateTargetObject(proxy);
	}

}
