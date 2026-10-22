package com.reach.strategy.social;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import com.reach.strategy.support.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 12A against PostgreSQL: handles on provisioning, profile changes and their races, public
 * versus private fields, and statistics computed from results stored by the real game flow
 * (scenario C).
 */
@PostgresIntegrationTest
class ProfileIT {

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

	private String username(String token) throws Exception {
		return read(api.getJson(token, "/me"), "$.username");
	}

	@Test
	void everyNewAccountGetsAValidHandleFromItsNameButNeverFromItsEmail() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 6);
		String token = TestJwtIssuer.get()
			.tokenFor(UUID.randomUUID())
			.displayName("Aldric " + suffix)
			.claim("email", "aldric@example.com")
			.sign();

		MvcResult me = api.getJson(token, "/me");

		assertThat(status(me)).isEqualTo(200);
		assertThat((String) read(me, "$.username")).isEqualTo("aldric_" + suffix);
		assertThat((Boolean) read(me, "$.usernameChosen")).isFalse();
		assertThat(body(me)).doesNotContain("example.com");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.player WHERE kind = 'HUMAN' AND username IS NULL",
				Integer.class)).as("every human account has a handle (migration V16 backfill + provisioning)").isZero();
	}

	@Test
	void handlesAreValidatedReservedAndUniqueIgnoringCase() throws Exception {
		ApiDriver.Account first = api.newAccount("taken");
		String other = ApiDriver.newPlayer();

		assertThat(read(api.patchJson(other, "/me", "{\"username\":\"" + first.username().toUpperCase() + "\"}"), "$.code")
			.toString()).isEqualTo("USERNAME_TAKEN");
		assertThat(read(api.patchJson(other, "/me", "{\"username\":\"Admin\"}"), "$.code").toString())
			.isEqualTo("USERNAME_RESERVED");
		assertThat(read(api.patchJson(other, "/me", "{\"username\":\"<b>x</b>\"}"), "$.code").toString())
			.isEqualTo("INVALID_USERNAME");
		assertThat(read(api.patchJson(other, "/me", "{\"displayName\":\"<script>alert(1)</script>\"}"), "$.code")
			.toString()).isEqualTo("INVALID_DISPLAY_NAME");
		assertThat(status(api.patchJson(other, "/me", "{}"))).isEqualTo(400);

		MvcResult mine = api.patchJson(other, "/me", "{\"username\":\"Fresh_" + first.username().substring(6) + "\",\"emblem\":\"tower\"}");
		assertThat(status(mine)).isEqualTo(200);
		assertThat((String) read(mine, "$.username")).isEqualTo("fresh_" + first.username().substring(6));
		assertThat((Boolean) read(mine, "$.usernameChosen")).isTrue();
		assertThat((String) read(mine, "$.emblem")).isEqualTo("tower");
		assertThat(read(api.patchJson(other, "/me", "{\"emblem\":\"http://x/y.png\"}"), "$.code").toString())
			.isEqualTo("INVALID_EMBLEM");
	}

	@Test
	void concurrentClaimsOfOneHandleLeaveExactlyOneOwner() throws Exception {
		List<String> tokens = List.of(ApiDriver.newPlayer(), ApiDriver.newPlayer(), ApiDriver.newPlayer(),
				ApiDriver.newPlayer(), ApiDriver.newPlayer(), ApiDriver.newPlayer());
		for (String token : tokens) {
			api.getJson(token, "/me");
		}
		String wanted = ("race_" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20);

		List<Integer> statuses = Concurrently.run(tokens.size(),
				i -> status(api.patchJson(tokens.get(i), "/me", "{\"username\":\"" + wanted + "\"}")));

		assertThat(statuses).containsOnlyOnce(200);
		assertThat(statuses).filteredOn(s -> s != 200).containsOnly(409);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.player WHERE username = ?", Integer.class, wanted))
			.isEqualTo(1);
	}

	@Test
	void publicProfilesShowOnlyPublicFields() throws Exception {
		ApiDriver.Account owner = api.newAccount("public");
		String viewer = ApiDriver.newPlayer();

		MvcResult profile = api.getJson(viewer, "/profiles/" + owner.username().toUpperCase());

		assertThat(status(profile)).isEqualTo(200);
		Map<String, Object> fields = JsonPath.read(body(profile), "$");
		assertThat(fields.keySet()).containsExactlyInAnyOrder("username", "displayName", "emblem", "memberSince", "you",
				"relation", "matchHistoryPublic", "statistics", "recentMatches");
		assertThat((Boolean) read(profile, "$.you")).isFalse();
		assertThat((Integer) read(profile, "$.statistics.completed")).isZero();
		assertThat((Object) read(profile, "$.statistics.winRateBps")).isNull();
		assertThat(body(profile)).doesNotContain(owner.id());
		assertThat(status(api.getJson(viewer, "/profiles/nobody_" + UUID.randomUUID().toString().substring(0, 8))))
			.isEqualTo(404);
		assertThat(status(api.anonymous("/api/v1/profiles/" + owner.username()))).isEqualTo(401);
	}

	@Test
	void computerAccountsHaveNoProfile() throws Exception {
		String computerName = jdbc.queryForList("SELECT display_name FROM game.player WHERE kind = 'COMPUTER' LIMIT 1",
				String.class).stream().findFirst().orElse("AI#000000000000");
		assertThat(status(api.getJson(ApiDriver.newPlayer(), "/profiles/" + computerName))).isEqualTo(404);
	}

	// --- Scenario C: statistics from the authoritative result flow -----------------------------

	/** Varn (host) wins at the round limit after a province is ceded to it (as in VictoryIT). */
	private Lobby finishedGameWonByHost() throws Exception {
		Lobby game = world.startedGame();
		jdbc.update("""
				UPDATE game.province_state ps SET owner_nation_id = ?::uuid, controller_nation_id = ?::uuid
				FROM game.province_template pt WHERE ps.province_template_id = pt.id AND pt.key = 'saltreach' AND ps.game_id = ?::uuid
				""", world.nationId(game, "varn"), world.nationId(game, "varn"), game.gameId());
		jumpToRound(game, 30);
		world.endTurn(game, game.host(), 59);
		// Several end-turn requests race for the final round: the result is stored exactly once.
		List<Integer> statuses = Concurrently.run(3, i -> status(api.endTurn(game.guest(), game.gameId(), 60, UUID.randomUUID())));
		assertThat(statuses).containsOnlyOnce(200);
		return game;
	}

	private void jumpToRound(Lobby game, int round) {
		jdbc.update("UPDATE game.game SET current_round = ?, turn_number = ?, active_seat = 0 WHERE id = ?::uuid", round,
				2 * round - 1, game.gameId());
	}

	@Test
	void finishedGamesCountOnceForWinnerAndLoserAndDrawsForBoth() throws Exception {
		Lobby won = finishedGameWonByHost();
		String host = username(won.host());
		String guest = username(won.guest());

		MvcResult hostProfile = api.getJson(won.guest(), "/profiles/" + host);
		assertThat((Integer) read(hostProfile, "$.statistics.completed")).isEqualTo(1);
		assertThat((Integer) read(hostProfile, "$.statistics.wins")).isEqualTo(1);
		assertThat((Integer) read(hostProfile, "$.statistics.winRateBps")).isEqualTo(10_000);
		assertThat((String) read(hostProfile, "$.statistics.favouriteFaction.key")).isEqualTo("varn");
		assertThat((String) read(hostProfile, "$.recentMatches[0].outcome")).isEqualTo("WIN");
		assertThat((Object) read(hostProfile, "$.recentMatches[0].gameId")).as("game ids only for the owner").isNull();

		MvcResult guestProfile = api.getJson(won.guest(), "/profiles/" + guest);
		assertThat((Integer) read(guestProfile, "$.statistics.losses")).isEqualTo(1);
		assertThat((Integer) read(guestProfile, "$.statistics.wins")).isZero();
		assertThat((String) read(guestProfile, "$.recentMatches[0].gameId")).isEqualTo(won.gameId());

		// A draw: an untouched symmetric game.
		Lobby drawn = world.startedGame();
		jumpToRound(drawn, 30);
		world.completeRound(drawn, 30);
		String drawHost = username(drawn.host());
		assertThat((Integer) read(api.getJson(drawn.guest(), "/profiles/" + drawHost), "$.statistics.draws")).isEqualTo(1);

		// Unfinished games never count.
		Lobby running = world.startedGame();
		assertThat((Integer) read(api.getJson(running.guest(), "/profiles/" + username(running.host())),
				"$.statistics.completed")).isZero();
	}

	@Test
	void theOwnerCanHideTheMatchHistoryFromOthersButNotFromThemselves() throws Exception {
		Lobby won = finishedGameWonByHost();
		String host = username(won.host());
		assertThat(status(api.patchJson(won.host(), "/me", "{\"matchHistoryPublic\":false}"))).isEqualTo(200);

		MvcResult seenByOther = api.getJson(won.guest(), "/profiles/" + host);
		assertThat((Object) read(seenByOther, "$.recentMatches")).isNull();
		assertThat((Integer) read(seenByOther, "$.statistics.wins")).as("statistics stay public").isEqualTo(1);
		assertThat(ApiDriver.<List<Object>>read(api.getJson(won.host(), "/profiles/" + host), "$.recentMatches")).hasSize(1);
	}

}
