package com.reach.strategy.campaign;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 10B peace conferences against PostgreSQL (ruleset v6): the request and answer, the
 * game-wide pause with out-of-turn negotiation, immutable revisions with approvals bound to one
 * revision, the approvals the terms require, atomic settlement, separate peace, NPC counterparts,
 * elimination and domination.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v6", "strategy.world.default-map=grand-campaign" })
class PeaceConferenceIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

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

	private String gold(CampaignScenario.Game game, String from, String to, long amountMg) {
		return "{\"type\":\"GOLD\",\"fromNationId\":\"" + c.nationId(game, from) + "\",\"toNationId\":\""
				+ c.nationId(game, to) + "\",\"amountMg\":" + amountMg + "}";
	}

	private String province(CampaignScenario.Game game, String from, String to, String provinceKey) {
		return "{\"type\":\"PROVINCE\",\"fromNationId\":\"" + c.nationId(game, from) + "\",\"toNationId\":\""
				+ c.nationId(game, to) + "\",\"provinceKey\":\"" + provinceKey + "\"}";
	}

	/** Requested by {@code requester}, accepted by {@code counterpart}; returns the conference id. */
	private String open(CampaignScenario.Game game, String warId, String requester, String counterpart, String kind)
			throws Exception {
		MvcResult requested = api.requestConference(game.token(requester), game.gameId(), warId, kind, UUID.randomUUID());
		assertThat(status(requested)).as(body(requested)).isEqualTo(201);
		String id = read(requested, "$.conference.id");
		MvcResult accepted = api.answerConference(game.token(counterpart), game.gameId(), id, true, UUID.randomUUID());
		assertThat(status(accepted)).as(body(accepted)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(accepted, "$.conference.status")).isEqualTo("ACTIVE");
		return id;
	}

	private MvcResult submit(CampaignScenario.Game game, String nationKey, String conferenceId, int expected,
			String... terms) throws Exception {
		return api.submitDraft(game.token(nationKey), game.gameId(), conferenceId, expected,
				"[" + String.join(",", terms) + "]", UUID.randomUUID());
	}

	private MvcResult approve(CampaignScenario.Game game, String nationKey, String conferenceId, int revision)
			throws Exception {
		return api.decideDraft(game.token(nationKey), game.gameId(), conferenceId, revision, "approve",
				UUID.randomUUID());
	}

	private int activeSeat(CampaignScenario.Game game) {
		return jdbc.queryForObject("SELECT active_seat FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
	}

	private String rowStatus(String table, String id) {
		return jdbc.queryForObject("SELECT status FROM game." + table + " WHERE id = ?::uuid", String.class, id);
	}

	private long persons(CampaignScenario.Game game) {
		Long people = jdbc.queryForObject("SELECT coalesce(sum(population), 0) FROM game.city WHERE game_id = ?::uuid",
				Long.class, game.gameId());
		Long soldiers = jdbc.queryForObject(
				"SELECT coalesce(sum(manpower), 0) FROM game.battalion WHERE game_id = ?::uuid", Long.class,
				game.gameId());
		return people + soldiers;
	}

	// --- lifecycle and pause ----------------------------------------------------------------------

	@Test
	void anActiveConferencePausesTheWholeGameButAllowsNegotiationOutOfTurn() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		int turn = c.turn(game);
		int round = c.round(game);
		int seat = activeSeat(game);

		// Only a war leader asks for the general peace; requesting does not pause anything yet.
		assertThat(code(api.requestConference(game.token("vostrava"), game.gameId(), warId, "GENERAL",
				UUID.randomUUID())))
			.isEqualTo("403 NOT_A_PARTICIPANT");
		assertThat(code(api.requestConference(game.token("castavar"), game.gameId(), warId, "SEPARATE",
				UUID.randomUUID())))
			.isEqualTo("409 LEADER_CANNOT_LEAVE");
		MvcResult requested = api.requestConference(game.token("nordmark"), game.gameId(), warId, "GENERAL",
				UUID.randomUUID());
		assertThat(status(requested)).as(body(requested)).isEqualTo(201);
		String id = read(requested, "$.conference.id");
		assertThat(c.paused(game)).isFalse();
		assertThat(code(api.answerConference(game.token("nordmark"), game.gameId(), id, true, UUID.randomUUID())))
			.isEqualTo("403 NOT_CONFERENCE_COUNTERPART");

		// The other leader accepts — whoever's turn it is.
		MvcResult accepted = api.answerConference(game.token("castavar"), game.gameId(), id, true, UUID.randomUUID());
		assertThat(status(accepted)).as(body(accepted)).isEqualTo(200);
		assertThat(c.paused(game)).isTrue();
		assertThat(ApiDriver.<Integer>read(accepted, "$.conference.currentRevision")).isEqualTo(1);
		assertThat(ApiDriver.<List<Object>>read(accepted, "$.conference.terms")).isEmpty();

		// Nothing else happens while it lasts: no end of turn, no gameplay command.
		String active = c.activeToken(game);
		assertThat(code(api.endTurn(active, game.gameId(), turn, UUID.randomUUID())))
			.isEqualTo("409 GAME_PAUSED_FOR_CONFERENCE");
		assertThat(code(api.sendGold(active, game.gameId(), c.nationId(game, "aestia"), 1000, UUID.randomUUID())))
			.isEqualTo("409 GAME_PAUSED_FOR_CONFERENCE");
		MvcResult state = api.state(active, game.gameId());
		assertThat(ApiDriver.<String>read(state, "$.pausedConferenceId")).isEqualTo(id);
		assertThat(ApiDriver.<Boolean>read(state, "$.yourTurn")).isFalse();
		assertThat(code(api.submitDraft(game.token("vostrava"), game.gameId(), id, 1, "[]", UUID.randomUUID())))
			.isEqualTo("403 NOT_CONFERENCE_PRINCIPAL");

		// Cancelling resumes the game exactly where it stopped.
		MvcResult cancelled = api.cancelConference(game.token("castavar"), game.gameId(), id, UUID.randomUUID());
		assertThat(status(cancelled)).as(body(cancelled)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(cancelled, "$.conference.status")).isEqualTo("CANCELLED");
		assertThat(c.paused(game)).isFalse();
		assertThat(c.turn(game)).isEqualTo(turn);
		assertThat(c.round(game)).isEqualTo(round);
		assertThat(activeSeat(game)).isEqualTo(seat);
		assertThat(status(c.endTurn(game))).isEqualTo(200);
	}

	@Test
	void aRefusedRequestNeverPausesAndOnlyOneConferenceRunsAtATime() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava"));
		String first = c.declareWar(game, "castavar", "nordmark");
		String second = c.declareWar(game, "vostrava", "castavar");

		MvcResult requested = api.requestConference(game.token("castavar"), game.gameId(), first, "GENERAL",
				UUID.randomUUID());
		String id = read(requested, "$.conference.id");
		assertThat(code(api.requestConference(game.token("castavar"), game.gameId(), first, "GENERAL",
				UUID.randomUUID())))
			.isEqualTo("409 CONFERENCE_ALREADY_REQUESTED");
		MvcResult rejected = api.answerConference(game.token("nordmark"), game.gameId(), id, false, UUID.randomUUID());
		assertThat(ApiDriver.<String>read(rejected, "$.conference.status")).isEqualTo("REJECTED");
		assertThat(c.paused(game)).isFalse();

		open(game, first, "nordmark", "castavar", "GENERAL");
		assertThat(code(api.requestConference(game.token("vostrava"), game.gameId(), second, "GENERAL",
				UUID.randomUUID())))
			.isEqualTo("409 CONFERENCE_IN_PROGRESS");
	}

	// --- revisions and approvals ------------------------------------------------------------------

	@Test
	void approvalsBelongToOneRevisionAndTheLastApprovalSettlesAtomically() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		int turn = c.turn(game);
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");

		MvcResult r2 = submit(game, "nordmark", id, 1, gold(game, "castavar", "nordmark", 20_000));
		assertThat(status(r2)).as(body(r2)).isEqualTo(201);
		assertThat(ApiDriver.<Integer>read(r2, "$.conference.currentRevision")).isEqualTo(2);
		// A draft built on an old revision, and an approval of an old revision, are refused.
		assertThat(code(submit(game, "castavar", id, 1))).isEqualTo("409 STALE_REVISION");
		assertThat(code(approve(game, "castavar", id, 1))).isEqualTo("409 STALE_REVISION");

		assertThat(status(approve(game, "castavar", id, 2))).isEqualTo(200);
		assertThat(code(approve(game, "castavar", id, 2))).isEqualTo("409 ALREADY_DECIDED");
		// Any change is a new revision: the earlier approval does not carry over.
		MvcResult r3 = submit(game, "nordmark", id, 2, gold(game, "castavar", "nordmark", 30_000));
		assertThat(ApiDriver.<List<String>>read(r3, "$.conference.approvals[*].decision")).containsOnlyNulls();
		assertThat(ApiDriver.<List<Object>>read(r3, "$.conference.revisions")).hasSize(3);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.conference_decision WHERE conference_id = ?::uuid",
				Integer.class, id)).isEqualTo(1);

		long castavar = c.treasury(game, "castavar");
		long nordmark = c.treasury(game, "nordmark");
		assertThat(status(approve(game, "castavar", id, 3))).isEqualTo(200);
		assertThat(c.treasury(game, "castavar")).isEqualTo(castavar);
		MvcResult settled = approve(game, "nordmark", id, 3);
		assertThat(status(settled)).as(body(settled)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(settled, "$.conference.status")).isEqualTo("CONCLUDED");
		assertThat(ApiDriver.<String>read(settled, "$.treaty.id")).isNotNull();
		assertThat(c.treasury(game, "castavar")).isEqualTo(castavar - 30_000);
		assertThat(c.treasury(game, "nordmark")).isEqualTo(nordmark + 30_000);
		assertThat(rowStatus("war", warId)).isEqualTo("ENDED");
		assertThat(jdbc.queryForObject("SELECT kind || '/' || revision FROM game.peace_treaty WHERE conference_id = ?::uuid",
				String.class, id)).isEqualTo("GENERAL/3");
		assertThat(c.paused(game)).isFalse();
		assertThat(c.turn(game)).isEqualTo(turn);
		MvcResult overview = api.diplomacy(game.token("castavar"), game.gameId());
		assertThat(ApiDriver.<List<Integer>>read(overview,
				"$.nations[?(@.nationId == '" + c.nationId(game, "nordmark") + "')].truceUntilRound"))
			.doesNotContainNull()
			.hasSize(1);
	}

	@Test
	void aNationThatGivesSomethingUpMustApproveButABeneficiaryNeedNot() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava"));
		c.alliance(game, "nordmark", "vostrava", "DEFENSIVE");
		String warId = c.declareWar(game, "castavar", "nordmark");
		c.callAndHonour(game, warId, "nordmark", "vostrava");
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");
		String vostrava = c.nationId(game, "vostrava");

		// Vostrava benefits: only the two leaders approve.
		MvcResult benefit = submit(game, "nordmark", id, 1, gold(game, "castavar", "vostrava", 5_000));
		assertThat(ApiDriver.<List<String>>read(benefit, "$.conference.approvals[*].nationId"))
			.containsExactlyInAnyOrder(c.nationId(game, "castavar"), c.nationId(game, "nordmark"));
		assertThat(code(approve(game, "vostrava", id, 2))).isEqualTo("403 APPROVAL_NOT_REQUIRED");

		// Vostrava pays: its approval is required, and the leaders' approvals alone do not settle.
		MvcResult pays = submit(game, "castavar", id, 2, gold(game, "vostrava", "castavar", 5_000));
		assertThat(ApiDriver.<List<String>>read(pays, "$.conference.approvals[*].nationId")).contains(vostrava)
			.hasSize(3);
		assertThat(code(submit(game, "vostrava", id, 3))).isEqualTo("403 NOT_CONFERENCE_PRINCIPAL");
		approve(game, "castavar", id, 3);
		MvcResult leaders = approve(game, "nordmark", id, 3);
		assertThat(ApiDriver.<String>read(leaders, "$.conference.status")).isEqualTo("ACTIVE");
		MvcResult view = api.conference(game.token("vostrava"), game.gameId(), id);
		assertThat(ApiDriver.<Boolean>read(view, "$.youMustDecide")).isTrue();
		long before = c.treasury(game, "vostrava");
		MvcResult settled = approve(game, "vostrava", id, 3);
		assertThat(ApiDriver.<String>read(settled, "$.conference.status")).as(body(settled)).isEqualTo("CONCLUDED");
		assertThat(c.treasury(game, "vostrava")).isEqualTo(before - 5_000);
		// The war ended for everyone; Vostrava has a truce with Castavar too.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.truce t
				WHERE t.game_id = ?::uuid AND ((t.nation_a_id = ?::uuid AND t.nation_b_id = ?::uuid)
				    OR (t.nation_a_id = ?::uuid AND t.nation_b_id = ?::uuid))
				""", Integer.class, game.gameId(), vostrava, c.nationId(game, "castavar"), c.nationId(game, "castavar"),
				vostrava)).isEqualTo(1);
	}

	@Test
	void aSettlementThatNoLongerHoldsIsRolledBackCompletely() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");
		submit(game, "castavar", id, 1, gold(game, "nordmark", "castavar", 40_000));
		approve(game, "castavar", id, 2);
		// Nordmark's treasury no longer covers the reparations.
		c.world.setTreasury(game.lobby(), "nordmark", 1_000);
		assertThat(code(approve(game, "nordmark", id, 2))).isEqualTo("409 PEACE_TERMS_NO_LONGER_VALID");
		assertThat(rowStatus("peace_conference", id)).isEqualTo("ACTIVE");
		assertThat(rowStatus("war", warId)).isEqualTo("ACTIVE");
		assertThat(c.treasury(game, "nordmark")).isEqualTo(1_000);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.conference_decision WHERE conference_id = ?::uuid",
				Integer.class, id)).isEqualTo(1);
		assertThat(c.paused(game)).isTrue();
	}

	@Test
	void concurrentFinalApprovalsSettleExactlyOnce() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");
		submit(game, "castavar", id, 1, gold(game, "nordmark", "castavar", 10_000));
		long before = c.treasury(game, "castavar");
		List<String> nations = List.of("castavar", "nordmark", "castavar", "nordmark");
		List<MvcResult> results = Concurrently.run(4, i -> approve(game, nations.get(i), id, 2));
		assertThat(results).filteredOn(r -> status(r) == 200).hasSize(2);
		assertThat(c.treasury(game, "castavar")).isEqualTo(before + 10_000);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.peace_treaty WHERE war_id = ?::uuid", Integer.class,
				warId)).isEqualTo(1);
	}

	// --- separate peace ---------------------------------------------------------------------------

	@Test
	void aNonLeaderLeavesTheWarBySeparatePeaceWhileTheWarGoesOn() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava"));
		c.alliance(game, "nordmark", "vostrava", "DEFENSIVE");
		String warId = c.declareWar(game, "castavar", "nordmark");
		c.callAndHonour(game, warId, "nordmark", "vostrava");
		String lent = c.place(game, "vostrava", "nordheim", "INFANTRY", 400);
		MvcResult attached = api.attach(c.turnOf(game, "vostrava"), game.gameId(), List.of(lent),
				c.nationId(game, "nordmark"), UUID.randomUUID());
		assertThat(status(attached)).as(body(attached)).isEqualTo(200);
		long persons = persons(game);

		assertThat(code(api.requestConference(game.token("nordmark"), game.gameId(), warId, "SEPARATE",
				UUID.randomUUID())))
			.isEqualTo("409 LEADER_CANNOT_LEAVE");
		String id = open(game, warId, "vostrava", "castavar", "SEPARATE");
		MvcResult draft = submit(game, "castavar", id, 1, gold(game, "vostrava", "castavar", 2_000));
		assertThat(status(draft)).as(body(draft)).isEqualTo(201);
		// The war leader Nordmark is not a party to Vostrava's separate peace.
		assertThat(code(submit(game, "castavar", id, 2, gold(game, "nordmark", "castavar", 2_000))))
			.isEqualTo("422 PEACE_TERMS_INVALID");
		approve(game, "vostrava", id, 2);
		MvcResult settled = approve(game, "castavar", id, 2);
		assertThat(ApiDriver.<String>read(settled, "$.conference.status")).as(body(settled)).isEqualTo("CONCLUDED");

		assertThat(rowStatus("war", warId)).isEqualTo("ACTIVE");
		MvcResult war = api.war(game.token("nordmark"), game.gameId(), warId);
		String vostrava = c.nationId(game, "vostrava");
		assertThat(ApiDriver.<List<String>>read(war, "$.war.participants[?(@.nationId == '" + vostrava + "')].leftReason"))
			.containsExactly("SEPARATE_PEACE");
		MvcResult castavarView = api.diplomacy(game.token("castavar"), game.gameId());
		assertThat(ApiDriver.<List<String>>read(castavarView, "$.nations[?(@.nationId == '" + vostrava + "')].status"))
			.containsExactly("PEACE");
		assertThat(ApiDriver.<List<String>>read(castavarView,
				"$.nations[?(@.nationId == '" + c.nationId(game, "nordmark") + "')].status"))
			.containsExactly("WAR");
		// Vostrava's lent battalion is back under its command and out of Nordmark's land.
		assertThat(jdbc.queryForObject("SELECT commander_nation_id FROM game.battalion WHERE id = ?::uuid", UUID.class,
				lent)).isNull();
		assertThat(c.world.provinceOf(lent)).isNotEqualTo("nordheim");
		assertThat(persons(game)).isEqualTo(persons);
		assertThat(c.paused(game)).isFalse();
		assertThat(jdbc.queryForObject("SELECT exiting_nation_id::text FROM game.peace_treaty WHERE conference_id = ?::uuid",
				String.class, id)).isEqualTo(vostrava);
	}

	// --- NPC counterparts, elimination, domination ------------------------------------------------

	@Test
	void anNpcNationTalksAtOnceAndGivesUpOnlyWhatItHasAlreadyLost() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "frisia");
		MvcResult requested = api.requestConference(game.token("castavar"), game.gameId(), warId, "GENERAL",
				UUID.randomUUID());
		assertThat(ApiDriver.<String>read(requested, "$.conference.status")).as(body(requested)).isEqualTo("ACTIVE");
		String id = read(requested, "$.conference.id");

		// Frisia refuses to give up a province Castavar does not hold.
		MvcResult refused = submit(game, "castavar", id, 1, province(game, "frisia", "castavar", "brabantia"));
		assertThat(ApiDriver.<List<String>>read(refused,
				"$.conference.approvals[?(@.nationId == '" + c.nationId(game, "frisia") + "')].decision"))
			.containsExactly("REJECTED");

		// Once every Frisian province is occupied, it cedes them all and is eliminated.
		for (String key : List.of("westerdijk", "oostergo", "brabantia", "geldermark")) {
			c.world.occupy(game.lobby(), key, "castavar");
		}
		long persons = persons(game);
		MvcResult all = submit(game, "castavar", id, 2, province(game, "frisia", "castavar", "westerdijk"),
				province(game, "frisia", "castavar", "oostergo"), province(game, "frisia", "castavar", "brabantia"),
				province(game, "frisia", "castavar", "geldermark"));
		assertThat(status(all)).as(body(all)).isEqualTo(201);
		assertThat(ApiDriver.<List<String>>read(all,
				"$.conference.approvals[?(@.nationId == '" + c.nationId(game, "frisia") + "')].decision"))
			.containsExactly("APPROVED");
		MvcResult settled = approve(game, "castavar", id, 3);
		assertThat(ApiDriver.<String>read(settled, "$.conference.status")).as(body(settled)).isEqualTo("CONCLUDED");

		assertThat(jdbc.queryForObject("SELECT eliminated_round FROM game.nation WHERE id = ?::uuid", Integer.class,
				c.nationId(game, "frisia"))).isEqualTo(c.round(game));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.battalion WHERE nation_id = ?::uuid", Integer.class,
				c.nationId(game, "frisia"))).isZero();
		// Its soldiers went home as civilians: nobody was created or lost.
		assertThat(persons(game)).isEqualTo(persons);
		MvcResult events = api.diplomacyEvents(game.token("nordmark"), game.gameId());
		assertThat(ApiDriver.<List<String>>read(events, "$[*].kind")).contains("NATION_ELIMINATED");
		MvcResult state = api.state(game.token("nordmark"), game.gameId());
		assertThat(ApiDriver.<String>read(state, "$.status")).isEqualTo("IN_PROGRESS");
		assertThat(ApiDriver.<List<Integer>>read(state,
				"$.nations[?(@.id == '" + c.nationId(game, "frisia") + "')].eliminatedRound"))
			.doesNotContainNull();
	}

	@Test
	void theLastNationStandingWinsByDomination() throws Exception {
		CampaignScenario.Game game = c.start("sundered-reach", List.of("varn", "ostra"));
		String warId = c.declareWar(game, "varn", "ostra");
		String id = open(game, warId, "varn", "ostra", "GENERAL");
		List<String> ostra = List.of("kaldric", "ostmark", "sablewater", "ostrava", "marrowfield", "saltreach");
		String[] terms = ostra.stream().map(p -> province(game, "ostra", "varn", p)).toArray(String[]::new);
		MvcResult draft = submit(game, "varn", id, 1, terms);
		assertThat(status(draft)).as(body(draft)).isEqualTo(201);
		approve(game, "ostra", id, 2);
		MvcResult settled = approve(game, "varn", id, 2);
		assertThat(status(settled)).as(body(settled)).isEqualTo(200);

		MvcResult state = api.state(game.token("varn"), game.gameId());
		assertThat(ApiDriver.<String>read(state, "$.status")).isEqualTo("FINISHED");
		MvcResult result = api.result(game.token("ostra"), game.gameId());
		assertThat(status(result)).as(body(result)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(result, "$.reason")).isEqualTo("DOMINATION");
		assertThat(ApiDriver.<String>read(result, "$.winnerNationId")).isEqualTo(c.nationId(game, "varn"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.game_result WHERE game_id = ?::uuid", Integer.class,
				game.gameId())).isEqualTo(1);
		assertThat(c.paused(game)).isFalse();
	}

	@Test
	void anEliminatedHumanTakesNoMoreTurnsAndCanGiveNoMoreOrders() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");
		String[] terms = List.of("holmgard", "wendland", "pomerel", "westfal", "nordheim", "marchia", "harzland")
			.stream()
			.map(p -> province(game, "nordmark", "castavar", p))
			.toArray(String[]::new);
		submit(game, "castavar", id, 1, terms);
		approve(game, "nordmark", id, 2);
		MvcResult settled = approve(game, "castavar", id, 2);
		assertThat(ApiDriver.<String>read(settled, "$.conference.status")).as(body(settled)).isEqualTo("CONCLUDED");

		assertThat(jdbc.queryForObject("SELECT gp.eliminated_round FROM game.game_player gp JOIN game.nation n ON n.game_player_id = gp.id WHERE n.id = ?::uuid",
				Integer.class, c.nationId(game, "nordmark"))).isNotNull();
		MvcResult state = api.state(game.token("nordmark"), game.gameId());
		assertThat(ApiDriver.<Boolean>read(state, "$.youAreEliminated")).isTrue();
		assertThat(ApiDriver.<String>read(state, "$.status")).isEqualTo("IN_PROGRESS");
		assertThat(code(api.sendGold(game.token("nordmark"), game.gameId(), c.nationId(game, "vostrava"), 1_000,
				UUID.randomUUID())))
			.isEqualTo("409 NATION_ELIMINATED");
		// Two full rounds pass without Nordmark ever being the active player.
		for (int i = 0; i < 4; i++) {
			assertThat(c.activeToken(game)).isNotEqualTo(game.token("nordmark"));
			assertThat(status(c.endTurn(game))).isEqualTo(200);
		}
	}

	@Test
	void conferencesAndCallsNeedAuthenticationAndMembership() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");
		String base = "/api/v1/games/" + game.gameId();
		assertThat(status(api.anonymous(base + "/diplomacy/conferences/" + id))).isEqualTo(401);
		String stranger = ApiDriver.newPlayer();
		assertThat(code(api.conference(stranger, game.gameId(), id))).isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.requestConference(stranger, game.gameId(), warId, "GENERAL", UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.answerConference(stranger, game.gameId(), id, true, UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.submitDraft(stranger, game.gameId(), id, 1, "[]", UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.decideDraft(stranger, game.gameId(), id, 1, "approve", UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.cancelConference(stranger, game.gameId(), id, UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.callToArms(stranger, game.gameId(), warId, c.nationId(game, "castavar"), UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.attach(stranger, game.gameId(), List.of(UUID.randomUUID().toString()),
				c.nationId(game, "castavar"), UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		// Commands without an idempotency key are refused before anything else.
		assertThat(code(api.decideDraft(game.token("castavar"), game.gameId(), id, 1, "approve", null)))
			.isEqualTo("400 IDEMPOTENCY_KEY_MISSING");
	}

	@Test
	void whenTheActivePlayerIsEliminatedTheTurnPassesOnAsPlayResumes() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava"));
		String warId = c.declareWar(game, "castavar", "nordmark");
		assertThat(c.activeToken(game)).isEqualTo(game.token("castavar"));
		int turn = c.turn(game);
		String id = open(game, warId, "castavar", "nordmark", "GENERAL");
		String[] terms = List.of("galmar", "leomir", "sierravel", "lusande", "meseta", "valcaro", "andarra")
			.stream()
			.map(p -> province(game, "castavar", "nordmark", p))
			.toArray(String[]::new);
		submit(game, "nordmark", id, 1, terms);
		approve(game, "castavar", id, 2);
		MvcResult settled = approve(game, "nordmark", id, 2);
		assertThat(ApiDriver.<String>read(settled, "$.conference.status")).as(body(settled)).isEqualTo("CONCLUDED");
		assertThat(c.paused(game)).isFalse();
		assertThat(c.turn(game)).isEqualTo(turn + 1);
		assertThat(c.activeToken(game)).isNotEqualTo(game.token("castavar"));
		assertThat(status(c.endTurn(game))).isEqualTo(200);
	}

}
