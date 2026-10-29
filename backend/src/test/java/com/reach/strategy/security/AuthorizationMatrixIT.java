package com.reach.strategy.security;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 15B authorization audit as a regression matrix: every game-scoped endpoint, called by an
 * anonymous client, by a signed-in player who is not a member, and with ids of another game, must
 * neither reveal nor change anything. Identity always comes from the token, never from the request.
 */
@PostgresIntegrationTest
class AuthorizationMatrixIT {

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

	record Endpoint(HttpMethod method, String path, String body) {

		@Override
		public String toString() {
			return method + " " + path;
		}

	}

	/** Every game-scoped endpoint with a well-formed body, so validation never answers first. */
	static List<Endpoint> gameEndpoints(String gameId, String cityId, String battalionId, String provinceKey) {
		String g = "/api/v1/games/" + gameId;
		String c = g + "/cities/" + cityId;
		String any = UUID.randomUUID().toString();
		String d = g + "/diplomacy";
		String term = "{\"type\":\"GOLD\",\"fromNationId\":\"" + any + "\",\"toNationId\":\"" + any + "\",\"amountMg\":1000}";
		List<Endpoint> all = new ArrayList<>();
		for (String read : List.of(g, g + "/state", g + "/result", g + "/economy", c + "/economy", g + "/military",
				g + "/provinces/" + provinceKey + "/military", g + "/battles", g + "/battles/" + any,
				g + "/battalions/" + battalionId, g + "/research", c + "/institutions", g + "/sieges",
				g + "/sieges/" + any, g + "/provinces/" + provinceKey + "/fortification", d, d + "/wars/" + any,
				d + "/peace-proposals", d + "/peace-proposals/" + any, d + "/treaties", d + "/tributes", d + "/events",
				d + "/conferences/" + any, g + "/invitations")) {
			all.add(new Endpoint(HttpMethod.GET, read, null));
		}
		all.add(new Endpoint(HttpMethod.PUT, g + "/players/me/nation", "{\"nationId\":\"" + any + "\"}"));
		Map<String, String> commands = new java.util.LinkedHashMap<>();
		commands.put(g + "/start", null);
		commands.put(g + "/turn/end", "{\"expectedTurnNumber\":1}");
		commands.put(c + "/workshops", "{\"tier\":1}");
		commands.put(c + "/workshops/" + any + "/repair", null);
		commands.put(c + "/workshops/" + any + "/upgrade", null);
		commands.put(c + "/recruitments", "{\"unitType\":\"INFANTRY\"}");
		commands.put(g + "/moves", "{\"battalionIds\":[\"" + battalionId + "\"],\"toProvinceKey\":\"" + provinceKey + "\"}");
		commands.put(g + "/armies", "{\"name\":\"Host\",\"battalionIds\":[\"" + battalionId + "\"]}");
		commands.put(g + "/attachments", "{\"battalionIds\":[\"" + battalionId + "\"],\"commanderNationId\":\"" + any + "\"}");
		commands.put(g + "/attachments/release", "{\"battalionIds\":[\"" + battalionId + "\"]}");
		commands.put(g + "/battalions/" + battalionId + "/reinforcements", "{\"soldiers\":10}");
		commands.put(g + "/battalions/" + battalionId + "/disband", null);
		commands.put(c + "/institutions", "{\"type\":\"MILITARY_ACADEMY\"}");
		commands.put(g + "/research/technologies", "{\"track\":\"MILITARY\",\"targetLevel\":2}");
		commands.put(g + "/research/doctrines", "{\"doctrine\":\"OFFENSIVE\"}");
		commands.put(c + "/fortress", "{\"targetLevel\":1}");
		commands.put(c + "/fortress/repair", null);
		commands.put(d + "/peace-proposals/check", "{\"warId\":\"" + any + "\",\"terms\":[" + term + "]}");
		commands.put(d + "/wars", "{\"targetNationId\":\"" + any + "\"}");
		commands.put(d + "/alliances", "{\"nationId\":\"" + any + "\",\"kind\":\"NORMAL\"}");
		commands.put(d + "/alliances/" + any + "/accept", null);
		commands.put(d + "/alliances/" + any + "/reject", null);
		commands.put(d + "/alliances/" + any + "/terminate", null);
		commands.put(d + "/gifts", "{\"recipientNationId\":\"" + any + "\",\"amountMg\":1000}");
		commands.put(d + "/wars/" + any + "/calls", "{\"allyNationId\":\"" + any + "\"}");
		commands.put(d + "/calls/" + any + "/answer", "{\"accept\":true}");
		commands.put(d + "/wars/" + any + "/conferences", "{\"kind\":\"GENERAL\"}");
		commands.put(d + "/conferences/" + any + "/answer", "{\"accept\":true}");
		commands.put(d + "/conferences/" + any + "/cancel", null);
		commands.put(d + "/conferences/" + any + "/drafts", "{\"expectedRevision\":0,\"terms\":[" + term + "]}");
		commands.put(d + "/conferences/" + any + "/revisions/1/approve", null);
		commands.put(d + "/conferences/" + any + "/revisions/1/reject", null);
		commands.put(d + "/peace-proposals", "{\"warId\":\"" + any + "\",\"terms\":[" + term + "]}");
		commands.put(d + "/peace-proposals/" + any + "/accept", null);
		commands.put(d + "/peace-proposals/" + any + "/reject", null);
		commands.put(d + "/peace-proposals/" + any + "/cancel", null);
		commands.put(d + "/peace-proposals/" + any + "/counter", "{\"terms\":[" + term + "]}");
		commands.forEach((path, json) -> all.add(new Endpoint(HttpMethod.POST, path, json)));
		return all;
	}

