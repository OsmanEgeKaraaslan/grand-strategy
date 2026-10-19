package com.reach.strategy.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 2 end to end: real Supabase-style tokens, the real security chain, real PostgreSQL.
 * Every test uses fresh users, so tests are independent within the shared database.
 */
@PostgresIntegrationTest
class LobbyIT {

	static final TestJwtIssuer ISSUER = TestJwtIssuer.get();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	// --- Profiles ---------------------------------------------------------------------------

	@Test
	void anonymousRequestsToProtectedApisGet401() throws Exception {
		mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/games")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/games/join").contentType(MediaType.APPLICATION_JSON).content("{\"inviteCode\":\"x\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void firstRequestProvisionsTheProfileOnceAndUsesTheSignUpName() throws Exception {
		UUID supabaseUser = UUID.randomUUID();
		String name = uniqueName("Aldric");
		String token = ISSUER.tokenFor(supabaseUser).displayName(name).sign();

		String firstId = JsonPath.read(body(mockMvc.perform(as(get("/api/v1/me"), token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value(name))
			.andReturn()), "$.id");
		String secondId = JsonPath.read(body(mockMvc.perform(as(get("/api/v1/me"), token)).andReturn()), "$.id");

		assertThat(secondId).isEqualTo(firstId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.player WHERE supabase_user_id = ?", Integer.class,
				supabaseUser)).isEqualTo(1);
	}

