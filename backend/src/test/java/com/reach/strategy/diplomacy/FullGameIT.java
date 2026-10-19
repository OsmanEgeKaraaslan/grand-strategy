package com.reach.strategy.diplomacy;

import static com.reach.strategy.diplomacy.DiplomacyScenario.term;
import static com.reach.strategy.diplomacy.DiplomacyScenario.terms;
import static com.reach.strategy.support.ApiDriver.body;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.military.MilitaryScenario;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * One complete two-player game through the public API, ruleset v5, from the lobby to the stored
 * result: economy and research, a declared war with battles, a siege and an occupation, war score,
 * a negotiated peace with every kind of term, ten rounds of tribute, peaceful play afterwards and
 * the round-30 finish. No database shortcuts change the game except where noted.
 */
@PostgresIntegrationTest
class FullGameIT {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	private ApiDriver api;

	private MilitaryScenario world;

	private DiplomacyScenario d;

	private Lobby game;

	private int turn = 1;

	private static void ok(MvcResult result, int expected) throws Exception {
		assertThat(status(result)).as(body(result)).isEqualTo(expected);
	}

	/** Ends the active player's turn (host on odd turns, guest on even ones). */
	private void endTurn() throws Exception {
		ok(api.endTurn(turn % 2 == 1 ? game.host() : game.guest(), game.gameId(), turn, UUID.randomUUID()), 200);
		turn++;
	}

	private int round() {
		return (turn + 1) / 2;
	}

	private MvcResult move(String token, List<String> ids, String to) throws Exception {
		return api.move(token, game.gameId(), ids, to, UUID.randomUUID());
	}

	@Test
	void aWholeGameFromLobbyToResult() throws Exception {
		api = new ApiDriver(mockMvc);
		d = new DiplomacyScenario(api, jdbc);
		world = d.world;

		// 1. Create and start a game (host = Varn, guest = Ostra); every nation starts at peace.
		game = world.startedGame();
		String varn = d.varn(game);
		String ostra = d.ostra(game);
		assertThat((Boolean) read(api.diplomacy(game.host(), game.gameId()), "$.available")).isTrue();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		String saltreach = api.cityId(game.guest(), game.gameId(), "saltreach");
		String sablewater = api.cityId(game.guest(), game.gameId(), "sablewater");

		// 2. Workshops and a research institution (built when the treasury allows).
		ok(api.buildWorkshop(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID()), 201);
		endTurn();
		ok(api.buildWorkshop(game.guest(), game.gameId(), sablewater, 1, UUID.randomUUID()), 201);
		endTurn();
		boolean academy = false;
		while (!academy) {
			if (world.treasury(game, "varn") >= 60_000) {
				ok(api.buildInstitution(game.host(), game.gameId(), varnhold, "MILITARY_ACADEMY", UUID.randomUUID()), 201);
				academy = true;
			}
			endTurn();
			endTurn();
		}
		assertThat(world.count("game.institution", game)).isEqualTo(1);

		// 3. Research: the academy produces military points until level 2 can be unlocked.
		while ((Integer) world.research(game, "varn").get("military_points") < 40) {
			endTurn();
			endTurn();
			assertThat(round()).isLessThan(15);
		}
		ok(api.unlockTechnology(game.host(), game.gameId(), "MILITARY", 2, UUID.randomUUID()), 200);
		assertThat(world.research(game, "varn")).containsEntry("military_level", 2);

		// 4. War: Varn declares war on Ostra.
		String warId = d.declareWar(game, game.host(), ostra);

		// 5. Recruitment and movement: a new battalion in Varnhold, the field army to Elmsford.
		ok(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID()), 201);
		List<String> army = world.battalions(game, "varn", "varnhold")
			.stream()
			.filter(id -> world.battalion(id).get("last_moved_turn_number") == null
					&& ((Number) jdbc.queryForObject("SELECT recruited_turn_number FROM game.battalion WHERE id = ?::uuid",
							Integer.class, id)).intValue() == 0)
			.toList();
		assertThat(army).hasSize(4);
		ok(move(game.host(), army, "elmsford"), 200);
		endTurn();
		// Ostra screens Sablewater with a single infantry battalion.
		String screen = world.battalions(game, "ostra", "ostrava")
			.stream()
			.filter(id -> "INFANTRY".equals(world.battalion(id).get("unit_type")))
			.findFirst()
			.orElseThrow();
		ok(move(game.guest(), List.of(screen), "sablewater"), 200);
		endTurn();

		// 6. Combat: the Varn army attacks across the river and wins; a siege of Sablewater begins.
		MvcResult battle = move(game.host(), army, "sablewater");
		ok(battle, 200);
		assertThat((String) read(battle, "$.outcome")).isEqualTo("ATTACK_WON");
		assertThat(world.sieges(game)).extracting(s -> s.get("status")).containsExactly("ACTIVE");
		endTurn();
		endTurn();
		// One battalion marches on to unfortified Marrowfield and occupies it at the round end.
		String occupier = world.battalions(game, "varn", "sablewater").getFirst();
		ok(move(game.host(), List.of(occupier), "marrowfield"), 200);
		endTurn();
		endTurn();
		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("ostra", "varn");
		assertThat(((Number) world.sieges(game).getFirst().get("progress")).intValue()).as("siege rounds").isPositive();

