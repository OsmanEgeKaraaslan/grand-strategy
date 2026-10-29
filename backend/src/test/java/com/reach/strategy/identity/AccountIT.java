package com.reach.strategy.identity;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Account;
import com.reach.strategy.support.TestJwtIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Phase 15A against PostgreSQL: legal acceptance with versions, and account deletion. */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.legal.require-acceptance=true", "strategy.legal.terms-version=draft-1",
		"strategy.legal.privacy-version=draft-1" })
class AccountIT {

	private static final String ACCEPT = "{\"termsVersion\":\"draft-1\",\"privacyVersion\":\"draft-1\"}";
	private static final String CONFIRM = "{\"confirmation\":\"DELETE\"}";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	/** A token for {@code subject} whose session signed in with a password {@code secondsAgo} seconds ago. */
	private static String signedInAgo(UUID subject, long secondsAgo) {
		return TestJwtIssuer.get()
			.tokenFor(subject)
			.claim("amr", List.of(Map.of("method", "password", "timestamp", Instant.now().getEpochSecond() - secondsAgo)))
			.sign();
	}

	/** A new account that accepted the current documents, plus a token proving a fresh sign-in. */
	private record Member(Account account, String freshToken) {
	}

	private Member member(String prefix) throws Exception {
		UUID subject = UUID.randomUUID();
		String token = signedInAgo(subject, 5);
		assertThat(status(api.postJson(token, "/me/terms-acceptance", ACCEPT))).isEqualTo(200);
		String username = (prefix + "_" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20);
		MvcResult named = api.patchJson(token, "/me", "{\"username\":\"" + username + "\"}");
		assertThat(status(named)).as(body(named)).isEqualTo(200);
		return new Member(new Account(token, username, read(named, "$.id")), token);
	}

