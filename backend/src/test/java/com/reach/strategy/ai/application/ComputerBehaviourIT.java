package com.reach.strategy.ai.application;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.campaign.CampaignScenario;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 11B/C against PostgreSQL (ruleset v7, Grand Campaign, computer turns played in the request
 * that hands them the turn): computers develop and arm within the rules, attack and besiege a human
 * invader, answer a human's alliance offer and call to arms, judge a human's treaty revisions, and
 * accept their own elimination when nothing is left.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign" })
class ComputerBehaviourIT {

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

	private void rounds(CampaignScenario.Game game, int rounds) throws Exception {
		for (int i = 0; i < rounds; i++) {
			c.completeRound(game);
		}
	}

	private int commands(CampaignScenario.Game game, String nationKey, String type) {
		return jdbc.queryForObject("""
				SELECT count(*) FROM game.game_command gc
				JOIN game.nation n ON n.game_player_id = gc.game_player_id AND n.game_id = gc.game_id
				JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE gc.game_id = ?::uuid AND n.control = 'AI' AND (?::text IS NULL OR nt.key = ?::text) AND gc.command_type = ?
				""", Integer.class, game.gameId(), nationKey, nationKey, type);
	}

	private String term(CampaignScenario.Game game, String from, String to, String province) {
		return "{\"type\":\"PROVINCE\",\"fromNationId\":\"" + c.nationId(game, from) + "\",\"toNationId\":\""
				+ c.nationId(game, to) + "\",\"provinceKey\":\"" + province + "\"}";
	}

