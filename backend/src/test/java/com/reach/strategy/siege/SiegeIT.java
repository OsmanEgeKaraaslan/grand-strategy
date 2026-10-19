package com.reach.strategy.siege;

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
import com.reach.strategy.support.Concurrently;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 7 against PostgreSQL through the real API, ruleset v3. Starting fortresses: Varnhold and
 * Ostrava level 2; Greyspire, Kaldric, Elmsford and Sablewater level 1; everything else unfortified.
 * Varn (host) besieges Ostra's Sablewater (level 1, 8,000 people, required 600 progress points)
 * from Elmsford across the river; Ostra's starting army stands in Ostrava next door.
 */
@PostgresIntegrationTest
// Phase 7 rules: ruleset v3 (fortresses and sieges, no research and no population growth).
@TestPropertySource(properties = "strategy.ruleset.current-version=v3")
class SiegeIT {

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

	private List<String> varnArmyInElmsford(Lobby game, int infantry, int artillery) {
		for (int i = 0; i < infantry; i++) {
			world.place(game, "varn", "elmsford", "INFANTRY", 1_000);
		}
		for (int i = 0; i < artillery; i++) {
			world.place(game, "varn", "elmsford", "ARTILLERY", 1_000);
		}
		return world.battalions(game, "varn", "elmsford");
	}

	private Map<String, Object> onlySiege(Lobby game) {
		List<Map<String, Object>> sieges = world.sieges(game);
		assertThat(sieges).hasSize(1);
		return sieges.getFirst();
	}

	// --- starting state ----------------------------------------------------------------------

	@Test
	void newGamesStartWithTheRulesetFortressesAndPayTheirUpkeep() throws Exception {
		Lobby game = world.startedGame();

		MvcResult overview = api.sieges(game.host(), game.gameId());
		assertThat(status(overview)).isEqualTo(200);
		assertThat((Boolean) read(overview, "$.fortificationsAvailable")).isTrue();
		assertThat(ApiDriver.<List<String>>read(overview, "$.fortifications[*].provinceKey"))
			.containsExactly("elmsford", "greyspire", "kaldric", "ostrava", "sablewater", "varnhold");
		assertThat(ApiDriver.<List<Integer>>read(overview, "$.fortifications[?(@.provinceKey == 'varnhold')].level"))
			.containsExactly(2);
		assertThat(ApiDriver.<List<Integer>>read(overview, "$.fortifications[?(@.provinceKey == 'varnhold')].integrity"))
			.containsExactly(200);
		assertThat((List<?>) read(overview, "$.activeSieges")).isEmpty();
		// 3 × 0.5 + 1.5 battalions, plus fortresses 2 + 1 + 1 gold.
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg")).isEqualTo(7_000);
	}

	// --- occupation policy ---------------------------------------------------------------------

	@Test
	void anUnfortifiedProvinceIsStillOccupiedTheSimpleWay() throws Exception {
		Lobby game = world.startedGame();
		String raider = world.place(game, "varn", "fenwick", "INFANTRY", 1_000);

		assertThat(status(api.move(game.host(), game.gameId(), List.of(raider), "marrowfield", UUID.randomUUID())))
			.isEqualTo(200);
		world.completeRound(game, 1);

		assertThat(world.ownerAndController(game, "marrowfield")).containsExactly("ostra", "varn");
		assertThat(world.sieges(game)).isEmpty();
	}

	@Test
	void aFortifiedProvinceFallsOnlyAfterAFullSiegeWithoutArtilleryStarvingTheCity() throws Exception {
		Lobby game = world.startedGame();
		List<String> army = varnArmyInElmsford(game, 2, 0);

		MvcResult moved = api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());
		assertThat((String) read(moved, "$.outcome")).isEqualTo("MOVED");

