package com.reach.strategy.game;

import static com.reach.strategy.support.ApiDriver.newPlayer;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Nation selection and game start against PostgreSQL, through the real API and security. */
@PostgresIntegrationTest
class GameStartIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	// --- nation selection -------------------------------------------------------------------

	@Test
	void playersPickDistinctNationsAndMayChangeThemBeforeTheStart() throws Exception {
		Lobby lobby = api.readyLobby("Choice");

		MvcResult picked = api.chooseNation(lobby.host(), lobby.gameId(), lobby.firstNationId());
		assertThat(status(picked)).isEqualTo(200);
		assertThat(ApiDriver.<List<Integer>>read(picked, "$.nations[?(@.id == '" + lobby.firstNationId() + "')].chosenBySeat")).containsExactly(0);

		MvcResult conflict = api.chooseNation(lobby.guest(), lobby.gameId(), lobby.firstNationId());
		assertThat(status(conflict)).isEqualTo(409);
		assertThat((String) read(conflict, "$.code")).isEqualTo("NATION_TAKEN");

		assertThat(status(api.chooseNation(lobby.host(), lobby.gameId(), lobby.secondNationId()))).isEqualTo(200);
		assertThat(status(api.chooseNation(lobby.guest(), lobby.gameId(), lobby.firstNationId()))).isEqualTo(200);
		assertThat((Boolean) read(api.lobby(lobby.host(), lobby.gameId()), "$.canStart")).isTrue();
		assertThat((Boolean) read(api.lobby(lobby.guest(), lobby.gameId()), "$.canStart")).isFalse();
	}

	@Test
	void outsidersCannotChooseNationsOrSeeTheGame() throws Exception {
		Lobby lobby = api.readyLobby("Private");
		String outsider = newPlayer();

		assertThat(status(api.chooseNation(outsider, lobby.gameId(), lobby.firstNationId()))).isEqualTo(404);
		assertThat(status(api.start(outsider, lobby.gameId()))).isEqualTo(404);
		assertThat(status(api.chooseNation(lobby.host(), lobby.gameId(), UUID.randomUUID().toString()))).isEqualTo(422);
	}

	@Test
	void concurrentPicksOfTheSameNationCannotBothSucceed() throws Exception {
		for (int round = 0; round < 5; round++) {
			Lobby lobby = api.readyLobby("Race " + round);
			List<String> players = List.of(lobby.host(), lobby.guest());

			List<Integer> statuses = Concurrently.run(2,
					i -> status(api.chooseNation(players.get(i), lobby.gameId(), lobby.firstNationId())));

			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
			assertThat(jdbc.queryForObject(
					"SELECT count(*) FROM game.game_player WHERE game_id = ?::uuid AND nation_template_id = ?::uuid",
					Integer.class, lobby.gameId(), lobby.firstNationId())).isEqualTo(1);
		}
	}

	// --- start --------------------------------------------------------------------------------

	@Test
	void theHostStartsAndTheWorldIsInstantiatedFromTheTemplate() throws Exception {
		Lobby lobby = api.startedGame("Genesis");

		MvcResult lobbyView = api.lobby(lobby.host(), lobby.gameId());
		assertThat((String) read(lobbyView, "$.status")).isEqualTo("IN_PROGRESS");
		assertThat(count("game.nation", lobby.gameId())).isEqualTo(2);
		assertThat(count("game.province_state", lobby.gameId())).isEqualTo(12);
		assertThat(count("game.city", lobby.gameId())).isEqualTo(12);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM game.province_state WHERE game_id = ?::uuid AND owner_nation_id <> controller_nation_id",
				Integer.class, lobby.gameId())).as("nobody is occupied at the start").isZero();

		MvcResult state = api.state(lobby.guest(), lobby.gameId());
		assertThat(status(state)).isEqualTo(200);
		assertThat((Integer) read(state, "$.round")).isEqualTo(1);
		assertThat((Integer) read(state, "$.activeSeat")).as("host goes first").isZero();
		assertThat((Boolean) read(state, "$.yourTurn")).isFalse();
		assertThat((List<?>) read(state, "$.provinces")).hasSize(12);
		// Ruleset v2 raises the starting forces (3 infantry, 1 cavalry) from the capital: 12,000 − 4,000.
		assertThat(ApiDriver.<List<Integer>>read(state, "$.provinces[?(@.key == 'varnhold')].city.population")).containsExactly(8000);
	}

	@Test
	void onlyTheHostCanStartAndOnlyWhenEveryoneHasADistinctNation() throws Exception {
		String host = newPlayer();
		MvcResult created = api.createGame(host, "Early");
		String gameId = read(created, "$.id");
		assertThat((String) read(api.start(host, gameId), "$.code")).isEqualTo("GAME_NOT_READY");

		Lobby lobby = api.readyLobby("Nations missing");
		api.chooseNation(lobby.host(), lobby.gameId(), lobby.firstNationId());
		assertThat((String) read(api.start(lobby.host(), lobby.gameId()), "$.code")).isEqualTo("NATIONS_NOT_SELECTED");

		api.chooseNation(lobby.guest(), lobby.gameId(), lobby.secondNationId());
		MvcResult byGuest = api.start(lobby.guest(), lobby.gameId());
		assertThat(status(byGuest)).isEqualTo(403);
		assertThat((String) read(byGuest, "$.code")).isEqualTo("NOT_HOST");
		assertThat(count("game.nation", lobby.gameId())).isZero();
	}

	@Test
	void repeatedAndConcurrentStartRequestsStartTheGameOnce() throws Exception {
		Lobby lobby = api.readyLobby("Once");
		api.chooseNation(lobby.host(), lobby.gameId(), lobby.firstNationId());
		api.chooseNation(lobby.guest(), lobby.gameId(), lobby.secondNationId());

		List<Integer> statuses = Concurrently.run(6, i -> status(api.start(lobby.host(), lobby.gameId())));
		assertThat(statuses).containsOnly(200);
		assertThat(status(api.start(lobby.host(), lobby.gameId()))).isEqualTo(200);

		assertThat(count("game.nation", lobby.gameId())).isEqualTo(2);
		assertThat(count("game.province_state", lobby.gameId())).isEqualTo(12);
		assertThat((Integer) read(api.state(lobby.host(), lobby.gameId()), "$.turnNumber")).isEqualTo(1);
	}

	@Test
	void nationAssignmentsAreLockedOnceStarted() throws Exception {
		Lobby lobby = api.startedGame("Locked");

		MvcResult change = api.chooseNation(lobby.host(), lobby.gameId(), lobby.secondNationId());
		assertThat(status(change)).isEqualTo(409);
		assertThat((String) read(change, "$.code")).isIn("GAME_ALREADY_STARTED");
	}

	// --- independence between games --------------------------------------------------------

	@Test
	void everyGameHasItsOwnIndependentWorld() throws Exception {
		Lobby first = api.startedGame("World A");
		Lobby second = api.startedGame("World B");

		Set<String> firstProvinces = new HashSet<>(ids("game.province_state", first.gameId()));
		Set<String> secondProvinces = new HashSet<>(ids("game.province_state", second.gameId()));
		assertThat(firstProvinces).hasSize(12).doesNotContainAnyElementsOf(secondProvinces);
		assertThat(ids("game.nation", first.gameId())).doesNotContainAnyElementsOf(ids("game.nation", second.gameId()));

		// Changing one game's state leaves the other untouched.
		jdbc.update("UPDATE game.city SET population = 1 WHERE game_id = ?::uuid", first.gameId());
		assertThat(ApiDriver.<List<Integer>>read(api.state(second.host(), second.gameId()),
				"$.provinces[?(@.key == 'varnhold')].city.population")).containsExactly(8000);
	}

	@Test
	void playersCannotReadOrActInOtherGames() throws Exception {
		Lobby first = api.startedGame("Mine");
		Lobby second = api.startedGame("Theirs");

		assertThat(status(api.state(second.host(), first.gameId()))).isEqualTo(404);
		assertThat(status(api.lobby(second.guest(), first.gameId()))).isEqualTo(404);
		assertThat(status(api.endTurn(second.host(), first.gameId(), 1, UUID.randomUUID()))).isEqualTo(404);
		assertThat((Integer) read(api.state(first.host(), first.gameId()), "$.turnNumber")).isEqualTo(1);
	}

	@Test
	void databaseRefusesToMixRowsOfDifferentGames() throws Exception {
		Lobby first = api.startedGame("FK A");
		Lobby second = api.startedGame("FK B");
		String foreignNation = ids("game.nation", second.gameId()).getFirst();
		String ownProvince = ids("game.province_state", first.gameId()).getFirst();

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE game.province_state SET controller_nation_id = ?::uuid WHERE id = ?::uuid", foreignNation,
				ownProvince))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Integer count(String table, String gameId) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE game_id = ?::uuid", Integer.class, gameId);
	}

	private List<String> ids(String table, String gameId) {
		return jdbc.queryForList("SELECT id::text FROM " + table + " WHERE game_id = ?::uuid ORDER BY id", String.class,
				gameId);
	}

}
