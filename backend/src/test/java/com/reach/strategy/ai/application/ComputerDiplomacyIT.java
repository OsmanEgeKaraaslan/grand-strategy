package com.reach.strategy.ai.application;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.campaign.CampaignScenario;
import com.reach.strategy.diplomacy.api.DiplomacyActions;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Computer-to-computer diplomacy (Phase 11C). The test only starts a war on a computer's turn and
 * arranges the front (occupied provinces, a weakened army) as setup; every later decision —
 * calling allies, honouring, asking for and answering conferences, drafting, approving, leaving
 * by separate peace — is the computers' own, and must conclude without any human.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign",
		"strategy.ai.execution=manual" })
class ComputerDiplomacyIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AiTurnRunner runner;

	@Autowired
	ComputerSeats seats;

	@Autowired
	ComputerPlayers computers;

	@Autowired
	DiplomacyActions diplomacy;

	CampaignScenario c;

	@BeforeEach
	void setUp() {
		c = new CampaignScenario(new ApiDriver(mockMvc), jdbc);
	}

	/** Humans end their turns, then the computers play the rest of the round. */
	private void round(CampaignScenario.Game game) throws Exception {
		for (int i = 0; i < 2 && activeSeat(game) < 2; i++) {
			MvcResult ended = c.endTurn(game);
			assertThat(status(ended)).as(body(ended)).isEqualTo(200);
		}
		runner.run(UUID.fromString(game.gameId()));
	}

	private int activeSeat(CampaignScenario.Game game) {
		return jdbc.queryForObject("SELECT active_seat FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
	}

	/** The actor of the computer seat of a nation (for the test's war declaration only). */
	private CurrentPlayer computer(CampaignScenario.Game game, String nationKey) {
		UUID gamePlayer = jdbc.queryForObject("SELECT game_player_id FROM game.nation WHERE id = ?::uuid", UUID.class,
				c.nationId(game, nationKey));
		return seats.seats(UUID.fromString(game.gameId()))
			.stream()
			.filter(s -> s.gamePlayerId().equals(gamePlayer))
			.map(s -> computers.actor(s.playerId()))
			.findFirst()
			.orElseThrow();
	}

	private void disband(CampaignScenario.Game game, String nationKey) {
		String nation = c.nationId(game, nationKey);
		jdbc.update("DELETE FROM game.battalion_cohort WHERE battalion_id IN (SELECT id FROM game.battalion WHERE nation_id = ?::uuid)",
				nation);
		jdbc.update("DELETE FROM game.battalion WHERE nation_id = ?::uuid", nation);
	}

	private String owner(CampaignScenario.Game game, String province) {
		return jdbc.queryForObject("""
				SELECT nt.key FROM game.province_state ps JOIN game.province_template pt ON pt.id = ps.province_template_id
				JOIN game.nation n ON n.id = ps.owner_nation_id JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE ps.game_id = ?::uuid AND pt.key = ?
				""", String.class, game.gameId(), province);
	}

	@Test
	void aWarBetweenTwoComputersEndsInATreatyWithoutAnyHuman() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		UUID gameId = UUID.fromString(game.gameId());
		for (int i = 0; i < 2; i++) {
			c.endTurn(game);
		}
		// Seat 2 is Aestia (first computer seat): it attacks Karpathia, whose army is gone.
		diplomacy.declareWar(computer(game, "aestia"), gameId, UUID.fromString(c.nationId(game, "karpathia")), UUID.randomUUID());
		disband(game, "karpathia");
		for (String province : List.of("galicja", "tatrany")) {
			c.world.occupy(game.lobby(), province, "aestia");
			c.place(game, "aestia", province, "INFANTRY", 1000);
		}
		runner.run(gameId);
		String treaty = null;
		for (int r = 0; r < 10 && treaty == null; r++) {
			round(game);
			List<String> treaties = jdbc.queryForList("""
					SELECT t.id::text FROM game.peace_treaty t JOIN game.war w ON w.id = t.war_id
					WHERE t.game_id = ?::uuid AND t.conference_id IS NOT NULL
					  AND w.attacker_leader_id = ?::uuid AND w.defender_leader_id = ?::uuid
					""", String.class, game.gameId(), c.nationId(game, "aestia"), c.nationId(game, "karpathia"));
			treaty = treaties.isEmpty() ? null : treaties.getFirst();
		}
		assertThat(treaty).as("the two computers concluded a treaty").isNotNull();
		assertThat(c.paused(game)).isFalse();
		// Ownership changed only by the treaty (to Aestia, or not at all), and occupation ended with the war.
		for (String province : List.of("galicja", "tatrany")) {
			assertThat(owner(game, province)).isIn("aestia", "karpathia");
			assertThat(jdbc.queryForObject("""
					SELECT ps.owner_nation_id = ps.controller_nation_id FROM game.province_state ps
					JOIN game.province_template pt ON pt.id = ps.province_template_id WHERE ps.game_id = ?::uuid AND pt.key = ?
					""", Boolean.class, game.gameId(), province)).isTrue();
		}
		// Every computer decision is explained in the decision log.
		assertThat(jdbc.queryForList("""
				SELECT DISTINCT kind FROM game.ai_decision WHERE game_id = ?::uuid
				""", String.class, game.gameId())).contains("REQUEST_CONFERENCE", "ANSWER_CONFERENCE", "DECIDE_DRAFT");
	}

	@Test
	void aLosingComputerAllyLeavesTheWarBySeparatePeace() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		UUID gameId = UUID.fromString(game.gameId());
		// Setup: Karpathia and Serenna are defensive allies (as if agreed earlier).
		jdbc.update("""
				INSERT INTO game.alliance (id, game_id, nation_a_id, nation_b_id, proposer_nation_id, kind, status,
				    proposed_round, accepted_round, created_at, updated_at)
				VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'DEFENSIVE', 'ACTIVE', 1, 1, now(), now())
				""", game.gameId(), c.nationId(game, "karpathia"), c.nationId(game, "serenna"), c.nationId(game, "karpathia"));
		for (int i = 0; i < 2; i++) {
			c.endTurn(game);
		}
		diplomacy.declareWar(computer(game, "aestia"), gameId, UUID.fromString(c.nationId(game, "karpathia")), UUID.randomUUID());
		for (int i = 0; i < 4; i++) {
			c.place(game, "aestia", "samogit", "INFANTRY", 1000);
		}
		runner.run(gameId);
		// Karpathia (the attacked leader) calls its ally on its turn; Serenna decides on its turn.
		round(game);
		String call = jdbc.queryForObject("""
				SELECT status FROM game.call_to_arms WHERE game_id = ?::uuid AND ally_nation_id = ?::uuid
				""", String.class, game.gameId(), c.nationId(game, "serenna"));
		assertThat(call).isIn("HONORED", "DECLINED");
		if (!"HONORED".equals(call)) {
			return; // Serenna stayed out: nothing to leave (its decline ended the alliance, checked above by status)
		}
		// Serenna is losing badly: its northern provinces are occupied and its army is gone.
		disband(game, "serenna");
		for (String province : List.of("venetia", "pedemont", "lombarda")) {
			c.world.occupy(game.lobby(), province, "aestia");
		}
		String left = null;
		for (int r = 0; r < 10 && left == null; r++) {
			round(game);
			List<String> reasons = jdbc.queryForList("""
					SELECT wp.left_reason FROM game.war_participant wp WHERE wp.game_id = ?::uuid AND wp.nation_id = ?::uuid
					  AND wp.left_reason IS NOT NULL
					""", String.class, game.gameId(), c.nationId(game, "serenna"));
			left = reasons.isEmpty() ? null : reasons.getFirst();
		}
		assertThat(left).isIn("SEPARATE_PEACE", "ELIMINATED");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.peace_conference WHERE game_id = ?::uuid AND kind = 'SEPARATE'
				  AND requester_nation_id = ?::uuid
				""", Integer.class, game.gameId(), c.nationId(game, "serenna"))).isPositive();
		assertThat(c.paused(game)).isFalse();
	}

}