	/** Endpoints that are not scoped to one game. */
	static List<Endpoint> accountEndpoints() {
		String any = UUID.randomUUID().toString();
		return List.of(new Endpoint(HttpMethod.GET, "/api/v1/me", null),
				new Endpoint(HttpMethod.PATCH, "/api/v1/me", "{\"emblem\":\"tower\"}"),
				new Endpoint(HttpMethod.POST, "/api/v1/me/terms-acceptance", "{\"termsVersion\":\"a\",\"privacyVersion\":\"b\"}"),
				new Endpoint(HttpMethod.POST, "/api/v1/me/deletion", "{\"confirmation\":\"DELETE\"}"),
				new Endpoint(HttpMethod.GET, "/api/v1/games", null),
				new Endpoint(HttpMethod.POST, "/api/v1/games", "{\"name\":\"Anon\"}"),
				new Endpoint(HttpMethod.POST, "/api/v1/games/join", "{\"inviteCode\":\"ABCDEFGH\"}"),
				new Endpoint(HttpMethod.GET, "/api/v1/maps", null), new Endpoint(HttpMethod.GET, "/api/v1/maps/" + any, null),
				new Endpoint(HttpMethod.GET, "/api/v1/profiles/someone", null),
				new Endpoint(HttpMethod.GET, "/api/v1/players/search?q=abc", null),
				new Endpoint(HttpMethod.GET, "/api/v1/friends", null),
				new Endpoint(HttpMethod.POST, "/api/v1/friends/requests", "{\"username\":\"someone\"}"),
				new Endpoint(HttpMethod.POST, "/api/v1/friends/requests/" + any + "/accept", null),
				new Endpoint(HttpMethod.DELETE, "/api/v1/friends/someone", null),
				new Endpoint(HttpMethod.POST, "/api/v1/blocks", "{\"username\":\"someone\"}"),
				new Endpoint(HttpMethod.DELETE, "/api/v1/blocks/someone", null),
				new Endpoint(HttpMethod.GET, "/api/v1/notifications", null),
				new Endpoint(HttpMethod.GET, "/api/v1/notifications/unread-count", null),
				new Endpoint(HttpMethod.POST, "/api/v1/notifications/read", "{}"),
				new Endpoint(HttpMethod.GET, "/api/v1/invitations", null),
				new Endpoint(HttpMethod.POST, "/api/v1/invitations", "{\"gameId\":\"" + any + "\",\"username\":\"someone\"}"),
				new Endpoint(HttpMethod.POST, "/api/v1/invitations/" + any + "/accept", null),
				new Endpoint(HttpMethod.GET, "/api/v1/matchmaking/quick", null),
				new Endpoint(HttpMethod.POST, "/api/v1/matchmaking/quick/join", null),
				new Endpoint(HttpMethod.POST, "/api/v1/matchmaking/quick/heartbeat", null),
				new Endpoint(HttpMethod.POST, "/api/v1/matchmaking/quick/cancel", null),
				new Endpoint(HttpMethod.POST, "/api/v1/matchmaking/quick/acknowledge", null));
	}

	private MvcResult call(Endpoint endpoint, String token) throws Exception {
		MockHttpServletRequestBuilder builder = request(endpoint.method(), endpoint.path())
			.header("Idempotency-Key", UUID.randomUUID().toString());
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		if (endpoint.body() != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(endpoint.body());
		}
		return mockMvc.perform(builder).andReturn();
	}

