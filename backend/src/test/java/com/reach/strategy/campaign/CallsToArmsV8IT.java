package com.reach.strategy.campaign;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.game.application.TurnService;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Ruleset v8 calls to arms against PostgreSQL (docs/DIPLOMACY.md §12a): a truce forbids declaring
 * war, not answering a valid call; a declarer may invite its normal allies to join the attack, each
 * answers on its own turn, and declining an invitation keeps the alliance. Computer nations stay
 * passive here (their turns are passed without actions) so the scenario is deterministic.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v8", "strategy.world.default-map=grand-campaign",
		"strategy.ai.execution=manual" })
class CallsToArmsV8IT {

	static final List<String> HUMANS = List.of("castavar", "nordmark", "vostrava", "dravonia");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TurnService turns;

	@Autowired
	ComputerSeats seats;

	@Autowired
	ComputerPlayers computers;

	ApiDriver api;

	CampaignScenario c;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		c = new CampaignScenario(api, jdbc);
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private int activeSeat(CampaignScenario.Game game) {
		return jdbc.queryForObject("SELECT active_seat FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
	}

	/** Ends turns (humans by their token, computers without any action) until it is {@code nationKey}'s. */
	private String on(CampaignScenario.Game game, String nationKey) throws Exception {
		int target = game.nationKeys().indexOf(nationKey);
		for (int i = 0; i < 40 && activeSeat(game) != target; i++) {
			int seat = activeSeat(game);
			if (seat < game.tokens().size()) {
				MvcResult ended = c.endTurn(game);
				assertThat(status(ended)).as(body(ended)).isEqualTo(200);
			}
			else {
				UUID gameId = UUID.fromString(game.gameId());
				ComputerSeats.Seat computer = seats.seats(gameId).stream().filter(s -> s.seat() == seat).findFirst().orElseThrow();
				turns.endTurn(computers.actor(computer.playerId()), gameId, UUID.randomUUID(), c.turn(game));
			}
		}
		assertThat(activeSeat(game)).isEqualTo(target);
		return game.token(nationKey);
	}

	private String nation(CampaignScenario.Game game, String key) {
		return c.nationId(game, key);
	}

	private String alliance(CampaignScenario.Game game, String proposer, String partner, String kind) throws Exception {
		MvcResult proposed = api.proposeAlliance(on(game, proposer), game.gameId(), nation(game, partner), kind,
				UUID.randomUUID());
		assertThat(status(proposed)).as(body(proposed)).isEqualTo(201);
		String id = read(proposed, "$.alliance.id");
		MvcResult accepted = api.alliance(on(game, partner), game.gameId(), id, "accept", UUID.randomUUID());
		assertThat(status(accepted)).as(body(accepted)).isEqualTo(200);
		return id;
	}

	private String declare(CampaignScenario.Game game, String attacker, String target, List<String> invite)
			throws Exception {
		MvcResult war = api.declareWar(on(game, attacker), game.gameId(), nation(game, target),
				invite.stream().map(k -> nation(game, k)).toList(), UUID.randomUUID());
		assertThat(status(war)).as(body(war)).isEqualTo(201);
		return read(war, "$.war.id");
	}

	/** War, then a white peace at a conference: leaves a truce between the two nations. */
	private void truce(CampaignScenario.Game game, String a, String b) throws Exception {
		String warId = declare(game, a, b, List.of());
		MvcResult requested = api.requestConference(game.token(a), game.gameId(), warId, "GENERAL", UUID.randomUUID());
		assertThat(status(requested)).as(body(requested)).isEqualTo(201);
		String conference = read(requested, "$.conference.id");
		assertThat(status(api.answerConference(game.token(b), game.gameId(), conference, true, UUID.randomUUID())))
			.isEqualTo(200);
		assertThat(status(api.decideDraft(game.token(a), game.gameId(), conference, 1, "approve", UUID.randomUUID())))
			.isEqualTo(200);
		MvcResult settled = api.decideDraft(game.token(b), game.gameId(), conference, 1, "approve", UUID.randomUUID());
		assertThat(status(settled)).as(body(settled)).isEqualTo(200);
		assertThat(ApiDriver.<List<Integer>>read(api.diplomacy(game.token(a), game.gameId()),
				"$.nations[?(@.nationId == '" + nation(game, b) + "')].truceUntilRound")).doesNotContainNull().hasSize(1);
	}

	private String pendingCall(CampaignScenario.Game game, String warId, String ally) throws Exception {
		List<String> ids = read(api.diplomacy(game.token(ally), game.gameId()),
				"$.calls[?(@.warId == '" + warId + "' && @.allyNationId == '" + nation(game, ally) + "' && @.status == 'PENDING')].id");
		assertThat(ids).hasSize(1);
		return ids.getFirst();
	}

	private List<String> side(CampaignScenario.Game game, String warId, String key) throws Exception {
		return read(api.war(game.token(key), game.gameId(), warId),
				"$.war.participants[?(@.nationId == '" + nation(game, key) + "')].side");
	}

	@Test
	void aTruceForbidsDeclaringWarButNotHonouringADefensiveCall() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		truce(game, "castavar", "nordmark");
		alliance(game, "castavar", "dravonia", "NORMAL");
		String warId = declare(game, "nordmark", "dravonia", List.of());

		// Declaring war directly is still forbidden by the truce.
		assertThat(code(api.declareWar(on(game, "castavar"), game.gameId(), nation(game, "nordmark"), UUID.randomUUID())))
			.isEqualTo("409 TRUCE_IN_EFFECT");

		MvcResult called = api.callToArms(on(game, "dravonia"), game.gameId(), warId, nation(game, "castavar"),
				UUID.randomUUID());
		assertThat(status(called)).as(body(called)).isEqualTo(201);
		assertThat((String) read(called, "$.call.side")).isEqualTo("DEFENDER");
		MvcResult honoured = api.answerCall(on(game, "castavar"), game.gameId(), read(called, "$.call.id"), true,
				UUID.randomUUID());
		assertThat(status(honoured)).as(body(honoured)).isEqualTo(200);
		assertThat(side(game, warId, "castavar")).containsExactly("DEFENDER");
	}