	@Test
	void concurrentFirstLoginsOfOneUserCreateExactlyOnePlayer() throws Exception {
		UUID supabaseUser = UUID.randomUUID();
		String token = ISSUER.tokenFor(supabaseUser).sign();

		List<Integer> statuses = concurrently(8, () -> mockMvc.perform(as(get("/api/v1/me"), token)).andReturn()
			.getResponse()
			.getStatus());

		assertThat(statuses).containsOnly(200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.player WHERE supabase_user_id = ?", Integer.class,
				supabaseUser)).isEqualTo(1);
	}

	@Test
	void aTakenOrInvalidSignUpNameFallsBackToAGeneratedName() throws Exception {
		String name = uniqueName("Taken");
		mockMvc.perform(as(get("/api/v1/me"), ISSUER.tokenFor(UUID.randomUUID()).displayName(name).sign()))
			.andExpect(jsonPath("$.displayName").value(name));

		mockMvc.perform(as(get("/api/v1/me"), ISSUER.tokenFor(UUID.randomUUID()).displayName(name.toUpperCase()).sign()))
			.andExpect(jsonPath("$.displayName").value(org.hamcrest.Matchers.startsWith("Commander-")));
		mockMvc.perform(as(get("/api/v1/me"), ISSUER.tokenFor(UUID.randomUUID()).displayName("<b>x</b>").sign()))
			.andExpect(jsonPath("$.displayName").value(org.hamcrest.Matchers.startsWith("Commander-")));
	}

	@Test
	void playersCanRenameOnlyThemselvesAndNamesAreUnique() throws Exception {
		String alice = ISSUER.newUserToken();
		String bob = ISSUER.newUserToken();
		String aliceName = uniqueName("Alice");

		mockMvc.perform(as(patch("/api/v1/me"), alice).contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"" + aliceName + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value(aliceName));

		mockMvc.perform(as(patch("/api/v1/me"), bob).contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"" + aliceName.toLowerCase() + "\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DISPLAY_NAME_TAKEN"));

		// There is no way to address another profile: a client-supplied id is not even accepted.
		mockMvc.perform(as(patch("/api/v1/me"), bob).contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"Bobby\",\"id\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isBadRequest());
	}

	// --- Lobby ------------------------------------------------------------------------------

	@Test
	void twoPlayerLobbyLifecycle() throws Exception {
		String a = ISSUER.newUserToken();
		String b = ISSUER.newUserToken();
		String c = ISSUER.newUserToken();

		// A creates: first slot, waiting, invite code visible to A.
		MvcResult created = mockMvc.perform(as(post("/api/v1/games"), a).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Twin Shores\"}"))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/games/")))
			.andExpect(jsonPath("$.status").value("WAITING_FOR_PLAYERS"))
			.andExpect(jsonPath("$.yourSeat").value(0))
			.andExpect(jsonPath("$.youAreHost").value(true))
			.andExpect(jsonPath("$.availableSlots").value(1))
			.andExpect(jsonPath("$.participants.length()").value(1))
			.andReturn();
		String gameId = JsonPath.read(body(created), "$.id");
		String inviteCode = JsonPath.read(body(created), "$.inviteCode");

		// C (not a member) cannot see A's game, and it is not in C's list.
		mockMvc.perform(as(get("/api/v1/games/" + gameId), c))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
			.andExpect(jsonPath("$.inviteCode").doesNotExist());
		mockMvc.perform(as(get("/api/v1/games"), c)).andExpect(jsonPath("$.length()").value(0));

		// B joins with the code (formatting and case do not matter): READY.
		mockMvc.perform(join(b, inviteCode.toLowerCase()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(gameId))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.yourSeat").value(1))
			.andExpect(jsonPath("$.availableSlots").value(0))
			.andExpect(jsonPath("$.participants.length()").value(2));

		// B joining again: no duplicate membership.
		mockMvc.perform(join(b, inviteCode)).andExpect(status().isOk()).andExpect(jsonPath("$.playerCount").value(2));
		assertThat(memberCount(gameId)).isEqualTo(2);

		// C cannot join the full game.
		mockMvc.perform(join(c, inviteCode))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("GAME_FULL"));
		assertThat(memberCount(gameId)).isEqualTo(2);

		// A (polling) sees the lobby READY with both participants; lists do not carry the code.
		mockMvc.perform(as(get("/api/v1/games/" + gameId), a))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.participants[0].host").value(true))
			.andExpect(jsonPath("$.participants[1].you").value(false));
		mockMvc.perform(as(get("/api/v1/games"), b))
			.andExpect(jsonPath("$[0].id").value(gameId))
			.andExpect(jsonPath("$[0].yourSeat").value(1))
			.andExpect(jsonPath("$[0].inviteCode").doesNotExist());
	}

	@Test
	void twoPlayersRacingForTheLastSlotCannotBothSucceed() throws Exception {
		for (int round = 0; round < 5; round++) {
			String host = ISSUER.newUserToken();
			MvcResult created = mockMvc.perform(as(post("/api/v1/games"), host).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Race " + round + "\"}")).andReturn();
			String gameId = JsonPath.read(body(created), "$.id");
			String inviteCode = JsonPath.read(body(created), "$.inviteCode");
			List<String> challengers = List.of(ISSUER.newUserToken(), ISSUER.newUserToken(), ISSUER.newUserToken());

			List<Integer> statuses = concurrently(challengers.size(),
					index -> mockMvc.perform(join(challengers.get(index), inviteCode)).andReturn().getResponse().getStatus());

			assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
			assertThat(memberCount(gameId)).isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT player_count FROM game.game WHERE id = ?::uuid", Integer.class, gameId))
				.isEqualTo(2);
		}
	}

	@Test
	void unknownAndMalformedInviteCodesAreNotFound() throws Exception {
		String player = ISSUER.newUserToken();
		mockMvc.perform(join(player, "AAAA-BBBB-CCCC")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVITE_CODE_NOT_FOUND"));
		mockMvc.perform(join(player, "definitely wrong")).andExpect(status().isNotFound());
		mockMvc.perform(join(player, "")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void createValidatesTheName() throws Exception {
		mockMvc.perform(as(post("/api/v1/games"), ISSUER.newUserToken()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"x\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void expiredTokensAreRejectedEvenForExistingPlayers() throws Exception {
		UUID user = UUID.randomUUID();
		mockMvc.perform(as(get("/api/v1/me"), ISSUER.tokenFor(user).sign())).andExpect(status().isOk());
		mockMvc.perform(as(get("/api/v1/me"), ISSUER.tokenFor(user).expiresAt(Instant.now().minusSeconds(120)).sign()))
			.andExpect(status().isUnauthorized());
	}

	// --- Database constraints (backstops behind the application rules) -----------------------

	@Test
	void databaseRejectsOverfillingDuplicateMembershipsAndInvalidStatus() throws Exception {
		String host = ISSUER.newUserToken();
		String guest = ISSUER.newUserToken();
		MvcResult created = mockMvc.perform(as(post("/api/v1/games"), host).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"Constraint Keep\"}")).andReturn();
		String gameId = JsonPath.read(body(created), "$.id");
		mockMvc.perform(join(guest, JsonPath.read(body(created), "$.inviteCode")));
		UUID guestPlayer = UUID.fromString(JsonPath.read(body(mockMvc.perform(as(get("/api/v1/me"), guest)).andReturn()), "$.id"));

		assertThatThrownBy(() -> jdbc.update("UPDATE game.game SET player_count = 3 WHERE id = ?::uuid", gameId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE game.game SET status = 'WAITING_FOR_PLAYERS' WHERE id = ?::uuid", gameId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO game.game_player (id, game_id, player_id, seat_order, joined_at) VALUES (?, ?::uuid, ?, 5, now())",
				UUID.randomUUID(), gameId, guestPlayer))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// --- helpers ----------------------------------------------------------------------------

	private MockHttpServletRequestBuilder join(String token, String inviteCode) {
		return as(post("/api/v1/games/join"), token).contentType(MediaType.APPLICATION_JSON)
			.content("{\"inviteCode\":\"" + inviteCode + "\"}");
	}

	private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private Integer memberCount(String gameId) {
		return jdbc.queryForObject("SELECT count(*) FROM game.game_player WHERE game_id = ?::uuid", Integer.class, gameId);
	}

	private static String uniqueName(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static List<Integer> concurrently(int threads, Callable<Integer> task) throws Exception {
		return concurrently(threads, index -> task.call());
	}

	/** Runs the task on {@code threads} threads released at the same instant. */
	private static List<Integer> concurrently(int threads, IndexedTask task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					start.await();
					return task.run(index);
				}));
			}
			start.countDown();
			List<Integer> results = new ArrayList<>();
			for (Future<Integer> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

	@FunctionalInterface
	private interface IndexedTask {

		Integer run(int index) throws Exception;

	}

}
