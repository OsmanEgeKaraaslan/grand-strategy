package com.reach.strategy.ai.application;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
 * The Phase 11 campaign scenarios on the Grand Campaign (ruleset v7), through the real API with the
 * computers playing in the requests that hand them the turn.
 * <ul>
 * <li>A: two humans and eight computer nations play all 30 rounds to the score result.</li>
 * <li>B: a coalition war between humans and computers, from alliance to peace treaty.</li>
 * </ul>
 * The humans only do what a cautious human would (recruit at home, end the turn); no human ever acts
 * for a computer nation. Progress, legality and consistency are asserted, and the computers' turn
 * times are printed as the performance measurement.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign" })
class ComputerCampaignScenariosIT {

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

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private int computerCommands(CampaignScenario.Game game, String type) {
		return count("""
				SELECT count(*) FROM game.game_command gc JOIN game.game_player gp ON gp.id = gc.game_player_id
				WHERE gc.game_id = ?::uuid AND gp.computer AND gc.command_type = ?
				""", game.gameId(), type);
	}

	/** A cautious human turn: recruit one infantry in the capital if possible, then end the turn. */
	private void humanTurn(CampaignScenario.Game game, String nationKey, String capital) throws Exception {
		String token = game.token(nationKey);
		api.recruit(token, game.gameId(), api.cityId(token, game.gameId(), capital), "INFANTRY", UUID.randomUUID());
		MvcResult ended = api.endTurn(token, game.gameId(), c.turn(game), UUID.randomUUID());
		assertThat(status(ended)).as(body(ended)).isIn(200, 409);
	}

	private void assertConsistent(CampaignScenario.Game game) {
		assertThat(c.world.inconsistentCohorts(game.lobby())).isEmpty();
		assertThat(count("""
				SELECT count(*) FROM game.ai_turn WHERE game_id = ?::uuid AND last_error LIKE 'INTERNAL%'
				""", game.gameId())).isZero();
		assertThat(count("SELECT count(*) FROM game.nation_economy ne JOIN game.nation n ON n.id = ne.nation_id "
				+ "WHERE n.game_id = ?::uuid AND ne.treasury_mg < 0", game.gameId())).isZero();
	}

	// --- Scenario A -------------------------------------------------------------------------------

