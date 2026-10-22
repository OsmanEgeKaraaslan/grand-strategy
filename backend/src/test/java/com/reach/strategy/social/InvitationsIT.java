package com.reach.strategy.social;

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

/**
 * Phase 13A against PostgreSQL (default test ruleset v5 and the two-nation map — the legacy
 * compatibility case): invitations, their authorization, capacity under simultaneous acceptance,
 * notifications and the active-game summary.
 */
@PostgresIntegrationTest
class InvitationsIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
	}

	private void befriend(Account a, Account b) throws Exception {
		String id = read(api.postJson(a.token(), "/friends/requests", "{\"username\":\"" + b.username() + "\"}"), "$.id");
		assertThat(status(api.postJson(b.token(), "/friends/requests/" + id + "/accept", "{}"))).isEqualTo(200);
	}

	private String lobby(Account host, int players) throws Exception {
		return read(api.createGame(host.token(), "Invite test", players, players > 2 ? "grand-campaign" : null), "$.id");
	}

	private MvcResult invite(Account from, String gameId, Account to) throws Exception {
		return api.postJson(from.token(), "/invitations", "{\"gameId\":\"" + gameId + "\",\"username\":\"" + to.username() + "\"}");
	}

	private String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	@Test
	void aFriendInvitedToALobbyJoinsItOnAcceptance() throws Exception {
		Account host = api.newAccount("inviter");
		Account friend = api.newAccount("invitee");
		befriend(host, friend);
		String gameId = lobby(host, 2);

		MvcResult invited = invite(host, gameId, friend);
		assertThat(status(invited)).isEqualTo(200);
		assertThat((String) read(invited, "$.status")).isEqualTo("PENDING");
		assertThat((Boolean) read(invited, "$.joinable")).isTrue();
		String invitationId = read(invited, "$.id");
		assertThat((String) read(invite(host, gameId, friend), "$.id")).as("duplicate returns the pending one").isEqualTo(invitationId);

		MvcResult incoming = api.getJson(friend.token(), "/invitations");
		assertThat(ApiDriver.<List<String>>read(incoming, "$.incoming[*].id")).containsExactly(invitationId);
		assertThat((String) read(incoming, "$.incoming[0].inviter.username")).isEqualTo(host.username());
		assertThat((String) read(incoming, "$.incoming[0].mapName")).isNotBlank();
		MvcResult note = api.getJson(friend.token(), "/notifications");
		assertThat((String) read(note, "$.items[0].kind")).isEqualTo("GAME_INVITATION");
		assertThat((String) read(note, "$.items[0].gameName")).isEqualTo("Invite test");

		assertThat(status(api.lobby(friend.token(), gameId))).as("not a member before accepting").isEqualTo(404);
		MvcResult accepted = api.postJson(friend.token(), "/invitations/" + invitationId + "/accept", "{}");
		assertThat((String) read(accepted, "$.status")).isEqualTo("ACCEPTED");
		assertThat(status(api.lobby(friend.token(), gameId))).isEqualTo(200);
		assertThat((Integer) read(api.lobby(host.token(), gameId), "$.playerCount")).isEqualTo(2);
		assertThat((String) read(api.getJson(host.token(), "/notifications"), "$.items[0].kind")).isEqualTo("INVITATION_ACCEPTED");
		// A retried acceptance after a lost response is harmless.
		assertThat(status(api.postJson(friend.token(), "/invitations/" + invitationId + "/accept", "{}"))).isEqualTo(200);
		assertThat((Integer) read(api.lobby(host.token(), gameId), "$.playerCount")).isEqualTo(2);
	}

	@Test
	void onlyTheHostInvitesOnlyFriendsAndOnlyIntoAnOpenLobby() throws Exception {
		Account host = api.newAccount("hoster");
		Account member = api.newAccount("member");
		Account stranger = api.newAccount("stranger");
		Account friend = api.newAccount("pal");
		befriend(host, member);
		befriend(member, friend);
		befriend(host, friend);
		String gameId = lobby(host, 3);
		assertThat(status(api.postJson(member.token(), "/invitations/"
				+ read(invite(host, gameId, member), "$.id") + "/accept", "{}"))).isEqualTo(200);

		assertThat(code(invite(member, gameId, friend))).isEqualTo("403 NOT_HOST");
		assertThat(code(invite(stranger, gameId, friend))).as("non-members cannot see the game").isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(invite(host, gameId, stranger))).isEqualTo("409 NOT_FRIENDS");
		assertThat(code(invite(host, gameId, member))).isEqualTo("409 ALREADY_IN_GAME");

		String invitationId = read(invite(host, gameId, friend), "$.id");
		assertThat(code(api.postJson(stranger.token(), "/invitations/" + invitationId + "/accept", "{}")))
			.as("nobody accepts someone else's invitation").isEqualTo("404 INVITATION_NOT_FOUND");
		assertThat(ApiDriver.<List<Object>>read(api.getJson(stranger.token(), "/invitations"), "$.incoming")).isEmpty();
		assertThat(code(api.getJson(stranger.token(), "/games/" + gameId + "/invitations"))).isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.postJson(friend.token(), "/invitations/" + invitationId + "/cancel", "{}")))
			.as("only the inviter withdraws").isEqualTo("404 INVITATION_NOT_FOUND");

		assertThat((String) read(api.postJson(host.token(), "/invitations/" + invitationId + "/cancel", "{}"), "$.status"))
			.isEqualTo("CANCELLED");
		assertThat(code(api.postJson(friend.token(), "/invitations/" + invitationId + "/accept", "{}")))
			.isEqualTo("409 INVITATION_NOT_PENDING");
	}

	@Test
	void declinedInvitationsTellTheHost() throws Exception {
		Account host = api.newAccount("decliner_h");
		Account friend = api.newAccount("decliner_f");
		befriend(host, friend);
		String invitationId = read(invite(host, lobby(host, 2), friend), "$.id");

		assertThat((String) read(api.postJson(friend.token(), "/invitations/" + invitationId + "/decline", "{}"), "$.status"))
			.isEqualTo("DECLINED");
		assertThat((String) read(api.getJson(host.token(), "/notifications"), "$.items[0].kind")).isEqualTo("INVITATION_DECLINED");
	}

	@Test
	void twoInviteesRacingForTheLastSeatLeaveExactlyOneSeated() throws Exception {
		Account host = api.newAccount("racehost");
		Account first = api.newAccount("racer_a");
		Account second = api.newAccount("racer_b");
		befriend(host, first);
		befriend(host, second);
		String gameId = lobby(host, 2);
		String firstInvitation = read(invite(host, gameId, first), "$.id");
		String secondInvitation = read(invite(host, gameId, second), "$.id");
		List<String> invitations = List.of(firstInvitation, secondInvitation);
		List<Account> invitees = List.of(first, second);

		List<Integer> statuses = Concurrently.run(2,
				i -> status(api.postJson(invitees.get(i).token(), "/invitations/" + invitations.get(i) + "/accept", "{}")));

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat((Integer) read(api.lobby(host.token(), gameId), "$.playerCount")).isEqualTo(2);
		assertThat(jdbc.queryForList("SELECT status FROM game.game_invitation WHERE game_id = ?::uuid ORDER BY status",
				String.class, gameId)).containsExactly("ACCEPTED", "INVALIDATED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game_player WHERE game_id = ?::uuid", Integer.class, gameId))
			.isEqualTo(2);
	}

	@Test
	void anInvitationToAStartedGameIsInvalidatedOnAcceptance() throws Exception {
		Account host = api.newAccount("startedhost");
		Account friend = api.newAccount("latecomer");
		Account guest = api.newAccount("guest");
		befriend(host, friend);
		befriend(host, guest);
		String gameId = lobby(host, 2);
		String lateInvitation = read(invite(host, gameId, friend), "$.id");
		api.postJson(guest.token(), "/invitations/" + read(invite(host, gameId, guest), "$.id") + "/accept", "{}");

		assertThat(code(api.postJson(friend.token(), "/invitations/" + lateInvitation + "/accept", "{}")))
			.isEqualTo("409 GAME_FULL");
		assertThat(jdbc.queryForObject("SELECT reason FROM game.game_invitation WHERE id = ?::uuid", String.class, lateInvitation))
			.isEqualTo("GAME_FULL");
		assertThat(code(invite(host, gameId, friend))).isEqualTo("409 LOBBY_NOT_JOINABLE");
	}

	@Test
	void blockingWithdrawsPendingInvitations() throws Exception {
		Account host = api.newAccount("blockhost");
		Account friend = api.newAccount("blockfriend");
		befriend(host, friend);
		String invitationId = read(invite(host, lobby(host, 2), friend), "$.id");

		api.postJson(friend.token(), "/blocks", "{\"username\":\"" + host.username() + "\"}");

		assertThat(code(api.postJson(friend.token(), "/invitations/" + invitationId + "/accept", "{}")))
			.isEqualTo("409 INVITATION_NOT_PENDING");
	}

	@Test
	void myGamesShowWhatIsNeededToResume() throws Exception {
		ApiDriver.Lobby game = api.startedGame("Resume me");

		MvcResult hostGames = api.getJson(game.host(), "/games");
		String path = "$[?(@.id == '" + game.gameId() + "')]";
		assertThat(ApiDriver.<List<String>>read(hostGames, path + ".status")).containsExactly("IN_PROGRESS");
		assertThat(ApiDriver.<List<Boolean>>read(hostGames, path + ".yourTurn")).containsExactly(true);
		assertThat(ApiDriver.<List<Integer>>read(hostGames, path + ".currentRound")).containsExactly(1);
		assertThat(ApiDriver.<List<Integer>>read(hostGames, path + ".humanPlayers")).containsExactly(2);
		assertThat(ApiDriver.<List<String>>read(hostGames, path + ".yourNation.name")).hasSize(1);
		assertThat(ApiDriver.<List<String>>read(hostGames, path + ".mapName")).hasSize(1);
		assertThat(ApiDriver.<List<Boolean>>read(api.getJson(game.guest(), "/games"), path + ".yourTurn")).containsExactly(false);
		assertThat(ApiDriver.<List<String>>read(api.getJson(game.guest(), "/notifications"), "$.items[*].kind"))
			.as("the guest hears that the host started the game").contains("GAME_STARTED");
	}

}
