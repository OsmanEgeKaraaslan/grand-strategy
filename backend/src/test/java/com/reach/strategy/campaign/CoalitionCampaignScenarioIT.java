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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Phase 10 end-to-end scenario on the Grand Campaign (ruleset v6), through the real API:
 * ten nations, four of them human (Castavar, Nordmark, Vostrava, Dravonia) and six NPC-controlled.
 * A defensive alliance, two calls to arms, a separate peace in one war, an attached battalion in a
 * coalition battle and a general peace conference whose terms need an ally's consent in the other,
 * plus a gold gift. Four humans are needed because NPC nations are passive placeholders: they
 * declare no wars, join no alliances and answer no calls, so every coalition here is human.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v6", "strategy.world.default-map=grand-campaign" })
class CoalitionCampaignScenarioIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	private String gold(CampaignScenario c, CampaignScenario.Game game, String from, String to, long amountMg) {
		return "{\"type\":\"GOLD\",\"fromNationId\":\"" + c.nationId(game, from) + "\",\"toNationId\":\""
				+ c.nationId(game, to) + "\",\"amountMg\":" + amountMg + "}";
	}

	private static void ok(MvcResult result, int expected) throws Exception {
		assertThat(status(result)).as(body(result)).isEqualTo(expected);
	}

	@Test
	void fourNationsFightTwoCoalitionWarsAndMakeSeparateAndGeneralPeace() throws Exception {
		ApiDriver api = new ApiDriver(mockMvc);
		CampaignScenario c = new CampaignScenario(api, jdbc);
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark", "vostrava", "dravonia"));
		String g = game.gameId();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.nation WHERE game_id = ?::uuid AND control = 'NPC'",
				Integer.class, g)).isEqualTo(6);

		// A gold gift, recorded in the public history.
		long castavarGold = c.treasury(game, "castavar");
		ok(api.sendGold(c.turnOf(game, "castavar"), g, c.nationId(game, "nordmark"), 10_000, UUID.randomUUID()), 201);
		assertThat(c.treasury(game, "castavar")).isEqualTo(castavarGold - 10_000);

		// Nordmark and Vostrava form a defensive alliance.
		c.alliance(game, "nordmark", "vostrava", "DEFENSIVE");

		// War A: Castavar attacks Nordmark, which calls Vostrava; Vostrava honours.
		String warA = c.declareWar(game, "castavar", "nordmark");
		c.callAndHonour(game, warA, "nordmark", "vostrava");

		// War B: Dravonia attacks Vostrava, which calls Nordmark; Nordmark honours.
		String warB = c.declareWar(game, "dravonia", "vostrava");
		c.callAndHonour(game, warB, "vostrava", "nordmark");

		// Separate peace in war B: Nordmark (not a leader there) settles with Dravonia's leader.
		MvcResult requested = api.requestConference(game.token("nordmark"), g, warB, "SEPARATE", UUID.randomUUID());
		ok(requested, 201);
		String separate = read(requested, "$.conference.id");
		ok(api.answerConference(game.token("dravonia"), g, separate, true, UUID.randomUUID()), 200);
		ok(api.submitDraft(game.token("dravonia"), g, separate, 1, "[" + gold(c, game, "nordmark", "dravonia", 1_000) + "]",
				UUID.randomUUID()), 201);
		ok(api.decideDraft(game.token("nordmark"), g, separate, 2, "approve", UUID.randomUUID()), 200);
		MvcResult separatePeace = api.decideDraft(game.token("dravonia"), g, separate, 2, "approve", UUID.randomUUID());
		assertThat(ApiDriver.<String>read(separatePeace, "$.conference.status")).as(body(separatePeace))
			.isEqualTo("CONCLUDED");
		MvcResult b = api.war(game.token("vostrava"), g, warB);
		assertThat(ApiDriver.<String>read(b, "$.war.status")).isEqualTo("ACTIVE");
		assertThat(ApiDriver.<List<String>>read(b,
				"$.war.participants[?(@.nationId == '" + c.nationId(game, "nordmark") + "')].leftReason"))
			.containsExactly("SEPARATE_PEACE");

		// War A: Vostrava lends a battalion to Nordmark, which attacks with both nations' troops.
		String invader = c.place(game, "castavar", "westfal", "INFANTRY", 300);
		String north = c.place(game, "nordmark", "nordheim", "INFANTRY", 500);
		String lent = c.place(game, "vostrava", "nordheim", "INFANTRY", 400);
		ok(api.attach(c.turnOf(game, "vostrava"), g, List.of(lent), c.nationId(game, "nordmark"), UUID.randomUUID()),
				200);
		MvcResult battle = api.move(c.turnOf(game, "nordmark"), g, List.of(north, lent), "westfal", UUID.randomUUID());
		ok(battle, 200);
		List<Map<String, Object>> nations = read(battle, "$.battle.details.nations");
		assertThat(nations).extracting(n -> n.get("nationId"))
			.containsExactlyInAnyOrder(c.nationId(game, "nordmark"), c.nationId(game, "vostrava"),
					c.nationId(game, "castavar"));
		assertThat(c.world.inconsistentCohorts(game.lobby())).isEmpty();
		assertThat(invader).isNotNull();

		// General peace in war A: Vostrava pays part of the price, so it must approve as well.
		MvcResult general = api.requestConference(game.token("castavar"), g, warA, "GENERAL", UUID.randomUUID());
		ok(general, 201);
		String conference = read(general, "$.conference.id");
		ok(api.answerConference(game.token("nordmark"), g, conference, true, UUID.randomUUID()), 200);
		MvcResult draft = api.submitDraft(game.token("nordmark"), g, conference, 1,
				"[" + gold(c, game, "vostrava", "castavar", 2_000) + "," + gold(c, game, "nordmark", "castavar", 2_000) + "]",
				UUID.randomUUID());
		ok(draft, 201);
		assertThat(ApiDriver.<List<String>>read(draft, "$.conference.approvals[*].nationId")).containsExactlyInAnyOrder(
				c.nationId(game, "castavar"), c.nationId(game, "nordmark"), c.nationId(game, "vostrava"));
		// Dravonia is not involved and cannot interfere; normal play is paused for everyone.
		assertThat(status(api.decideDraft(game.token("dravonia"), g, conference, 2, "approve", UUID.randomUUID())))
			.isEqualTo(403);
		assertThat(status(c.endTurn(game))).isEqualTo(409);
		ok(api.decideDraft(game.token("castavar"), g, conference, 2, "approve", UUID.randomUUID()), 200);
		ok(api.decideDraft(game.token("nordmark"), g, conference, 2, "approve", UUID.randomUUID()), 200);
		long vostravaGold = c.treasury(game, "vostrava");
		MvcResult peace = api.decideDraft(game.token("vostrava"), g, conference, 2, "approve", UUID.randomUUID());
		assertThat(ApiDriver.<String>read(peace, "$.conference.status")).as(body(peace)).isEqualTo("CONCLUDED");
		assertThat(c.treasury(game, "vostrava")).isEqualTo(vostravaGold - 2_000);

		// War A is over; war B goes on between Dravonia and Vostrava; play resumes.
		assertThat(jdbc.queryForObject("SELECT status FROM game.war WHERE id = ?::uuid", String.class, warA))
			.isEqualTo("ENDED");
		assertThat(jdbc.queryForObject("SELECT status FROM game.war WHERE id = ?::uuid", String.class, warB))
			.isEqualTo("ACTIVE");
		assertThat(c.paused(game)).isFalse();
		ok(c.endTurn(game), 200);
		MvcResult overview = api.diplomacy(game.token("castavar"), g);
		assertThat(ApiDriver.<List<String>>read(overview, "$.gifts[*].amountMg")).hasSize(1);
		assertThat(ApiDriver.<List<String>>read(overview, "$.conferences[*].status")).containsExactlyInAnyOrder(
				"CONCLUDED", "CONCLUDED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.peace_treaty WHERE game_id = ?::uuid", Integer.class,
				g)).isEqualTo(2);
	}

}