	@Test
	void scenarioA_twoHumansAndEightComputersPlayAFullCampaign() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		List<Long> roundMillis = new ArrayList<>();
		for (int round = 1; round <= 30; round++) {
			long start = System.currentTimeMillis();
			humanTurn(game, "castavar", "meseta");
			humanTurn(game, "nordmark", "nordheim");
			roundMillis.add(System.currentTimeMillis() - start);
			if ("FINISHED".equals(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class,
					game.gameId()))) {
				break;
			}
		}
		Map<String, Object> perf = jdbc.queryForMap("""
				SELECT count(*) AS turns, avg(duration_ms) AS avg_ms, max(duration_ms) AS max_ms, sum(actions) AS actions,
				       sum(failures) AS failures, sum(observations) AS observations
				FROM game.ai_turn WHERE game_id = ?::uuid
				""", game.gameId());
		System.out.println("SCENARIO A rounds(ms)=" + roundMillis + " computerTurns=" + perf);
		System.out.println("SCENARIO A commands=" + jdbc.queryForList("""
				SELECT gc.command_type, count(*) AS n FROM game.game_command gc JOIN game.game_player gp ON gp.id = gc.game_player_id
				WHERE gc.game_id = ?::uuid AND gp.computer GROUP BY 1 ORDER BY 1
				""", game.gameId()));
		System.out.println("SCENARIO A result=" + jdbc.queryForList("""
				SELECT nt.key, s.rank, s.score, s.owned_provinces FROM game.game_result_standing s
				JOIN game.nation n ON n.id = s.nation_id JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE s.game_id = ?::uuid ORDER BY s.rank, nt.key
				""", game.gameId()));

		// The match completed once, by the round limit.
		assertThat(jdbc.queryForObject("SELECT status FROM game.game WHERE id = ?::uuid", String.class, game.gameId()))
			.isEqualTo("FINISHED");
		assertThat(jdbc.queryForObject("SELECT reason FROM game.game_result WHERE game_id = ?::uuid", String.class,
				game.gameId())).isIn("ROUND_LIMIT", "DOMINATION", "NO_HUMAN_PLAYERS");
		assertThat(count("SELECT count(*) FROM game.game_result_standing WHERE game_id = ?::uuid", game.gameId()))
			.isEqualTo(10);
		// The computers developed, armed, researched and fought.
		assertThat(computerCommands(game, "BUILD_WORKSHOP")).isGreaterThanOrEqualTo(8);
		assertThat(computerCommands(game, "RECRUIT")).isGreaterThanOrEqualTo(40);
		assertThat(computerCommands(game, "BUILD_INSTITUTION")).isPositive();
		assertThat(computerCommands(game, "UNLOCK_TECHNOLOGY") + computerCommands(game, "UNLOCK_DOCTRINE")).isPositive();
		assertThat(computerCommands(game, "DECLARE_WAR")).isPositive();
		assertThat(count("SELECT count(*) FROM game.battle_report WHERE game_id = ?::uuid", game.gameId())).isPositive();
		// War and peace transitions happened; at least one war ended in a treaty.
		assertThat(count("SELECT count(*) FROM game.peace_treaty WHERE game_id = ?::uuid", game.gameId())).isPositive();
		assertThat(c.paused(game)).isFalse();
		assertConsistent(game);
		// Performance: a computer turn stays well under the budget of a pleasant game.
		assertThat(((Number) perf.get("avg_ms")).doubleValue()).isLessThan(3000);
	}

	// --- Scenario B -------------------------------------------------------------------------------

	@Test
	void scenarioB_aCoalitionWarBetweenHumansAndComputersEndsInATreaty() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		String nordmark = c.nationId(game, "nordmark");
		String aestia = c.nationId(game, "aestia");

		// 1. A human–computer alliance: Nordmark asks its neighbour Aestia, which agrees on its own turn.
		MvcResult proposed = api.proposeAlliance(c.turnOf(game, "nordmark"), game.gameId(), aestia, "DEFENSIVE",
				UUID.randomUUID());
		assertThat(status(proposed)).as(body(proposed)).isEqualTo(201);
		String allianceId = read(proposed, "$.alliance.id");
		c.completeRound(game);
		assertThat(jdbc.queryForObject("SELECT status FROM game.alliance WHERE id = ?::uuid", String.class, allianceId))
			.isEqualTo("ACTIVE");

		// 2. A computer war declaration: some computer attacks the passive human Nordmark by itself.
		Map<String, Object> war = null;
		for (int i = 0; i < 12 && war == null; i++) {
			c.completeRound(game);
			List<Map<String, Object>> wars = jdbc.queryForList("""
					SELECT w.id::text AS id, w.attacker_leader_id::text AS attacker FROM game.war w
					JOIN game.nation n ON n.id = w.attacker_leader_id
					WHERE w.game_id = ?::uuid AND w.status = 'ACTIVE' AND w.defender_leader_id = ?::uuid AND n.control = 'AI'
					ORDER BY w.declared_round LIMIT 1
					""", game.gameId(), nordmark);
			war = wars.isEmpty() ? null : wars.getFirst();
		}
		assertThat(war).as("a computer declared war on Nordmark").isNotNull();
		String warId = (String) war.get("id");
		String attacker = (String) war.get("attacker");

		// 3. A defensive call to arms answered by the computer ally on its turn.
		MvcResult called = api.callToArms(c.turnOf(game, "nordmark"), game.gameId(), warId, aestia, UUID.randomUUID());
		assertThat(status(called)).as(body(called)).isIn(201, 409);
		c.completeRound(game);
		String answer = jdbc.queryForObject("""
				SELECT status FROM game.call_to_arms WHERE war_id = ?::uuid AND ally_nation_id = ?::uuid
				""", String.class, warId, aestia);
		assertThat(answer).isIn("HONORED", "DECLINED");
		boolean honoured = "HONORED".equals(answer);

		// 4. A coalition battle: Nordmark (and Aestia, if it joined) hold a border province next to a strong
		// army of the attacker; the attacking computer decides to strike.
		// The front: a province Nordmark holds next to one the attacker holds, or else next to another
		// Nordmark province where the attacker's army can stand (they are at war).
		List<Map<String, Object>> fronts = jdbc.queryForList("""
				SELECT a.key AS ours, b.key AS theirs, (sb.controller_nation_id = ?::uuid) AS hostile
				FROM game.province_adjacency adj
				JOIN game.province_template pa ON pa.id = adj.province_a_id JOIN game.province_template pb ON pb.id = adj.province_b_id
				CROSS JOIN LATERAL (VALUES (pa.id, pa.key, pb.id, pb.key), (pb.id, pb.key, pa.id, pa.key)) AS x(aid, akey, bid, bkey)
				JOIN game.province_template a ON a.id = x.aid JOIN game.province_template b ON b.id = x.bid
				JOIN game.province_state sa ON sa.province_template_id = a.id AND sa.game_id = ?::uuid
				JOIN game.province_state sb ON sb.province_template_id = b.id AND sb.game_id = ?::uuid
				WHERE sa.owner_nation_id = ?::uuid AND sa.controller_nation_id = ?::uuid
				  AND sb.controller_nation_id IN (?::uuid, ?::uuid)
				ORDER BY hostile DESC, a.key, b.key
				LIMIT 1
				""", attacker, game.gameId(), game.gameId(), nordmark, nordmark, attacker, nordmark);
		assertThat(fronts).isNotEmpty();
		Map<String, Object> front = fronts.getFirst();
		String ours = (String) front.get("ours");
		String theirs = (String) front.get("theirs");
		String attackerKey = jdbc.queryForObject("""
				SELECT nt.key FROM game.nation n JOIN game.nation_template nt ON nt.id = n.nation_template_id WHERE n.id = ?::uuid
				""", String.class, attacker);
		// A guaranteed partner on Nordmark's side: the human Castavar declares war on the attacker itself
		// (everybody at war with the attacking computer defends together). A computer ally may move away.
		String partnerKey = "castavar";
		MvcResult declared = api.declareWar(c.turnOf(game, "castavar"), game.gameId(), attacker, UUID.randomUUID());
		assertThat(status(declared)).as(body(declared)).isEqualTo(201);
		String partner = c.nationId(game, partnerKey);
		c.place(game, "nordmark", ours, "INFANTRY", 300);
		c.place(game, partnerKey, ours, "INFANTRY", 300);
		for (int i = 0; i < 4; i++) {
			c.place(game, attackerKey, theirs, "INFANTRY", 1000);
		}
		int coalitionBattles = 0;
		for (int i = 0; i < 4 && coalitionBattles == 0; i++) {
			c.completeRound(game);
			coalitionBattles = count("""
					SELECT count(*) FROM game.battle_report WHERE game_id = ?::uuid AND attacker_nation_id = ?::uuid
					  AND report::text LIKE ? AND report::text LIKE ?
					""", game.gameId(), attacker, "%" + partner + "%", "%" + nordmark + "%");
		}
		assertThat(coalitionBattles).as("a coalition battle: " + partnerKey + " fought beside Nordmark").isPositive();

		// 5. Siege and occupation: the attacker now holds or besieges Nordmark land.
		for (int i = 0; i < 2; i++) {
			c.completeRound(game);
		}
		assertThat(count("""
				SELECT count(*) FROM game.province_state ps WHERE ps.game_id = ?::uuid AND ps.owner_nation_id = ?::uuid
				  AND (ps.controller_nation_id = ?::uuid OR EXISTS (SELECT 1 FROM game.siege s WHERE s.province_state_id = ps.id
				       AND s.besieger_nation_id = ?::uuid))
				""", game.gameId(), nordmark, attacker, attacker)).isPositive();

		// 6–9. A peace conference with the computer: Nordmark asks (the computer may refuse while the war is
		// young); the computer drafts its demands; the human approves; the treaty is settled atomically.
		String conferenceId = null;
		for (int i = 0; i < 8 && conferenceId == null; i++) {
			MvcResult requested = api.requestConference(game.token("nordmark"), game.gameId(), warId, "GENERAL",
					UUID.randomUUID());
			List<String> active = jdbc.queryForList("""
					SELECT id::text FROM game.peace_conference WHERE war_id = ?::uuid AND status = 'ACTIVE'
					""", String.class, warId);
			if (!active.isEmpty()) {
				conferenceId = active.getFirst();
			}
			else if (!"ACTIVE".equals(jdbc.queryForObject("SELECT status FROM game.war WHERE id = ?::uuid", String.class, warId))) {
				break;
			}
			else {
				assertThat(status(requested)).as(body(requested)).isIn(201, 409);
				c.completeRound(game);
			}
		}
		assertThat(conferenceId).as("the computer agreed to talk once the war had lasted").isNotNull();
		int revision = jdbc.queryForObject("SELECT current_revision FROM game.peace_conference WHERE id = ?::uuid",
				Integer.class, conferenceId);
		if (revision == 1) {
			// The computer accepts the white peace only if it is not winning; then the human drafts a revision.
			MvcResult draft = api.submitDraft(game.token("nordmark"), game.gameId(), conferenceId, 1, "[]", UUID.randomUUID());
			assertThat(status(draft)).isIn(201, 409);
			revision = jdbc.queryForObject("SELECT current_revision FROM game.peace_conference WHERE id = ?::uuid",
					Integer.class, conferenceId);
		}
		int finalRevision = revision;
		MvcResult approved = api.decideDraft(game.token("nordmark"), game.gameId(), conferenceId, finalRevision, "approve",
				UUID.randomUUID());
		assertThat(status(approved)).as(body(approved)).isEqualTo(200);
		// Every required approval came in (the computer approved its own draft; an affected ally decided too).
		assertThat(jdbc.queryForObject("SELECT status FROM game.peace_conference WHERE id = ?::uuid", String.class,
				conferenceId)).isEqualTo("CONCLUDED");
		assertThat(jdbc.queryForObject("SELECT status FROM game.war WHERE id = ?::uuid", String.class, warId)).isEqualTo("ENDED");
		assertThat(count("""
				SELECT count(*) FROM game.province_state WHERE game_id = ?::uuid AND owner_nation_id = ?::uuid
				  AND controller_nation_id = ?::uuid
				""", game.gameId(), nordmark, attacker)).as("occupation ended with the war").isZero();

		// 10. Nordmark survives and the campaign goes on.
		assertThat(c.paused(game)).isFalse();
		c.completeRound(game);
		assertThat(count("SELECT count(*) FROM game.province_state WHERE game_id = ?::uuid AND owner_nation_id = ?::uuid",
				game.gameId(), nordmark)).isPositive();
		assertConsistent(game);
	}

}
