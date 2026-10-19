package com.reach.strategy.military;

import static com.reach.strategy.support.ApiDriver.newPlayer;
import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.reach.strategy.PostgresIntegrationTest;
import com.reach.strategy.support.ApiDriver;
import com.reach.strategy.support.ApiDriver.Lobby;
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 6 against PostgreSQL through the real API: reinforcement (cost, civilians, limits, location,
 * races, retries), origin cohorts, disbanding with per-cohort returns and fallback, and population
 * conservation across recruit → reinforce → battle → disband. Host = Varn (Varnhold 8,000 people
 * after the ruleset v2 starting forces), guest = Ostra.
 */
@PostgresIntegrationTest
// Phase 5/6 rules: ruleset v2 has no fortresses, so every province is occupied the simple way.
@TestPropertySource(properties = "strategy.ruleset.current-version=v2")
class ReinforcementAndDisbandIT {

	static final long START_TREASURY = 50_000;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiDriver api;

	MilitaryScenario world;

	@BeforeEach
	void setUp() {
		api = new ApiDriver(mockMvc);
		world = new MilitaryScenario(api, jdbc);
	}

	private String infantryIn(Lobby game, String nationKey, String provinceKey) {
		return jdbc.queryForObject("""
				SELECT b.id::text FROM game.battalion b
				JOIN game.province_state ps ON ps.id = b.province_state_id
				JOIN game.province_template pt ON pt.id = ps.province_template_id
				WHERE b.game_id = ?::uuid AND b.nation_id = ?::uuid AND pt.key = ? AND b.unit_type = 'INFANTRY'
				ORDER BY b.id LIMIT 1
				""", String.class, game.gameId(), world.nationId(game, nationKey), provinceKey);
	}

	// --- reinforcement ------------------------------------------------------------------------

	@Test
	void reinforcing350SoldiersCosts3point5GoldAndExactly350Civilians() throws Exception {
		Lobby game = world.startedGame();
		String battalion = infantryIn(game, "varn", "varnhold");
		world.damage(battalion, 650);

		MvcResult details = api.battalion(game.host(), game.gameId(), battalion);
		assertThat(status(details)).isEqualTo(200);
		assertThat((Integer) read(details, "$.reinforcement.missing")).isEqualTo(350);
		assertThat((Integer) read(details, "$.reinforcement.maxSoldiers")).isEqualTo(350);
		assertThat(longAt(details, "$.reinforcement.costOfMaxMg")).isEqualTo(3_500);
		assertThat((Integer) read(details, "$.reinforcement.cityPopulation")).isEqualTo(8_000);
		assertThat((Integer) read(details, "$.battalion.upkeepMg")).isEqualTo(325);

		MvcResult reinforced = api.reinforce(game.host(), game.gameId(), battalion, 350, UUID.randomUUID());

		assertThat(status(reinforced)).isEqualTo(201);
		assertThat((Integer) read(reinforced, "$.battalion.manpower")).isEqualTo(1_000);
		assertThat(longAt(reinforced, "$.costMg")).isEqualTo(3_500);
		assertThat(longAt(reinforced, "$.treasuryMg")).isEqualTo(START_TREASURY - 3_500);
		assertThat((Integer) read(reinforced, "$.cityPopulation")).isEqualTo(7_650);
		assertThat((String) read(reinforced, "$.battalion.eligibility")).isEqualTo("REINFORCED_THIS_TURN");
		assertThat(world.population(game, "varnhold")).isEqualTo(7_650);
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY - 3_500);
		assertThat(world.cohorts(battalion)).containsExactly(Map.entry("varnhold", 1_000));

		// Employment recalculates from the smaller population: 4,000 workshop + 3,650 farmers.
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		assertThat(longAt(api.cityEconomy(game.host(), game.gameId(), varnhold), "$.potentialIncome.totalMg"))
			.isEqualTo(4_000 * 3 + 3_650);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg"))
			.as("the refilled battalion costs full upkeep again").isEqualTo(3_000);
		assertThat(code(api.move(game.host(), game.gameId(), List.of(battalion), "elmsford", UUID.randomUUID())))
			.isEqualTo("422 BATTALION_REINFORCED_THIS_TURN");

