package com.reach.strategy.social;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Scenario A (Phase 13A): friends → private lobby → invitation → nations → start → resume, three users. */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign" })
class FriendsPrivateGameScenarioIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void scenarioA_friendsInviteEachOtherIntoAPrivateCampaignAndResumeIt() throws Exception {
		ApiDriver api = new ApiDriver(mockMvc);
		Account a = api.newAccount("scen_a");
		Account b = api.newAccount("scen_b");
		Account c = api.newAccount("scen_c");

		// 1–3: A befriends B (C stays a stranger).
		String requestId = read(api.postJson(a.token(), "/friends/requests", "{\"username\":\"" + b.username() + "\"}"), "$.id");
		assertThat((String) read(api.postJson(b.token(), "/friends/requests/" + requestId + "/accept", "{}"), "$.status"))
			.isEqualTo("ACCEPTED");

		// 4–7: A creates a private Grand Campaign lobby and invites B, who accepts.
		MvcResult created = api.createGame(a.token(), "Friends' campaign", 2, "grand-campaign");
		String gameId = read(created, "$.id");
		String invitationId = read(api.postJson(a.token(), "/invitations",
				"{\"gameId\":\"" + gameId + "\",\"username\":\"" + b.username() + "\"}"), "$.id");
		assertThat(status(api.postJson(c.token(), "/invitations/" + invitationId + "/accept", "{}"))).isEqualTo(404);
		assertThat((String) read(api.postJson(b.token(), "/invitations/" + invitationId + "/accept", "{}"), "$.status"))
			.isEqualTo("ACCEPTED");

		// 8: both pick distinct playable nations through the normal lobby flow, then the host starts.
		MvcResult lobby = api.lobby(a.token(), gameId);
		List<String> playable = read(lobby, "$.nations[?(@.playable == true)].id");
		assertThat(status(api.chooseNation(b.token(), gameId, playable.get(0)))).isEqualTo(200);
		assertThat(read(api.chooseNation(a.token(), gameId, playable.get(0)), "$.code").toString()).isEqualTo("NATION_TAKEN");
		assertThat(status(api.chooseNation(a.token(), gameId, playable.get(1)))).isEqualTo(200);
		assertThat(status(api.start(a.token(), gameId))).isEqualTo(200);

		// 9: valid membership and computer seats for every other nation.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game_player WHERE game_id = ?::uuid AND NOT computer",
				Integer.class, gameId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.nation WHERE game_id = ?::uuid AND control = 'AI'",
				Integer.class, gameId)).isEqualTo(8);
		assertThat(status(api.state(c.token(), gameId))).isEqualTo(404);
		assertThat(ApiDriver.<List<String>>read(api.getJson(b.token(), "/notifications"), "$.items[*].kind")).contains("GAME_STARTED");

		// 10: both can resume later from their game lists; play continues where it was.
		for (Account player : List.of(a, b)) {
			MvcResult games = api.getJson(player.token(), "/games");
			assertThat(ApiDriver.<List<String>>read(games, "$[?(@.id == '" + gameId + "')].status")).containsExactly("IN_PROGRESS");
			assertThat(ApiDriver.<List<Integer>>read(games, "$[?(@.id == '" + gameId + "')].computerPlayers")).containsExactly(8);
		}
		assertThat(status(api.endTurn(a.token(), gameId, 1, UUID.randomUUID()))).isEqualTo(200);
		assertThat((Boolean) read(api.state(b.token(), gameId), "$.yourTurn")).isTrue();
	}

}
