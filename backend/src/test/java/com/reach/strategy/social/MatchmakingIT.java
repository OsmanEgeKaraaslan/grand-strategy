package com.reach.strategy.social;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Account;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 13B against PostgreSQL with the current ruleset (v7, computer opponents) and the Grand
 * Campaign: joining, pairing, exactly-once game creation under races, cancellation, expiry,
 * recovery after reconnecting and the game that results (scenario B).
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign",
		"strategy.social.matchmaking.max-unfinished-games=3" })
class MatchmakingIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		// The queue is shared by the whole database: searches left by earlier tests must not pair here.
		jdbc.update("UPDATE game.matchmaking_ticket SET status = 'EXPIRED', ended_at = now() WHERE status = 'WAITING'");
	}

	private MvcResult join(Account a) throws Exception {
		return api.postJson(a.token(), "/matchmaking/quick/join", "{}");
	}

	private int gamesOf(Account a) {
		return jdbc.queryForObject("SELECT count(*) FROM game.game_player WHERE player_id = ?::uuid", Integer.class, a.id());
	}

	@Test
	void aLonePlayerSearchesAndARepeatedJoinKeepsTheSameSearch() throws Exception {
		Account a = api.newAccount("lonely");
		assertThat((String) read(api.getJson(a.token(), "/matchmaking/quick"), "$.state")).isEqualTo("NONE");

		MvcResult first = join(a);
		assertThat((String) read(first, "$.state")).isEqualTo("SEARCHING");
		assertThat((String) read(join(a), "$.ticketId")).isEqualTo(read(first, "$.ticketId"));
		assertThat((String) read(api.postJson(a.token(), "/matchmaking/quick/heartbeat", "{}"), "$.state")).isEqualTo("SEARCHING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.matchmaking_ticket WHERE player_id = ?::uuid", Integer.class,
				a.id())).isEqualTo(1);
		assertThat(gamesOf(a)).as("nobody is invented as an opponent").isZero();

		assertThat((String) read(api.postJson(a.token(), "/matchmaking/quick/cancel", "{}"), "$.state")).isEqualTo("CANCELLED");
		assertThat(status(api.anonymous("/api/v1/matchmaking/quick"))).isEqualTo(401);
	}

	@Test
	void scenarioB_twoPlayersAreMatchedIntoOneCampaignWithEightComputerNations() throws Exception {
		Account a = api.newAccount("qm_alpha");
		Account b = api.newAccount("qm_bravo");
		join(a);

		MvcResult matched = join(b);
		assertThat((String) read(matched, "$.state")).isEqualTo("MATCHED");
		String gameId = read(matched, "$.match.gameId");
		assertThat((String) read(matched, "$.match.mapName")).isEqualTo("Grand Campaign");
		assertThat((String) read(matched, "$.match.opponent.username")).isEqualTo(a.username());
		String bFaction = read(matched, "$.match.yourFaction.key");
		String aFaction = read(matched, "$.match.opponent.faction.key");
		assertThat(bFaction).isNotEqualTo(aFaction);

		// A sees the same match on their next poll, and was notified.
		MvcResult aView = api.postJson(a.token(), "/matchmaking/quick/heartbeat", "{}");
		assertThat((String) read(aView, "$.state")).isEqualTo("MATCHED");
		assertThat((String) read(aView, "$.match.gameId")).isEqualTo(gameId);
		assertThat((String) read(aView, "$.match.yourFaction.key")).isEqualTo(aFaction);
		assertThat((String) read(api.getJson(a.token(), "/notifications"), "$.items[0].kind")).isEqualTo("MATCH_FOUND");

		// Exactly one game: two human seats with distinct playable POWER nations, eight computer seats.
		assertThat(gamesOf(a)).isEqualTo(1);
		assertThat(gamesOf(b)).isEqualTo(1);
		List<Map<String, Object>> seats = jdbc.queryForList("""
				SELECT gp.computer, nt.key, nt.tier, nt.playable, gp.seat_order FROM game.game_player gp
				JOIN game.nation_template nt ON nt.id = gp.nation_template_id
				WHERE gp.game_id = ?::uuid ORDER BY gp.seat_order
				""", gameId);
		assertThat(seats).hasSize(10);
		List<Map<String, Object>> humans = seats.stream().filter(s -> !(Boolean) s.get("computer")).toList();
		assertThat(humans).hasSize(2);
		assertThat(humans).allSatisfy(h -> {
			assertThat(h.get("playable")).isEqualTo(true);
			assertThat(h.get("tier")).isEqualTo("POWER");
		});
		assertThat(humans.stream().map(h -> h.get("key")).toList()).containsExactly(aFaction, bFaction);
		assertThat(jdbc.queryForObject("SELECT ruleset_version FROM game.game WHERE id = ?::uuid", String.class, gameId))
			.as("the current ruleset, not a hard-coded one").isEqualTo("v7");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.nation WHERE game_id = ?::uuid AND control = 'AI'",
				Integer.class, gameId)).isEqualTo(8);

		// Player A stops polling, comes back later and recovers the same match instead of a new search.
		MvcResult back = join(a);
		assertThat((String) read(back, "$.state")).isEqualTo("MATCHED");
		assertThat((String) read(back, "$.match.gameId")).isEqualTo(gameId);
		assertThat(gamesOf(a)).isEqualTo(1);
		assertThat(ApiDriver.<List<String>>read(api.getJson(a.token(), "/games"), "$[*].id")).contains(gameId);

		// The game plays normally: A (who waited longer) moves first, then B, then the computers.
		MvcResult state = api.state(a.token(), gameId);
		assertThat((Boolean) read(state, "$.yourTurn")).isTrue();
		assertThat(status(api.endTurn(a.token(), gameId, 1, UUID.randomUUID()))).isEqualTo(200);
		assertThat(status(api.endTurn(b.token(), gameId, 2, UUID.randomUUID()))).isEqualTo(200);
		assertThat((Integer) read(api.state(a.token(), gameId), "$.round")).isEqualTo(2);
		assertThat(status(api.state(api.newAccount("outsider").token(), gameId))).as("games stay private").isEqualTo(404);

		// Opening the match acknowledges it; the next join is a new search.
		assertThat((String) read(api.postJson(a.token(), "/matchmaking/quick/acknowledge", "{}"), "$.state")).isEqualTo("NONE");
		assertThat((String) read(join(a), "$.state")).isEqualTo("SEARCHING");
		api.postJson(a.token(), "/matchmaking/quick/cancel", "{}");
	}

	@Test
	void simultaneousJoinsCreateExactlyOneGamePerPair() throws Exception {
		List<Account> players = List.of(api.newAccount("rush_a"), api.newAccount("rush_b"), api.newAccount("rush_c"),
				api.newAccount("rush_d"));

		List<String> states = Concurrently.run(players.size(), i -> read(join(players.get(i)), "$.state"));
		// Everyone who is still searching polls once more (the last joiner of a pair may already be matched).
		for (Account p : players) {
			api.postJson(p.token(), "/matchmaking/quick/heartbeat", "{}");
		}

		assertThat(states).contains("MATCHED");
		Set<String> games = new HashSet<>();
		for (Account p : players) {
			MvcResult view = api.getJson(p.token(), "/matchmaking/quick");
			assertThat((String) read(view, "$.state")).isEqualTo("MATCHED");
			games.add(read(view, "$.match.gameId"));
			assertThat(gamesOf(p)).as("each player is in exactly one game").isEqualTo(1);
		}
		assertThat(games).hasSize(2);
		for (String game : games) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game_player WHERE game_id = ?::uuid AND NOT computer",
					Integer.class, game)).isEqualTo(2);
			assertThat(jdbc.queryForObject("""
					SELECT count(DISTINCT nation_template_id) FROM game.game_player WHERE game_id = ?::uuid AND NOT computer
					""", Integer.class, game)).isEqualTo(2);
		}
	}

	@Test
	void cancellingAfterTheMatchWasCommittedReturnsTheMatch() throws Exception {
		Account a = api.newAccount("late_cancel");
		Account b = api.newAccount("matcher");
		join(a);
		String gameId = read(join(b), "$.match.gameId");

		MvcResult cancelled = api.postJson(a.token(), "/matchmaking/quick/cancel", "{}");
		assertThat((String) read(cancelled, "$.state")).isEqualTo("MATCHED");
		assertThat((String) read(cancelled, "$.match.gameId")).isEqualTo(gameId);
		assertThat(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class, gameId))
			.isEqualTo("IN_PROGRESS");
	}

	@Test
	void aRaceBetweenCancelAndMatchHasOneConsistentOutcome() throws Exception {
		for (int round = 0; round < 3; round++) {
			setUp();
			Account a = api.newAccount("racer_c" + round);
			Account b = api.newAccount("racer_j" + round);
			join(a);

			List<String> results = Concurrently.run(2,
					i -> JsonPath.read(ApiDriver.body(i == 0 ? api.postJson(a.token(), "/matchmaking/quick/cancel", "{}") : join(b)),
							"$.state"));

			String aState = read(api.getJson(a.token(), "/matchmaking/quick"), "$.state");
			String bState = read(api.getJson(b.token(), "/matchmaking/quick"), "$.state");
			if (aState.equals("CANCELLED")) {
				assertThat(bState).isEqualTo("SEARCHING");
				assertThat(gamesOf(a) + gamesOf(b)).isZero();
			}
			else {
				assertThat(aState).isEqualTo("MATCHED");
				assertThat(bState).isEqualTo("MATCHED");
				assertThat(gamesOf(a)).isEqualTo(1);
				assertThat(gamesOf(b)).isEqualTo(1);
			}
			assertThat(results).hasSize(2);
			api.postJson(b.token(), "/matchmaking/quick/cancel", "{}");
		}
	}

	@Test
	void abandonedSearchesExpireAndAreNeverMatched() throws Exception {
		Account a = api.newAccount("abandoned");
		Account b = api.newAccount("newcomer");
		join(a);
		jdbc.update("UPDATE game.matchmaking_ticket SET heartbeat_at = now() - interval '10 minutes' WHERE player_id = ?::uuid",
				UUID.fromString(a.id()));

		assertThat((String) read(api.getJson(a.token(), "/matchmaking/quick"), "$.state")).isEqualTo("EXPIRED");
		assertThat((String) read(join(b), "$.state")).isEqualTo("SEARCHING");
		assertThat(gamesOf(a)).isZero();
		assertThat((String) read(join(a), "$.state")).as("a fresh search is matched").isEqualTo("MATCHED");
	}

	@Test
	void newSearchesAreRefusedWithTooManyUnfinishedGames() throws Exception {
		Account a = api.newAccount("busy");
		for (int i = 0; i < 3; i++) {
			api.createGame(a.token(), "Open lobby " + i);
		}
		MvcResult refused = join(a);
		assertThat(status(refused)).isEqualTo(409);
		assertThat((String) read(refused, "$.code")).isEqualTo("TOO_MANY_ACTIVE_GAMES");
	}

	@Test
	void blockedPlayersAreNotPaired() throws Exception {
		Account a = api.newAccount("qm_blocker");
		Account b = api.newAccount("qm_blocked");
		api.postJson(a.token(), "/blocks", "{\"username\":\"" + b.username() + "\"}");
		join(a);
		assertThat((String) read(join(b), "$.state")).isEqualTo("SEARCHING");
		api.postJson(a.token(), "/matchmaking/quick/cancel", "{}");
		api.postJson(b.token(), "/matchmaking/quick/cancel", "{}");
	}

}
