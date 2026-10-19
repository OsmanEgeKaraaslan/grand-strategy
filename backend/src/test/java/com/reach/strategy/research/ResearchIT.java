package com.reach.strategy.research;

import static com.reach.strategy.support.ApiDriver.read;
import static com.reach.strategy.support.ApiDriver.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * Phase 8 against PostgreSQL through the real API, ruleset v4: institutions, research income,
 * technology and doctrine unlocks, their effects in real battles, sieges and upkeep, Tier 2
 * upgrades and population growth. Host = Varn (Varnhold 8,000 people, one Tier 1 workshop),
 * guest = Ostra. Institutions cost 60 gold, take 2 rounds and give 10 points per round.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = "strategy.ruleset.current-version=v4")
class ResearchIT {

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

	private int pool(MvcResult research, String pool) throws Exception {
		return ApiDriver.<List<Integer>>read(research, "$.pools[?(@.pool == '" + pool + "')].points").getFirst();
	}

	// --- institutions and research income -------------------------------------------------------

	@Test
	void aNewGameStartsWithResearchButNoInstitutions() throws Exception {
		Lobby game = world.startedGame();

		MvcResult research = api.research(game.host(), game.gameId());
		assertThat(status(research)).isEqualTo(200);
		assertThat((Boolean) read(research, "$.available")).isTrue();
		assertThat((Integer) read(research, "$.military.level")).isEqualTo(1);
		assertThat((Integer) read(research, "$.civilian.next.costPoints")).isEqualTo(40);
		assertThat((List<?>) read(research, "$.institutions")).isEmpty();
		assertThat(ApiDriver.<List<Integer>>read(research, "$.pools[*].points")).containsOnly(0);
		assertThat(world.count("game.institution", game)).isZero();
		assertThat((List<?>) read(research, "$.doctrines")).hasSize(4);
	}

	@Test
	void anInstitutionCostsGoldTakesTwoRoundsAndProducesFromTheRoundAfter() throws Exception {
		Lobby game = world.startedGame();
		world.setTreasury(game, "varn", 1_000_000);
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		MvcResult options = api.cityInstitutions(game.host(), game.gameId(), varnhold);
		assertThat(ApiDriver.<List<Integer>>read(options, "$.options[?(@.type == 'UNIVERSITY')].completesAfterRound"))
			.containsExactly(2);

		MvcResult built = api.buildInstitution(game.host(), game.gameId(), varnhold, "UNIVERSITY", UUID.randomUUID());
		assertThat(status(built)).isEqualTo(201);
		assertThat(longAt(built, "$.costMg")).isEqualTo(60_000);
		assertThat((String) read(built, "$.institution.status")).isEqualTo("UNDER_CONSTRUCTION");
		assertThat(world.treasury(game, "varn")).isEqualTo(1_000_000 - 60_000);
		long upkeep = longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg");

		world.completeRound(game, 1);
		assertThat(pool(api.research(game.host(), game.gameId()), "CIVILIAN")).isZero();
		world.completeRound(game, 2);
		MvcResult afterCompletion = api.research(game.host(), game.gameId());
		assertThat(pool(afterCompletion, "CIVILIAN")).as("no free research on the completion round").isZero();
		assertThat((String) read(afterCompletion, "$.institutions[0].status")).isEqualTo("ACTIVE");
		assertThat(ApiDriver.<List<Integer>>read(afterCompletion, "$.pools[?(@.pool == 'CIVILIAN')].perRound"))
			.containsExactly(10);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg"))
			.as("+1 gold institution upkeep once active").isEqualTo(upkeep + 1_000);
		world.completeRound(game, 3);
		assertThat(pool(api.research(game.host(), game.gameId()), "CIVILIAN")).isEqualTo(10);
	}