	@Test
	void aDeclarerInvitesANormalAllyWhoMayJoinTheAttackDespiteATruce() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		truce(game, "castavar", "nordmark");
		String allianceId = alliance(game, "castavar", "dravonia", "NORMAL");

		MvcResult war = api.declareWar(on(game, "dravonia"), game.gameId(), nation(game, "nordmark"),
				List.of(nation(game, "castavar"), nation(game, "castavar")), UUID.randomUUID());
		assertThat(status(war)).as(body(war)).isEqualTo(201);
		String warId = read(war, "$.war.id");
		assertThat(ApiDriver.<List<String>>read(war, "$.invitedAllies")).containsExactly(nation(game, "castavar"));
		// Invited, not forced in.
		assertThat(side(game, warId, "castavar")).isEmpty();

		String callId = pendingCall(game, warId, "castavar");
		MvcResult honoured = api.answerCall(on(game, "castavar"), game.gameId(), callId, true, UUID.randomUUID());
		assertThat(status(honoured)).as(body(honoured)).isEqualTo(200);
		assertThat(side(game, warId, "castavar")).containsExactly("ATTACKER");
		assertThat(ApiDriver.<List<String>>read(api.diplomacy(game.token("nordmark"), game.gameId()),
				"$.nations[?(@.nationId == '" + nation(game, "castavar") + "')].status")).containsExactly("WAR");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.war_front WHERE war_id = ?::uuid AND active
				  AND nation_a_id = ?::uuid AND nation_b_id = ?::uuid
				""", Integer.class, warId, nation(game, "castavar"), nation(game, "nordmark"))).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM game.alliance WHERE id = ?::uuid", String.class, allianceId))
			.isEqualTo("ACTIVE");
	}

	@Test
	void severalAlliesCanBeInvitedAndDecliningAnInvitationKeepsTheAlliance() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		String withCastavar = alliance(game, "castavar", "dravonia", "NORMAL");
		String withVostrava = alliance(game, "vostrava", "dravonia", "NORMAL");

		String warId = declare(game, "dravonia", "nordmark", List.of("castavar", "vostrava"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.call_to_arms WHERE war_id = ?::uuid AND side = 'ATTACKER'",
				Integer.class, warId)).isEqualTo(2);

		String castavarCall = pendingCall(game, warId, "castavar");
		String vostravaCall = pendingCall(game, warId, "vostrava");
		assertThat(status(api.answerCall(on(game, "castavar"), game.gameId(), castavarCall, true, UUID.randomUUID())))
			.isEqualTo(200);
		MvcResult declined = api.answerCall(on(game, "vostrava"), game.gameId(), vostravaCall, false, UUID.randomUUID());
		assertThat(status(declined)).as(body(declined)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(declined, "$.call.status")).isEqualTo("DECLINED");

		assertThat(side(game, warId, "castavar")).containsExactly("ATTACKER");
		assertThat(side(game, warId, "vostrava")).isEmpty();
		assertThat(jdbc.queryForList("SELECT status FROM game.alliance WHERE id IN (?::uuid, ?::uuid)", String.class,
				withCastavar, withVostrava)).containsOnly("ACTIVE");
		// Answering twice is impossible.
		assertThat(code(api.answerCall(game.token("vostrava"), game.gameId(), vostravaCall, true, UUID.randomUUID())))
			.isEqualTo("409 CALL_NOT_PENDING");
	}

	@Test
	void onlyNormalAlliesCanBeInvitedAndOtherParticipationRulesStillApply() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		alliance(game, "castavar", "dravonia", "DEFENSIVE");
		alliance(game, "vostrava", "dravonia", "NORMAL");
		alliance(game, "vostrava", "nordmark", "NORMAL");

		// A defensive-pact partner is no offensive ally: the whole declaration is refused.
		MvcResult refused = api.declareWar(on(game, "dravonia"), game.gameId(), nation(game, "nordmark"),
				List.of(nation(game, "castavar")), UUID.randomUUID());
		assertThat(code(refused)).isEqualTo("422 INVITEE_NOT_ELIGIBLE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.war WHERE game_id = ?::uuid", Integer.class,
				game.gameId())).isZero();

		// Vostrava is also allied with the target: it is invited, but cannot join (not a truce matter).
		String warId = declare(game, "dravonia", "nordmark", List.of("vostrava"));
		String call = pendingCall(game, warId, "vostrava");
		assertThat(code(api.answerCall(on(game, "vostrava"), game.gameId(), call, true, UUID.randomUUID())))
			.isEqualTo("409 ALLIED_WITH_ENEMY");
		assertThat(side(game, warId, "vostrava")).isEmpty();

		// Without invitations nobody is called.
		CampaignScenario.Game quiet = c.start("grand-campaign", HUMANS);
		alliance(quiet, "castavar", "dravonia", "NORMAL");
		String quietWar = declare(quiet, "dravonia", "nordmark", List.of());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.call_to_arms WHERE war_id = ?::uuid", Integer.class,
				quietWar)).isZero();
	}

	@Test
	void gamesOnOlderRulesetsKeepTheirRules() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		// A running v7 game: the ruleset is stored per game (the game and its diplomacy record); v7 and v8
		// differ only in their diplomacy switches and AI values.
		jdbc.update("UPDATE game.game SET ruleset_version = 'v7' WHERE id = ?::uuid", game.gameId());
		jdbc.update("UPDATE game.diplomacy_game SET ruleset_version = 'v7' WHERE game_id = ?::uuid", game.gameId());
		truce(game, "castavar", "nordmark");
		alliance(game, "castavar", "dravonia", "NORMAL");

		assertThat(code(api.declareWar(on(game, "dravonia"), game.gameId(), nation(game, "vostrava"),
				List.of(nation(game, "castavar")), UUID.randomUUID()))).isEqualTo("409 OFFENSIVE_CALLS_NOT_AVAILABLE");

		String warId = declare(game, "nordmark", "dravonia", List.of());
		MvcResult called = api.callToArms(on(game, "dravonia"), game.gameId(), warId, nation(game, "castavar"),
				UUID.randomUUID());
		assertThat(code(api.answerCall(on(game, "castavar"), game.gameId(), read(called, "$.call.id"), true,
				UUID.randomUUID()))).isEqualTo("409 TRUCE_IN_EFFECT");
	}

}