		world.completeRound(game, 1);
		assertThat(status(api.move(game.host(), game.gameId(), List.of(battalion), "elmsford", UUID.randomUUID())))
			.as("movement returns next turn").isEqualTo(200);
	}

	@Test
	void atMost500ReplacementsPerTurnAndNeverAboveMaximum() throws Exception {
		Lobby game = world.startedGame();
		String battalion = infantryIn(game, "varn", "varnhold");
		world.damage(battalion, 200);

		assertThat(code(api.reinforce(game.host(), game.gameId(), battalion, 600, UUID.randomUUID())))
			.isEqualTo("422 REINFORCEMENT_LIMIT_REACHED");
		assertThat(status(api.reinforce(game.host(), game.gameId(), battalion, 300, UUID.randomUUID()))).isEqualTo(201);
		assertThat(code(api.reinforce(game.host(), game.gameId(), battalion, 300, UUID.randomUUID())))
			.as("only 200 of this turn's 500 are left").isEqualTo("422 REINFORCEMENT_LIMIT_REACHED");
		assertThat(status(api.reinforce(game.host(), game.gameId(), battalion, 200, UUID.randomUUID()))).isEqualTo(201);
		assertThat(world.manpower(battalion)).isEqualTo(700);
		assertThat((Integer) read(api.battalion(game.host(), game.gameId(), battalion), "$.reinforcement.turnAllowance"))
			.isZero();

		world.completeRound(game, 1);
		assertThat(code(api.reinforce(game.host(), game.gameId(), battalion, 301, UUID.randomUUID())))
			.isEqualTo("422 REINFORCEMENT_EXCEEDS_MAXIMUM");
		assertThat(status(api.reinforce(game.host(), game.gameId(), battalion, 300, UUID.randomUUID()))).isEqualTo(201);
		assertThat(code(api.reinforce(game.host(), game.gameId(), battalion, 1, UUID.randomUUID())))
			.isEqualTo("422 BATTALION_AT_FULL_STRENGTH");
		assertThat(world.manpower(battalion)).isEqualTo(1_000);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_000 - 800);
	}

	@Test
	void onlyTheActiveOwnerReinforcesInItsOwnControlledCityWithEnoughPeopleAndGold() throws Exception {
		Lobby game = world.startedGame();
		String mine = infantryIn(game, "varn", "varnhold");
		String theirs = infantryIn(game, "ostra", "ostrava");
		world.damage(mine, 500);
		world.damage(theirs, 500);
		String inEnemyCity = world.place(game, "varn", "sablewater", "INFANTRY", 500);
		String inOccupiedCity = world.place(game, "varn", "elmsford", "INFANTRY", 500);
		world.occupy(game, "elmsford", "ostra");

		assertThat(code(api.reinforce(game.guest(), game.gameId(), theirs, 100, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(code(api.reinforce(game.host(), game.gameId(), theirs, 100, UUID.randomUUID())))
			.isEqualTo("403 NOT_YOUR_BATTALION");
		assertThat(code(api.reinforce(newPlayer(), game.gameId(), mine, 100, UUID.randomUUID())))
			.isEqualTo("404 GAME_NOT_FOUND");
		assertThat(code(api.reinforce(game.host(), game.gameId(), inEnemyCity, 100, UUID.randomUUID())))
			.isEqualTo("422 INVALID_REINFORCEMENT_LOCATION");
		assertThat(code(api.reinforce(game.host(), game.gameId(), inOccupiedCity, 100, UUID.randomUUID())))
			.isEqualTo("422 INVALID_REINFORCEMENT_LOCATION");
		assertThat(status(api.reinforce(game.host(), game.gameId(), mine, 100, null))).isEqualTo(400);
		assertThat(status(api.reinforce(game.host(), game.gameId(), mine, 0, UUID.randomUUID()))).isEqualTo(400);
		assertThat(code(api.reinforce(game.host(), game.gameId(), UUID.randomUUID().toString(), 100, UUID.randomUUID())))
			.isEqualTo("404 BATTALION_NOT_FOUND");

		world.setPopulation(game, "varnhold", 1_100);
		assertThat(code(api.reinforce(game.host(), game.gameId(), mine, 200, UUID.randomUUID())))
			.isEqualTo("422 INSUFFICIENT_POPULATION");
		world.setPopulation(game, "varnhold", 8_000);
		world.setTreasury(game, "varn", 1_999);
		assertThat(code(api.reinforce(game.host(), game.gameId(), mine, 200, UUID.randomUUID())))
			.isEqualTo("422 INSUFFICIENT_GOLD");
		world.setTreasury(game, "varn", START_TREASURY);
		world.setArrears(game, "varn", true);
		assertThat(code(api.reinforce(game.host(), game.gameId(), mine, 200, UUID.randomUUID())))
			.isEqualTo("422 IN_ARREARS");
		assertThat(ApiDriver.<List<String>>read(api.battalion(game.host(), game.gameId(), mine),
				"$.reinforcement.blockers")).containsExactly("IN_ARREARS");

		assertThat(world.manpower(mine)).isEqualTo(500);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_000);
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY);
		assertThat(world.commands(game, "REINFORCE")).isZero();
	}

	@Test
	void simultaneousReinforcementsRespectTheTurnLimitAndARetriedOneRunsOnce() throws Exception {
		Lobby game = world.startedGame();
		String battalion = infantryIn(game, "varn", "varnhold");
		world.damage(battalion, 400);

		List<MvcResult> results = Concurrently.run(5,
				i -> api.reinforce(game.host(), game.gameId(), battalion, 200, UUID.randomUUID()));

		assertThat(results.stream().filter(r -> status(r) == 201)).hasSize(2);
		assertThat(results.stream().filter(r -> status(r) == 422)).hasSize(3);
		assertThat(world.manpower(battalion)).isEqualTo(800);
		assertThat(world.population(game, "varnhold")).isEqualTo(7_600);
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY - 4_000);

		world.completeRound(game, 1);
		UUID key = UUID.randomUUID();
		List<MvcResult> retries = Concurrently.run(4, i -> api.reinforce(game.host(), game.gameId(), battalion, 100, key));
		assertThat(retries).allSatisfy(r -> assertThat(status(r)).isEqualTo(201));
		assertThat(retries).allSatisfy(r -> assertThat(longAt(r, "$.costMg")).isEqualTo(1_000));
		assertThat(world.manpower(battalion)).isEqualTo(900);
		assertThat(world.population(game, "varnhold")).isEqualTo(7_500);
		assertThat(world.inconsistentCohorts(game)).isEmpty();
	}

	// --- disbanding ---------------------------------------------------------------------------

	@Test
	void disbandingReturnsTheSurvivorsHomeAndRemovesTheUpkeep() throws Exception {
		Lobby game = world.startedGame();
		String battalion = infantryIn(game, "varn", "varnhold");
		world.damage(battalion, 750);
		MvcResult preview = api.battalion(game.host(), game.gameId(), battalion);
		assertThat((Boolean) read(preview, "$.disband.allowed")).isTrue();
		assertThat((Integer) read(preview, "$.disband.returns[0].soldiers")).isEqualTo(750);
		assertThat(longAt(preview, "$.disband.upkeepSavedMg")).isEqualTo(375);
		UUID key = UUID.randomUUID();

		MvcResult disbanded = api.disband(game.host(), game.gameId(), battalion, key);

		assertThat(status(disbanded)).isEqualTo(200);
		assertThat((Integer) read(disbanded, "$.soldiersReturned")).isEqualTo(750);
		assertThat((String) read(disbanded, "$.returns[0].provinceKey")).isEqualTo("varnhold");
		assertThat(longAt(disbanded, "$.upkeepSavedMg")).isEqualTo(375);
		assertThat(world.battalion(battalion)).isNull();
		assertThat(world.cohorts(battalion)).isEmpty();
		assertThat(world.population(game, "varnhold")).as("only the 750 survivors return").isEqualTo(8_750);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg"))
			.as("2,875 before, minus the disbanded battalion").isEqualTo(2_500);

		MvcResult retry = api.disband(game.host(), game.gameId(), battalion, key);
		assertThat(status(retry)).isEqualTo(200);
		assertThat((Integer) read(retry, "$.soldiersReturned")).isEqualTo(750);
		assertThat(code(api.disband(game.host(), game.gameId(), battalion, UUID.randomUUID())))
			.isEqualTo("409 BATTALION_DISBANDED");
		assertThat(code(api.reinforce(game.host(), game.gameId(), battalion, 10, UUID.randomUUID())))
			.isEqualTo("409 BATTALION_DISBANDED");
		assertThat(world.population(game, "varnhold")).isEqualTo(8_750);

		world.completeRound(game, 1);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.lastRound.upkeepMg"))
			.as("disbanded battalions are not charged").isEqualTo(2_500);
	}

	@Test
	void disbandingIsOnlyAllowedInOwnControlledTerritoryByTheActiveOwner() throws Exception {
		Lobby game = world.startedGame();
		String inEnemyLand = world.place(game, "varn", "sablewater", "INFANTRY", 1_000);
		String theirs = infantryIn(game, "ostra", "ostrava");
		String mine = infantryIn(game, "varn", "varnhold");
		long civilians = world.civilians(game);

		assertThat(code(api.disband(game.host(), game.gameId(), inEnemyLand, UUID.randomUUID())))
			.isEqualTo("422 NOT_FRIENDLY_TERRITORY");
		assertThat(code(api.disband(game.host(), game.gameId(), theirs, UUID.randomUUID())))
			.isEqualTo("403 NOT_YOUR_BATTALION");
		assertThat(code(api.disband(game.guest(), game.gameId(), theirs, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		world.occupy(game, "varnhold", "ostra");
		assertThat(code(api.disband(game.host(), game.gameId(), mine, UUID.randomUUID())))
			.as("owned but occupied is not friendly-controlled").isEqualTo("422 NOT_FRIENDLY_TERRITORY");
		assertThat(world.civilians(game)).isEqualTo(civilians);
		assertThat(world.commands(game, "DISBAND")).isZero();
	}

	@Test
	void simultaneousDisbandsReturnTheSoldiersOnce() throws Exception {
		Lobby game = world.startedGame();
		String battalion = infantryIn(game, "varn", "varnhold");

		List<MvcResult> results = Concurrently.run(4,
				i -> api.disband(game.host(), game.gameId(), battalion, UUID.randomUUID()));

		assertThat(results.stream().filter(r -> status(r) == 200)).hasSize(1);
		assertThat(results.stream().filter(r -> status(r) == 409)).hasSize(3);
		assertThat(world.population(game, "varnhold")).isEqualTo(9_000);
	}

	@Test
	void eachCohortGoesHomeAndALostOriginFallsBackToTheLocalCity() throws Exception {
		Lobby game = world.startedGame();
		String first = infantryIn(game, "varn", "varnhold");
		world.damage(first, 600);
		String second = world.battalions(game, "varn", "varnhold").stream().filter(id -> !id.equals(first))
			.filter(id -> "INFANTRY".equals(world.battalion(id).get("unit_type"))).findFirst().orElseThrow();
		world.damage(second, 600);
		api.move(game.host(), game.gameId(), List.of(first, second), "elmsford", UUID.randomUUID());
		world.completeRound(game, 1);

		assertThat(status(api.reinforce(game.host(), game.gameId(), first, 400, UUID.randomUUID()))).isEqualTo(201);
		assertThat(status(api.reinforce(game.host(), game.gameId(), second, 400, UUID.randomUUID()))).isEqualTo(201);
		assertThat(world.cohorts(first)).containsExactly(Map.entry("elmsford", 400), Map.entry("varnhold", 600));
		MvcResult details = api.battalion(game.host(), game.gameId(), first);
		assertThat(ApiDriver.<List<String>>read(details, "$.cohorts[*].provinceKey"))
			.containsExactlyInAnyOrder("varnhold", "elmsford");
		assertThat(world.population(game, "elmsford")).isEqualTo(8_000 - 800);

		MvcResult home = api.disband(game.host(), game.gameId(), first, UUID.randomUUID());
		assertThat(ApiDriver.<List<Boolean>>read(home, "$.returns[*].fallback")).containsOnly(false);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_600);
		assertThat(world.population(game, "elmsford")).isEqualTo(7_600);

		// Varnhold falls to Ostra: the second battalion's Varnhold cohort cannot go home.
		world.occupy(game, "varnhold", "ostra");
		MvcResult fallback = api.disband(game.host(), game.gameId(), second, UUID.randomUUID());
		assertThat(ApiDriver.<List<String>>read(fallback, "$.returns[*].provinceKey")).containsExactly("elmsford");
		assertThat(ApiDriver.<List<Boolean>>read(fallback, "$.returns[*].fallback")).containsExactly(true);
		assertThat(world.population(game, "varnhold")).isEqualTo(8_600);
		assertThat(world.population(game, "elmsford")).isEqualTo(8_600);
	}

	// --- conservation and isolation -----------------------------------------------------------

	@Test
	void peopleAreConservedThroughRecruitReinforceBattleAndDisbandExceptTheDead() throws Exception {
		Lobby game = world.startedGame();
		long people = world.civilians(game) + world.totalManpower(game);
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		String recruit = read(api.recruit(game.host(), game.gameId(), varnhold, "INFANTRY", UUID.randomUUID()),
				"$.battalion.id");
		world.place(game, "ostra", "elmsford", "INFANTRY", 1_000);
		long withEnemy = people + 1_000;

		List<String> attackers = world.battalions(game, "varn", "varnhold").stream().filter(id -> !id.equals(recruit))
			.toList();
		MvcResult battle = api.move(game.host(), game.gameId(), attackers, "elmsford", UUID.randomUUID());
		int dead = (Integer) read(battle, "$.battle.details.attackerLosses")
				+ (Integer) read(battle, "$.battle.details.defenderLosses");
		assertThat(dead).isPositive();
		assertThat(world.inconsistentCohorts(game)).as("losses are taken from the cohorts").isEmpty();
		world.completeRound(game, 1);

		String damaged = attackers.getFirst();
		int missing = 1_000 - world.manpower(damaged);
		api.move(game.host(), game.gameId(), List.of(damaged), "varnhold", UUID.randomUUID());
		world.completeRound(game, 2);
		if (missing > 0) {
			assertThat(status(api.reinforce(game.host(), game.gameId(), damaged, Math.min(missing, 500),
					UUID.randomUUID()))).isEqualTo(201);
		}
		assertThat(status(api.disband(game.host(), game.gameId(), damaged, UUID.randomUUID()))).isEqualTo(200);
		assertThat(status(api.disband(game.host(), game.gameId(), recruit, UUID.randomUUID()))).isEqualTo(200);

		assertThat(world.civilians(game) + world.totalManpower(game)).isEqualTo(withEnemy - dead);
		assertThat(world.inconsistentCohorts(game)).isEmpty();
	}

	@Test
	void battalionsOfAnotherGameCannotBeReinforcedOrDisbanded() throws Exception {
		Lobby first = world.startedGame();
		Lobby second = world.startedGame();
		String foreign = infantryIn(first, "varn", "varnhold");
		world.damage(foreign, 500);

		assertThat(code(api.reinforce(second.host(), second.gameId(), foreign, 100, UUID.randomUUID())))
			.isEqualTo("404 BATTALION_NOT_FOUND");
		assertThat(code(api.disband(second.host(), second.gameId(), foreign, UUID.randomUUID())))
			.isEqualTo("404 BATTALION_NOT_FOUND");
		assertThat(code(api.battalion(second.host(), second.gameId(), foreign))).isEqualTo("404 BATTALION_NOT_FOUND");
		assertThat(world.manpower(foreign)).isEqualTo(500);
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private static long longAt(MvcResult result, String path) {
		try {
			return ((Number) read(result, path)).longValue();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
