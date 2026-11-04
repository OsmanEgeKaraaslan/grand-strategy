package com.reach.strategy.security;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.campaign.CampaignScenario;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.TestJwtIssuer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Moderation (Q-MOD1): only configured administrators may use it; a suspended account is refused at
 * once, its lobbies are abandoned and its seats in running games are played by the computer from
 * then on; names can be reset; stuck games can be abandoned; every action is logged.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.admin.supabase-user-ids=" + ModerationIT.ADMIN,
		"strategy.ruleset.current-version=v8", "strategy.world.default-map=grand-campaign", "strategy.ai.execution=manual" })
class ModerationIT {

	static final String ADMIN = "0a3f4e5c-6b7d-4e8f-9a0b-1c2d3e4f5a6b";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ComputerSeats seats;

	ApiDriver api;

	CampaignScenario c;

	String admin;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		c = new CampaignScenario(api, jdbc);
		admin = TestJwtIssuer.get().tokenFor(UUID.fromString(ADMIN)).sign();
	}

	private String playerId(String token) throws Exception {
		return read(api.getJson(token, "/me"), "$.id");
	}

	private int actions(String action, String targetPlayerId) {
		return jdbc.queryForObject("SELECT count(*) FROM game.moderation_action WHERE action = ? AND target_player_id = ?::uuid",
				Integer.class, action, targetPlayerId);
	}

	@Test
	void onlyAdministratorsMayModerate() throws Exception {
		String player = ApiDriver.newPlayer();
		String target = playerId(ApiDriver.newPlayer());
		String gameId = read(api.createGame(ApiDriver.newPlayer(), "Not yours"), "$.id");

		assertThat(ApiDriver.<Boolean>read(api.getJson(admin, "/me"), "$.admin")).isTrue();
		assertThat(ApiDriver.<Boolean>read(api.getJson(player, "/me"), "$.admin")).isFalse();
		List<MvcResult> refused = List.of(api.getJson(player, "/admin/players"),
				api.postJson(player, "/admin/players/" + target + "/ban", "{\"reason\":\"spam\"}"),
				api.postJson(player, "/admin/players/" + target + "/unban", "{}"),
				api.postJson(player, "/admin/players/" + target + "/name-reset", "{}"), api.getJson(player, "/admin/games"),
				api.postJson(player, "/admin/games/" + gameId + "/abandon", "{\"reason\":\"stuck\"}"));
		assertThat(refused).allSatisfy(r -> assertThat(status(r) + " " + read(r, "$.code")).isEqualTo("403 ADMIN_ONLY"));
		assertThat(status(api.anonymous("/api/v1/admin/players"))).isEqualTo(401);
		assertThat(status(api.anonymous("/api/v1/admin/games"))).isEqualTo(401);
		assertThat(actions("BAN", target)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class, gameId))
			.isEqualTo("WAITING_FOR_PLAYERS");

		// Administrators cannot moderate themselves (nor each other).
		MvcResult self = api.postJson(admin, "/admin/players/" + playerId(admin) + "/ban", "{\"reason\":\"oops\"}");
		assertThat(status(self) + " " + read(self, "$.code")).isEqualTo("409 CANNOT_MODERATE_ADMIN");
	}

	@Test
	void aSuspendedPlayersSeatIsPlayedByTheComputerAndTheirLobbiesClose() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String banned = game.token("nordmark");
		String bannedId = playerId(banned);
		String lobbyId = read(api.createGame(banned, "Banned host lobby"), "$.id");

		MvcResult ban = api.postJson(admin, "/admin/players/" + bannedId + "/ban", "{\"reason\":\"Offensive messages\"}");
		assertThat(status(ban)).as(body(ban)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(ban, "$.banReason")).isEqualTo("Offensive messages");

		MvcResult refused = api.getJson(banned, "/me");
		assertThat(status(refused) + " " + read(refused, "$.code")).isEqualTo("403 ACCOUNT_SUSPENDED");
		assertThat(ApiDriver.<String>read(refused, "$.detail")).contains("Offensive messages");
		assertThat(status(api.state(banned, game.gameId()))).isEqualTo(403);

		assertThat(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class, lobbyId))
			.isEqualTo("ABANDONED");
		Map<String, Object> seat = jdbc.queryForMap("""
				SELECT gp.computer, p.kind, n.control, g.status
				FROM game.game_player gp JOIN game.player p ON p.id = gp.player_id
				JOIN game.nation n ON n.game_id = gp.game_id AND n.game_player_id = gp.id
				JOIN game.game g ON g.id = gp.game_id
				WHERE gp.game_id = ?::uuid AND gp.seat_order = 1
				""", game.gameId());
		assertThat(seat).containsEntry("computer", true).containsEntry("kind", "COMPUTER").containsEntry("control", "AI")
			.containsEntry("status", "IN_PROGRESS");

		// The other human plays on; then the computer is on turn for the former seat.
		MvcResult ended = api.endTurn(game.token("castavar"), game.gameId(), c.turn(game), UUID.randomUUID());
		assertThat(status(ended)).as(body(ended)).isEqualTo(200);
		assertThat(seats.activeComputerTurn(UUID.fromString(game.gameId()))).hasValueSatisfying(
				turn -> assertThat(turn.seat().seat()).isEqualTo(1));
		assertThat(actions("BAN", bannedId)).isEqualTo(1);

		// Reinstated: the account works again, but the seat stays with the computer.
		MvcResult unban = api.postJson(admin, "/admin/players/" + bannedId + "/unban", "{}");
		assertThat(status(unban)).as(body(unban)).isEqualTo(200);
		assertThat(status(api.getJson(banned, "/me"))).isEqualTo(200);
		assertThat(status(api.state(banned, game.gameId()))).isEqualTo(404);
		assertThat(actions("UNBAN", bannedId)).isEqualTo(1);
	}

	@Test
	void aGameWhoseLastHumanIsSuspendedEndsAtOnce() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		for (String token : game.tokens()) {
			MvcResult ban = api.postJson(admin, "/admin/players/" + playerId(token) + "/ban", "{\"reason\":\"Cheating\"}");
			assertThat(status(ban)).as(body(ban)).isEqualTo(200);
		}
		assertThat(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class, game.gameId()))
			.isEqualTo("FINISHED");
		assertThat(jdbc.queryForObject("SELECT reason FROM game.game_result WHERE game_id = ?::uuid", String.class,
				game.gameId())).isEqualTo("NO_HUMAN_PLAYERS");
	}

	@Test
	void anOffensiveNameIsReplacedByAPlaceholder() throws Exception {
		ApiDriver.Account rude = api.newAccount("rude");
		api.patchJson(rude.token(), "/me", "{\"displayName\":\"Very Rude Name\"}");

		MvcResult found = api.getJson(admin, "/admin/players?query=" + rude.username());
		assertThat(ApiDriver.<List<String>>read(found, "$[*].id")).containsExactly(rude.id());

		MvcResult reset = api.postJson(admin, "/admin/players/" + rude.id() + "/name-reset", "{}");
		assertThat(status(reset)).as(body(reset)).isEqualTo(200);
		MvcResult me = api.getJson(rude.token(), "/me");
		assertThat(ApiDriver.<String>read(me, "$.displayName")).isNotEqualTo("Very Rude Name");
		assertThat(ApiDriver.<String>read(me, "$.username")).isNotEqualTo(rude.username()).startsWith("commander_");
		assertThat(ApiDriver.<Boolean>read(me, "$.usernameChosen")).isFalse();
		assertThat(actions("RESET_NAME", rude.id())).isEqualTo(1);
	}

	@Test
	void aStuckGameCanBeAbandonedAndStaysReadable() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		assertThat(ApiDriver.<List<String>>read(api.getJson(admin, "/admin/games"), "$[*].id")).contains(game.gameId());

		MvcResult abandoned = api.postJson(admin, "/admin/games/" + game.gameId() + "/abandon", "{\"reason\":\"Players left\"}");
		assertThat(status(abandoned)).as(body(abandoned)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(abandoned, "$.status")).isEqualTo("ABANDONED");

		MvcResult command = api.endTurn(game.token("castavar"), game.gameId(), c.turn(game), UUID.randomUUID());
		assertThat(status(command) + " " + read(command, "$.code")).isEqualTo("409 GAME_ABANDONED");
		MvcResult state = api.state(game.token("castavar"), game.gameId());
		assertThat(status(state)).as(body(state)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(state, "$.status")).isEqualTo("ABANDONED");

		MvcResult again = api.postJson(admin, "/admin/games/" + game.gameId() + "/abandon", "{\"reason\":\"Twice\"}");
		assertThat(status(again) + " " + read(again, "$.code")).isEqualTo("409 GAME_ALREADY_CLOSED");
		assertThat(ApiDriver.<List<String>>read(api.getJson(admin, "/admin/games"), "$[*].id")).doesNotContain(game.gameId());
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM game.moderation_action WHERE action = 'ABANDON_GAME' AND target_game_id = ?::uuid",
				Integer.class, game.gameId())).isEqualTo(1);
	}

}