	/** A fingerprint of a game's mutable state: any applied command changes it. */
	private Map<String, Object> fingerprint(String gameId) {
		return jdbc.queryForMap("""
				SELECT g.state_version, g.turn_number, g.status,
				       (SELECT count(*) FROM game.game_command c WHERE c.game_id = g.id) AS commands,
				       (SELECT count(*) FROM game.battalion b WHERE b.game_id = g.id) AS battalions
				FROM game.game g WHERE g.id = ?::uuid
				""", gameId);
	}

	private static String withoutInstance(String json) {
		return json.replaceAll("\"instance\":\"[^\"]*\"", "");
	}

	private Lobby gameWithABattalion() throws Exception {
		Lobby game = world.startedGame();
		world.place(game, "varn", "varnhold", "INFANTRY", 500);
		return game;
	}

	@Test
	void everyEndpointRefusesAnonymousCallers() throws Exception {
		Lobby game = gameWithABattalion();
		String city = api.cityId(game.host(), game.gameId(), "varnhold");
		String battalion = world.battalions(game, "varn", "varnhold").getFirst();
		Map<String, Object> before = fingerprint(game.gameId());

		List<String> leaks = new ArrayList<>();
		List<Endpoint> endpoints = new ArrayList<>(gameEndpoints(game.gameId(), city, battalion, "varnhold"));
		endpoints.addAll(accountEndpoints());
		for (Endpoint endpoint : endpoints) {
			MvcResult result = call(endpoint, null);
			if (status(result) != 401 || !body(result).contains("\"UNAUTHENTICATED\"")) {
				leaks.add(endpoint + " -> " + status(result));
			}
		}
		assertThat(leaks).isEmpty();
		assertThat(fingerprint(game.gameId())).isEqualTo(before);
	}

	@Test
	void aSignedInOutsiderCanNeitherSeeNorChangeAGame() throws Exception {
		Lobby game = gameWithABattalion();
		String city = api.cityId(game.host(), game.gameId(), "varnhold");
		String battalion = world.battalions(game, "varn", "varnhold").getFirst();
		Map<String, Object> before = fingerprint(game.gameId());
		String outsider = ApiDriver.newPlayer();

		List<String> leaks = new ArrayList<>();
		for (Endpoint endpoint : gameEndpoints(game.gameId(), city, battalion, "varnhold")) {
			MvcResult result = call(endpoint, outsider);
			// Indistinguishable from a game that does not exist: no existence, ruleset or turn leaks.
			if (status(result) != 404 || !"GAME_NOT_FOUND".equals(read(result, "$.code"))) {
				leaks.add(endpoint + " -> " + status(result) + " " + body(result));
			}
		}
		assertThat(leaks).isEmpty();
		assertThat(fingerprint(game.gameId())).isEqualTo(before);
	}

	@Test
	void idsOfAnotherGameCannotBeUsedThroughOnesOwnGame() throws Exception {
		Lobby mine = gameWithABattalion();
		Lobby theirs = gameWithABattalion();
		String theirCity = api.cityId(theirs.host(), theirs.gameId(), "varnhold");
		String theirBattalion = world.battalions(theirs, "varn", "varnhold").getFirst();
		Map<String, Object> theirsBefore = fingerprint(theirs.gameId());

		List<String> leaks = new ArrayList<>();
		// The host of "mine" is the active player there, so turn checks do not stop these requests first.
		for (Endpoint endpoint : gameEndpoints(mine.gameId(), theirCity, theirBattalion, "varnhold")) {
			if (!endpoint.path().contains(theirCity) && !endpoint.path().contains(theirBattalion)
					&& (endpoint.body() == null || !endpoint.body().contains(theirBattalion))) {
				continue;
			}
			MvcResult result = call(endpoint, mine.host());
			if (status(result) < 400 || status(result) >= 500) {
				leaks.add(endpoint + " -> " + status(result) + " " + body(result));
			}
			// The problem's "instance" repeats the caller's own URL; anything else must not mention the foreign ids.
			else if (withoutInstance(body(result)).contains(theirCity) || withoutInstance(body(result)).contains(theirBattalion)) {
				leaks.add(endpoint + " echoes the foreign id: " + body(result));
			}
		}
		assertThat(leaks).isEmpty();
		assertThat(fingerprint(theirs.gameId())).isEqualTo(theirsBefore);
	}