		MvcResult province = api.fortification(game.host(), game.gameId(), "sablewater");
		assertThat((String) read(province, "$.activeSiege.status")).isEqualTo("ACTIVE");
		assertThat((Integer) read(province, "$.activeSiege.requiredProgress")).isEqualTo(600);
		assertThat((Integer) read(province, "$.activeSiege.progress")).isZero();
		assertThat((Integer) read(province, "$.activeSiege.ratePerRound")).isEqualTo(100);
		assertThat((Integer) read(province, "$.activeSiege.estimatedRoundsLeft")).isEqualTo(6);
		assertThat((Integer) read(province, "$.activeSiege.artilleryManpower")).isZero();
		assertThat((Integer) read(province, "$.activeSiege.nextAttrition")).isEqualTo(400);
		assertThat(world.ownerAndController(game, "sablewater")).as("control holds during the siege")
			.containsExactly("ostra", "ostra");

		// Only the global round end advances a siege — not the host's own end of turn.
		world.endTurn(game, game.host(), 1);
		assertThat(onlySiege(game).get("progress")).isEqualTo(0);
		UUID roundEndKey = UUID.randomUUID();
		assertThat(status(api.endTurn(game.guest(), game.gameId(), 2, roundEndKey))).isEqualTo(200);
		assertThat(onlySiege(game).get("progress")).isEqualTo(100);
		assertThat(world.population(game, "sablewater")).isEqualTo(7_600);
		MvcResult retry = api.endTurn(game.guest(), game.gameId(), 2, roundEndKey);
		assertThat((Boolean) read(retry, "$.replayed")).isTrue();
		assertThat(onlySiege(game).get("progress")).as("a retried end of turn changes nothing").isEqualTo(100);
		assertThat(world.population(game, "sablewater")).isEqualTo(7_600);
		String sablewater = api.cityId(game.guest(), game.gameId(), "sablewater");
		assertThat(longAt(api.cityEconomy(game.guest(), game.gameId(), sablewater), "$.potentialIncome.totalMg"))
			.as("the economy uses the starving population").isEqualTo(7_600);

		for (int round = 2; round <= 5; round++) {
			world.completeRound(game, round);
		}
		assertThat(onlySiege(game).get("progress")).isEqualTo(500);
		assertThat(world.ownerAndController(game, "sablewater")).containsExactly("ostra", "ostra");

		world.completeRound(game, 6);
		Map<String, Object> siege = onlySiege(game);
		assertThat(siege.get("status")).isEqualTo("SUCCEEDED");
		assertThat(siege.get("end_reason")).isEqualTo("CAPTURED");
		assertThat(siege.get("artillery_siege")).isEqualTo(false);
		assertThat(siege.get("population_lost")).isEqualTo(8_000 - 5_883);
		assertThat(world.population(game, "sablewater")).isEqualTo(5_883);
		assertThat(world.ownerAndController(game, "sablewater")).as("control changes, ownership never")
			.containsExactly("ostra", "varn");
		assertThat(world.fortress(game, "sablewater")).containsEntry("status", "ACTIVE").containsEntry("integrity", 100);
		assertThat(longAt(api.cityEconomy(game.guest(), game.gameId(), sablewater), "$.collectibleIncomeMg"))
			.as("Q-W2: an occupied city pays nobody").isZero();

		MvcResult details = api.siege(game.host(), game.gameId(), siege.get("id").toString());
		assertThat((List<?>) read(details, "$.rounds")).hasSize(6);
		assertThat(ApiDriver.<List<Integer>>read(details, "$.rounds[*].populationLost"))
			.containsExactly(400, 380, 361, 342, 325, 309);
		assertThat(ApiDriver.<List<String>>read(api.sieges(game.guest(), game.gameId()), "$.recentSieges[*].status"))
			.containsExactly("SUCCEEDED");

