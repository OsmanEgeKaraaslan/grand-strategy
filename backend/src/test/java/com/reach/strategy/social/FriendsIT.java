package com.reach.strategy.social;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Account;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Phase 12B against PostgreSQL: search, the request state machine, races, removal, blocks, notifications. */
@PostgresIntegrationTest
class FriendsIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	private MvcResult request(Account from, Account to) throws Exception {
		return api.postJson(from.token(), "/friends/requests", "{\"username\":\"" + to.username() + "\"}");
	}

	private String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private int friendships(Account a, Account b) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM game.friendship
				WHERE (player_low_id = ?::uuid AND player_high_id = ?::uuid) OR (player_low_id = ?::uuid AND player_high_id = ?::uuid)
				""", Integer.class, a.id(), b.id(), b.id(), a.id());
	}

	@Test
	void searchFindsExactAndPrefixMatchesWithRelationsButNoPrivateData() throws Exception {
		Account me = api.newAccount("searcher");
		Account target = api.newAccount("findme");

		MvcResult exact = api.getJson(me.token(), "/players/search?q=" + target.username().toUpperCase());
		assertThat(ApiDriver.<List<String>>read(exact, "$[*].username")).first().isEqualTo(target.username());
		assertThat((String) read(exact, "$[0].relation")).isEqualTo("NONE");
		assertThat(body(exact)).doesNotContain(target.id()).doesNotContain("supabase").doesNotContain("@");

		MvcResult prefix = api.getJson(me.token(), "/players/search?q=findme");
		assertThat(ApiDriver.<List<String>>read(prefix, "$[*].username")).hasSizeLessThanOrEqualTo(10)
			.allMatch(u -> u.startsWith("findme"));
		assertThat(ApiDriver.<List<Object>>read(api.getJson(me.token(), "/players/search?q=f%25"), "$")).isEmpty();
		assertThat(ApiDriver.<List<Object>>read(api.getJson(me.token(), "/players/search?q=fi"), "$"))
			.as("short queries only match exactly").isEmpty();
	}

	@Test
	void aRequestAcceptedMakesBothFriendsAndNotifiesBoth() throws Exception {
		Account a = api.newAccount("alpha");
		Account b = api.newAccount("bravo");

		MvcResult sent = request(a, b);
		assertThat((String) read(sent, "$.status")).isEqualTo("PENDING");
		String requestId = read(sent, "$.id");
		assertThat(ApiDriver.<Integer>read(api.getJson(b.token(), "/notifications/unread-count"), "$.unread")).isEqualTo(1);
		MvcResult bNotifications = api.getJson(b.token(), "/notifications");
		assertThat((String) read(bNotifications, "$.items[0].kind")).isEqualTo("FRIEND_REQUEST");
		assertThat((String) read(bNotifications, "$.items[0].actor.username")).isEqualTo(a.username());
		assertThat(ApiDriver.<List<String>>read(api.getJson(b.token(), "/friends"), "$.incoming[*].id")).containsExactly(requestId);
		assertThat(ApiDriver.<List<String>>read(api.getJson(a.token(), "/friends"), "$.outgoing[*].username"))
			.containsExactly(b.username());

		assertThat((String) read(api.postJson(b.token(), "/friends/requests/" + requestId + "/accept", "{}"), "$.status"))
			.isEqualTo("ACCEPTED");
		assertThat(ApiDriver.<List<String>>read(api.getJson(a.token(), "/friends"), "$.friends[*].username"))
			.containsExactly(b.username());
		assertThat(ApiDriver.<List<String>>read(api.getJson(b.token(), "/friends"), "$.friends[*].username"))
			.containsExactly(a.username());
		assertThat((String) read(api.getJson(a.token(), "/notifications"), "$.items[0].kind")).isEqualTo("FRIEND_ACCEPTED");
		assertThat((String) read(api.getJson(a.token(), "/profiles/" + b.username()), "$.relation")).isEqualTo("FRIEND");

		// A retried accept succeeds without a second friendship.
		assertThat(status(api.postJson(b.token(), "/friends/requests/" + requestId + "/accept", "{}"))).isEqualTo(200);
		assertThat(friendships(a, b)).isEqualTo(1);
		assertThat(code(request(a, b))).isEqualTo("409 ALREADY_FRIENDS");
	}

	@Test
	void onlyTheRightPartyMayAnswerAndNobodyCanBefriendThemselves() throws Exception {
		Account a = api.newAccount("sender");
		Account b = api.newAccount("receiver");
		Account c = api.newAccount("intruder");
		String requestId = read(request(a, b), "$.id");

		assertThat(code(api.postJson(c.token(), "/friends/requests/" + requestId + "/accept", "{}")))
			.isEqualTo("404 FRIEND_REQUEST_NOT_FOUND");
		assertThat(code(api.postJson(a.token(), "/friends/requests/" + requestId + "/accept", "{}")))
			.as("the sender cannot accept their own request").isEqualTo("404 FRIEND_REQUEST_NOT_FOUND");
		assertThat(code(api.postJson(b.token(), "/friends/requests/" + requestId + "/cancel", "{}")))
			.isEqualTo("404 FRIEND_REQUEST_NOT_FOUND");
		assertThat(code(request(a, a))).isEqualTo("422 CANNOT_FRIEND_SELF");

		assertThat((String) read(api.postJson(b.token(), "/friends/requests/" + requestId + "/decline", "{}"), "$.status"))
			.isEqualTo("DECLINED");
		assertThat(code(api.postJson(b.token(), "/friends/requests/" + requestId + "/accept", "{}")))
			.isEqualTo("409 REQUEST_NOT_PENDING");
		assertThat(friendships(a, b)).isZero();

		// After a decline a new request may be sent; the sender can withdraw it.
		String second = read(request(a, b), "$.id");
		assertThat((String) read(api.postJson(a.token(), "/friends/requests/" + second + "/cancel", "{}"), "$.status"))
			.isEqualTo("CANCELLED");
		assertThat(ApiDriver.<List<Object>>read(api.getJson(b.token(), "/friends"), "$.incoming")).isEmpty();
	}

	@Test
	void repeatedAndCrossedRequestsNeverDuplicate() throws Exception {
		Account a = api.newAccount("repeat");
		Account b = api.newAccount("crossed");

		List<String> ids = Concurrently.run(5, i -> read(request(a, b), "$.id"));
		assertThat(ids).as("one pending request for the pair").containsOnly(ids.getFirst());

		Account c = api.newAccount("left");
		Account d = api.newAccount("right");
		List<String> statuses = Concurrently.run(2, i -> read(i == 0 ? request(c, d) : request(d, c), "$.status"));
		assertThat(friendships(c, d)).as("crossed requests become one friendship").isEqualTo(1);
		assertThat(statuses).contains("ACCEPTED");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.friend_request
				WHERE status = 'PENDING' AND sender_id IN (?::uuid, ?::uuid) AND recipient_id IN (?::uuid, ?::uuid)
				""", Integer.class, c.id(), d.id(), c.id(), d.id())).isZero();
	}

	@Test
	void eitherFriendCanEndTheFriendship() throws Exception {
		Account a = api.newAccount("ender");
		Account b = api.newAccount("ended");
		api.postJson(b.token(), "/friends/requests/" + read(request(a, b), "$.id") + "/accept", "{}");

		assertThat(status(api.deleteJson(b.token(), "/friends/" + a.username()))).isEqualTo(204);
		assertThat(friendships(a, b)).isZero();
		assertThat(ApiDriver.<List<Object>>read(api.getJson(a.token(), "/friends"), "$.friends")).isEmpty();
		assertThat(status(api.deleteJson(a.token(), "/friends/" + b.username()))).as("idempotent").isEqualTo(204);
	}

	@Test
	void blockingEndsTheFriendshipAndStopsRequestsBothWays() throws Exception {
		Account a = api.newAccount("blocker");
		Account b = api.newAccount("blocked");
		api.postJson(b.token(), "/friends/requests/" + read(request(a, b), "$.id") + "/accept", "{}");
		Account c = api.newAccount("pesterer");
		request(c, a);

		assertThat((String) read(api.postJson(a.token(), "/blocks", "{\"username\":\"" + b.username() + "\"}"), "$.relation"))
			.isEqualTo("BLOCKED");
		api.postJson(a.token(), "/blocks", "{\"username\":\"" + c.username() + "\"}");

		assertThat(friendships(a, b)).isZero();
		assertThat(code(request(b, a))).isEqualTo("409 PLAYER_UNAVAILABLE");
		assertThat(code(request(a, b))).isEqualTo("409 PLAYER_UNAVAILABLE");
		MvcResult mine = api.getJson(a.token(), "/friends");
		assertThat(ApiDriver.<List<Object>>read(mine, "$.incoming")).as("the pending request was cancelled").isEmpty();
		assertThat(ApiDriver.<List<String>>read(mine, "$.blocked[*].username")).contains(b.username(), c.username());
		assertThat(ApiDriver.<List<String>>read(api.getJson(a.token(), "/notifications"), "$.items[*].actor.username"))
			.doesNotContain(c.username());
		assertThat((String) read(api.getJson(b.token(), "/profiles/" + a.username()), "$.relation"))
			.as("being blocked is not revealed").isEqualTo("NONE");

		assertThat(status(api.deleteJson(a.token(), "/blocks/" + b.username()))).isEqualTo(204);
		assertThat((String) read(request(b, a), "$.status")).isEqualTo("PENDING");
	}

	@Test
	void notificationsBelongToTheirRecipientOnly() throws Exception {
		Account a = api.newAccount("noter");
		Account b = api.newAccount("notee");
		Account c = api.newAccount("snoop");
		request(a, b);
		String notificationId = read(api.getJson(b.token(), "/notifications"), "$.items[0].id");

		api.postJson(c.token(), "/notifications/read", "{\"ids\":[\"" + notificationId + "\"]}");
		assertThat(ApiDriver.<Integer>read(api.getJson(b.token(), "/notifications/unread-count"), "$.unread")).isEqualTo(1);
		assertThat(ApiDriver.<List<Object>>read(api.getJson(c.token(), "/notifications"), "$.items")).isEmpty();

		assertThat(ApiDriver.<Integer>read(api.postJson(b.token(), "/notifications/read", "{}"), "$.unread")).isZero();
		assertThat(status(api.anonymous("/api/v1/notifications"))).isEqualTo(401);
	}

}