		// 7. War score from the battle and the occupation, computed by the server.
		MvcResult war = api.war(game.guest(), game.gameId(), warId);
		assertThat((Integer) read(war, "$.war.score.attacker.battlesWon")).isEqualTo(1);
		assertThat((Integer) read(war, "$.war.score.attacker.occupation")).isPositive();
		assertThat((Integer) read(war, "$.war.score.net")).isPositive();

		// 8. Varn proposes peace: Marrowfield, reparations, tribute and 1,000 people from Saltreach.
		long ostraTreasury = world.treasury(game, "ostra");
		long reparations = Math.min(20_000, ostraTreasury / 2);
		String proposal = d.proposePeace(game, game.host(), warId,
				terms(term("PROVINCE", ostra, varn, "\"provinceKey\":\"marrowfield\""),
						term("GOLD", ostra, varn, "\"amountMg\":" + reparations), term("TRIBUTE", ostra, varn, ""),
						term("POPULATION", ostra, varn, "\"sourceCityId\":\"" + saltreach + "\",\"destinationCityId\":\""
								+ elmsford + "\",\"people\":1000")));
		endTurn();

		// 9. Ostra accepts on its own turn.
		long varnGold = world.treasury(game, "varn");
		long ostraGold = world.treasury(game, "ostra");
		long people = d.people(game);
		MvcResult accepted = d.acceptPeace(game, game.guest(), proposal);
		ok(accepted, 200);
		int signedRound = round();

		// 10. Every term took effect exactly; the total population is conserved.
		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("varn", "varn");
		assertThat(world.treasury(game, "varn")).isEqualTo(varnGold + reparations);
		assertThat(world.treasury(game, "ostra")).isEqualTo(ostraGold - reparations);
		assertThat(d.people(game)).isEqualTo(people);

		// 12. The war is over: the siege is lifted and the Varn army has withdrawn from Sablewater.
		assertThat(d.wars(game, "ENDED")).isEqualTo(1);
		assertThat(world.sieges(game)).extracting(s -> s.get("status")).containsExactly("LIFTED");
		assertThat(world.battalions(game, "varn", "sablewater")).isEmpty();
		assertThat(world.ownerAndController(game, "sablewater")).containsExactly("ostra", "ostra");
		assertThat(world.inconsistentCohorts(game)).isEmpty();
		endTurn();

		// 11 + 13. Play continues in peace; tribute is charged once per round for ten rounds.
		ok(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID()), 201);
		while (d.tributePayments(game).size() < 10) {
			endTurn();
			assertThat(round()).isLessThan(30);
		}
		List<Map<String, Object>> payments = d.tributePayments(game);
		assertThat(payments).extracting(p -> p.get("round_number"))
			.containsExactlyElementsOf(java.util.stream.IntStream.range(signedRound, signedRound + 10).boxed().toList());
		for (Map<String, Object> p : payments) {
			assertThat((Long) p.get("due_mg"))
				.isEqualTo((Long) d.report(game, "ostra", (Integer) p.get("round_number")).get("gross_income_mg") / 10);
		}
		assertThat(d.tribute(game)).containsEntry("status", "COMPLETED");
		int paymentsAfterTen = d.tributePayments(game).size();

		// 14. The remaining rounds pass without war (the truce has ended, nobody declares).
		while (round() <= 30 && "IN_PROGRESS".equals(d.gameStatus(game))) {
			endTurn();
		}
		assertThat(d.tributePayments(game)).hasSize(paymentsAfterTen);

		// 15 + 16. The game finished after round 30 and its result is stored and readable.
		assertThat(d.gameStatus(game)).isEqualTo("FINISHED");
		MvcResult result = api.result(game.host(), game.gameId());
		ok(result, 200);
		assertThat((Integer) read(result, "$.finishedRound")).isEqualTo(30);
		assertThat((String) read(result, "$.outcome")).isEqualTo("WINNER");
		assertThat((String) read(result, "$.winnerNationId")).isEqualTo(varn);
		assertThat(ApiDriver.<List<Integer>>read(result, "$.standings[*].ownedProvinces")).containsExactly(7, 5);
		assertThat(((Number) read(result, "$.statistics.peaceTreaties")).intValue()).isEqualTo(1);
		assertThat(((Number) read(result, "$.statistics.warsDeclared")).intValue()).isEqualTo(1);
		assertThat(((Number) read(result, "$.statistics.provincesCeded")).intValue()).isEqualTo(1);
		assertThat(status(api.endTurn(game.host(), game.gameId(), turn, UUID.randomUUID()))).isEqualTo(409);
		assertThat(status(api.state(game.guest(), game.gameId()))).isEqualTo(200);
	}

}
