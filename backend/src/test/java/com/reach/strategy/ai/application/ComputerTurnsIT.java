package com.reach.strategy.ai.application;

import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.campaign.CampaignScenario;
import com.reach.strategy.economy.api.EconomyActions;
import com.reach.strategy.game.api.ComputerSeats;
import com.reach.strategy.identity.api.ComputerPlayers;
import com.reach.strategy.identity.api.CurrentPlayer;
import com.reach.strategy.military.api.MilitaryActions;
import com.reach.strategy.platform.web.ApiException;
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
 * Phase 11A against PostgreSQL (ruleset v7, Grand Campaign): computer seats for every nation nobody
 * chose, one computer turn per seat and round after the human seats, recovery of an interrupted
 * computer turn, no duplicate processing, and the narrow authority of computer accounts. The runner
 * is driven manually here so the tests can stop the game exactly at a computer seat.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = { "strategy.ruleset.current-version=v7", "strategy.world.default-map=grand-campaign",
		"strategy.ai.execution=manual" })
class ComputerTurnsIT {

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
	EconomyActions economy;

	@Autowired
	MilitaryActions military;

	ApiDriver api;

	CampaignScenario c;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		c = new CampaignScenario(api, jdbc);
	}

	private int activeSeat(CampaignScenario.Game game) {
		return jdbc.queryForObject("SELECT active_seat FROM game.game WHERE id = ?::uuid", Integer.class, game.gameId());
	}

	/** Both humans end their turn; the game then waits at the first computer seat. */
	private void humansEndTheirTurns(CampaignScenario.Game game) throws Exception {
		for (int i = 0; i < 2; i++) {
			MvcResult ended = c.endTurn(game);
			assertThat(status(ended)).as(body(ended)).isEqualTo(200);
		}
	}

	private int aiTurns(CampaignScenario.Game game, String status) {
		return jdbc.queryForObject("SELECT count(*) FROM game.ai_turn WHERE game_id = ?::uuid AND status = ?",
				Integer.class, game.gameId(), status);
	}

	@Test
	void everyNationNobodyChoseGetsAComputerSeatAfterTheHumans() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT gp.seat_order, gp.computer, p.kind, p.supabase_user_id, n.control, nt.key
				FROM game.game_player gp JOIN game.player p ON p.id = gp.player_id
				JOIN game.nation n ON n.game_player_id = gp.id AND n.game_id = gp.game_id
				JOIN game.nation_template nt ON nt.id = n.nation_template_id
				WHERE gp.game_id = ?::uuid ORDER BY gp.seat_order
				""", game.gameId());
		assertThat(rows).hasSize(10);
		assertThat(rows.subList(0, 2)).allSatisfy(r -> assertThat(r).containsEntry("control", "HUMAN").containsEntry("computer", false));
		assertThat(rows.subList(2, 10)).allSatisfy(r -> {
			assertThat(r).containsEntry("control", "AI").containsEntry("computer", true).containsEntry("kind", "COMPUTER");
			assertThat(r.get("supabase_user_id")).isNull();
		});
		// Computer seats follow the human seats in nation key order.
		assertThat(rows.subList(2, 10)).extracting(r -> r.get("key"))
			.containsExactly("aestia", "aurelia", "dravonia", "frisia", "helvaria", "karpathia", "serenna", "vostrava");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.nation WHERE game_id = ?::uuid AND control = 'NPC'",
				Integer.class, game.gameId())).isZero();
		// They are not lobby participants; the state shows them as computers.
		assertThat(ApiDriver.<List<Object>>read(api.lobby(game.tokens().getFirst(), game.gameId()), "$.participants")).hasSize(2);
		MvcResult state = api.state(game.tokens().getFirst(), game.gameId());
		assertThat(ApiDriver.<List<Boolean>>read(state, "$.players[*].computer")).containsExactly(false, false, true, true,
				true, true, true, true, true, true);
		assertThat(ApiDriver.<List<String>>read(state, "$.players[?(@.computer == true)].displayName")).containsOnly("Computer");
	}

	@Test
	void eachComputerSeatPlaysOncePerRoundAndTheRoundCompletesOnce() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		humansEndTheirTurns(game);
		assertThat(activeSeat(game)).isEqualTo(2);
		assertThat(c.round(game)).isEqualTo(1);
		// Humans cannot act for the waiting computer seat.
		assertThat(status(c.api.endTurn(game.tokens().getFirst(), game.gameId(), c.turn(game), UUID.randomUUID())))
			.isEqualTo(409);

		runner.run(UUID.fromString(game.gameId()));

		assertThat(c.round(game)).isEqualTo(2);
		assertThat(activeSeat(game)).isZero();
		assertThat(c.turn(game)).isEqualTo(11);
		assertThat(aiTurns(game, "DONE")).isEqualTo(8);
		assertThat(jdbc.queryForObject("""
				SELECT count(DISTINCT nation_id) FROM game.ai_turn WHERE game_id = ?::uuid AND round_number = 1
				""", Integer.class, game.gameId())).isEqualTo(8);
		// The completed round was processed exactly once (round 1 income for every nation).
		assertThat(jdbc.queryForObject("SELECT count(*) FROM game.round_resolution WHERE game_id = ?::uuid", Integer.class,
				game.gameId())).isEqualTo(1);

		// Nothing is due while a human is on turn: running again changes nothing.
		runner.run(UUID.fromString(game.gameId()));
		assertThat(c.turn(game)).isEqualTo(11);

		humansEndTheirTurns(game);
		runner.run(UUID.fromString(game.gameId()));
		assertThat(c.round(game)).isEqualTo(3);
		assertThat(aiTurns(game, "DONE")).isEqualTo(16);
	}

	@Test
	void anInterruptedComputerTurnContinuesWhereItStopped() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		humansEndTheirTurns(game);
		ComputerSeats.Turn turn = seats.activeComputerTurn(UUID.fromString(game.gameId())).orElseThrow();
		UUID nation = jdbc.queryForObject("SELECT id FROM game.nation WHERE game_id = ?::uuid AND game_player_id = ?",
				UUID.class, game.gameId(), turn.seat().gamePlayerId());
		// A runner crashed in the middle of this turn: progress row left RUNNING, one observation made.
		jdbc.update("""
				INSERT INTO game.ai_turn (game_id, turn_number, round_number, game_player_id, nation_id, status, observations,
				    started_at)
				VALUES (?::uuid, ?, 1, ?, ?, 'RUNNING', 1, now())
				""", game.gameId(), turn.turnNumber(), turn.seat().gamePlayerId(), nation);

		runner.run(UUID.fromString(game.gameId()));

		assertThat(c.round(game)).isEqualTo(2);
		assertThat(aiTurns(game, "DONE")).isEqualTo(8);
		assertThat(aiTurns(game, "RUNNING")).isZero();
	}

	@Test
	void concurrentRunnersNeverPlayATurnTwice() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		humansEndTheirTurns(game);
		UUID gameId = UUID.fromString(game.gameId());
		Concurrently.run(4, i -> {
			runner.run(gameId);
			return null;
		});
		runner.run(gameId);
		assertThat(c.round(game)).isEqualTo(2);
		assertThat(aiTurns(game, "DONE")).isEqualTo(8);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.game_command gc JOIN game.game_player gp ON gp.id = gc.game_player_id
				WHERE gc.game_id = ?::uuid AND gp.computer AND gc.command_type = 'END_TURN'
				""", Integer.class, game.gameId())).isEqualTo(8);
	}

	@Test
	void aComputerAccountActsOnlyForItsOwnSeatInItsOwnGame() throws Exception {
		CampaignScenario.Game game = c.start("grand-campaign", List.of("castavar", "nordmark"));
		CampaignScenario.Game other = c.start("grand-campaign", List.of("vostrava", "dravonia"));
		ComputerSeats.Seat seat = seats.seats(UUID.fromString(game.gameId())).getFirst();
		CurrentPlayer computer = computers.actor(seat.playerId());

		// Not a member of another game.
		assertThatThrownBy(() -> economy.overview(computer, UUID.fromString(other.gameId())))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("GAME_NOT_FOUND"));
		// Cannot give orders to a human nation's battalions.
		humansEndTheirTurns(game);
		String human = jdbc.queryForObject("""
				SELECT b.id::text FROM game.battalion b JOIN game.nation n ON n.id = b.nation_id
				WHERE b.game_id = ?::uuid AND n.control = 'HUMAN' LIMIT 1
				""", String.class, game.gameId());
		ComputerSeats.Turn turn = seats.activeComputerTurn(UUID.fromString(game.gameId())).orElseThrow();
		CurrentPlayer onTurn = computers.actor(turn.seat().playerId());
		assertThatThrownBy(() -> military.move(onTurn, UUID.fromString(game.gameId()), List.of(UUID.fromString(human)),
				"meseta", UUID.randomUUID()))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_YOUR_BATTALION"));
		// A seat that is not on turn cannot act in turn.
		CurrentPlayer waiting = computers.actor(seats.seats(UUID.fromString(game.gameId())).getLast().playerId());
		assertThatThrownBy(() -> military.recruit(waiting, UUID.fromString(game.gameId()), UUID.randomUUID(), "INFANTRY",
				UUID.randomUUID()))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_ACTIVE_PLAYER"));
		// Human accounts can never be obtained as computer actors.
		UUID humanPlayer = jdbc.queryForObject("""
				SELECT player_id FROM game.game_player WHERE game_id = ?::uuid AND NOT computer LIMIT 1
				""", UUID.class, game.gameId());
		assertThatThrownBy(() -> computers.actor(humanPlayer)).isInstanceOf(IllegalArgumentException.class);
		// Computer accounts have no Supabase identity, so no token can ever resolve to one.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM game.player WHERE kind = 'COMPUTER' AND supabase_user_id IS NOT NULL
				""", Integer.class)).isZero();
		assertThat(read(api.state(game.tokens().getFirst(), game.gameId()), "$.yourSeat").toString()).isEqualTo("0");
	}

}