		world.completeRound(game, 7);
		assertThat(world.sieges(game)).as("the capture is applied once").hasSize(1);
		assertThat(world.population(game, "sablewater")).as("no attrition after the fall").isEqualTo(5_883);
	}

	@Test
	void artilleryHalvesTheSiegeSavesCiviliansButDamagesTheFortressAndAWorkshop() throws Exception {
		Lobby game = world.startedGame();
		String workshop = world.workshop(game, "sablewater");
		List<String> army = varnArmyInElmsford(game, 1, 2);
		api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());
		MvcResult province = api.fortification(game.host(), game.gameId(), "sablewater");
		assertThat((Integer) read(province, "$.activeSiege.ratePerRound")).isEqualTo(200);
		assertThat((Integer) read(province, "$.activeSiege.estimatedRoundsLeft")).isEqualTo(3);

		for (int round = 1; round <= 3; round++) {
			world.completeRound(game, round);
		}

		Map<String, Object> siege = onlySiege(game);
		assertThat(siege.get("status")).isEqualTo("SUCCEEDED");
		assertThat(siege.get("artillery_siege")).isEqualTo(true);
		assertThat(siege.get("damaged_building_id").toString()).isEqualTo(workshop);
		assertThat(world.population(game, "sablewater")).as("3 rounds instead of 6").isEqualTo(6_859);
		assertThat(world.fortress(game, "sablewater")).containsEntry("status", "DAMAGED").containsEntry("integrity", 0);
		assertThat(world.buildingStatus(workshop)).isEqualTo("DAMAGED");
		String sablewater = api.cityId(game.guest(), game.gameId(), "sablewater");
		MvcResult city = api.cityEconomy(game.guest(), game.gameId(), sablewater);
		assertThat((Integer) read(city, "$.employment.tier1Workers")).as("its workers farm instead").isZero();
		assertThat((Integer) read(city, "$.employment.agricultureWorkers")).isEqualTo(6_859);
		assertThat(ApiDriver.<List<String>>read(city, "$.workshops[*].status")).containsExactly("DAMAGED");

		// Ostra takes the city back (simulated) and repairs on its own turn of round 4.
		jdbc.update("DELETE FROM game.battalion WHERE game_id = ?::uuid AND nation_id = ?::uuid",
				game.gameId(), world.nationId(game, "varn"));
		world.occupy(game, "sablewater", "ostra");
		world.endTurn(game, game.host(), 7);
		long treasury = world.treasury(game, "ostra");
		assertThat(code(api.repairWorkshop(game.host(), game.gameId(), sablewater, workshop, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		MvcResult quote = api.cityEconomy(game.guest(), game.gameId(), sablewater);
		assertThat(longAt(quote, "$.workshops[0].repair.costMg")).isEqualTo(12_000);
		MvcResult repaired = api.repairWorkshop(game.guest(), game.gameId(), sablewater, workshop, UUID.randomUUID());
		assertThat(status(repaired)).isEqualTo(200);
		assertThat((String) read(repaired, "$.workshop.status")).isEqualTo("UNDER_REPAIR");
		assertThat((Integer) read(repaired, "$.workshop.repairCompletesAfterRound")).isEqualTo(4);
		assertThat(code(api.repairWorkshop(game.guest(), game.gameId(), sablewater, workshop, UUID.randomUUID())))
			.isEqualTo("422 BUILDING_NOT_DAMAGED");
		MvcResult fortRepair = api.repairFortress(game.guest(), game.gameId(), sablewater, UUID.randomUUID());
		assertThat(status(fortRepair)).isEqualTo(201);
		assertThat(longAt(fortRepair, "$.costMg")).as("30% of the 50 gold level 1").isEqualTo(15_000);
		assertThat(world.treasury(game, "ostra")).isEqualTo(treasury - 12_000 - 15_000);
		assertThat((Integer) read(api.cityEconomy(game.guest(), game.gameId(), sablewater), "$.employment.tier1Workers"))
			.as("not before the repair completes").isZero();

		world.endTurn(game, game.guest(), 8);
		assertThat(world.buildingStatus(workshop)).isEqualTo("ACTIVE");
		assertThat(world.fortress(game, "sablewater")).containsEntry("status", "ACTIVE").containsEntry("integrity", 100);
		MvcResult restored = api.cityEconomy(game.guest(), game.gameId(), sablewater);
		assertThat((Integer) read(restored, "$.employment.tier1Workers")).isEqualTo(4_000);
		assertThat(longAt(restored, "$.potentialIncome.totalMg")).isEqualTo(4_000 * 3 + 2_859);
	}

	@Test
	void fieldCombatComesFirstAndOnlyAVictorBesieges() throws Exception {
		Lobby game = world.startedGame();
		world.place(game, "ostra", "sablewater", "INFANTRY", 1_000);
		String scout = world.place(game, "varn", "elmsford", "CAVALRY", 300);

		MvcResult repelled = api.move(game.host(), game.gameId(), List.of(scout), "sablewater", UUID.randomUUID());
		assertThat((String) read(repelled, "$.outcome")).isEqualTo("ATTACK_REPELLED");
		assertThat(world.sieges(game)).isEmpty();

		List<String> army = new java.util.ArrayList<>();
		for (int i = 0; i < 4; i++) {
			army.add(world.place(game, "varn", "elmsford", "INFANTRY", 1_000));
		}
		MvcResult won = api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());
		assertThat((String) read(won, "$.outcome")).isEqualTo("ATTACK_WON");
		assertThat((String) read(won, "$.battle.details.retreatProvinceKey")).isEqualTo("ostrava");
		Map<String, Object> siege = onlySiege(game);
		assertThat(siege.get("status")).as("the victors begin the siege at once").isEqualTo("ACTIVE");
		assertThat(siege.get("besieger_nation_id").toString()).isEqualTo(world.nationId(game, "varn"));
		assertThat(world.ownerAndController(game, "sablewater")).containsExactly("ostra", "ostra");
	}

	@Test
	void withdrawingLiftsTheSiegeAndReenteringStartsFromZero() throws Exception {
		Lobby game = world.startedGame();
		List<String> army = varnArmyInElmsford(game, 2, 0);
		api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());
		world.completeRound(game, 1);
		assertThat(onlySiege(game).get("progress")).isEqualTo(100);

		api.move(game.host(), game.gameId(), List.of(army.get(0)), "elmsford", UUID.randomUUID());
		assertThat(onlySiege(game).get("status")).as("one besieger is enough").isEqualTo("ACTIVE");
		api.move(game.host(), game.gameId(), List.of(army.get(1)), "elmsford", UUID.randomUUID());
		Map<String, Object> lifted = onlySiege(game);
		assertThat(lifted.get("status")).isEqualTo("LIFTED");
		assertThat(lifted.get("end_reason")).isEqualTo("BESIEGERS_GONE");
		assertThat((Object) read(api.fortification(game.host(), game.gameId(), "sablewater"), "$.activeSiege"))
			.isNull();

		world.endTurn(game, game.host(), 3);
		world.endTurn(game, game.guest(), 4);
		assertThat(world.population(game, "sablewater")).as("no attrition without a siege").isEqualTo(7_600);
		api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());
		List<Map<String, Object>> sieges = world.sieges(game);
		assertThat(sieges).hasSize(2);
		assertThat(sieges.get(1).get("status")).isEqualTo("ACTIVE");
		assertThat(sieges.get(1).get("progress")).as("progress is lost when lifted (Q-S8)").isEqualTo(0);
	}

	@Test
	void aReliefArmyThatBeatsTheBesiegersEndsTheSiege() throws Exception {
		Lobby game = world.startedGame();
		String besieger = world.place(game, "varn", "elmsford", "INFANTRY", 600);
		api.move(game.host(), game.gameId(), List.of(besieger), "sablewater", UUID.randomUUID());
		world.completeRound(game, 1);
		world.endTurn(game, game.host(), 3);

		MvcResult relief = api.move(game.guest(), game.gameId(), world.battalions(game, "ostra", "ostrava"), "sablewater",
				UUID.randomUUID());

		assertThat((String) read(relief, "$.outcome")).isEqualTo("ATTACK_WON");
		Map<String, Object> siege = onlySiege(game);
		assertThat(siege.get("status")).isEqualTo("LIFTED");
		assertThat(world.ownerAndController(game, "sablewater")).containsExactly("ostra", "ostra");
		world.endTurn(game, game.guest(), 4);
		assertThat(world.population(game, "sablewater")).isEqualTo(7_600);
	}

	@Test
	void aBesiegedCityCanNeitherRecruitNorBuildAndItsSoldiersSettleElsewhere() throws Exception {
		Lobby game = world.startedGame();
		api.move(game.host(), game.gameId(), varnArmyInElmsford(game, 2, 0), "sablewater", UUID.randomUUID());
		world.endTurn(game, game.host(), 1);
		String sablewater = api.cityId(game.guest(), game.gameId(), "sablewater");
		world.setTreasury(game, "ostra", 1_000_000);

		assertThat(code(api.recruit(game.guest(), game.gameId(), sablewater, "INFANTRY", UUID.randomUUID())))
			.isEqualTo("409 CITY_BESIEGED");
		assertThat(code(api.buildWorkshop(game.guest(), game.gameId(), sablewater, 1, UUID.randomUUID())))
			.isEqualTo("409 CITY_BESIEGED");
		assertThat(code(api.buildFortress(game.guest(), game.gameId(), sablewater, 2, UUID.randomUUID())))
			.isEqualTo("409 CITY_BESIEGED");
		assertThat(ApiDriver.<List<String>>read(api.fortification(game.guest(), game.gameId(), "sablewater"),
				"$.upgrade.blockers")).contains("BESIEGED");

		// A battalion raised in Sablewater disbands at home in Ostrava: its besieged city cannot take it back.
		String native_ = world.place(game, "ostra", "ostrava", "INFANTRY", 1_000);
		jdbc.update("UPDATE game.battalion SET origin_city_id = ?::uuid WHERE id = ?::uuid", sablewater, native_);
		jdbc.update("UPDATE game.battalion_cohort SET origin_city_id = ?::uuid WHERE battalion_id = ?::uuid",
				sablewater, native_);
		int ostrava = world.population(game, "ostrava");
		MvcResult disbanded = api.disband(game.guest(), game.gameId(), native_, UUID.randomUUID());
		assertThat(status(disbanded)).isEqualTo(200);
		assertThat(ApiDriver.<List<Boolean>>read(disbanded, "$.returns[*].fallback")).containsExactly(true);
		assertThat(world.population(game, "ostrava")).isEqualTo(ostrava + 1_000);
		assertThat(world.population(game, "sablewater")).isEqualTo(8_000);
	}

	// --- fortress works -----------------------------------------------------------------------

	@Test
	void buildingAFortressTakesTwoRoundsAndCanBeOrderedOnce() throws Exception {
		Lobby game = world.startedGame();
		String harrowmere = api.cityId(game.host(), game.gameId(), "harrowmere");
		MvcResult quote = api.fortification(game.host(), game.gameId(), "harrowmere");
		assertThat((Integer) read(quote, "$.upgrade.targetLevel")).isEqualTo(1);
		assertThat(longAt(quote, "$.upgrade.costMg")).isEqualTo(50_000);
		assertThat((Integer) read(quote, "$.upgrade.completesAfterRound")).isEqualTo(2);
		assertThat((Boolean) read(quote, "$.upgrade.allowed")).isTrue();

		List<MvcResult> results = Concurrently.run(4,
				i -> api.buildFortress(game.host(), game.gameId(), harrowmere, 1, UUID.randomUUID()));

		assertThat(results.stream().filter(r -> status(r) == 201)).hasSize(1);
		assertThat(results.stream().filter(r -> status(r) == 409)).hasSize(3);
		assertThat(world.treasury(game, "varn")).isEqualTo(START_TREASURY - 50_000);
		assertThat(world.fortress(game, "harrowmere")).containsEntry("level", 0).containsEntry("project", "UPGRADE");

		world.completeRound(game, 1);
		assertThat(world.fortress(game, "harrowmere")).containsEntry("level", 0);
		world.completeRound(game, 2);
		assertThat(world.fortress(game, "harrowmere")).containsEntry("level", 1).containsEntry("integrity", 100)
			.containsEntry("project", null);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg")).isEqualTo(8_000);
	}

	@Test
	void onlyTheActiveOwnerWhoControlsTheCityFortifiesAndStaleOrdersAreRejected() throws Exception {
		Lobby game = world.startedGame();
		world.setTreasury(game, "varn", 1_000_000);
		world.setTreasury(game, "ostra", 1_000_000);
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		String brindle = api.cityId(game.host(), game.gameId(), "brindle");

		assertThat(code(api.buildFortress(game.host(), game.gameId(), elmsford, 1, UUID.randomUUID())))
			.as("Elmsford is already level 1").isEqualTo("409 STALE_FORTRESS_LEVEL");
		assertThat(code(api.buildFortress(game.guest(), game.gameId(), elmsford, 2, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(code(api.repairFortress(game.host(), game.gameId(), elmsford, UUID.randomUUID())))
			.isEqualTo("422 FORTRESS_NOT_DAMAGED");
		assertThat(code(api.repairFortress(game.host(), game.gameId(), brindle, UUID.randomUUID())))
			.isEqualTo("404 FORTRESS_NOT_FOUND");
		world.occupy(game, "brindle", "ostra");
		assertThat(code(api.buildFortress(game.host(), game.gameId(), brindle, 1, UUID.randomUUID())))
			.isEqualTo("409 PROVINCE_OCCUPIED");
		assertThat(status(api.buildFortress(game.host(), game.gameId(), elmsford, 2, null))).isEqualTo(400);
		assertThat(status(api.buildFortress(game.host(), game.gameId(), elmsford, 0, UUID.randomUUID()))).isEqualTo(400);

		world.endTurn(game, game.host(), 1);
		assertThat(code(api.buildFortress(game.guest(), game.gameId(), elmsford, 2, UUID.randomUUID())))
			.isEqualTo("403 NOT_OWNER");
		assertThat(code(api.buildFortress(game.guest(), game.gameId(), brindle, 1, UUID.randomUUID())))
			.as("occupying is not owning").isEqualTo("403 NOT_OWNER");
		assertThat(world.treasury(game, "varn")).isEqualTo(1_000_000);
		assertThat(world.treasury(game, "ostra")).isEqualTo(1_000_000);
		assertThat(world.commands(game, "BUILD_FORTRESS") + world.commands(game, "REPAIR_FORTRESS")).isZero();
	}

	@Test
	void simultaneousRepairsOfADamagedFortressChargeOnce() throws Exception {
		Lobby game = world.startedGame();
		world.damageFortress(game, "varnhold");
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");

		List<MvcResult> results = Concurrently.run(4,
				i -> api.repairFortress(game.host(), game.gameId(), varnhold, UUID.randomUUID()));

		assertThat(results.stream().filter(r -> status(r) == 201)).hasSize(1);
		assertThat(results.stream().filter(r -> status(r) == 409)).hasSize(3);
		assertThat(world.treasury(game, "varn")).as("30% of the 100 gold level 2").isEqualTo(START_TREASURY - 30_000);
		assertThat(code(api.buildFortress(game.host(), game.gameId(), varnhold, 3, UUID.randomUUID())))
			.isEqualTo("409 FORTRESS_PROJECT_IN_PROGRESS");

		world.completeRound(game, 1);
		assertThat(world.fortress(game, "varnhold")).containsEntry("status", "ACTIVE").containsEntry("integrity", 200)
			.containsEntry("level", 2);
	}

	// --- isolation ----------------------------------------------------------------------------

	@Test
	void siegesAndFortressesBelongToTheirGame() throws Exception {
		Lobby first = world.startedGame();
		Lobby second = world.startedGame();
		api.move(first.host(), first.gameId(), varnArmyInElmsford(first, 1, 0), "sablewater", UUID.randomUUID());
		String siegeId = onlySiege(first).get("id").toString();
		String firstVarnhold = api.cityId(first.host(), first.gameId(), "varnhold");
		world.damageFortress(first, "varnhold");

		assertThat(code(api.siege(second.host(), second.gameId(), siegeId))).isEqualTo("404 SIEGE_NOT_FOUND");
		assertThat(code(api.repairFortress(second.host(), second.gameId(), firstVarnhold, UUID.randomUUID())))
			.isEqualTo("404 CITY_NOT_FOUND");
		assertThat((List<?>) read(api.sieges(second.host(), second.gameId()), "$.activeSieges")).isEmpty();
		assertThat(status(api.sieges(ApiDriver.newPlayer(), first.gameId()))).isEqualTo(404);
		assertThat(world.fortress(second, "varnhold")).containsEntry("status", "ACTIVE");
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private static long longAt(MvcResult result, String path) throws Exception {
		return ((Number) read(result, path)).longValue();
	}

}
