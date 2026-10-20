package com.reach.strategy.campaign;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
 * Phase 10B against PostgreSQL through the real API (ruleset v6, Grand Campaign): defensive calls to
 * arms, attached battalions and coalition battles. Castavar attacks Nordmark; Vostrava is
 * Nordmark's defensive ally. The three are human; the other seven nations are NPC-controlled.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v6", "strategy.world.default-map=grand-campaign" })
class CoalitionWarIT {

	static final List<String> HUMANS = List.of("castavar", "nordmark", "vostrava");

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

	private record War(CampaignScenario.Game game, String warId, String allianceId) {
	}

	/** Nordmark and Vostrava are defensive allies; Castavar has declared war on Nordmark. */
	private War war() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		String allianceId = c.alliance(game, "nordmark", "vostrava", "DEFENSIVE");
		String warId = c.declareWar(game, "castavar", "nordmark");
		return new War(game, warId, allianceId);
	}

	// --- calls to arms ----------------------------------------------------------------------------

	@Test
	void inV6NoAllyJoinsAutomaticallyAndTheCalledAllyHonoursOnItsTurn() throws Exception {
		War w = war();
		CampaignScenario.Game game = w.game();
		MvcResult detail = api.war(game.token("nordmark"), game.gameId(), w.warId());
		assertThat(ApiDriver.<List<String>>read(detail, "$.war.participants[*].nationId"))
			.containsExactlyInAnyOrder(c.nationId(game, "castavar"), c.nationId(game, "nordmark"));

		// Only the attacked leader calls, only its allies, on its turn.
		String vostrava = c.nationId(game, "vostrava");
		assertThat(code(api.callToArms(game.token("nordmark"), game.gameId(), w.warId(), vostrava, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		String nordmark = c.turnOf(game, "nordmark");
		assertThat(code(api.callToArms(nordmark, game.gameId(), w.warId(), c.nationId(game, "aestia"),
				UUID.randomUUID())))
			.isEqualTo("409 NOT_ALLIED");
		UUID key = UUID.randomUUID();
		MvcResult called = api.callToArms(nordmark, game.gameId(), w.warId(), vostrava, key);
		assertThat(status(called)).as(body(called)).isEqualTo(201);
		String callId = read(called, "$.call.id");
		assertThat(ApiDriver.<String>read(called, "$.call.status")).isEqualTo("PENDING");
		// Same key: the same call, not a second one.
		MvcResult replay = api.callToArms(nordmark, game.gameId(), w.warId(), vostrava, key);
		assertThat(ApiDriver.<String>read(replay, "$.call.id")).isEqualTo(callId);
		assertThat(code(api.callToArms(nordmark, game.gameId(), w.warId(), vostrava, UUID.randomUUID())))
			.isEqualTo("409 CALL_ALREADY_PENDING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.call_to_arms WHERE game_id = ?::uuid", Integer.class,
				game.gameId())).isEqualTo(1);

		// The caller cannot answer for its ally; the ally sees that it can.
		String vToken = c.turnOf(game, "vostrava");
		MvcResult overview = api.diplomacy(vToken, game.gameId());
		assertThat(ApiDriver.<List<Boolean>>read(overview, "$.calls[?(@.id == '" + callId + "')].canAnswer"))
			.containsExactly(true);
		MvcResult honoured = api.answerCall(vToken, game.gameId(), callId, true, UUID.randomUUID());
		assertThat(status(honoured)).as(body(honoured)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(honoured, "$.call.status")).isEqualTo("HONORED");
		assertThat(code(api.answerCall(vToken, game.gameId(), callId, false, UUID.randomUUID())))
			.isEqualTo("409 CALL_NOT_PENDING");

		detail = api.war(vToken, game.gameId(), w.warId());
		assertThat(ApiDriver.<List<String>>read(detail, "$.war.participants[?(@.nationId == '" + vostrava + "')].joinReason"))
			.containsExactly("CALL_TO_ARMS");
		assertThat(ApiDriver.<List<String>>read(detail, "$.war.participants[?(@.nationId == '" + vostrava + "')].side"))
			.containsExactly("DEFENDER");
		MvcResult castavarView = api.diplomacy(game.token("castavar"), game.gameId());
		assertThat(ApiDriver.<List<String>>read(castavarView, "$.nations[?(@.nationId == '" + vostrava + "')].status"))
			.containsExactly("WAR");
		// The war leaders are unchanged: the declarer and the target.
		assertThat(ApiDriver.<String>read(detail, "$.war.attackerLeaderId")).isEqualTo(c.nationId(game, "castavar"));
		assertThat(ApiDriver.<String>read(detail, "$.war.defenderLeaderId")).isEqualTo(c.nationId(game, "nordmark"));
	}

	@Test
	void decliningACallEndsTheAllianceWithoutAnyOtherPenalty() throws Exception {
		War w = war();
		CampaignScenario.Game game = w.game();
		String vostrava = c.nationId(game, "vostrava");
		MvcResult called = api.callToArms(c.turnOf(game, "nordmark"), game.gameId(), w.warId(), vostrava,
				UUID.randomUUID());
		String callId = read(called, "$.call.id");
		long treasury = c.treasury(game, "vostrava");

		String vToken = c.turnOf(game, "vostrava");
		assertThat(code(api.answerCall(game.token("castavar"), game.gameId(), callId, false, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		MvcResult declined = api.answerCall(vToken, game.gameId(), callId, false, UUID.randomUUID());
		assertThat(status(declined)).as(body(declined)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(declined, "$.call.status")).isEqualTo("DECLINED");
		assertThat(jdbc.queryForObject("SELECT status || '/' || end_reason FROM game.alliance WHERE id = ?::uuid",
				String.class, w.allianceId())).isEqualTo("ENDED/CALL_DECLINED");
		assertThat(c.treasury(game, "vostrava")).isEqualTo(treasury);
		MvcResult detail = api.war(vToken, game.gameId(), w.warId());
		assertThat(ApiDriver.<List<String>>read(detail, "$.war.participants[*].nationId")).doesNotContain(vostrava);
		MvcResult events = api.diplomacyEvents(vToken, game.gameId());
		assertThat(ApiDriver.<List<String>>read(events, "$[*].kind")).contains("CALL_DECLINED", "ALLIANCE_ENDED");
	}

	// --- attachments and coalition battles -------------------------------------------------------

	@Test
	void anAttachedBattalionFightsUnderItsCommanderInACoalitionBattleWithPerNationReports() throws Exception {
		War w = war();
		CampaignScenario.Game game = w.game();
		c.callAndHonour(game, w.warId(), "nordmark", "vostrava");
		String invader = c.place(game, "castavar", "westfal", "INFANTRY", 300);
		String north = c.place(game, "nordmark", "nordheim", "INFANTRY", 500);
		String east = c.place(game, "vostrava", "nordheim", "INFANTRY", 400);
		String nordmark = c.nationId(game, "nordmark");
		String vostrava = c.nationId(game, "vostrava");

		// Only the owner attaches, only to a partner, on its turn.
		String vToken = c.turnOf(game, "vostrava");
		assertThat(code(api.attach(vToken, game.gameId(), List.of(east), c.nationId(game, "aestia"), UUID.randomUUID())))
			.isEqualTo("409 ATTACHMENT_NOT_ALLOWED");
		assertThat(code(api.attach(vToken, game.gameId(), List.of(north), nordmark, UUID.randomUUID())))
			.startsWith("4");
		MvcResult attached = api.attach(vToken, game.gameId(), List.of(east), nordmark, UUID.randomUUID());
		assertThat(status(attached)).as(body(attached)).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT commander_nation_id::text FROM game.battalion WHERE id = ?::uuid",
				String.class, east)).isEqualTo(nordmark);
		// The owner cannot move it while it is attached.
		assertThat(code(api.move(vToken, game.gameId(), List.of(east), "westfal", UUID.randomUUID())))
			.isEqualTo("409 BATTALION_ATTACHED");

		String nToken = c.turnOf(game, "nordmark");
		MvcResult moved = api.move(nToken, game.gameId(), List.of(north, east), "westfal", UUID.randomUUID());
		assertThat(status(moved)).as(body(moved)).isEqualTo(200);
		assertThat(ApiDriver.<String>read(moved, "$.battle.details.winner")).isEqualTo("ATTACKER");
		List<Map<String, Object>> nations = read(moved, "$.battle.details.nations");
		assertThat(nations).extracting(n -> n.get("nationId"))
			.containsExactlyInAnyOrder(nordmark, vostrava, c.nationId(game, "castavar"));
		assertThat(nations).filteredOn(n -> vostrava.equals(n.get("nationId")))
			.singleElement()
			.satisfies(n -> assertThat(n.get("side")).isEqualTo("ATTACKER"));
		// Losses are booked on the battalions that took them, and every soldier is accounted for.
		List<Map<String, Object>> attackers = read(moved, "$.battle.details.attackers");
		assertThat(attackers).extracting(a -> a.get("nationId")).containsExactlyInAnyOrder(nordmark, vostrava);
		for (Map<String, Object> a : attackers) {
			String id = (String) a.get("battalionId");
			assertThat(c.world.manpower(id)).isEqualTo(((Number) a.get("manpowerAfter")).intValue());
			assertThat(c.world.cohorts(id).values().stream().mapToInt(Integer::intValue).sum())
				.isEqualTo(c.world.manpower(id));
		}
		assertThat(c.world.inconsistentCohorts(game.lobby())).isEmpty();
		// The battle is reported once, by the commander; no merged nation exists.
		assertThat(ApiDriver.<String>read(moved, "$.battle.attackerNationId")).isEqualTo(nordmark);

		// Movement is per battalion per round: neither the commander nor the owner after release
		// can move it again this round.
		assertThat(code(api.move(nToken, game.gameId(), List.of(east), "nordheim", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_ALREADY_MOVED");
		vToken = c.turnOf(game, "vostrava");
		MvcResult released = api.release(vToken, game.gameId(), List.of(east), UUID.randomUUID());
		assertThat(status(released)).as(body(released)).isEqualTo(200);
		assertThat(code(api.move(vToken, game.gameId(), List.of(east), "nordheim", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_ALREADY_MOVED");
		assertThat(invader).isNotNull();
	}

	@Test
	void alliedBattalionsDefendTogetherWithoutAttachment() throws Exception {
		War w = war();
		CampaignScenario.Game game = w.game();
		c.callAndHonour(game, w.warId(), "nordmark", "vostrava");
		String raider = c.place(game, "castavar", "westfal", "INFANTRY", 900);
		c.place(game, "nordmark", "nordheim", "INFANTRY", 200);
		c.place(game, "vostrava", "nordheim", "INFANTRY", 200);

		String cToken = c.turnOf(game, "castavar");
		MvcResult moved = api.move(cToken, game.gameId(), List.of(raider), "nordheim", UUID.randomUUID());
		assertThat(status(moved)).as(body(moved)).isEqualTo(200);
		List<Map<String, Object>> defenders = read(moved, "$.battle.details.defenders");
		// Nordmark's garrison and Vostrava's battalion all fight, each for its own nation.
		assertThat(defenders).extracting(d -> d.get("nationId"))
			.contains(c.nationId(game, "nordmark"), c.nationId(game, "vostrava"))
			.containsOnly(c.nationId(game, "nordmark"), c.nationId(game, "vostrava"));
		assertThat(c.world.inconsistentCohorts(game.lobby())).isEmpty();
	}

	@Test
	void anAttachmentEndsWhenTheAllianceThatAllowedItEnds() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", HUMANS);
		String allianceId = c.alliance(game, "nordmark", "vostrava", "NORMAL");
		String east = c.place(game, "vostrava", "nordheim", "INFANTRY", 400);
		String vToken = c.turnOf(game, "vostrava");
		MvcResult attached = api.attach(vToken, game.gameId(), List.of(east), c.nationId(game, "nordmark"),
				UUID.randomUUID());
		assertThat(status(attached)).as(body(attached)).isEqualTo(200);

		MvcResult ended = api.alliance(vToken, game.gameId(), allianceId, "terminate", UUID.randomUUID());
		assertThat(status(ended)).as(body(ended)).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT commander_nation_id FROM game.battalion WHERE id = ?::uuid", UUID.class,
				east)).isNull();
		// Without the normal alliance's access it has gone home; nothing was lost.
		assertThat(c.world.provinceOf(east)).isNotEqualTo("nordheim");
		assertThat(c.world.manpower(east)).isEqualTo(400);
	}

	@Test
	void concurrentAttachRequestsTakeEffectOnce() throws Exception {
		War w = war();
		CampaignScenario.Game game = w.game();
		c.callAndHonour(game, w.warId(), "nordmark", "vostrava");
		String east = c.place(game, "vostrava", "nordheim", "INFANTRY", 400);
		String vToken = c.turnOf(game, "vostrava");
		String nordmark = c.nationId(game, "nordmark");
		List<MvcResult> results = Concurrently.run(4,
				i -> api.attach(vToken, game.gameId(), List.of(east), nordmark, UUID.randomUUID()));
		assertThat(results).filteredOn(r -> status(r) == 200).hasSize(1);
		assertThat(c.world.commands(game.lobby(), "ATTACH_BATTALIONS")).isEqualTo(1);
	}

}
