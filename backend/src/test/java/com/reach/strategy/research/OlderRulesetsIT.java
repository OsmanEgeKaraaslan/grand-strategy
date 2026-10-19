package com.reach.strategy.research;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** A ruleset v3 game keeps Phase 7 behaviour: no research, no institutions, no upgrades, no growth. */
@PostgresIntegrationTest
@TestPropertySource(properties = "strategy.ruleset.current-version=v3")
class OlderRulesetsIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	MilitaryScenario world;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		world = new MilitaryScenario(api, jdbc);
	}

	@Test
	void aV3GameHasNoResearchNoUpgradesAndNoGrowth() throws Exception {
		Lobby game = world.startedGame();
		world.setTreasury(game, "varn", 1_000_000);
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");

		assertThat((Boolean) read(api.research(game.host(), game.gameId()), "$.available")).isFalse();
		assertThat(code(api.buildInstitution(game.host(), game.gameId(), varnhold, "UNIVERSITY", UUID.randomUUID())))
			.isEqualTo("422 NOT_AVAILABLE_IN_RULESET");
		assertThat(code(api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 2, UUID.randomUUID())))
			.isEqualTo("422 NOT_AVAILABLE_IN_RULESET");
		assertThat(code(api.upgradeWorkshop(game.host(), game.gameId(), varnhold, world.firstWorkshop(game, "varnhold"),
				UUID.randomUUID()))).isEqualTo("422 NOT_AVAILABLE_IN_RULESET");
		assertThat((Object) read(api.cityEconomy(game.host(), game.gameId(), varnhold), "$.growth")).isNull();
		assertThat(world.count("game.nation_research", game)).isZero();

		world.completeRound(game, 1);
		assertThat(world.population(game, "varnhold")).as("no growth before v4").isEqualTo(8_000);
		assertThat(world.count("game.population_growth", game)).isZero();
		assertThat(world.count("game.research_round_report", game)).isZero();
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.lastRound.upkeepMg")).isEqualTo(7_000);
	}

	/** Phase 9 leaves older games alone: no diplomacy, every nation permanently at war, no game end. */
	@Test
	void aV3GameKeepsPermanentWarAndNeverFinishes() throws Exception {
		Lobby game = world.startedGame();
		String ostra = world.nationId(game, "ostra");

		assertThat((Boolean) read(api.diplomacy(game.host(), game.gameId()), "$.available")).isFalse();
		assertThat(code(api.declareWar(game.host(), game.gameId(), ostra, UUID.randomUUID())))
			.isEqualTo("409 DIPLOMACY_NOT_AVAILABLE");
		assertThat(world.count("game.diplomacy_game", game)).isZero();

		String varn = world.place(game, "varn", "elmsford", "INFANTRY", 1000);
		MvcResult moved = api.move(game.host(), game.gameId(), java.util.List.of(varn), "sablewater", UUID.randomUUID());
		assertThat(status(moved)).as("no declaration needed before v5").isEqualTo(200);

		jdbc.update("UPDATE game.game SET current_round = 30, turn_number = 59, active_seat = 0 WHERE id = ?::uuid",
				game.gameId());
		world.endTurn(game, game.host(), 59);
		world.endTurn(game, game.guest(), 60);
		assertThat((String) read(api.state(game.host(), game.gameId()), "$.status")).isEqualTo("IN_PROGRESS");
		assertThat(world.count("game.game_result", game)).isZero();
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private static long longAt(MvcResult result, String path) throws Exception {
		return ((Number) read(result, path)).longValue();
	}

}