	@Test
	void anAccountWithoutAcceptanceMayOnlyUseItsOwnAccountEndpointsUntilItAccepts() throws Exception {
		String token = ApiDriver.newPlayer();

		MvcResult me = api.getJson(token, "/me");
		assertThat(status(me)).isEqualTo(200);
		assertThat((Boolean) read(me, "$.terms.accepted")).isFalse();
		assertThat((Object) read(me, "$.terms.acceptedTermsVersion")).isNull();
		assertThat((String) read(me, "$.terms.currentTermsVersion")).isEqualTo("draft-1");
		assertThat(code(api.getJson(token, "/friends"))).isEqualTo("403 TERMS_NOT_ACCEPTED");
		assertThat(code(api.createGame(token, "Before accepting"))).isEqualTo("403 TERMS_NOT_ACCEPTED");
		assertThat(status(api.patchJson(token, "/me", "{\"emblem\":\"tower\"}"))).isEqualTo(200);

		MvcResult accepted = api.postJson(token, "/me/terms-acceptance", ACCEPT);
		assertThat(status(accepted)).as(body(accepted)).isEqualTo(200);
		assertThat((Boolean) read(accepted, "$.terms.accepted")).isTrue();
		assertThat((String) read(accepted, "$.terms.acceptedAt")).isNotBlank();
		assertThat(status(api.getJson(token, "/friends"))).isEqualTo(200);

		// Accepting again changes nothing and keeps a single history row.
		assertThat(status(api.postJson(token, "/me/terms-acceptance", ACCEPT))).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.terms_acceptance WHERE player_id = ?::uuid",
				Integer.class, (String) read(me, "$.id"))).isEqualTo(1);
	}

	@Test
	void consentGivenOnTheSignUpFormIsRecordedOnFirstLoginOnlyForTheCurrentVersions() throws Exception {
		String current = TestJwtIssuer.get()
			.tokenFor(UUID.randomUUID())
			.claim("user_metadata", Map.of("display_name", "Consenting " + UUID.randomUUID().toString().substring(0, 6),
					"accepted_terms_version", "draft-1", "accepted_privacy_version", "draft-1"))
			.sign();
		MvcResult me = api.getJson(current, "/me");
		assertThat((Boolean) read(me, "$.terms.accepted")).isTrue();
		assertThat(status(api.getJson(current, "/friends"))).isEqualTo(200);

		String outdated = TestJwtIssuer.get()
			.tokenFor(UUID.randomUUID())
			.claim("user_metadata", Map.of("accepted_terms_version", "draft-0", "accepted_privacy_version", "draft-1"))
			.sign();
		assertThat((Boolean) read(api.getJson(outdated, "/me"), "$.terms.accepted")).isFalse();
		assertThat(code(api.getJson(outdated, "/friends"))).isEqualTo("403 TERMS_NOT_ACCEPTED");
	}

	@Test
	void acceptingAVersionOtherThanTheCurrentOneIsRefused() throws Exception {
		String token = ApiDriver.newPlayer();
		assertThat(code(api.postJson(token, "/me/terms-acceptance",
				"{\"termsVersion\":\"draft-0\",\"privacyVersion\":\"draft-1\"}"))).isEqualTo("409 TERMS_VERSION_OUTDATED");
		assertThat(status(api.postJson(token, "/me/terms-acceptance", "{\"termsVersion\":\"\",\"privacyVersion\":\"x\"}")))
			.isEqualTo(400);
	}

	@Test
	void aPlayerWhoAcceptedAnOlderVersionIsAskedAgain() throws Exception {
		Member member = member("older");
		jdbc.update("UPDATE game.player SET terms_version = 'draft-0' WHERE id = ?::uuid", member.account().id());

		assertThat(code(api.getJson(member.freshToken(), "/friends"))).isEqualTo("403 TERMS_NOT_ACCEPTED");
		MvcResult me = api.getJson(member.freshToken(), "/me");
		assertThat((Boolean) read(me, "$.terms.accepted")).isFalse();
		assertThat((String) read(me, "$.terms.acceptedTermsVersion")).isEqualTo("draft-0");
	}

	@Test
	void deletionNeedsATypedConfirmationAndARecentSignIn() throws Exception {
		UUID subject = UUID.randomUUID();
		String stale = signedInAgo(subject, 3600);
		assertThat(status(api.postJson(stale, "/me/terms-acceptance", ACCEPT))).isEqualTo(200);

		assertThat(status(api.postJson(stale, "/me/deletion", "{\"confirmation\":\"yes\"}"))).isEqualTo(400);
		assertThat(code(api.postJson(stale, "/me/deletion", CONFIRM))).isEqualTo("403 REAUTHENTICATION_REQUIRED");
		String noAmr = TestJwtIssuer.get().tokenFor(subject).sign();
		assertThat(code(api.postJson(noAmr, "/me/deletion", CONFIRM))).isEqualTo("403 REAUTHENTICATION_REQUIRED");

		assertThat(status(api.getJson(stale, "/me"))).isEqualTo(200);
	}

	@Test
	void deletionIsRefusedWhileThePlayerSitsInAnUnfinishedGame() throws Exception {
		Member host = member("host");
		MvcResult lobby = api.createGame(host.freshToken(), "Unfinished");
		assertThat(status(lobby)).isEqualTo(201);

		MvcResult refused = api.postJson(host.freshToken(), "/me/deletion", CONFIRM);
		assertThat(code(refused)).isEqualTo("409 ACCOUNT_DELETION_BLOCKED");
		assertThat((String) read(refused, "$.detail")).contains("1 unfinished game");
		assertThat(status(api.getJson(host.freshToken(), "/me"))).isEqualTo(200);
	}

	@Test
	void deletionAnonymizesTheAccountRemovesItsSocialDataAndKeepsFinishedMatches() throws Exception {
		Member leaving = member("leaving");
		Member friend = member("friend");
		Member blocker = member("blocker");
		Account self = leaving.account();

		// A friendship (with notifications), a block, and a finished game together.
		String requestId = read(api.postJson(self.token(), "/friends/requests",
				"{\"username\":\"" + friend.account().username() + "\"}"), "$.id");
		assertThat(status(api.postJson(friend.freshToken(), "/friends/requests/" + requestId + "/accept", "{}")))
			.isEqualTo(200);
		assertThat(status(api.postJson(blocker.freshToken(), "/blocks",
				"{\"username\":\"" + self.username() + "\"}"))).isEqualTo(200);
		MvcResult created = api.createGame(self.token(), "Finished together");
		String gameId = read(created, "$.id");
		assertThat(status(api.join(friend.freshToken(), read(created, "$.inviteCode")))).isEqualTo(200);
		List<String> nations = com.jayway.jsonpath.JsonPath.read(body(created), "$.nations[*].id");
		api.chooseNation(self.token(), gameId, nations.get(0));
		api.chooseNation(friend.freshToken(), gameId, nations.get(1));
		assertThat(status(api.start(self.token(), gameId))).isEqualTo(200);
		jdbc.update("UPDATE game.game SET status = 'FINISHED' WHERE id = ?::uuid", gameId);

		MvcResult deleted = api.postJson(leaving.freshToken(), "/me/deletion", CONFIRM);
		assertThat(status(deleted)).as(body(deleted)).isEqualTo(200);
		assertThat((String) read(deleted, "$.deletedAt")).isNotBlank();

		// The account is anonymized but still a participant of the finished game.
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT display_name, username, emblem, match_history_public, deleted_at FROM game.player WHERE id = ?::uuid",
				self.id());
		String suffix = self.id().replace("-", "").substring(0, 12);
		assertThat(row.get("display_name")).isEqualTo("Deleted#" + suffix);
		assertThat(row.get("username")).isEqualTo("deleted_" + suffix);
		assertThat(row.get("emblem")).isEqualTo("crown");
		assertThat(row.get("match_history_public")).isEqualTo(false);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game_player WHERE player_id = ?::uuid AND game_id = ?::uuid",
				Integer.class, self.id(), gameId)).isEqualTo(1);

		// Its social data is gone, in both directions.
		assertThat(jdbc.queryForObject("""
				SELECT (SELECT count(*) FROM game.friendship WHERE player_low_id = ?::uuid OR player_high_id = ?::uuid)
				     + (SELECT count(*) FROM game.friend_request WHERE sender_id = ?::uuid OR recipient_id = ?::uuid)
				     + (SELECT count(*) FROM game.player_block WHERE blocker_id = ?::uuid OR blocked_id = ?::uuid)
				     + (SELECT count(*) FROM game.notification WHERE player_id = ?::uuid OR actor_player_id = ?::uuid)
				""", Integer.class, self.id(), self.id(), self.id(), self.id(), self.id(), self.id(), self.id(), self.id()))
			.isZero();
		MvcResult friendsOfFriend = api.getJson(friend.freshToken(), "/friends");
		assertThat(ApiDriver.<List<String>>read(friendsOfFriend, "$.friends[*].username")).isEmpty();

		// The account cannot be used, found or reclaimed.
		assertThat(code(api.getJson(leaving.freshToken(), "/me"))).isEqualTo("403 ACCOUNT_DELETED");
		assertThat(code(api.postJson(leaving.freshToken(), "/me/deletion", CONFIRM))).isEqualTo("403 ACCOUNT_DELETED");
		assertThat(status(api.getJson(friend.freshToken(), "/profiles/" + self.username()))).isEqualTo(404);
		assertThat(status(api.getJson(friend.freshToken(), "/profiles/deleted_" + suffix))).isEqualTo(404);
		assertThat(ApiDriver.<List<String>>read(api.getJson(friend.freshToken(), "/players/search?q=deleted"), "$[*].username"))
			.isEmpty();
		assertThat(code(api.patchJson(friend.freshToken(), "/me", "{\"username\":\"deleted_abc\"}")))
			.isEqualTo("422 USERNAME_RESERVED");
		// The finished game still names its (anonymized) participant for the other player.
		MvcResult lobbyView = api.lobby(friend.freshToken(), gameId);
		assertThat(status(lobbyView)).isEqualTo(200);
		assertThat(body(lobbyView)).contains("Deleted#" + suffix).doesNotContain(self.username());
	}

}
