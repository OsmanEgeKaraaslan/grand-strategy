package com.reach.strategy.ai.application;

import static com.reach.strategy.support.ApiDriver.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.campaign.CampaignScenario;
import com.reach.strategy.diplomacy.api.DiplomacyActions;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryActions;
import com.reach.strategy.platform.web.ApiException;
import com.reach.strategy.support.ApiDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 11 negative tests: a computer seat issuing commands through the same services is held to
 * every rule a human is — military movement limits, treasury and population, diplomatic
 * restrictions, treaty approvals and the ownership of battalions.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign",
		"strategy.ai.execution=manual" })
class ComputerActorLimitsIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ComputerSeats seats;

	@Autowired
	ComputerPlayers computers;

	@Autowired
	MilitaryActions military;

	@Autowired
	DiplomacyActions diplomacy;

	ApiDriver api;

	CampaignScenario c;

	CampaignScenario.Game game;

	UUID gameId;

	/** The first computer seat (Aestia) is on turn. */
	CurrentPlayer aestia;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiDriver(mockMvc);
		c = new CampaignScenario(api, jdbc);
		game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		gameId = UUID.fromString(game.gameId());
		c.endTurn(game);
		c.endTurn(game);
		aestia = computers.actor(seats.activeComputerTurn(gameId).orElseThrow().seat().playerId());
	}

	private static String code(Runnable command) {
		try {
			command.run();
			return "OK";
		}
		catch (ApiException e) {
			return e.code();
		}
	}

	private UUID battalionOf(String nationKey) {
		return jdbc.queryForObject("SELECT id FROM game.battalion WHERE nation_id = ?::uuid ORDER BY id LIMIT 1", UUID.class,
				c.nationId(game, nationKey));
	}

	private UUID city(String province) {
		return jdbc.queryForObject("""
				SELECT c.id FROM game.city c JOIN game.province_state ps ON ps.id = c.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id WHERE ps.game_id = ?::uuid AND pt.key = ?
				""", UUID.class, game.gameId(), province);
	}

	@Test
	void aBattalionMovesOncePerRoundAndOnlyToAnAdjacentProvinceItMayEnter() {
		UUID battalion = battalionOf("aestia");
		// Aestia's capital aestmark borders livonia (own) — not vostrov (not adjacent, not at war).
		assertThat(code(() -> military.move(aestia, gameId, List.of(battalion), "vostrov", UUID.randomUUID())))
			.isNotEqualTo("OK");
		assertThat(code(() -> military.move(aestia, gameId, List.of(battalion), "livonia", UUID.randomUUID()))).isEqualTo("OK");
		assertThat(code(() -> military.move(aestia, gameId, List.of(battalion), "aestmark", UUID.randomUUID())))
			.isEqualTo("BATTALION_ALREADY_MOVED");
	}

	@Test
	void recruitmentNeedsGoldAndPeople() {
		UUID capital = city("aestmark");
		jdbc.update("UPDATE game.nation_economy SET treasury_mg = 0 WHERE nation_id = ?::uuid", c.nationId(game, "aestia"));
		assertThat(code(() -> military.recruit(aestia, gameId, capital, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("INSUFFICIENT_GOLD");
		jdbc.update("UPDATE game.nation_economy SET treasury_mg = 1000000 WHERE nation_id = ?::uuid", c.nationId(game, "aestia"));
		jdbc.update("UPDATE game.city SET population = 1500 WHERE id = ?::uuid", capital);
		assertThat(code(() -> military.recruit(aestia, gameId, capital, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("INSUFFICIENT_POPULATION");
	}

	@Test
	void aComputerCannotCommandAnotherNationsBattalions() {
		assertThat(code(() -> military.move(aestia, gameId, List.of(battalionOf("karpathia")), "galicja", UUID.randomUUID())))
			.isEqualTo("NOT_YOUR_BATTALION");
		assertThat(code(() -> military.move(aestia, gameId, List.of(battalionOf("castavar")), "meseta", UUID.randomUUID())))
			.isEqualTo("NOT_YOUR_BATTALION");
	}

	@Test
	void diplomaticRestrictionsApplyToComputersToo() {
		UUID karpathia = UUID.fromString(c.nationId(game, "karpathia"));
		assertThat(code(() -> diplomacy.declareWar(aestia, gameId, karpathia, UUID.randomUUID()))).isEqualTo("OK");
		// At war already, and an alliance with the enemy is impossible.
		assertThat(code(() -> diplomacy.declareWar(aestia, gameId, karpathia, UUID.randomUUID()))).isNotEqualTo("OK");
		assertThat(code(() -> diplomacy.proposeAlliance(aestia, gameId, karpathia, "NORMAL", UUID.randomUUID())))
			.isNotEqualTo("OK");
		// Gifts to an enemy are refused.
		assertThat(code(() -> diplomacy.sendGold(aestia, gameId, karpathia, 1000, UUID.randomUUID()))).isEqualTo("GIFT_TO_ENEMY");
	}

	@Test
	void aComputerCannotApproveReviseAnswerOrEndAConferenceThatIsNotItsBusiness() throws Exception {
		CampaignScenario.Game humans = c.start("grand-campaign", List.of("castavar", "nordmark"));
		UUID humansGame = UUID.fromString(humans.gameId());
		String warId = c.declareWar(humans, "castavar", "nordmark");
		String id = read(api.requestConference(humans.token("castavar"), humans.gameId(), warId, "GENERAL", UUID.randomUUID()),
				"$.conference.id");
		UUID conference = UUID.fromString(id);
		CurrentPlayer bystander = computers.actor(seats.seats(humansGame).getFirst().playerId());
		assertThat(code(() -> diplomacy.answerConference(bystander, humansGame, conference, true, UUID.randomUUID())))
			.isEqualTo("NOT_CONFERENCE_COUNTERPART");
		api.answerConference(humans.token("nordmark"), humans.gameId(), id, true, UUID.randomUUID());
		assertThat(code(() -> diplomacy.decideDraft(bystander, humansGame, conference, 1, true, UUID.randomUUID())))
			.isEqualTo("APPROVAL_NOT_REQUIRED");
		assertThat(code(() -> diplomacy.submitDraft(bystander, humansGame, conference, 1, List.of(), UUID.randomUUID())))
			.isEqualTo("NOT_CONFERENCE_PRINCIPAL");
		assertThat(code(() -> diplomacy.cancelConference(bystander, humansGame, conference, UUID.randomUUID())))
			.isEqualTo("NOT_CONFERENCE_PRINCIPAL");
		// A computer of another game is not even a member here.
		assertThat(code(() -> diplomacy.decideDraft(aestia, humansGame, conference, 1, true, UUID.randomUUID())))
			.isEqualTo("GAME_NOT_FOUND");
	}

}