	@Test
	void institutionsAreBuiltOnceByTheActiveOwnerWithTheMoney() throws Exception {
		Lobby game = world.startedGame();
		String elmsford = api.cityId(game.host(), game.gameId(), "elmsford");
		String brindle = api.cityId(game.host(), game.gameId(), "brindle");

		assertThat(code(api.buildInstitution(game.host(), game.gameId(), elmsford, "BARRACKS", UUID.randomUUID())))
			.as("50 gold < 60 gold").isEqualTo("422 INSUFFICIENT_GOLD");
		world.setTreasury(game, "varn", 1_000_000);
		assertThat(code(api.buildInstitution(game.guest(), game.gameId(), elmsford, "BARRACKS", UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		world.occupy(game, "brindle", "ostra");
		assertThat(code(api.buildInstitution(game.host(), game.gameId(), brindle, "BARRACKS", UUID.randomUUID())))
			.isEqualTo("409 PROVINCE_OCCUPIED");
		assertThat(status(api.buildInstitution(game.host(), game.gameId(), elmsford, "ACADEMY", UUID.randomUUID())))
			.isEqualTo(400);

		List<MvcResult> results = Concurrently.run(4,
				i -> api.buildInstitution(game.host(), game.gameId(), elmsford, "BARRACKS", UUID.randomUUID()));
		assertThat(results.stream().filter(r -> status(r) == 201)).hasSize(1);
		assertThat(results.stream().filter(r -> status(r) == 409)).hasSize(3);
		assertThat(world.treasury(game, "varn")).isEqualTo(1_000_000 - 60_000);

		world.endTurn(game, game.host(), 1);
		world.setTreasury(game, "ostra", 1_000_000);
		assertThat(code(api.buildInstitution(game.guest(), game.gameId(), elmsford, "UNIVERSITY", UUID.randomUUID())))
			.isEqualTo("403 NOT_OWNER");
	}

	@Test
	void researchIsPaidOncePerRoundAndInstitutionsAddUp() throws Exception {
		Lobby game = world.startedGame();
		world.activeInstitution(game, "varnhold", "MILITARY_ACADEMY");
		world.activeInstitution(game, "elmsford", "MILITARY_ACADEMY");
		world.activeInstitution(game, "harrowmere", "UNIVERSITY");

		world.endTurn(game, game.host(), 1);
		UUID key = UUID.randomUUID();
		api.endTurn(game.guest(), game.gameId(), 2, key);
		assertThat((Boolean) read(api.endTurn(game.guest(), game.gameId(), 2, key), "$.replayed")).isTrue();

		MvcResult research = api.research(game.host(), game.gameId());
		assertThat(pool(research, "MILITARY")).isEqualTo(20);
		assertThat(pool(research, "CIVILIAN")).isEqualTo(10);
		assertThat(pool(research, "DOCTRINE")).isZero();
		assertThat(ApiDriver.<List<Integer>>read(research, "$.pools[?(@.pool == 'MILITARY')].lastRound"))
			.containsExactly(20);
		assertThat(world.count("game.research_round_report", game)).isEqualTo(2);
		assertThat(pool(api.research(game.guest(), game.gameId()), "MILITARY")).as("Ostra has no institutions").isZero();
	}

	// --- unlocks --------------------------------------------------------------------------------

	@Test
	void technologyIsBoughtLevelByLevelWithPointsAndNeverTwice() throws Exception {
		Lobby game = world.startedGame();
		world.setResearchPoints(game, "varn", 0, 40, 0);
		UUID key = UUID.randomUUID();

		MvcResult unlocked = api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 2, key);
		assertThat(status(unlocked)).isEqualTo(200);
		assertThat((Integer) read(unlocked, "$.research.civilian.level")).isEqualTo(2);
		assertThat((Integer) read(unlocked, "$.research.effects.highestUnlockedWorkshopTier")).isEqualTo(2);
		assertThat(world.research(game, "varn")).containsEntry("civilian_points", 0).containsEntry("civilian_level", 2);

		assertThat(status(api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 2, key))).isEqualTo(200);
		assertThat(world.research(game, "varn")).as("a retry spends nothing").containsEntry("civilian_points", 0)
			.containsEntry("civilian_level", 2);
		assertThat(code(api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 3, UUID.randomUUID())))
			.isEqualTo("422 INSUFFICIENT_RESEARCH_POINTS");

		world.setResearchPoints(game, "varn", 999, 999, 0);
		assertThat(code(api.unlockTechnology(game.host(), game.gameId(), "MILITARY", 3, UUID.randomUUID())))
			.as("level 2 first").isEqualTo("409 STALE_TECHNOLOGY_LEVEL");
		assertThat(code(api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 2, UUID.randomUUID())))
			.isEqualTo("409 STALE_TECHNOLOGY_LEVEL");
		assertThat(status(api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 3, UUID.randomUUID())))
			.isEqualTo(200);
		assertThat(code(api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 4, UUID.randomUUID())))
			.isEqualTo("422 MAX_LEVEL_REACHED");
		assertThat(code(api.unlockTechnology(game.guest(), game.gameId(), "MILITARY", 2, UUID.randomUUID())))
			.isEqualTo("409 NOT_ACTIVE_PLAYER");
		assertThat(world.research(game, "ostra")).containsEntry("military_level", 1);
		assertThat(status(api.unlockTechnology(game.host(), game.gameId(), "MILITARY", 1, UUID.randomUUID())))
			.isEqualTo(400);
	}

	@Test
	void oneDoctrineIsChosenWithDoctrinePoints() throws Exception {
		Lobby game = world.startedGame();
		assertThat(code(api.unlockDoctrine(game.host(), game.gameId(), "MILITARY_LOGISTICS", UUID.randomUUID())))
			.isEqualTo("422 INSUFFICIENT_RESEARCH_POINTS");
		world.setResearchPoints(game, "varn", 0, 0, 100);

		MvcResult chosen = api.unlockDoctrine(game.host(), game.gameId(), "MILITARY_LOGISTICS", UUID.randomUUID());
		assertThat(status(chosen)).isEqualTo(200);
		assertThat(ApiDriver.<List<Boolean>>read(chosen, "$.research.doctrines[?(@.key == 'MILITARY_LOGISTICS')].chosen"))
			.containsExactly(true);
		assertThat(world.research(game, "varn")).containsEntry("doctrine_points", 70);
		assertThat(code(api.unlockDoctrine(game.host(), game.gameId(), "RIVER_ENGINEERING", UUID.randomUUID())))
			.isEqualTo("409 DOCTRINE_TIER_FULL");
		assertThat(code(api.unlockDoctrine(game.host(), game.gameId(), "MILITARY_LOGISTICS", UUID.randomUUID())))
			.isEqualTo("409 DOCTRINE_ALREADY_CHOSEN");
		assertThat(code(api.unlockDoctrine(game.host(), game.gameId(), "BLITZ", UUID.randomUUID())))
			.isEqualTo("404 DOCTRINE_NOT_FOUND");
		assertThat(world.research(game, "varn")).containsEntry("doctrine_points", 70);
	}

	// --- effects in real calculations -----------------------------------------------------------

	@Test
	void militaryTechnologyMakesExistingBattalionsHitHarderWithoutAddingSoldiers() throws Exception {
		Lobby plain = world.startedGame();
		Lobby teched = world.startedGame();
		world.setTechnology(teched, "varn", 2, 1);
		int base = attackElmsford(plain);
		world.place(teched, "ostra", "elmsford", "INFANTRY", 1_000);

		MvcResult battle = api.move(teched.host(), teched.gameId(), world.battalions(teched, "varn", "varnhold"),
				"elmsford", UUID.randomUUID());
		int withTech = (Integer) read(battle, "$.battle.details.defenderLosses");

		assertThat(withTech).isGreaterThan(base);
		assertThat((Integer) read(battle, "$.battle.details.attackerInitial")).isEqualTo(4_000);
		assertThat((Integer) read(battle, "$.battle.details.attackerModifiers.militaryLevel")).isEqualTo(2);
		assertThat((Integer) read(battle, "$.battle.details.attackerModifiers.damageBonusBps.INFANTRY")).isEqualTo(1_000);
		assertThat((Integer) read(battle, "$.battle.details.defenderModifiers.militaryLevel")).isEqualTo(1);
	}

	private int attackElmsford(Lobby game) throws Exception {
		world.place(game, "ostra", "elmsford", "INFANTRY", 1_000);
		MvcResult battle = api.move(game.host(), game.gameId(), world.battalions(game, "varn", "varnhold"), "elmsford",
				UUID.randomUUID());
		return read(battle, "$.battle.details.defenderLosses");
	}

	@Test
	void riverEngineeringAndMountainWarfareSoftenTheRealTerrainPenalties() throws Exception {
		Lobby river = world.startedGame();
		world.setResearchPoints(river, "varn", 0, 0, 30);
		api.unlockDoctrine(river.host(), river.gameId(), "RIVER_ENGINEERING", UUID.randomUUID());
		world.place(river, "ostra", "sablewater", "INFANTRY", 1_000);
		List<String> riverArmy = List.of(world.place(river, "varn", "elmsford", "INFANTRY", 1_000),
				world.place(river, "varn", "elmsford", "INFANTRY", 1_000));
		assertThat(ApiDriver.<Double>read(api.military(river.host(), river.gameId()), "$.modifiers.riverAttackMultiplier"))
			.isEqualTo(0.9);
		MvcResult crossing = api.move(river.host(), river.gameId(), riverArmy, "sablewater", UUID.randomUUID());
		assertThat(ApiDriver.<Double>read(crossing, "$.battle.details.phases[0].attacker.terrainMultiplier"))
			.as("−20% halved").isEqualTo(0.9);

		Lobby mountain = world.startedGame();
		world.setResearchPoints(mountain, "varn", 0, 0, 30);
		api.unlockDoctrine(mountain.host(), mountain.gameId(), "MOUNTAIN_WARFARE", UUID.randomUUID());
		world.place(mountain, "ostra", "kaldric", "INFANTRY", 1_000);
		List<String> climbers = List.of(world.place(mountain, "varn", "greyspire", "INFANTRY", 1_000),
				world.place(mountain, "varn", "greyspire", "INFANTRY", 1_000));
		MvcResult assault = api.move(mountain.host(), mountain.gameId(), climbers, "kaldric", UUID.randomUUID());
		assertThat(ApiDriver.<Double>read(assault, "$.battle.details.phases[0].attacker.terrainMultiplier"))
			.as("−35% halved").isEqualTo(0.825);
		assertThat(ApiDriver.<List<String>>read(assault, "$.battle.details.attackerModifiers.doctrines"))
			.containsExactly("MOUNTAIN_WARFARE");
	}

	@Test
	void siegeEngineeringSpeedsTheRealArtillerySiege() throws Exception {
		Lobby game = world.startedGame();
		world.setResearchPoints(game, "varn", 0, 0, 30);
		api.unlockDoctrine(game.host(), game.gameId(), "SIEGE_ENGINEERING", UUID.randomUUID());
		List<String> army = List.of(world.place(game, "varn", "elmsford", "INFANTRY", 1_000),
				world.place(game, "varn", "elmsford", "ARTILLERY", 1_000),
				world.place(game, "varn", "elmsford", "ARTILLERY", 1_000));
		api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());

		MvcResult province = api.fortification(game.host(), game.gameId(), "sablewater");
		assertThat((Integer) read(province, "$.activeSiege.ratePerRound")).as("100 + 100 × 1.5").isEqualTo(250);
		assertThat((Integer) read(province, "$.activeSiege.artilleryBonusBps")).isEqualTo(5_000);
		assertThat((Integer) read(province, "$.activeSiege.estimatedRoundsLeft")).isEqualTo(3);
		world.completeRound(game, 1);
		assertThat(world.sieges(game).getFirst().get("progress")).isEqualTo(250);
	}

	@Test
	void militaryLogisticsCutsTheBattalionUpkeepThatIsActuallyCharged() throws Exception {
		Lobby game = world.startedGame();
		world.setResearchPoints(game, "varn", 0, 0, 30);
		api.unlockDoctrine(game.host(), game.gameId(), "MILITARY_LOGISTICS", UUID.randomUUID());

		assertThat(longAt(api.military(game.host(), game.gameId()), "$.upkeep.totalMg")).as("3 gold × 0.8")
			.isEqualTo(2_400);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedUpkeepMg"))
			.as("battalions 2.4 + fortresses 4").isEqualTo(6_400);
		world.completeRound(game, 1);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.lastRound.upkeepMg")).isEqualTo(6_400);
		assertThat(longAt(api.economy(game.guest(), game.gameId()), "$.nation.lastRound.upkeepMg"))
			.as("Ostra has no doctrine").isEqualTo(7_000);
	}

	// --- Tier 2 ---------------------------------------------------------------------------------

	@Test
	void civilianTechnologyUnlocksTheTier2UpgradeWhichPaysOnlyAfterItCompletes() throws Exception {
		Lobby game = world.startedGame();
		world.setTreasury(game, "varn", 1_000_000);
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		String workshop = world.firstWorkshop(game, "varnhold");
		MvcResult before = api.cityEconomy(game.host(), game.gameId(), varnhold);
		assertThat(ApiDriver.<List<String>>read(before, "$.workshops[0].upgrade.blockers")).contains("TECHNOLOGY_REQUIRED");
		assertThat(code(api.upgradeWorkshop(game.host(), game.gameId(), varnhold, workshop, UUID.randomUUID())))
			.isEqualTo("422 TECHNOLOGY_REQUIRED");

		world.setResearchPoints(game, "varn", 0, 40, 0);
		api.unlockTechnology(game.host(), game.gameId(), "CIVILIAN", 2, UUID.randomUUID());
		MvcResult quote = api.cityEconomy(game.host(), game.gameId(), varnhold);
		assertThat((Boolean) read(quote, "$.workshops[0].upgrade.allowed")).isTrue();
		assertThat(longAt(quote, "$.workshops[0].upgrade.costMg")).isEqualTo(60_000);
		assertThat(longAt(quote, "$.workshops[0].upgrade.potentialIncomeAfterMg")).as("4,000 × 5 + 4,000")
			.isEqualTo(24_000);
		long projected = longAt(api.economy(game.host(), game.gameId()), "$.nation.projectedGrossIncomeMg");

		MvcResult upgraded = api.upgradeWorkshop(game.host(), game.gameId(), varnhold, workshop, UUID.randomUUID());
		assertThat(status(upgraded)).isEqualTo(200);
		assertThat((Integer) read(upgraded, "$.workshop.tier")).as("Tier 1 until it completes").isEqualTo(1);
		assertThat((Integer) read(upgraded, "$.workshop.upgradeTargetTier")).isEqualTo(2);
		assertThat(world.treasury(game, "varn")).isEqualTo(1_000_000 - 60_000);
		assertThat(code(api.upgradeWorkshop(game.host(), game.gameId(), varnhold, workshop, UUID.randomUUID())))
			.isEqualTo("422 WORKSHOP_NOT_UPGRADABLE");
		assertThat(longAt(api.cityEconomy(game.host(), game.gameId(), varnhold), "$.potentialIncome.totalMg"))
			.as("still producing at Tier 1").isEqualTo(16_000);

		world.completeRound(game, 1);
		assertThat(longAt(api.economy(game.host(), game.gameId()), "$.nation.lastRound.grossIncomeMg"))
			.as("no retroactive Tier 2 income").isEqualTo(projected);
		assertThat(world.building(workshop)).containsEntry("level", 2).containsEntry("upgrade_target_tier", null);
		MvcResult after = api.cityEconomy(game.host(), game.gameId(), varnhold);
		assertThat((Integer) read(after, "$.employment.tier2Workers")).isEqualTo(4_000);
		assertThat((Integer) read(after, "$.employment.tier1Workers")).isZero();
		assertThat(longAt(after, "$.potentialIncome.totalMg")).as("Tier 2 plus 4,040 farmers after growth")
			.isEqualTo(20_000 + 4_040);
		assertThat((Integer) read(after, "$.workshopCount")).as("same slot").isEqualTo(1);
	}

	@Test
	void bombardmentCancelsARunningUpgradeAndDamagesTheWorkshopAtItsCurrentTier() throws Exception {
		Lobby game = world.startedGame();
		String workshop = world.workshop(game, "sablewater");
		jdbc.update("""
				UPDATE game.building SET upgrade_target_tier = 2, upgrade_ordered_round = 1,
				    upgrade_completes_after_round = 99, upgrade_cost_paid_mg = 60000 WHERE id = ?::uuid
				""", workshop);
		List<String> army = List.of(world.place(game, "varn", "elmsford", "INFANTRY", 1_000),
				world.place(game, "varn", "elmsford", "ARTILLERY", 1_000),
				world.place(game, "varn", "elmsford", "ARTILLERY", 1_000));
		api.move(game.host(), game.gameId(), army, "sablewater", UUID.randomUUID());

		for (int round = 1; round <= 3; round++) {
			world.completeRound(game, round);
		}

		assertThat(world.sieges(game).getFirst().get("status")).isEqualTo("SUCCEEDED");
		assertThat(world.building(workshop)).containsEntry("status", "DAMAGED").containsEntry("level", 1)
			.containsEntry("upgrade_target_tier", null);
	}

	// --- growth ---------------------------------------------------------------------------------

	@Test
	void ownedCitiesGrowOncePerRoundButNotUnderSiegeOrOccupation() throws Exception {
		Lobby game = world.startedGame();
		String varnhold = api.cityId(game.host(), game.gameId(), "varnhold");
		MvcResult city = api.cityEconomy(game.host(), game.gameId(), varnhold);
		assertThat((Integer) read(city, "$.growth.nextRoundGrowth")).isEqualTo(40);
		assertThat((Integer) read(city, "$.growth.rateBps")).isEqualTo(50);
		api.move(game.host(), game.gameId(), List.of(world.place(game, "varn", "elmsford", "INFANTRY", 1_000)),
				"sablewater", UUID.randomUUID());
		world.occupy(game, "brindle", "ostra");
		String sablewater = api.cityId(game.guest(), game.gameId(), "sablewater");
		assertThat(ApiDriver.<List<String>>read(api.cityEconomy(game.guest(), game.gameId(), sablewater),
				"$.growth.blockers")).containsExactly("BESIEGED");

		world.endTurn(game, game.host(), 1);
		UUID key = UUID.randomUUID();
		api.endTurn(game.guest(), game.gameId(), 2, key);
		api.endTurn(game.guest(), game.gameId(), 2, key);

		assertThat(world.population(game, "varnhold")).isEqualTo(8_040);
		assertThat(world.population(game, "harrowmere")).isEqualTo(6_030);
		assertThat(world.population(game, "ostrava")).isEqualTo(8_040);
		assertThat(world.population(game, "sablewater")).as("siege attrition only").isEqualTo(7_600);
		assertThat(world.population(game, "brindle")).as("occupied: no growth").isEqualTo(7_000);
		assertThat(longAt(api.cityEconomy(game.host(), game.gameId(), varnhold), "$.potentialIncome.totalMg"))
			.as("the new people work from now on").isEqualTo(12_000 + 4_040);
	}

	// --- isolation ------------------------------------------------------------------------------

	@Test
	void researchIsPrivateAndScopedToItsGame() throws Exception {
		Lobby first = world.startedGame();
		Lobby second = world.startedGame();
		world.setResearchPoints(first, "varn", 40, 0, 0);
		api.unlockTechnology(first.host(), first.gameId(), "MILITARY", 2, UUID.randomUUID());

		assertThat(world.research(second, "varn")).containsEntry("military_level", 1);
		assertThat((Integer) read(api.research(second.host(), second.gameId()), "$.military.level")).isEqualTo(1);
		assertThat((Integer) read(api.research(first.guest(), first.gameId()), "$.military.level"))
			.as("the overview is the caller's own nation").isEqualTo(1);
		String firstCity = api.cityId(first.host(), first.gameId(), "varnhold");
		assertThat(code(api.cityInstitutions(second.host(), second.gameId(), firstCity))).isEqualTo("404 CITY_NOT_FOUND");
		assertThat(code(api.buildInstitution(second.host(), second.gameId(), firstCity, "UNIVERSITY", UUID.randomUUID())))
			.isEqualTo("404 CITY_NOT_FOUND");
		assertThat(status(api.research(ApiDriver.newPlayer(), first.gameId()))).isEqualTo(404);
	}

	private static String code(MvcResult result) throws Exception {
		return status(result) + " " + read(result, "$.code");
	}

	private static long longAt(MvcResult result, String path) throws Exception {
		return ((Number) read(result, path)).longValue();
	}

}
