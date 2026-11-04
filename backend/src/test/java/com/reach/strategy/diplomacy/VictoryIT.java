package com.reach.strategy.diplomacy;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PROVISIONAL Q-T2 match completion (ruleset v5): the game ends after round 30, standings by legal
 * ownership (20 points per province, 1 per full 1,000 people), the result is stored once and the
 * final state stays readable while every command is refused. The map is symmetric, so an
 * untouched game is a draw.
 */
@PostgresIntegrationTest
class VictoryIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	MilitaryScenario world;

	DiplomacyScenario d;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		d = new DiplomacyScenario(api, jdbc);
		world = d.world;
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private long ownedPopulation(Lobby game, String nationKey) {
		return jdbc.queryForObject("""
				SELECT coalesce(sum(c.population), 0) FROM game.city c
				JOIN game.province_state ps ON ps.id = c.province_state_id
				WHERE c.game_id = ?::uuid AND ps.owner_nation_id = ?::uuid
				""", Long.class, game.gameId(), world.nationId(game, nationKey));
	}

	/** Legal transfer stand-in (what a treaty would have done in an earlier round). */
	private void cede(Lobby game, String provinceKey, String toNationKey) {
		jdbc.update("""
				UPDATE game.province_state ps SET owner_nation_id = ?::uuid, controller_nation_id = ?::uuid
				FROM game.province_template pt WHERE ps.province_template_id = pt.id AND pt.key = ? AND ps.game_id = ?::uuid
				""", world.nationId(game, toNationKey), world.nationId(game, toNationKey), provinceKey, game.gameId());
	}

	@Test
	void theGameEndsAfterRoundThirtyWithTheLargestLegalTerritoryWinning() throws Exception {
		Lobby game = world.startedGame();
		cede(game, "saltreach", "varn");
		// Occupation does not count: Ostra holds Varn's Fenwick militarily only.
		world.occupy(game, "fenwick", "ostra");
		d.jumpToRound(game, 30);
		world.endTurn(game, game.host(), 59);

		MvcResult last = api.endTurn(game.guest(), game.gameId(), 60, UUID.randomUUID());

		assertThat(status(last)).isEqualTo(200);
		assertThat((Boolean) read(last, "$.finished")).isTrue();
		assertThat((Integer) read(last, "$.round")).isEqualTo(30);
		assertThat(d.gameStatus(game)).isEqualTo("FINISHED");

		MvcResult result = api.result(game.guest(), game.gameId());
		assertThat(status(result)).isEqualTo(200);
		assertThat((String) read(result, "$.outcome")).isEqualTo("WINNER");
		assertThat((String) read(result, "$.reason")).isEqualTo("ROUND_LIMIT");
		assertThat((Integer) read(result, "$.finishedRound")).isEqualTo(30);
		assertThat((String) read(result, "$.winnerNationId")).isEqualTo(d.varn(game));
		assertThat((String) read(result, "$.winnerName")).isEqualTo("Kingdom of Berlardfaul");
		long varnScore = 7 * 20 + ownedPopulation(game, "varn") / 1000;
		long ostraScore = 5 * 20 + ownedPopulation(game, "ostra") / 1000;
		assertThat(ApiDriver.<List<Integer>>read(result, "$.standings[*].rank")).containsExactly(1, 2);
		assertThat(ApiDriver.<List<Integer>>read(result, "$.standings[*].ownedProvinces")).containsExactly(7, 5);
		assertThat(ApiDriver.<List<Number>>read(result, "$.standings[*].score")).extracting(Number::longValue)
			.containsExactly(varnScore, ostraScore);
		assertThat(ApiDriver.<List<Boolean>>read(result, "$.standings[*].you")).containsExactly(false, true);
		assertThat(((Number) read(result, "$.statistics.roundsPlayed")).intValue()).isEqualTo(30);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game_result_standing WHERE game_id = ?::uuid",
				Integer.class, game.gameId())).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.game_result_standing s JOIN game.player p ON p.id = s.player_id
				WHERE s.game_id = ?::uuid
				""", Integer.class, game.gameId())).as("standings name the players").isEqualTo(2);
	}

	@Test
	void aFinishedGameStaysReadableButRefusesEveryCommand() throws Exception {
		Lobby game = world.startedGame();
		d.jumpToRound(game, 30);
		world.endTurn(game, game.host(), 59);
		world.endTurn(game, game.guest(), 60);
		long version = world.stateVersion(game);
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");

		MvcResult state = api.state(game.host(), game.gameId());
		assertThat(status(state)).isEqualTo(200);
		assertThat((String) read(state, "$.status")).isEqualTo("FINISHED");
		assertThat((Boolean) read(state, "$.yourTurn")).isFalse();
		assertThat(status(api.military(game.host(), game.gameId()))).isEqualTo(200);
		assertThat(status(api.economy(game.host(), game.gameId()))).isEqualTo(200);
		assertThat(status(api.research(game.host(), game.gameId()))).isEqualTo(200);
		assertThat(status(api.sieges(game.host(), game.gameId()))).isEqualTo(200);
		assertThat((Boolean) read(api.diplomacy(game.host(), game.gameId()), "$.yourTurn")).isFalse();
		assertThat((String) read(api.lobby(game.host(), game.gameId()), "$.status")).isEqualTo("FINISHED");

		assertThat(code(api.endTurn(game.host(), game.gameId(), 60, UUID.randomUUID()))).isEqualTo("409 GAME_FINISHED");
		assertThat(code(api.declareWar(game.host(), game.gameId(), d.ostra(game), UUID.randomUUID())))
			.isEqualTo("409 GAME_FINISHED");
		assertThat(code(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("409 GAME_FINISHED");
		assertThat(code(api.buildWorkshop(game.host(), game.gameId(), varnhold, 1, UUID.randomUUID())))
			.isEqualTo("409 GAME_FINISHED");
		assertThat(world.stateVersion(game)).isEqualTo(version);
		assertThat(world.count("game.game_result", game)).isEqualTo(1);
	}

	@Test
	void anUntouchedSymmetricGameIsADraw() throws Exception {
		Lobby game = world.startedGame();
		d.jumpToRound(game, 30);
		world.completeRound(game, 30);

		MvcResult result = api.result(game.host(), game.gameId());
		assertThat((String) read(result, "$.outcome")).isEqualTo("DRAW");
		assertThat((Object) read(result, "$.winnerNationId")).isNull();
		assertThat(ApiDriver.<List<Integer>>read(result, "$.standings[*].rank")).containsExactly(1, 1);
	}

	@Test
	void theLastRoundCompletesAndTheGameFinishesExactlyOnce() throws Exception {
		Lobby game = world.startedGame();
		d.jumpToRound(game, 30);
		world.endTurn(game, game.host(), 59);

		List<Integer> statuses = Concurrently.run(4,
				i -> status(api.endTurn(game.guest(), game.gameId(), 60, UUID.randomUUID())));

		assertThat(statuses).containsOnlyOnce(200);
		assertThat(statuses).filteredOn(s -> s != 200).containsOnly(409);
		assertThat(world.count("game.game_result", game)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.round_resolution WHERE game_id = ?::uuid AND round_number = 30",
				Integer.class, game.gameId())).isEqualTo(1);
	}

	@Test
	void noResultBeforeTheEndAndRoundTwentyNineIsNotTheEnd() throws Exception {
		Lobby game = world.startedGame();
		d.jumpToRound(game, 29);
		world.completeRound(game, 29);
		assertThat(d.gameStatus(game)).isEqualTo("IN_PROGRESS");
		assertThat(code(api.result(game.host(), game.gameId()))).isEqualTo("409 GAME_NOT_FINISHED");
	}

}