	private String decision(String conferenceId, int revision, String nationId) {
		List<String> rows = jdbc.queryForList(
				"SELECT decision FROM game.conference_decision WHERE conference_id = ?::uuid AND revision = ? AND nation_id = ?::uuid",
				String.class, conferenceId, revision, nationId);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	// --- development and legality -------------------------------------------------------------------

	@Test
	void computersBuildRecruitAndMoveOnlyWithinTheRules() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		rounds(game, 4);

		assertThat(commands(game, null, "BUILD_WORKSHOP")).isPositive();
		assertThat(commands(game, null, "RECRUIT")).isPositive();
		assertThat(commands(game, null, "MOVE")).isPositive();
		assertThat(commands(game, null, "END_TURN")).isEqualTo(4 * 8);
		// Nothing the computers did broke an invariant: soldiers stay attributed to their cities ...
		assertThat(c.world.inconsistentCohorts(game.lobby())).isEmpty();
		// ... every battalion stands where its nation may stand ...
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.battalion b JOIN game.province_state ps ON ps.id = b.province_state_id
				WHERE b.game_id = ?::uuid AND ps.controller_nation_id <> b.nation_id AND ps.owner_nation_id <> b.nation_id
				  AND NOT EXISTS (SELECT 1 FROM game.war_front f WHERE f.game_id = b.game_id AND f.active
				      AND ((f.nation_a_id = b.nation_id AND f.nation_b_id = ps.controller_nation_id)
				        OR (f.nation_b_id = b.nation_id AND f.nation_a_id = ps.controller_nation_id)))
				  AND NOT EXISTS (SELECT 1 FROM game.alliance a WHERE a.game_id = b.game_id AND a.status = 'ACTIVE'
				      AND ((a.nation_a_id = b.nation_id AND a.nation_b_id = ps.controller_nation_id)
				        OR (a.nation_b_id = b.nation_id AND a.nation_a_id = ps.controller_nation_id)))
				""", Integer.class, game.gameId())).isZero();
		// ... and no computer turn hit an internal error.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.ai_turn WHERE game_id = ?::uuid AND last_error LIKE 'INTERNAL%'
				""", Integer.class, game.gameId())).isZero();
		// Recruitment was paid: a computer nation's battalions never outnumber its start plus its recruitments.
		int aurelia = jdbc.queryForObject("SELECT count(*) FROM game.battalion WHERE nation_id = ?::uuid", Integer.class,
				c.nationId(game, "aurelia"));
		assertThat(aurelia).isLessThanOrEqualTo(7 + commands(game, "aurelia", "RECRUIT"));
	}

	// --- war against a human ------------------------------------------------------------------------

	@Test
	void aComputerDefeatsAWeakInvaderAndBesiegesItsFortress() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		c.declareWar(game, "castavar", "aurelia");
		// Castavar left a weak battalion in fortified Sierra Vel next to a strong Aurelian army.
		String weak = c.place(game, "castavar", "sierravel", "INFANTRY", 200);
		for (int i = 0; i < 3; i++) {
			c.place(game, "aurelia", "aquivelle", "INFANTRY", 1000);
		}
		rounds(game, 3);

		Integer battles = jdbc.queryForObject("""
				SELECT count(*) FROM game.battle_report br JOIN game.province_state ps ON ps.id = br.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE br.game_id = ?::uuid AND br.attacker_nation_id = ?::uuid AND pt.key = 'sierravel'
				""", Integer.class, game.gameId(), c.nationId(game, "aurelia"));
		assertThat(battles).isPositive();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.battalion b JOIN game.province_state ps ON ps.id = b.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id WHERE b.id = ?::uuid AND pt.key = 'sierravel'
				""", Integer.class, weak))
			.as("the invader's battalion was destroyed or driven out")
			.isZero();
		// With the defender gone, the computer besieges the fortress (or already took it).
		Integer siegedOrTaken = jdbc.queryForObject("""
				SELECT count(*) FROM game.province_state ps JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE ps.game_id = ?::uuid AND pt.key = 'sierravel' AND (ps.controller_nation_id = ?::uuid
				   OR EXISTS (SELECT 1 FROM game.siege s WHERE s.province_state_id = ps.id AND s.besieger_nation_id = ?::uuid))
				""", Integer.class, game.gameId(), c.nationId(game, "aurelia"), c.nationId(game, "aurelia"));
		assertThat(siegedOrTaken).isEqualTo(1);
		// It attacked only the nation it is at war with.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.battle_report WHERE game_id = ?::uuid AND attacker_nation_id = ?::uuid
				  AND defender_nation_id <> ?::uuid
				""", Integer.class, game.gameId(), c.nationId(game, "aurelia"), c.nationId(game, "castavar"))).isZero();
	}

	// --- alliances and calls ------------------------------------------------------------------------

	@Test
	void aComputerAnswersAHumansAllianceOfferAndCallToArmsOnItsOwnTurn() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		MvcResult proposed = api.proposeAlliance(c.turnOf(game, "nordmark"), game.gameId(), c.nationId(game, "aestia"),
				"DEFENSIVE", UUID.randomUUID());
		assertThat(status(proposed)).as(body(proposed)).isEqualTo(201);
		String allianceId = read(proposed, "$.alliance.id");
		c.completeRound(game);
		String status = jdbc.queryForObject("SELECT status FROM game.alliance WHERE id = ?::uuid", String.class, allianceId);
		// Aestia (diplomatic, Nordmark's neighbour, no war plans yet) answered on its turn.
		assertThat(status).isEqualTo("ACTIVE");

		String warId = c.declareWar(game, "castavar", "nordmark");
		MvcResult called = api.callToArms(c.turnOf(game, "nordmark"), game.gameId(), warId, c.nationId(game, "aestia"),
				UUID.randomUUID());
		assertThat(status(called)).as(body(called)).isEqualTo(201);
		String callId = read(called, "$.call.id");
		c.completeRound(game);
		String answer = jdbc.queryForObject("SELECT status FROM game.call_to_arms WHERE id = ?::uuid", String.class, callId);
		assertThat(answer).isIn("HONORED", "DECLINED");
		if ("HONORED".equals(answer)) {
			assertThat(jdbc.queryForObject("""
					SELECT side FROM game.war_participant WHERE war_id = ?::uuid AND nation_id = ?::uuid
					""", String.class, warId, c.nationId(game, "aestia"))).isEqualTo("DEFENDER");
		}
		else {
			assertThat(jdbc.queryForObject("SELECT status FROM game.alliance WHERE id = ?::uuid", String.class, allianceId))
				.isEqualTo("ENDED");
		}
	}

	// --- peace with a human -------------------------------------------------------------------------

	@Test
	void aLosingComputerJudgesEachRevisionAHumanProposes() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "aurelia");
		// Aurelia's western provinces are occupied and its army is gone.
		for (String province : List.of("aquivelle", "occitane", "limosin")) {
			c.world.occupy(game.lobby(), province, "castavar");
			c.place(game, "castavar", province, "INFANTRY", 1000);
		}
		jdbc.update("DELETE FROM game.battalion_cohort WHERE battalion_id IN (SELECT id FROM game.battalion WHERE nation_id = ?::uuid)",
				c.nationId(game, "aurelia"));
		jdbc.update("DELETE FROM game.battalion WHERE nation_id = ?::uuid", c.nationId(game, "aurelia"));
		String aurelia = c.nationId(game, "aurelia");

		MvcResult requested = api.requestConference(game.token("castavar"), game.gameId(), warId, "GENERAL", UUID.randomUUID());
		assertThat(status(requested)).as(body(requested)).isEqualTo(201);
		String id = read(requested, "$.conference.id");
		// The computer answered at once: losing badly, it talks.
		assertThat(jdbc.queryForObject("SELECT status FROM game.peace_conference WHERE id = ?::uuid", String.class, id))
			.isEqualTo("ACTIVE");

		// One occupied province: an acceptable price.
		MvcResult r2 = api.submitDraft(game.token("castavar"), game.gameId(), id, 1, "[" + term(game, "aurelia", "castavar",
				"aquivelle") + "]", UUID.randomUUID());
		assertThat(status(r2)).as(body(r2)).isEqualTo(201);
		assertThat(decision(id, 2, aurelia)).isEqualTo("APPROVED");

		// Now the capital and two provinces it still holds as well: far too much.
		MvcResult r3 = api.submitDraft(game.token("castavar"), game.gameId(), id, 2, "[" + term(game, "aurelia", "castavar",
				"aquivelle") + "," + term(game, "aurelia", "castavar", "aurel") + "," + term(game, "aurelia", "castavar",
				"champeaux") + "," + term(game, "aurelia", "castavar", "bourgogne") + "]", UUID.randomUUID());
		assertThat(status(r3)).as(body(r3)).isEqualTo(201);
		Map<String, Object> conference = jdbc.queryForMap(
				"SELECT current_revision FROM game.peace_conference WHERE id = ?::uuid", id);
		int current = (Integer) conference.get("current_revision");
		// It refused revision 3: either rejected it or answered with its own counter-draft.
		assertThat(current == 3 ? decision(id, 3, aurelia) : "COUNTERED").isIn("REJECTED", "COUNTERED");
		assertThat(decision(id, 3, aurelia)).isNotEqualTo("APPROVED");
		if (current > 3) {
			assertThat(jdbc.queryForObject("""
					SELECT author_nation_id::text FROM game.conference_revision WHERE conference_id = ?::uuid AND revision = ?
					""", String.class, id, current)).isEqualTo(aurelia);
			assertThat(decision(id, current, aurelia)).isEqualTo("APPROVED");
			// The human accepts the counter-draft: settled, the war is over, the game resumes.
			MvcResult accepted = api.decideDraft(game.token("castavar"), game.gameId(), id, current, "approve", UUID.randomUUID());
			assertThat(status(accepted)).as(body(accepted)).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT status FROM game.war WHERE id = ?::uuid", String.class, warId))
				.isEqualTo("ENDED");
			assertThat(c.paused(game)).isFalse();
		}
	}

	@Test
	void aComputerWithNothingLeftAcceptsTheEndAndIsEliminated() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String warId = c.declareWar(game, "castavar", "frisia");
		List<String> frisian = List.of("westerdijk", "oostergo", "brabantia", "geldermark");
		for (String province : frisian) {
			c.world.occupy(game.lobby(), province, "castavar");
		}
		MvcResult requested = api.requestConference(game.token("castavar"), game.gameId(), warId, "GENERAL", UUID.randomUUID());
		String id = read(requested, "$.conference.id");
		assertThat(jdbc.queryForObject("SELECT status FROM game.peace_conference WHERE id = ?::uuid", String.class, id))
			.isEqualTo("ACTIVE");
		String terms = "[" + String.join(",", frisian.stream().map(p -> term(game, "frisia", "castavar", p)).toList()) + "]";
		MvcResult draft = api.submitDraft(game.token("castavar"), game.gameId(), id, 1, terms, UUID.randomUUID());
		assertThat(status(draft)).as(body(draft)).isEqualTo(201);
		assertThat(decision(id, 2, c.nationId(game, "frisia"))).isEqualTo("APPROVED");
		MvcResult settled = api.decideDraft(game.token("castavar"), game.gameId(), id, 2, "approve", UUID.randomUUID());
		assertThat(status(settled)).as(body(settled)).isEqualTo(200);

		assertThat(jdbc.queryForObject("SELECT eliminated_round FROM game.nation WHERE id = ?::uuid", Integer.class,
				c.nationId(game, "frisia"))).isNotNull();
		int before = jdbc.queryForObject("SELECT count(*) FROM game.ai_turn WHERE game_id = ?::uuid AND nation_id = ?::uuid",
				Integer.class, game.gameId(), c.nationId(game, "frisia"));
		rounds(game, 2);
		// An eliminated computer takes no more turns; the other seven still do.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.ai_turn WHERE game_id = ?::uuid AND nation_id = ?::uuid",
				Integer.class, game.gameId(), c.nationId(game, "frisia"))).isEqualTo(before);
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT nation_id) FROM game.ai_turn WHERE game_id = ?::uuid
				  AND round_number = (SELECT current_round - 1 FROM game.game WHERE id = ?::uuid)
				""", Integer.class, game.gameId(), game.gameId())).isEqualTo(7);
	}

}