	@Test
	void aMemberCannotCommandAnotherNationsCitiesOrBattalionsOnTheirOwnTurn() throws Exception {
		Lobby game = gameWithABattalion();
		world.completeRound(game, 1);
		world.endTurn(game, game.host(), 3);
		// Now it is the guest's (ostra's) turn; the targets belong to varn.
		String hostCity = api.cityId(game.host(), game.gameId(), "varnhold");
		String hostBattalion = world.battalions(game, "varn", "varnhold").getFirst();
		Map<String, Object> before = fingerprint(game.gameId());

		List<String> leaks = new ArrayList<>();
		for (Endpoint endpoint : gameEndpoints(game.gameId(), hostCity, hostBattalion, "ostrava")) {
			boolean targetsHostAssets = endpoint.method() == HttpMethod.POST
					&& (endpoint.path().contains(hostCity) || endpoint.path().contains(hostBattalion)
							|| (endpoint.body() != null && endpoint.body().contains(hostBattalion)));
			if (!targetsHostAssets) {
				continue;
			}
			MvcResult result = call(endpoint, game.guest());
			if (status(result) < 400 || status(result) >= 500) {
				leaks.add(endpoint + " -> " + status(result) + " " + body(result));
			}
		}
		assertThat(leaks).isEmpty();
		assertThat(fingerprint(game.gameId())).isEqualTo(before);
	}

	@Test
	void aMemberWhoseTurnItIsNotCannotIssueCommands() throws Exception {
		Lobby game = gameWithABattalion();
		String guestCity = api.cityId(game.guest(), game.gameId(), "ostrava");
		Map<String, Object> before = fingerprint(game.gameId());

		for (Endpoint endpoint : List.of(
				new Endpoint(HttpMethod.POST, "/api/v1/games/" + game.gameId() + "/turn/end", "{\"expectedTurnNumber\":1}"),
				new Endpoint(HttpMethod.POST, "/api/v1/games/" + game.gameId() + "/cities/" + guestCity + "/recruitments",
						"{\"unitType\":\"INFANTRY\"}"),
				new Endpoint(HttpMethod.POST, "/api/v1/games/" + game.gameId() + "/cities/" + guestCity + "/workshops",
						"{\"tier\":1}"))) {
			MvcResult result = call(endpoint, game.guest());
			assertThat(status(result) + " " + read(result, "$.code")).as(endpoint.toString())
				.isEqualTo("409 NOT_ACTIVE_PLAYER");
		}
		assertThat(fingerprint(game.gameId())).isEqualTo(before);
	}

	@Test
	void lobbyActionsAreLimitedToMembersAndTheHost() throws Exception {
		Lobby lobby = api.readyLobby("Authz lobby");
		String outsider = ApiDriver.newPlayer();
		String nation = lobby.firstNationId();

		assertThat(status(api.chooseNation(outsider, lobby.gameId(), nation))).isEqualTo(404);
		assertThat(status(api.lobby(outsider, lobby.gameId()))).isEqualTo(404);
		assertThat(status(api.start(outsider, lobby.gameId()))).isEqualTo(404);
		api.chooseNation(lobby.host(), lobby.gameId(), lobby.firstNationId());
		api.chooseNation(lobby.guest(), lobby.gameId(), lobby.secondNationId());
		MvcResult guestStarts = api.start(lobby.guest(), lobby.gameId());
		assertThat(status(guestStarts) + " " + read(guestStarts, "$.code")).isEqualTo("403 NOT_HOST");
		// Sent invitations of a lobby are the host's business only.
		assertThat(status(api.getJson(outsider, "/games/" + lobby.gameId() + "/invitations"))).isEqualTo(404);
	}

	@Test
	void socialRequestsOfOtherPlayersCannotBeAnsweredOrCancelled() throws Exception {
		ApiDriver.Account sender = api.newAccount("sender");
		ApiDriver.Account recipient = api.newAccount("recipient");
		ApiDriver.Account intruder = api.newAccount("intruder");
		String requestId = read(api.postJson(sender.token(), "/friends/requests",
				"{\"username\":\"" + recipient.username() + "\"}"), "$.id");

		for (String action : List.of("accept", "decline", "cancel")) {
			MvcResult result = api.postJson(intruder.token(), "/friends/requests/" + requestId + "/" + action, "{}");
			assertThat(status(result)).as(action).isEqualTo(404);
		}
		// The sender cannot accept their own request on the recipient's behalf.
		assertThat(status(api.postJson(sender.token(), "/friends/requests/" + requestId + "/accept", "{}")))
			.isIn(404, 409);
		assertThat(jdbc.queryForObject("SELECT status FROM game.friend_request WHERE id = ?::uuid", String.class,
				requestId)).isEqualTo("PENDING");

		// Marking someone else's notifications as read does nothing to them.
		String notification = jdbc.queryForObject(
				"SELECT id::text FROM game.notification WHERE player_id = ?::uuid LIMIT 1", String.class, recipient.id());
		api.postJson(intruder.token(), "/notifications/read", "{\"ids\":[\"" + notification + "\"]}");
		assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM game.notification WHERE id = ?::uuid",
				Boolean.class, notification)).isTrue();
	}

}
